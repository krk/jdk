/*
 * Copyright (c) 2016, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#include "gc/shared/barrierSet.hpp"
#include "gc/shared/c2/barrierSetC2.hpp"
#include "gc/shared/c2/cardTableBarrierSetC2.hpp"
#include "gc/shared/gc_globals.hpp"
#include "opto/arraycopynode.hpp"
#include "opto/addnode.hpp"
#include "opto/convertnode.hpp"
#include "opto/graphKit.hpp"
#include "opto/matcher.hpp"
#include "opto/vectornode.hpp"
#include "utilities/powerOfTwo.hpp"

const TypeFunc* ArrayCopyNode::_arraycopy_type_Type = nullptr;

#ifndef PRODUCT
// Histograms for OptSmallArrayCopy diagnostics: [BasicType][max_count].
// max_count index 0 is unused; valid range is [1..8] (ArrayCopyLoadStoreMaxElem).
// Plain (non-atomic) counters: slight over/undercount under races is fine.
static volatile int _opt_small_counts[T_LONG + 1][9];
#endif

ArrayCopyNode::ArrayCopyNode(Compile* C, bool alloc_tightly_coupled, bool has_negative_length_guard)
  : CallNode(arraycopy_type(), nullptr, TypePtr::BOTTOM),
    _kind(None),
    _alloc_tightly_coupled(alloc_tightly_coupled),
    _has_negative_length_guard(has_negative_length_guard),
    _arguments_validated(false),
    _src_type(TypeOopPtr::BOTTOM),
    _dest_type(TypeOopPtr::BOTTOM) {
  init_class_id(Class_ArrayCopy);
  init_flags(Flag_is_macro);
  C->add_macro_node(this);
}

uint ArrayCopyNode::size_of() const { return sizeof(*this); }

ArrayCopyNode* ArrayCopyNode::make(GraphKit* kit, bool may_throw,
                                   Node* src, Node* src_offset,
                                   Node* dest, Node* dest_offset,
                                   Node* length,
                                   bool alloc_tightly_coupled,
                                   bool has_negative_length_guard,
                                   Node* src_klass, Node* dest_klass,
                                   Node* src_length, Node* dest_length) {

  ArrayCopyNode* ac = new ArrayCopyNode(kit->C, alloc_tightly_coupled, has_negative_length_guard);
  kit->set_predefined_input_for_runtime_call(ac);

  ac->init_req(ArrayCopyNode::Src, src);
  ac->init_req(ArrayCopyNode::SrcPos, src_offset);
  ac->init_req(ArrayCopyNode::Dest, dest);
  ac->init_req(ArrayCopyNode::DestPos, dest_offset);
  ac->init_req(ArrayCopyNode::Length, length);
  ac->init_req(ArrayCopyNode::SrcLen, src_length);
  ac->init_req(ArrayCopyNode::DestLen, dest_length);
  ac->init_req(ArrayCopyNode::SrcKlass, src_klass);
  ac->init_req(ArrayCopyNode::DestKlass, dest_klass);

  if (may_throw) {
    ac->set_req(TypeFunc::I_O , kit->i_o());
    kit->add_safepoint_edges(ac, false);
  }

  return ac;
}

void ArrayCopyNode::connect_outputs(GraphKit* kit, bool deoptimize_on_exception) {
  kit->set_all_memory_call(this, true);
  kit->set_control(kit->gvn().transform(new ProjNode(this,TypeFunc::Control)));
  kit->set_i_o(kit->gvn().transform(new ProjNode(this, TypeFunc::I_O)));
  kit->make_slow_call_ex(this, kit->env()->Throwable_klass(), true, deoptimize_on_exception);
  kit->set_all_memory_call(this);
}

#ifndef PRODUCT
const char* ArrayCopyNode::_kind_names[] = {"arraycopy", "arraycopy, validated arguments", "clone", "oop array clone", "CopyOf", "CopyOfRange"};

void ArrayCopyNode::dump_spec(outputStream *st) const {
  CallNode::dump_spec(st);
  st->print(" (%s%s)", _kind_names[_kind], _alloc_tightly_coupled ? ", tightly coupled allocation" : "");
}

void ArrayCopyNode::dump_compact_spec(outputStream* st) const {
  st->print("%s%s", _kind_names[_kind], _alloc_tightly_coupled ? ",tight" : "");
}
#endif

intptr_t ArrayCopyNode::get_length_if_constant(PhaseGVN *phase) const {
  // check that length is constant
  Node* length = in(ArrayCopyNode::Length);
  const Type* length_type = phase->type(length);

  if (length_type == Type::TOP) {
    return -1;
  }

  assert(is_clonebasic() || is_arraycopy() || is_copyof() || is_copyofrange(), "unexpected array copy type");

  return is_clonebasic() ? length->find_intptr_t_con(-1) : length->find_int_con(-1);
}

int ArrayCopyNode::get_count(PhaseGVN *phase) const {
  Node* src = in(ArrayCopyNode::Src);
  const Type* src_type = phase->type(src);

  if (is_clonebasic()) {
    if (src_type->isa_instptr()) {
      const TypeInstPtr* inst_src = src_type->is_instptr();
      ciInstanceKlass* ik = inst_src->instance_klass();
      // ciInstanceKlass::nof_nonstatic_fields() doesn't take injected
      // fields into account. They are rare anyway so easier to simply
      // skip instances with injected fields.
      if ((!inst_src->klass_is_exact() && (ik->is_interface() || ik->has_subklass())) || ik->has_injected_fields()) {
        return -1;
      }
      int nb_fields = ik->nof_nonstatic_fields();
      return nb_fields;
    } else {
      const TypeAryPtr* ary_src = src_type->isa_aryptr();
      assert (ary_src != nullptr, "not an array or instance?");
      // clone passes a length as a rounded number of longs. If we're
      // cloning an array we'll do it element by element. If the
      // length of the input array is constant, ArrayCopyNode::Length
      // must be too. Note that the opposite does not need to hold,
      // because different input array lengths (e.g. int arrays with
      // 3 or 4 elements) might lead to the same length input
      // (e.g. 2 double-words).
      assert(!ary_src->size()->is_con() || (get_length_if_constant(phase) >= 0) ||
             phase->is_IterGVN() || phase->C->inlining_incrementally() || StressReflectiveCode, "inconsistent");
      if (ary_src->size()->is_con()) {
        return ary_src->size()->get_con();
      }
      return -1;
    }
  }

  return get_length_if_constant(phase);
}

Node* ArrayCopyNode::load(BarrierSetC2* bs, PhaseGVN *phase, Node*& ctl, MergeMemNode* mem, Node* adr, const TypePtr* adr_type, const Type *type, BasicType bt) {
  // Pin the load: if this is an array load, it's going to be dependent on a condition that's not a range check for that
  // access. If that condition is replaced by an identical dominating one, then an unpinned load would risk floating
  // above runtime checks that guarantee it is within bounds.
  DecoratorSet decorators = C2_READ_ACCESS | C2_CONTROL_DEPENDENT_LOAD | IN_HEAP | C2_ARRAY_COPY | C2_UNKNOWN_CONTROL_LOAD;
  C2AccessValuePtr addr(adr, adr_type);
  C2OptAccess access(*phase, ctl, mem, decorators, bt, adr->in(AddPNode::Base), addr);
  Node* res = bs->load_at(access, type);
  ctl = access.ctl();
  return res;
}

void ArrayCopyNode::store(BarrierSetC2* bs, PhaseGVN *phase, Node*& ctl, MergeMemNode* mem, Node* adr, const TypePtr* adr_type, Node* val, const Type *type, BasicType bt) {
  DecoratorSet decorators = C2_WRITE_ACCESS | IN_HEAP | C2_ARRAY_COPY;
  if (is_alloc_tightly_coupled()) {
    decorators |= C2_TIGHTLY_COUPLED_ALLOC;
  }
  C2AccessValuePtr addr(adr, adr_type);
  C2AccessValue value(val, type);
  C2OptAccess access(*phase, ctl, mem, decorators, bt, adr->in(AddPNode::Base), addr);
  bs->store_at(access, value);
  ctl = access.ctl();
}


Node* ArrayCopyNode::try_clone_instance(PhaseGVN *phase, bool can_reshape, int count) {
  if (!is_clonebasic()) {
    return nullptr;
  }

  Node* base_src = in(ArrayCopyNode::Src);
  Node* base_dest = in(ArrayCopyNode::Dest);
  Node* ctl = in(TypeFunc::Control);
  Node* in_mem = in(TypeFunc::Memory);

  const Type* src_type = phase->type(base_src);
  const TypeInstPtr* inst_src = src_type->isa_instptr();
  if (inst_src == nullptr) {
    return nullptr;
  }

  MergeMemNode* mem = phase->transform(MergeMemNode::make(in_mem))->as_MergeMem();
  if (can_reshape) {
    phase->is_IterGVN()->_worklist.push(mem);
  }


  ciInstanceKlass* ik = inst_src->instance_klass();

  if (!inst_src->klass_is_exact()) {
    assert(!ik->is_interface(), "inconsistent klass hierarchy");
    if (ik->has_subklass()) {
      // Concurrent class loading.
      // Fail fast and return NodeSentinel to indicate that the transform failed.
      return NodeSentinel;
    } else {
      phase->C->dependencies()->assert_leaf_type(ik);
    }
  }

  const TypeInstPtr* dest_type = phase->type(base_dest)->is_instptr();
  if (dest_type->instance_klass() != ik) {
    // At parse time, the exact type of the object to clone was not known. That inexact type was captured by the CheckCastPP
    // of the newly allocated cloned object (in dest). The exact type is now known (in src), but the type for the cloned object
    // (dest) was not updated. When copying the fields below, Store nodes may write to offsets for fields that don't exist in
    // the inexact class. The stores would then be assigned an incorrect slice.
    return NodeSentinel;
  }

  assert(ik->nof_nonstatic_fields() <= ArrayCopyLoadStoreMaxElem, "too many fields");

  BarrierSetC2* bs = BarrierSet::barrier_set()->barrier_set_c2();
  for (int i = 0; i < count; i++) {
    ciField* field = ik->nonstatic_field_at(i);
    const TypePtr* adr_type = phase->C->alias_type(field)->adr_type();
    Node* off = phase->MakeConX(field->offset_in_bytes());
    Node* next_src = phase->transform(new AddPNode(base_src,base_src,off));
    Node* next_dest = phase->transform(new AddPNode(base_dest,base_dest,off));
    assert(phase->C->get_alias_index(adr_type) == phase->C->get_alias_index(phase->type(next_src)->isa_ptr()),
      "slice of address and input slice don't match");
    assert(phase->C->get_alias_index(adr_type) == phase->C->get_alias_index(phase->type(next_dest)->isa_ptr()),
      "slice of address and input slice don't match");
    BasicType bt = field->layout_type();

    const Type *type;
    if (bt == T_OBJECT) {
      if (!field->type()->is_loaded()) {
        type = TypeInstPtr::BOTTOM;
      } else {
        ciType* field_klass = field->type();
        type = TypeOopPtr::make_from_klass(field_klass->as_klass());
      }
    } else {
      type = Type::get_const_basic_type(bt);
    }

    Node* v = load(bs, phase, ctl, mem, next_src, adr_type, type, bt);
    store(bs, phase, ctl, mem, next_dest, adr_type, v, type, bt);
  }

  if (!finish_transform(phase, can_reshape, ctl, mem)) {
    // Return NodeSentinel to indicate that the transform failed
    return NodeSentinel;
  }

  return mem;
}

bool ArrayCopyNode::prepare_array_copy(PhaseGVN *phase, bool can_reshape,
                                       ArrayCopyAddr& src,
                                       ArrayCopyAddr& dest,
                                       ArrayCopyType& elem,
                                       bool& disjoint_bases) {
  src.base = in(ArrayCopyNode::Src);
  dest.base = in(ArrayCopyNode::Dest);
  src.adr = nullptr;
  dest.adr = nullptr;
  const Type* src_type = phase->type(src.base);
  const TypeAryPtr* ary_src = src_type->isa_aryptr();

  Node* src_offset = in(ArrayCopyNode::SrcPos);
  Node* dest_offset = in(ArrayCopyNode::DestPos);

  if (is_arraycopy() || is_copyofrange() || is_copyof()) {
    const Type* dest_type = phase->type(dest.base);
    const TypeAryPtr* ary_dest = dest_type->isa_aryptr();

    // newly allocated object is guaranteed to not overlap with source object
    disjoint_bases = is_alloc_tightly_coupled();
    if (ary_src  == nullptr || ary_src->elem()  == Type::BOTTOM ||
        ary_dest == nullptr || ary_dest->elem() == Type::BOTTOM) {
      // We don't know if arguments are arrays
      return false;
    }

    BasicType src_elem = ary_src->elem()->array_element_basic_type();
    BasicType dest_elem = ary_dest->elem()->array_element_basic_type();
    if (is_reference_type(src_elem, true)) src_elem = T_OBJECT;
    if (is_reference_type(dest_elem, true)) dest_elem = T_OBJECT;

    if (src_elem != dest_elem || dest_elem == T_VOID) {
      // We don't know if arguments are arrays of the same type
      return false;
    }

    BarrierSetC2* bs = BarrierSet::barrier_set()->barrier_set_c2();
    if (bs->array_copy_requires_gc_barriers(is_alloc_tightly_coupled(), dest_elem, false, false, BarrierSetC2::Optimization)) {
      // It's an object array copy but we can't emit the card marking
      // that is needed
      return false;
    }

    elem.value_type = ary_src->elem();

    uint shift  = exact_log2(type2aelembytes(dest_elem));
    uint header = arrayOopDesc::base_offset_in_bytes(dest_elem);

    src_offset = Compile::conv_I2X_index(phase, src_offset, ary_src->size());
    if (src_offset->is_top()) {
      // Offset is out of bounds (the ArrayCopyNode will be removed)
      return false;
    }
    dest_offset = Compile::conv_I2X_index(phase, dest_offset, ary_dest->size());
    if (dest_offset->is_top()) {
      // Offset is out of bounds (the ArrayCopyNode will be removed)
      if (can_reshape) {
        // record src_offset, so it can be deleted later (if it is dead)
        phase->is_IterGVN()->_worklist.push(src_offset);
      }
      return false;
    }

    Node* hook = new Node(1);
    hook->init_req(0, dest_offset);

    Node* src_scale  = phase->transform(new LShiftXNode(src_offset, phase->intcon(shift)));

    hook->destruct(phase);

    Node* dest_scale = phase->transform(new LShiftXNode(dest_offset, phase->intcon(shift)));

    src.adr = phase->transform(new AddPNode(src.base, src.base, src_scale));
    dest.adr = phase->transform(new AddPNode(dest.base, dest.base, dest_scale));

    src.adr = phase->transform(new AddPNode(src.base, src.adr, phase->MakeConX(header)));
    dest.adr = phase->transform(new AddPNode(dest.base, dest.adr, phase->MakeConX(header)));

    elem.type = dest_elem;
  } else {
    assert(ary_src != nullptr, "should be a clone");
    assert(is_clonebasic(), "should be");

    disjoint_bases = true;

    BasicType bt = ary_src->isa_aryptr()->elem()->array_element_basic_type();
    if (is_reference_type(bt, true)) {
      bt = T_OBJECT;
    }

    BarrierSetC2* bs = BarrierSet::barrier_set()->barrier_set_c2();
    if (bs->array_copy_requires_gc_barriers(true, bt, true, is_clone_inst(), BarrierSetC2::Optimization)) {
      return false;
    }

    src.adr = phase->transform(new AddPNode(src.base, src.base, src_offset));
    dest.adr = phase->transform(new AddPNode(dest.base, dest.base, dest_offset));

    // The address is offsetted to an aligned address where a raw copy would start.
    // If the clone copy is decomposed into load-stores - the address is adjusted to
    // point at where the array starts.
    const Type* toff = phase->type(src_offset);
    int offset = toff->isa_long() ? (int) toff->is_long()->get_con() : (int) toff->is_int()->get_con();
    int diff = arrayOopDesc::base_offset_in_bytes(bt) - offset;
    assert(diff >= 0, "clone should not start after 1st array element");
    if (diff > 0) {
      src.adr = phase->transform(new AddPNode(src.base, src.adr, phase->MakeConX(diff)));
      dest.adr = phase->transform(new AddPNode(dest.base, dest.adr, phase->MakeConX(diff)));
    }
    elem.type = bt;
    elem.value_type = ary_src->elem();
  }

  // Compute the array element address type from the escape analysis type.
  const TypePtr* src_atp = _src_type == TypeOopPtr::BOTTOM ? phase->type(src.base)->isa_ptr() : _src_type;
  const TypePtr* dest_atp = _dest_type == TypeOopPtr::BOTTOM ? phase->type(dest.base)->isa_ptr() : _dest_type;
  src.atp = src_atp->add_offset(Type::OffsetBot);
  dest.atp = dest_atp->add_offset(Type::OffsetBot);
  return true;
}

void ArrayCopyNode::array_copy_test_overlap(PhaseGVN *phase, bool can_reshape, bool disjoint_bases, int count, Node*& forward_ctl, Node*& backward_ctl) {
  Node* ctl = in(TypeFunc::Control);
  if (!disjoint_bases && count > 1) {
    Node* src_offset = in(ArrayCopyNode::SrcPos);
    Node* dest_offset = in(ArrayCopyNode::DestPos);
    assert(src_offset != nullptr && dest_offset != nullptr, "should be");
    Node* cmp = phase->transform(new CmpINode(src_offset, dest_offset));
    Node *bol = phase->transform(new BoolNode(cmp, BoolTest::lt));
    IfNode *iff = new IfNode(ctl, bol, PROB_FAIR, COUNT_UNKNOWN);

    phase->transform(iff);

    forward_ctl = phase->transform(new IfFalseNode(iff));
    backward_ctl = phase->transform(new IfTrueNode(iff));
  } else {
    forward_ctl = ctl;
  }
}

inline void ArrayCopyNode::copy_element(BarrierSetC2* bs, PhaseGVN* phase,
                                        Node*& ctl, MergeMemNode* mm,
                                        ArrayCopyAddr src, ArrayCopyAddr dest,
                                        ArrayCopyType elem, int i) {
  Node* s = src.adr;
  Node* d = dest.adr;
  if (i > 0) {
    Node* off = phase->MakeConX(type2aelembytes(elem.type) * i);
    s = phase->transform(new AddPNode(src.base, src.adr, off));
    d = phase->transform(new AddPNode(dest.base, dest.adr, off));
  }
  Node* v = load(bs, phase, ctl, mm, s, src.atp, elem.value_type, elem.type);
  store(bs, phase, ctl, mm, d, dest.atp, v, elem.value_type, elem.type);
}

Node* ArrayCopyNode::array_copy_forward(PhaseGVN *phase,
                                        bool can_reshape,
                                        Node*& forward_ctl,
                                        Node* mem,
                                        ArrayCopyAddr src,
                                        ArrayCopyAddr dest,
                                        ArrayCopyType elem) {
  assert(elem.max_count == elem.min_count, "count");

  if (!forward_ctl->is_top()) {
    // copy forward
    MergeMemNode* mm = MergeMemNode::make(mem);
    int count = elem.max_count;

    if (count > 0) {
      BarrierSetC2* bs = BarrierSet::barrier_set()->barrier_set_c2();
      for (int i = 0; i < count; i++) {
        copy_element(bs, phase, forward_ctl, mm, src, dest, elem, i);
      }
    } else if (can_reshape) {
      PhaseIterGVN* igvn = phase->is_IterGVN();
      igvn->_worklist.push(src.adr);
      igvn->_worklist.push(dest.adr);
    }
    return mm;
  }
  return phase->C->top();
}

// Copy chunk_size elements at the byte offset given by chunk_off (nullptr means
// offset 0, i.e. use src.adr/dest.adr directly without an AddP).
// Selects the widest available copy strategy: vector > scalar widening > element.
// Updates mm and copy_ctl in-place.  Returns false if no viable strategy exists.
bool ArrayCopyNode::copy_chunk(PhaseGVN* phase, BarrierSetC2* bs, Node*& copy_ctl, MergeMemNode* mm, ArrayCopyAddr src,
                               ArrayCopyAddr dest, ArrayCopyType elem, int alias_idx, int chunk_size, Node* chunk_off) {
  int elem_size = type2aelembytes(elem.type);
  Node* s = chunk_off == nullptr ? src.adr  : phase->transform(new AddPNode(src.base,  src.adr,  chunk_off));
  Node* d = chunk_off == nullptr ? dest.adr : phase->transform(new AddPNode(dest.base, dest.adr, chunk_off));

  if (is_java_primitive(elem.type) && Matcher::vector_size_supported(elem.type, chunk_size)) {
    Node* mem_in = mm->memory_at(alias_idx);
    Node* vload  = phase->transform(LoadVectorNode::make(0, copy_ctl, mem_in, s, src.atp, chunk_size, elem.type));
    Node* vstore = phase->transform(StoreVectorNode::make(0, copy_ctl, mem_in, d, dest.atp, vload, chunk_size));
    mm->set_memory_at(alias_idx, vstore);
  } else if (chunk_size > 1) {
    int chunk_bytes = chunk_size * elem_size;
    BasicType wide_bt;
    const Type* wide_type;
    switch (chunk_bytes) {
      case 2: wide_bt = T_SHORT; wide_type = TypeInt::SHORT; break;
      case 4: wide_bt = T_INT;   wide_type = TypeInt::INT;   break;
      case 8: wide_bt = T_LONG;  wide_type = TypeLong::LONG; break;
      default:
        return false;
    }
    Node* mem_in = mm->memory_at(alias_idx);
    Node* ld = LoadNode::make(*phase, copy_ctl, mem_in, s, src.atp, wide_type, wide_bt, MemNode::unordered,
                              LoadNode::DependsOnlyOnTest, false, false, true);
    ld = phase->transform(ld);
    StoreNode* st = StoreNode::make(*phase, copy_ctl, mem_in, d, dest.atp, ld, wide_bt, MemNode::unordered);
    st->set_mismatched_access();
    mm->set_memory_at(alias_idx, phase->transform(st));
  } else {
    Node* v = load(bs, phase, copy_ctl, mm, s, src.atp, elem.value_type, elem.type);
    store(bs, phase, copy_ctl, mm, d, dest.atp, v, elem.value_type, elem.type);
  }
  return true;
}

// Inline a forward arraycopy with non-constant but type-bounded length.
//
// Decomposes the conditional element count into power-of-2 chunks, testing
// one bit at a time from high to low.  Each bit test forms a flat if-diamond,
// giving ceil(log2(N)) branches instead of the N nested guards a linear
// staircase would need.
//
// For bit b with chunk_size = (1 << b):
//   if (remaining & chunk_size) {
//     copy chunk_size elements at offset (remaining & higher_bits_mask)
//   }
//
// The element offset for each chunk is computable directly from 'remaining'
// using bitwise AND — no Phi nodes are needed for address computation.
//
// For example, with type [0, 8] and length 7 (binary 111):
//   if (len & 4) copy elements 0-3           offset = 0
//   if (len & 2) copy elements 4-5           offset = len & 4 = 4
//   if (len & 1) copy element  6             offset = len & 6 = 6
Node* ArrayCopyNode::array_copy_forward_variable(PhaseGVN *phase, bool can_reshape, Node*& ctl, Node* mem,
                                                 ArrayCopyAddr src, ArrayCopyAddr dest, ArrayCopyType elem) {
  assert(elem.max_count >= elem.min_count, "count");

  MergeMemNode* mm = MergeMemNode::make(mem);
  Node* length = in(ArrayCopyNode::Length);
  int alias_idx = phase->C->get_alias_index(dest.atp);
  BarrierSetC2* bs = BarrierSet::barrier_set()->barrier_set_c2();
  int guarded_count = elem.max_count - elem.min_count;

  // Masked vector path: single branchless masked load + store for the
  // entire variable-length copy.  Requires hardware support for masked
  // vector operations (e.g. AVX-512 on x86).
  if (is_java_primitive(elem.type) && elem.max_count > 0) {
    int lane_count = round_up_power_of_2(elem.max_count);
    unsigned vec_size = lane_count * type2aelembytes(elem.type);
    if (Matcher::match_rule_supported_vector(Op_VectorMaskGen,     lane_count, elem.type) &&
        Matcher::match_rule_supported_vector(Op_LoadVectorMasked,  lane_count, elem.type) &&
        Matcher::match_rule_supported_vector(Op_StoreVectorMasked, lane_count, elem.type)) {
      if (phase->C->max_vector_size() < vec_size) {
        phase->C->set_max_vector_size(vec_size);
      }

      const TypeVect* vt = TypeVect::make(elem.type, lane_count);
      Node* len_long = phase->transform(new ConvI2LNode(length));
      Node* mask = phase->transform(VectorMaskGenNode::make(len_long, elem.type, lane_count));

      Node* mem_in = mm->memory_at(alias_idx);
      Node* vload  = phase->transform(new LoadVectorMaskedNode(ctl, mem_in, src.adr, src.atp, vt, mask));
      Node* vstore = phase->transform(new StoreVectorMaskedNode(ctl, mem_in, dest.adr, vload, dest.atp, mask));
      mm->set_memory_at(alias_idx, vstore);
      return mm;
    }
  }

  int elem_size = type2aelembytes(elem.type);
  int shift = exact_log2(elem_size);

  // Phase 1: Copy min_count elements unconditionally.  Decompose into power-of-2
  // chunks (high bit first) and use the widest available op per chunk.
  intptr_t byte_offset = 0;
  for (int b = (elem.min_count > 0 ? log2i(elem.min_count) : -1); b >= 0; b--) {
    int chunk_size = 1 << b;
    if (!(elem.min_count & chunk_size)) {
      continue;
    }
    Node* chunk_off = (byte_offset == 0) ? nullptr : phase->MakeConX(byte_offset);
    if (!copy_chunk(phase, bs, ctl, mm, src, dest, elem, alias_idx, chunk_size, chunk_off)) {
      return nullptr;
    }
    byte_offset += (intptr_t)chunk_size * elem_size;
  }

  if (guarded_count <= 0) {
    return mm;
  }

  // Phase 2: Binary staircase for the conditional region.
  Node* remaining;
  if (elem.min_count > 0) {
    remaining = phase->transform(new SubINode(length, phase->intcon(elem.min_count)));
  } else {
    remaining = length;
  }

  int highest_bit = log2i(guarded_count);
  intptr_t base_off = (intptr_t)elem.min_count * elem_size;

  for (int b = highest_bit; b >= 0; b--) {
    int chunk_size = 1 << b;

    // Guard: if (remaining & chunk_size) != 0.
    Node* cmp;
    BoolTest::mask bt;
    if (b == highest_bit) {
      // chunk_size = 2^highest_bit, so remaining < 2*chunk_size (by definition of highest_bit).
      // For any x in [0, 2*chunk_size): x & chunk_size != 0  iff  x >= chunk_size.
      cmp = phase->transform(new CmpINode(remaining, phase->intcon(chunk_size)));
      bt = BoolTest::ge;
    } else {
      Node* test = phase->transform(new AndINode(remaining, phase->intcon(chunk_size)));
      cmp = phase->transform(new CmpINode(test, phase->intcon(0)));
      bt = BoolTest::ne;
    }
    Node* bol  = phase->transform(new BoolNode(cmp, bt));
    IfNode* iff = phase->transform(new IfNode(ctl, bol, PROB_FAIR, COUNT_UNKNOWN))->as_If();
    Node* if_true  = phase->transform(new IfTrueNode(iff));
    Node* if_false = phase->transform(new IfFalseNode(iff));

    // Snapshot memory before the conditional stores.
    Node* mem_before = mm->memory_at(alias_idx);

    // Byte offset of this chunk's start: the element offset within the
    // guarded region is (remaining & higher_bits_mask), contributed by all
    // bits above 'b'.  Add min_count to get the absolute element index,
    // then scale to bytes.
    int offset_mask = ((1 << (highest_bit + 1)) - 1) & ~((1 << (b + 1)) - 1);

    Node* runtime_byte_off = nullptr;
    if (offset_mask != 0) {
      Node* runtime_elem_off = phase->transform(new AndINode(remaining, phase->intcon(offset_mask)));
      Node* runtime_elem_off_x = Compile::conv_I2X_index(phase, runtime_elem_off, TypeInt::POS);
      runtime_byte_off = (shift > 0)
        ? phase->transform(new LShiftXNode(runtime_elem_off_x, phase->intcon(shift)))
        : runtime_elem_off_x;
    }

    // Byte offset to the start of this chunk.
    Node* chunk_off;
    if (runtime_byte_off == nullptr && base_off == 0) {
      chunk_off = nullptr;
    } else if (runtime_byte_off == nullptr) {
      chunk_off = phase->MakeConX(base_off);
    } else if (base_off == 0) {
      chunk_off = runtime_byte_off;
    } else {
      chunk_off = phase->transform(new AddXNode(runtime_byte_off, phase->MakeConX(base_off)));
    }

    Node* copy_ctl = if_true;
    if (!copy_chunk(phase, bs, copy_ctl, mm, src, dest, elem, alias_idx, chunk_size, chunk_off)) {
      return nullptr;
    }

    // Merge the diamond.
    RegionNode* merge = new RegionNode(3);
    merge->init_req(1, copy_ctl);
    merge->init_req(2, if_false);
    merge = phase->transform(merge)->as_Region();

    PhiNode* mem_phi = new PhiNode(merge, Type::MEMORY, phase->C->get_adr_type(alias_idx));
    mem_phi->init_req(1, mm->memory_at(alias_idx));
    mem_phi->init_req(2, mem_before);
    mm->set_memory_at(alias_idx, phase->transform(mem_phi));

    ctl = merge;
  }

  return mm;
}

Node* ArrayCopyNode::array_copy_backward(PhaseGVN *phase,
                                         bool can_reshape,
                                         Node*& backward_ctl,
                                         Node* mem,
                                         ArrayCopyAddr src,
                                         ArrayCopyAddr dest,
                                         ArrayCopyType elem) {
  if (!backward_ctl->is_top()) {
    // copy backward
    MergeMemNode* mm = MergeMemNode::make(mem);
    int count = elem.max_count;

    BarrierSetC2* bs = BarrierSet::barrier_set()->barrier_set_c2();
    assert(elem.type != T_OBJECT || !bs->array_copy_requires_gc_barriers(false, T_OBJECT, false, false, BarrierSetC2::Optimization), "only tightly coupled allocations for object arrays");

    if (count > 0) {
      for (int i = count-1; i >= 0; i--) {
        copy_element(bs, phase, backward_ctl, mm, src, dest, elem, i);
      }
    } else if (can_reshape) {
      PhaseIterGVN* igvn = phase->is_IterGVN();
      igvn->_worklist.push(src.adr);
      igvn->_worklist.push(dest.adr);
    }
    return phase->transform(mm);
  }
  return phase->C->top();
}

bool ArrayCopyNode::finish_transform(PhaseGVN *phase, bool can_reshape,
                                     Node* ctl, Node *mem) {
  if (can_reshape) {
    PhaseIterGVN* igvn = phase->is_IterGVN();
    igvn->set_delay_transform(false);
    if (is_clonebasic()) {
      Node* out_mem = proj_out(TypeFunc::Memory);

      BarrierSetC2* bs = BarrierSet::barrier_set()->barrier_set_c2();
      if (out_mem->outcnt() != 1 || !out_mem->raw_out(0)->is_MergeMem() ||
          out_mem->raw_out(0)->outcnt() != 1 || !out_mem->raw_out(0)->raw_out(0)->is_MemBar()) {
        assert(bs->array_copy_requires_gc_barriers(true, T_OBJECT, true, is_clone_inst(), BarrierSetC2::Optimization), "can only happen with card marking");
        return false;
      }

      igvn->replace_node(out_mem->raw_out(0), mem);

      Node* out_ctl = proj_out(TypeFunc::Control);
      igvn->replace_node(out_ctl, ctl);
    } else {
      // replace fallthrough projections of the ArrayCopyNode by the
      // new memory, control and the input IO.
      CallProjections callprojs;
      extract_projections(&callprojs, true, false);

      if (callprojs.fallthrough_ioproj != nullptr) {
        igvn->replace_node(callprojs.fallthrough_ioproj, in(TypeFunc::I_O));
      }
      if (callprojs.fallthrough_memproj != nullptr) {
        igvn->replace_node(callprojs.fallthrough_memproj, mem);
      }
      if (callprojs.fallthrough_catchproj != nullptr) {
        igvn->replace_node(callprojs.fallthrough_catchproj, ctl);
      }

      // The ArrayCopyNode is not disconnected. It still has the
      // projections for the exception case. Replace current
      // ArrayCopyNode with a dummy new one with a top() control so
      // that this part of the graph stays consistent but is
      // eventually removed.

      set_req(0, phase->C->top());
      remove_dead_region(phase, can_reshape);
    }
  } else {
    if (in(TypeFunc::Control) != ctl) {
      // we can't return new memory and control from Ideal at parse time
      assert(!is_clonebasic() || UseShenandoahGC, "added control for clone?");
      phase->record_for_igvn(this);
      return false;
    }
  }
  return true;
}


Node *ArrayCopyNode::Ideal(PhaseGVN *phase, bool can_reshape) {
  if (remove_dead_region(phase, can_reshape))  return this;

  if (StressArrayCopyMacroNode && !can_reshape) {
    phase->record_for_igvn(this);
    return nullptr;
  }

  // See if it's a small array copy and we can inline it as
  // loads/stores
  // Here we can only do:
  // - arraycopy if all arguments were validated before and we don't
  // need card marking
  // - clone for which we don't need to do card marking

  if (!is_clonebasic() && !is_arraycopy_validated() &&
      !is_copyofrange_validated() && !is_copyof_validated()) {
    return nullptr;
  }

  assert(in(TypeFunc::Control) != nullptr &&
         in(TypeFunc::Memory) != nullptr &&
         in(ArrayCopyNode::Src) != nullptr &&
         in(ArrayCopyNode::Dest) != nullptr &&
         in(ArrayCopyNode::Length) != nullptr &&
         in(ArrayCopyNode::SrcPos) != nullptr &&
         in(ArrayCopyNode::DestPos) != nullptr, "broken inputs");

  if (in(TypeFunc::Control)->is_top() ||
      in(TypeFunc::Memory)->is_top() ||
      phase->type(in(ArrayCopyNode::Src)) == Type::TOP ||
      phase->type(in(ArrayCopyNode::Dest)) == Type::TOP ||
      (in(ArrayCopyNode::SrcPos) != nullptr && in(ArrayCopyNode::SrcPos)->is_top()) ||
      (in(ArrayCopyNode::DestPos) != nullptr && in(ArrayCopyNode::DestPos)->is_top())) {
    return nullptr;
  }

  int count = get_count(phase);

  if (count < 0 || count > ArrayCopyLoadStoreMaxElem) {
    if (!OptSmallArrayCopy) {
      return nullptr;
    }
    const TypeInt* length_type = phase->type(in(ArrayCopyNode::Length))->isa_int();
    if (length_type == nullptr || length_type->_lo < 0 ||
        length_type->_hi <= 0 || length_type->_hi > ArrayCopyLoadStoreMaxElem) {
      return nullptr;
    }

    ArrayCopyAddr ac_src = {};
    ArrayCopyAddr ac_dest = {};
    ArrayCopyType ac_elem = { T_ILLEGAL, nullptr, length_type->_lo, length_type->_hi };
    bool disjoint_bases = false;

    if (!prepare_array_copy(phase, can_reshape, ac_src, ac_dest, ac_elem, disjoint_bases)) {
      assert(ac_src.adr == nullptr, "no node can be left behind");
      assert(ac_dest.adr == nullptr, "no node can be left behind");
      return nullptr;
    }

    // Only handle disjoint copies (no overlap/backward needed)
    if (!disjoint_bases) {
      return nullptr;
    }
    Node* in_mem = in(TypeFunc::Memory);

    if (can_reshape) {
      assert(!phase->is_IterGVN()->delay_transform(), "cannot delay transforms");
      phase->is_IterGVN()->set_delay_transform(true);
    }

    Node* ctl = in(TypeFunc::Control);
    Node* mem = array_copy_forward_variable(phase, can_reshape, ctl, in_mem, ac_src, ac_dest, ac_elem);

    if (can_reshape) {
      assert(phase->is_IterGVN()->delay_transform(), "should be delaying transforms");
      phase->is_IterGVN()->set_delay_transform(false);
    }

    if (mem == nullptr) {
      return nullptr;
    }

    if (!finish_transform(phase, can_reshape, ctl, mem)) {
      if (can_reshape) {
        phase->is_IterGVN()->_worklist.push(mem);
      }
      return nullptr;
    }

#ifndef PRODUCT
    {
      int bt = (int)ac_elem.type;
      int mc = ac_elem.max_count;
      if (bt >= 0 && bt <= T_LONG && mc >= 1 && mc <= ArrayCopyLoadStoreMaxElem) {
        _opt_small_counts[bt][mc]++;
      }
      if (PrintOptoStatistics) {
        tty->print("OptSmallArrayCopy variable-length expanded: %-6s [%d..%d]  ",
                   type2name(ac_elem.type), ac_elem.min_count, ac_elem.max_count);
        phase->C->method()->print_name(tty);
        tty->cr();
      }
    }
#endif

    return mem;
  }

  Node* mem = try_clone_instance(phase, can_reshape, count);
  if (mem != nullptr) {
    return (mem == NodeSentinel) ? nullptr : mem;
  }

  ArrayCopyAddr ac_src = {};
  ArrayCopyAddr ac_dest = {};
  ArrayCopyType ac_elem = { T_ILLEGAL, nullptr, count, count };
  bool disjoint_bases = false;

  if (!prepare_array_copy(phase, can_reshape, ac_src, ac_dest, ac_elem, disjoint_bases)) {
    assert(ac_src.adr == nullptr, "no node can be left behind");
    assert(ac_dest.adr == nullptr, "no node can be left behind");
    return nullptr;
  }
  Node* in_mem = in(TypeFunc::Memory);

  if (can_reshape) {
    assert(!phase->is_IterGVN()->delay_transform(), "cannot delay transforms");
    phase->is_IterGVN()->set_delay_transform(true);
  }

  Node* backward_ctl = phase->C->top();
  Node* forward_ctl = phase->C->top();
  array_copy_test_overlap(phase, can_reshape, disjoint_bases, count, forward_ctl, backward_ctl);

  Node* forward_mem = array_copy_forward(phase, can_reshape, forward_ctl,
                                         in_mem, ac_src, ac_dest, ac_elem);

  Node* backward_mem = array_copy_backward(phase, can_reshape, backward_ctl,
                                           in_mem, ac_src, ac_dest, ac_elem);

  Node* ctl = nullptr;
  if (!forward_ctl->is_top() && !backward_ctl->is_top()) {
    ctl = new RegionNode(3);
    ctl->init_req(1, forward_ctl);
    ctl->init_req(2, backward_ctl);
    ctl = phase->transform(ctl);
    MergeMemNode* forward_mm = forward_mem->as_MergeMem();
    MergeMemNode* backward_mm = backward_mem->as_MergeMem();
    for (MergeMemStream mms(forward_mm, backward_mm); mms.next_non_empty2(); ) {
      if (mms.memory() != mms.memory2()) {
        Node* phi = new PhiNode(ctl, Type::MEMORY, phase->C->get_adr_type(mms.alias_idx()));
        phi->init_req(1, mms.memory());
        phi->init_req(2, mms.memory2());
        phi = phase->transform(phi);
        mms.set_memory(phi);
      }
    }
    mem = forward_mem;
  } else if (!forward_ctl->is_top()) {
    ctl = forward_ctl;
    mem = forward_mem;
  } else {
    assert(!backward_ctl->is_top(), "no copy?");
    ctl = backward_ctl;
    mem = backward_mem;
  }

  if (can_reshape) {
    assert(phase->is_IterGVN()->delay_transform(), "should be delaying transforms");
    phase->is_IterGVN()->set_delay_transform(false);
  }

  if (!finish_transform(phase, can_reshape, ctl, mem)) {
    if (can_reshape) {
      // put in worklist, so that if it happens to be dead it is removed
      phase->is_IterGVN()->_worklist.push(mem);
    }
    return nullptr;
  }

  return mem;
}

bool ArrayCopyNode::may_modify(const TypeOopPtr* t_oop, PhaseValues* phase) {
  Node* dest = in(ArrayCopyNode::Dest);
  if (dest->is_top()) {
    return false;
  }
  const TypeOopPtr* dest_t = phase->type(dest)->is_oopptr();
  assert(!dest_t->is_known_instance() || _dest_type->is_known_instance(), "result of EA not recorded");
  assert(in(ArrayCopyNode::Src)->is_top() || !phase->type(in(ArrayCopyNode::Src))->is_oopptr()->is_known_instance() ||
         _src_type->is_known_instance(), "result of EA not recorded");

  if (_dest_type != TypeOopPtr::BOTTOM || t_oop->is_known_instance()) {
    assert(_dest_type == TypeOopPtr::BOTTOM || _dest_type->is_known_instance(), "result of EA is known instance");
    return t_oop->instance_id() == _dest_type->instance_id();
  }

  return CallNode::may_modify_arraycopy_helper(dest_t, t_oop, phase);
}

bool ArrayCopyNode::may_modify_helper(const TypeOopPtr* t_oop, Node* n, PhaseValues* phase, ArrayCopyNode*& ac) {
  if (n != nullptr &&
      n->is_ArrayCopy() &&
      n->as_ArrayCopy()->may_modify(t_oop, phase)) {
    ac = n->as_ArrayCopy();
    return true;
  }
  return false;
}

bool ArrayCopyNode::may_modify(const TypeOopPtr* t_oop, MemBarNode* mb, PhaseValues* phase, ArrayCopyNode*& ac) {
  if (mb->trailing_expanded_array_copy()) {
    return true;
  }

  Node* c = mb->in(0);

  BarrierSetC2* bs = BarrierSet::barrier_set()->barrier_set_c2();
  // step over g1 gc barrier if we're at e.g. a clone with ReduceInitialCardMarks off
  c = bs->step_over_gc_barrier(c);

  CallNode* call = nullptr;
  guarantee(c != nullptr, "step_over_gc_barrier failed, there must be something to step to.");
  if (c->is_Region()) {
    for (uint i = 1; i < c->req(); i++) {
      if (c->in(i) != nullptr) {
        Node* n = c->in(i)->in(0);
        if (may_modify_helper(t_oop, n, phase, ac)) {
          assert(c == mb->in(0), "only for clone");
          return true;
        }
      }
    }
  } else if (may_modify_helper(t_oop, c->in(0), phase, ac)) {
#ifdef ASSERT
    bool use_ReduceInitialCardMarks = BarrierSet::barrier_set()->is_a(BarrierSet::CardTableBarrierSet) &&
      static_cast<CardTableBarrierSetC2*>(bs)->use_ReduceInitialCardMarks();
    assert(c == mb->in(0) || (ac->is_clonebasic() && !use_ReduceInitialCardMarks), "only for clone");
#endif
    return true;
  }

  return false;
}

// Does this array copy modify offsets between offset_lo and offset_hi
// in the destination array
// if must_modify is false, return true if the copy could write
// between offset_lo and offset_hi
// if must_modify is true, return true if the copy is guaranteed to
// write between offset_lo and offset_hi
bool ArrayCopyNode::modifies(intptr_t offset_lo, intptr_t offset_hi, PhaseValues* phase, bool must_modify) const {
  assert(_kind == ArrayCopy || _kind == CopyOf || _kind == CopyOfRange, "only for real array copies");

  Node* dest = in(Dest);
  Node* dest_pos = in(DestPos);
  Node* len = in(Length);

  const TypeInt *dest_pos_t = phase->type(dest_pos)->isa_int();
  const TypeInt *len_t = phase->type(len)->isa_int();
  const TypeAryPtr* ary_t = phase->type(dest)->isa_aryptr();

  if (dest_pos_t == nullptr || len_t == nullptr || ary_t == nullptr) {
    return !must_modify;
  }

  BasicType ary_elem = ary_t->isa_aryptr()->elem()->array_element_basic_type();
  if (is_reference_type(ary_elem, true)) ary_elem = T_OBJECT;

  uint header = arrayOopDesc::base_offset_in_bytes(ary_elem);
  uint elemsize = type2aelembytes(ary_elem);

  jlong dest_pos_plus_len_lo = (((jlong)dest_pos_t->_lo) + len_t->_lo) * elemsize + header;
  jlong dest_pos_plus_len_hi = (((jlong)dest_pos_t->_hi) + len_t->_hi) * elemsize + header;
  jlong dest_pos_lo = ((jlong)dest_pos_t->_lo) * elemsize + header;
  jlong dest_pos_hi = ((jlong)dest_pos_t->_hi) * elemsize + header;

  if (must_modify) {
    if (offset_lo >= dest_pos_hi && offset_hi < dest_pos_plus_len_lo) {
      return true;
    }
  } else {
    if (offset_hi >= dest_pos_lo && offset_lo < dest_pos_plus_len_hi) {
      return true;
    }
  }
  return false;
}

#ifndef PRODUCT
// Print the compile-time OptSmallArrayCopy histogram.  Called at JVM exit
// from Compile::print_statistics().
void ArrayCopyNode::print_opt_small_statistics() {
  bool any = false;
  for (int t = T_BOOLEAN; t <= T_LONG && !any; t++) {
    for (int mc = 1; mc <= ArrayCopyLoadStoreMaxElem && !any; mc++) {
      any = (_opt_small_counts[t][mc] > 0);
    }
  }

  tty->print_cr("OptSmallArrayCopy variable-length array compiled (type, count, sites):");
  if (!any) {
    tty->print_cr("  (none)");
    return;
  }
  const BasicType types[] = { T_BYTE, T_SHORT, T_INT, T_LONG };
  for (int ti = 0; ti < 4; ti++) {
    BasicType bt = types[ti];
    int row_total = 0;
    for (int mc = 1; mc <= ArrayCopyLoadStoreMaxElem; mc++) row_total += _opt_small_counts[(int)bt][mc];
    if (row_total == 0) continue;
    tty->print("  %-6s (total=%d):", type2name(bt), row_total);
    for (int mc = 1; mc <= ArrayCopyLoadStoreMaxElem; mc++) {
      int cnt = _opt_small_counts[(int)bt][mc];
      if (cnt > 0) tty->print("  [..%d]=%d", mc, cnt);
    }
    tty->cr();
  }
}
#endif

// As an optimization, choose the optimal vector size for bounded copy length
int ArrayCopyNode::get_partial_inline_vector_lane_count(BasicType type, jlong max_len) {
  assert(max_len > 0, JLONG_FORMAT, max_len);
  // We only care whether max_size_in_bytes is not larger than 32, we also want to avoid
  // multiplication overflow, so clamp max_len to [0, 64]
  int max_size_in_bytes = MIN2<jlong>(max_len, 64) * type2aelembytes(type);
  if (ArrayOperationPartialInlineSize > 16 && max_size_in_bytes <= 16) {
    return 16 / type2aelembytes(type);
  } else if (ArrayOperationPartialInlineSize > 32 && max_size_in_bytes <= 32) {
    return 32 / type2aelembytes(type);
  } else {
    return ArrayOperationPartialInlineSize / type2aelembytes(type);
  }
}
