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
#include "opto/graphKit.hpp"
#include "opto/rootnode.hpp"
#include "utilities/powerOfTwo.hpp"

const TypeFunc* ArrayCopyNode::_arraycopy_type_Type = nullptr;

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
                                       Node*& adr_src,
                                       Node*& base_src,
                                       Node*& adr_dest,
                                       Node*& base_dest,
                                       BasicType& copy_type,
                                       const Type*& value_type,
                                       bool& disjoint_bases) {
  base_src = in(ArrayCopyNode::Src);
  base_dest = in(ArrayCopyNode::Dest);
  const Type* src_type = phase->type(base_src);
  const TypeAryPtr* ary_src = src_type->isa_aryptr();

  Node* src_offset = in(ArrayCopyNode::SrcPos);
  Node* dest_offset = in(ArrayCopyNode::DestPos);

  if (is_arraycopy() || is_copyofrange() || is_copyof()) {
    const Type* dest_type = phase->type(base_dest);
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

    value_type = ary_src->elem();

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

    adr_src          = phase->transform(new AddPNode(base_src, base_src, src_scale));
    adr_dest         = phase->transform(new AddPNode(base_dest, base_dest, dest_scale));

    adr_src          = phase->transform(new AddPNode(base_src, adr_src, phase->MakeConX(header)));
    adr_dest         = phase->transform(new AddPNode(base_dest, adr_dest, phase->MakeConX(header)));

    copy_type = dest_elem;
  } else {
    assert(ary_src != nullptr, "should be a clone");
    assert(is_clonebasic(), "should be");

    disjoint_bases = true;

    BasicType elem = ary_src->isa_aryptr()->elem()->array_element_basic_type();
    if (is_reference_type(elem, true)) {
      elem = T_OBJECT;
    }

    BarrierSetC2* bs = BarrierSet::barrier_set()->barrier_set_c2();
    if (bs->array_copy_requires_gc_barriers(true, elem, true, is_clone_inst(), BarrierSetC2::Optimization)) {
      return false;
    }

    adr_src  = phase->transform(new AddPNode(base_src, base_src, src_offset));
    adr_dest = phase->transform(new AddPNode(base_dest, base_dest, dest_offset));

    // The address is offsetted to an aligned address where a raw copy would start.
    // If the clone copy is decomposed into load-stores - the address is adjusted to
    // point at where the array starts.
    const Type* toff = phase->type(src_offset);
    int offset = toff->isa_long() ? (int) toff->is_long()->get_con() : (int) toff->is_int()->get_con();
    int diff = arrayOopDesc::base_offset_in_bytes(elem) - offset;
    assert(diff >= 0, "clone should not start after 1st array element");
    if (diff > 0) {
      adr_src = phase->transform(new AddPNode(base_src, adr_src, phase->MakeConX(diff)));
      adr_dest = phase->transform(new AddPNode(base_dest, adr_dest, phase->MakeConX(diff)));
    }
    copy_type = elem;
    value_type = ary_src->elem();
  }
  return true;
}

const TypePtr* ArrayCopyNode::get_address_type(PhaseGVN* phase, const TypePtr* atp, Node* n) {
  if (atp == TypeOopPtr::BOTTOM) {
    atp = phase->type(n)->isa_ptr();
  }
  // adjust atp to be the correct array element address type
  return atp->add_offset(Type::OffsetBot);
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

Node* ArrayCopyNode::array_copy_forward(PhaseGVN *phase,
                                        bool can_reshape,
                                        Node*& forward_ctl,
                                        Node* mem,
                                        const TypePtr* atp_src,
                                        const TypePtr* atp_dest,
                                        Node* adr_src,
                                        Node* base_src,
                                        Node* adr_dest,
                                        Node* base_dest,
                                        BasicType copy_type,
                                        const Type* value_type,
                                        int count) {
  if (!forward_ctl->is_top()) {
    // copy forward
    MergeMemNode* mm = MergeMemNode::make(mem);

    if (count > 0) {
      BarrierSetC2* bs = BarrierSet::barrier_set()->barrier_set_c2();
      Node* v = load(bs, phase, forward_ctl, mm, adr_src, atp_src, value_type, copy_type);
      store(bs, phase, forward_ctl, mm, adr_dest, atp_dest, v, value_type, copy_type);
      for (int i = 1; i < count; i++) {
        Node* off  = phase->MakeConX(type2aelembytes(copy_type) * i);
        Node* next_src = phase->transform(new AddPNode(base_src,adr_src,off));
        Node* next_dest = phase->transform(new AddPNode(base_dest,adr_dest,off));
        v = load(bs, phase, forward_ctl, mm, next_src, atp_src, value_type, copy_type);
        store(bs, phase, forward_ctl, mm, next_dest, atp_dest, v, value_type, copy_type);
      }
    } else if (can_reshape) {
      PhaseIterGVN* igvn = phase->is_IterGVN();
      igvn->_worklist.push(adr_src);
      igvn->_worklist.push(adr_dest);
    }
    return mm;
  }
  return phase->C->top();
}

Node* ArrayCopyNode::array_copy_backward(PhaseGVN *phase,
                                         bool can_reshape,
                                         Node*& backward_ctl,
                                         Node* mem,
                                         const TypePtr* atp_src,
                                         const TypePtr* atp_dest,
                                         Node* adr_src,
                                         Node* base_src,
                                         Node* adr_dest,
                                         Node* base_dest,
                                         BasicType copy_type,
                                         const Type* value_type,
                                         int count) {
  if (!backward_ctl->is_top()) {
    // copy backward
    MergeMemNode* mm = MergeMemNode::make(mem);

    BarrierSetC2* bs = BarrierSet::barrier_set()->barrier_set_c2();
    assert(copy_type != T_OBJECT || !bs->array_copy_requires_gc_barriers(false, T_OBJECT, false, false, BarrierSetC2::Optimization), "only tightly coupled allocations for object arrays");

    if (count > 0) {
      for (int i = count-1; i >= 1; i--) {
        Node* off  = phase->MakeConX(type2aelembytes(copy_type) * i);
        Node* next_src = phase->transform(new AddPNode(base_src,adr_src,off));
        Node* next_dest = phase->transform(new AddPNode(base_dest,adr_dest,off));
        Node* v = load(bs, phase, backward_ctl, mm, next_src, atp_src, value_type, copy_type);
        store(bs, phase, backward_ctl, mm, next_dest, atp_dest, v, value_type, copy_type);
      }
      Node* v = load(bs, phase, backward_ctl, mm, adr_src, atp_src, value_type, copy_type);
      store(bs, phase, backward_ctl, mm, adr_dest, atp_dest, v, value_type, copy_type);
    } else if (can_reshape) {
      PhaseIterGVN* igvn = phase->is_IterGVN();
      igvn->_worklist.push(adr_src);
      igvn->_worklist.push(adr_dest);
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


// Eliminate a clone of a freshly-allocated non-escaping array.
//
// Pattern:
//   A = AllocateArray(T[], len)          // source (non-escaping)
//   fill: arraycopy(external -> A) or stores to A
//   B = AllocateArray(T[], A.length)     // clone destination (tightly coupled)
//   clone: ArrayCopy(CloneArray, src=A, dst=B)
//   ... uses of B ...                    // A is dead after clone
//
// Transform: replace all uses of B with A, remove the clone.
// After this, B's allocation becomes dead and is cleaned up by
// the existing allocation elimination pass.
//
// This is NOT scalar replacement: it works for any array length
// (including non-constant) because we simply reuse the source
// allocation instead of breaking it into scalars.
//
Node* ArrayCopyNode::try_eliminate_redundant_clone(PhaseGVN* phase) {
  assert(is_clone_array(), "only for array clones");

  Node* src = in(ArrayCopyNode::Src);
  Node* dst = in(ArrayCopyNode::Dest);

  // Source must be a fresh array allocation.
  if (src == nullptr || !src->is_CheckCastPP()) { NOT_PRODUCT(if (PrintEliminateAllocations) tty->print_cr("EliminateRedundantClone: bail - src not CheckCastPP")); return nullptr; }
  AllocateArrayNode* src_alloc = nullptr;
  {
    AllocateNode* a = AllocateNode::Ideal_allocation(src);
    if (a == nullptr || !a->is_AllocateArray()) { NOT_PRODUCT(if (PrintEliminateAllocations) tty->print_cr("EliminateRedundantClone: bail - src not AllocateArray")); return nullptr; }
    src_alloc = a->as_AllocateArray();
  }

  // Source allocation must be marked non-escaping by EA.
  if (!src_alloc->_is_non_escaping) { NOT_PRODUCT(if (PrintEliminateAllocations) tty->print_cr("EliminateRedundantClone: bail - src escaping")); return nullptr; }

  // If the source is scalar-replaceable, PhaseMacroExpand will handle both
  // allocations via scalar replacement.  Our optimization would conflict.
  if (src_alloc->_is_scalar_replaceable) { NOT_PRODUCT(if (PrintEliminateAllocations) tty->print_cr("EliminateRedundantClone: bail - src scalar replaceable")); return nullptr; }

  // Destination must also be a fresh allocation (tightly coupled with clone).
  if (dst == nullptr || !dst->is_CheckCastPP()) { NOT_PRODUCT(if (PrintEliminateAllocations) tty->print_cr("EliminateRedundantClone: bail - dst not CheckCastPP")); return nullptr; }
  if (AllocateNode::Ideal_allocation(dst) == nullptr) { NOT_PRODUCT(if (PrintEliminateAllocations) tty->print_cr("EliminateRedundantClone: bail - dst not Allocate")); return nullptr; }

  // Already transformed (src == dst means we already replaced dst with src).
  if (src == dst) return nullptr;

  // Verify that the source array has no uses that would be
  // invalidated by replacing the clone destination with the source.
  //
  // Allowed uses of src (A):
  //   - This clone ArrayCopyNode (as Src input)
  //   - An ArrayCopyNode where A is the Dest (the "fill")
  //   - SafePoints with debug-only references to A
  //   - AddP nodes whose users are all stores (filling the array)
  //   - MemBarStoreStore / MemBarCPUOrder (from init)
  //
  // Disallowed: loads from A after the clone, A passed as a non-debug
  // call argument, A returned, A in a Phi, etc.
  for (DUIterator_Fast imax, i = src->fast_outs(imax); i < imax; i++) {
    Node* use = src->fast_out(i);

    if (use == this) continue;  // this clone itself

    if (use->is_ArrayCopy()) {
      // A as Dest of another arraycopy (the fill) is fine.
      // A as Src of a different arraycopy means A's data is needed
      // elsewhere — we can't safely replace B with A in that case.
      if (use->in(ArrayCopyNode::Dest) == src) continue;  // fill
      NOT_PRODUCT(if (PrintEliminateAllocations) { tty->print("EliminateRedundantClone: bail - src used as ArrayCopy src: "); use->dump(); })
      return nullptr;
    }

    if (use->is_SafePoint()) {
      SafePointNode* sfpt = use->as_SafePoint();
      if (sfpt->is_Call() && sfpt->as_Call()->has_non_debug_use(src)) {
        NOT_PRODUCT(if (PrintEliminateAllocations) { tty->print("EliminateRedundantClone: bail - non-debug call arg: "); use->dump(); })
        return nullptr;  // A is a non-debug call argument
      }
      continue;  // debug info only
    }

    if (use->is_AddP()) {
      // AddP computes an address into A.  Only stores (fills) and
      // arraycopy stubs writing to A are allowed — no loads.
      for (DUIterator_Fast kmax, k = use->fast_outs(kmax); k < kmax; k++) {
        Node* addr_use = use->fast_out(k);
        if (addr_use->is_Store() || addr_use->is_ArrayCopy()) {
          // Verify this write is pre-clone: its control must dominate
          // the clone so the write happens before the clone in program
          // order.  Post-clone writes would become visible after
          // replacing B with A, violating clone semantics.
          if (!MemNode::all_controls_dominate(addr_use, this)) {
            NOT_PRODUCT(if (PrintEliminateAllocations) { tty->print("EliminateRedundantClone: bail - post-clone write: "); addr_use->dump(); })
            return nullptr;
          }
          continue;
        }
        if (addr_use->is_CallLeaf() &&
            addr_use->as_CallLeaf()->is_call_to_arraycopystub()) {
          // CallLeaf arraycopy stubs (e.g., unsafe_arraycopy from
          // Unsafe.copyMemory) may be on conditional paths — the
          // stub is guarded by a length > 0 check, creating a
          // control diamond that merges before the clone.  We skip
          // the domination check here: since A is non-escaping and
          // all uses are validated, any such write is necessarily
          // pre-clone (it feeds into the clone's input memory).
          continue;
        }
        if (addr_use->Opcode() == Op_CastP2X) continue;  // card mark
        // A load or anything else — bail.
        NOT_PRODUCT(if (PrintEliminateAllocations) { tty->print("EliminateRedundantClone: bail - AddP has non-store use: "); addr_use->dump(); })
        return nullptr;
      }
      continue;
    }

    if (use->Opcode() == Op_MemBarStoreStore ||
        use->Opcode() == Op_MemBarCPUOrder) {
      continue;
    }

    NOT_PRODUCT(if (PrintEliminateAllocations) { tty->print("EliminateRedundantClone: bail - unrecognized use: "); use->dump(); })
    return nullptr;  // unrecognized use
  }

  // Verify output memory chain pattern: ArrayCopy -> ProjMem -> MergeMem -> MemBar.
  // This is the same pattern that finish_transform checks for clonebasic.
  Node* out_mem = proj_out(TypeFunc::Memory);
  if (out_mem == nullptr) { NOT_PRODUCT(if (PrintEliminateAllocations) tty->print_cr("EliminateRedundantClone: bail - no out_mem")); return nullptr; }
  if (out_mem->outcnt() != 1 || !out_mem->raw_out(0)->is_MergeMem()) { NOT_PRODUCT(if (PrintEliminateAllocations) tty->print_cr("EliminateRedundantClone: bail - out_mem pattern mismatch")); return nullptr; }
  Node* old_mm = out_mem->raw_out(0);
  if (old_mm->outcnt() != 1 || !old_mm->raw_out(0)->is_MemBar()) { NOT_PRODUCT(if (PrintEliminateAllocations) tty->print_cr("EliminateRedundantClone: bail - MergeMem->MemBar mismatch")); return nullptr; }

  Node* out_ctl = proj_out(TypeFunc::Control);
  if (out_ctl == nullptr) return nullptr;

  // === All checks passed — perform the transformation. ===
  PhaseIterGVN* igvn = phase->is_IterGVN();

  Node* in_mem = in(TypeFunc::Memory);
  Node* in_ctl = in(TypeFunc::Control);

  NOT_PRODUCT(if (PrintEliminateAllocations) {
    tty->print("EliminateRedundantClone: replacing clone dst with src  ");
    this->dump();
  })

  // 1. Handle SafePoint debug info — replace dst with SafePointScalarCloneNode.
  //    This ensures deoptimization allocates a fresh copy instead of aliasing
  //    original and clone to the same object (which violates clone() != original).
  {
    // Collect SafePoints that reference dst in debug info.
    // Must collect first because replace_edges_in_range invalidates DU iterators.
    GrowableArray<SafePointNode*> sfpts;
    for (DUIterator_Fast imax, i = dst->fast_outs(imax); i < imax; i++) {
      Node* use = dst->fast_out(i);
      if (use->is_SafePoint()) {
        SafePointNode* sfpt = use->as_SafePoint();
        JVMState* jvms = sfpt->jvms();
        if (jvms != nullptr) {
          for (uint j = jvms->debug_start(); j < jvms->debug_end(); j++) {
            if (sfpt->in(j) == dst) {
              sfpts.append_if_missing(sfpt);
              break;
            }
          }
        }
      }
    }

    // For each SafePoint: replace dst in debug info with SafePointScalarCloneNode.
    const TypeOopPtr* dst_type = igvn->type(dst)->is_oopptr();
    for (int k = 0; k < sfpts.length(); k++) {
      SafePointNode* sfpt = sfpts.at(k);
      JVMState* jvms = sfpt->jvms();

      // Add src to SafePoint's scalar area (keeps src alive at this SafePoint).
      uint src_ind = sfpt->req() - jvms->scloff();
      sfpt->add_req(src);
      jvms->set_endoff(sfpt->req());

      // Create SafePointScalarCloneNode.
      SafePointScalarCloneNode* sclone = new SafePointScalarCloneNode(
          dst_type, src_ind, jvms->depth());
      sclone->init_req(0, phase->C->root());
      igvn->register_new_node_with_optimizer(sclone);

      // Replace dst with sclone in debug range only.
      sfpt->replace_edges_in_range(dst, sclone,
          jvms->debug_start(), jvms->debug_end(), igvn);
      igvn->_worklist.push(sfpt);
    }
  }

  // 2. Replace remaining (non-debug) uses of dst with src.
  igvn->replace_node(dst, src);

  // 3. Create a new MergeMemNode that passes through the input memory.
  //    This satisfies Ideal()'s return-value contract: the returned node
  //    must have _idx >= this->_idx (or be top).  IGVN will subsume 'this'
  //    with this MergeMemNode, then MergeMemNode::Identity will simplify
  //    it down to in_mem.
  MergeMemNode* mem = MergeMemNode::make(in_mem);
  igvn->register_new_node_with_optimizer(mem);

  // 4. Route the clone's output memory through our passthrough node.
  //    Replace the old MergeMem (between ProjMem and MemBar) with ours.
  igvn->replace_node(old_mm, mem);

  // 5. Route the clone's output control to the input control.
  igvn->replace_node(out_ctl, in_ctl);

  // 6. Remove from macro node list (no longer needs macro expansion).
  phase->C->remove_macro_node(this);

  return mem;
}

Node *ArrayCopyNode::Ideal(PhaseGVN *phase, bool can_reshape) {
  if (remove_dead_region(phase, can_reshape))  return this;
  if (can_reshape && is_clone_array() && EliminateRedundantClone) {
    Node* result = try_eliminate_redundant_clone(phase);
    if (result != nullptr) {
      return result;
    }
  }

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
    return nullptr;
  }

  Node* mem = try_clone_instance(phase, can_reshape, count);
  if (mem != nullptr) {
    return (mem == NodeSentinel) ? nullptr : mem;
  }

  Node* adr_src = nullptr;
  Node* base_src = nullptr;
  Node* adr_dest = nullptr;
  Node* base_dest = nullptr;
  BasicType copy_type = T_ILLEGAL;
  const Type* value_type = nullptr;
  bool disjoint_bases = false;

  if (!prepare_array_copy(phase, can_reshape,
                          adr_src, base_src, adr_dest, base_dest,
                          copy_type, value_type, disjoint_bases)) {
    assert(adr_src == nullptr, "no node can be left behind");
    assert(adr_dest == nullptr, "no node can be left behind");
    return nullptr;
  }

  Node* src = in(ArrayCopyNode::Src);
  Node* dest = in(ArrayCopyNode::Dest);
  const TypePtr* atp_src = get_address_type(phase, _src_type, src);
  const TypePtr* atp_dest = get_address_type(phase, _dest_type, dest);
  Node* in_mem = in(TypeFunc::Memory);

  if (can_reshape) {
    assert(!phase->is_IterGVN()->delay_transform(), "cannot delay transforms");
    phase->is_IterGVN()->set_delay_transform(true);
  }

  Node* backward_ctl = phase->C->top();
  Node* forward_ctl = phase->C->top();
  array_copy_test_overlap(phase, can_reshape, disjoint_bases, count, forward_ctl, backward_ctl);

  Node* forward_mem = array_copy_forward(phase, can_reshape, forward_ctl,
                                         in_mem,
                                         atp_src, atp_dest,
                                         adr_src, base_src, adr_dest, base_dest,
                                         copy_type, value_type, count);

  Node* backward_mem = array_copy_backward(phase, can_reshape, backward_ctl,
                                           in_mem,
                                           atp_src, atp_dest,
                                           adr_src, base_src, adr_dest, base_dest,
                                           copy_type, value_type, count);

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
