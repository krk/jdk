/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
 */

package compiler.escapeAnalysis;

import compiler.lib.ir_framework.*;
import java.util.Objects;

/*
 * @test
 * @bug 8155769
 * @summary Finding uncaptured initializing stores via AddP uses lets EA
 *          scalar-replace all allocations in one round instead of needing
 *          extra iterative-EA rounds.
 * @library /test/lib /
 * @run driver compiler.escapeAnalysis.TestInnerClassEA
 */
public class TestInnerClassEA {

    static class Value {
        int x;

        Value(int v) {
            this.x = v;
        }
    }

    static class Holder {
        Value val;

        Holder(Value v) {
            this.val = v;
        }
    }

    public static void main(String[] args) {
        TestFramework framework = new TestFramework();
        framework.addScenarios(
                new Scenario(0),
                new Scenario(1, "-XX:-ReduceFieldZeroing"),
                new Scenario(2, "-XX:+UnlockDiagnosticVMOptions", "-XX:-RecoverInitStores", "-XX:-ReduceFieldZeroing"),
                new Scenario(3, "-XX:+UnlockDiagnosticVMOptions", "-XX:-RecoverInitStores"));
        framework.start();
    }

    // Non-final static fields: the compiler cannot prove these are
    // non-null, so requireNonNull guards on them persist even with
    // RFZ on.  Used by the RFZ-independent test variants below.
    static Object GUARD  = new Object();
    static Object GUARD2 = new Object();

    @DontInline
    static void blackhole() {
    }

    @Test
    @Arguments(values = { Argument.RANDOM_EACH })
    @IR(failOn = { IRNode.ALLOC })
    // EA_AFTER_PROPAGATE_NSR is printed once per iterative-EA round,
    // before that round eliminates allocations. KEEP_LAST keeps the
    // last round's snapshot.
    //
    // With -ReduceFieldZeroing the store to Holder.val is not captured
    // by InitializeNode. The fix finds it via AddP uses, so both
    // allocations are SR in round 1 (the only round) — 2 Allocates.
    @IR(counts = { IRNode.ALLOC, "2" },
        phase = CompilePhase.EA_AFTER_PROPAGATE_NSR,
        applyIfAnd = { "ReduceFieldZeroing", "false", "RecoverInitStores", "true" })
    // Without the fix, Value is NSR in round 1, only Holder is eliminated,
    // and iterative EA needs round 2 where only Value remains — 1 Allocate.
    @IR(counts = { IRNode.ALLOC, "1" },
        phase = CompilePhase.EA_AFTER_PROPAGATE_NSR,
        applyIfAnd = { "ReduceFieldZeroing", "false", "RecoverInitStores", "false" })
    static int test(int x) {
        Value v = new Value(x);
        Holder h = new Holder(v);
        blackhole();
        return h.val.x;
    }

    // --- Objects.requireNonNull pattern ---
    // After inlining, requireNonNull inserts a null check whose IfTrue
    // becomes the store's control.  The fix walks through the guard
    // (sibling is uncommon trap) to find init_ctrl.

    static class NNHolder {
        Value val;

        NNHolder(Value v) {
            this.val = Objects.requireNonNull(v);
        }
    }

    @Test
    @Arguments(values = { Argument.RANDOM_EACH })
    @IR(failOn = { IRNode.ALLOC })
    // With RFZ on, IGVN removes the null check (value is provably non-null)
    // and RFZ captures the store.  The fix matters when RFZ is off.
    @IR(counts = { IRNode.ALLOC, "2" },
        phase = CompilePhase.EA_AFTER_PROPAGATE_NSR,
        applyIfAnd = { "ReduceFieldZeroing", "false", "RecoverInitStores", "true" })
    @IR(counts = { IRNode.ALLOC, "1" },
        phase = CompilePhase.EA_AFTER_PROPAGATE_NSR,
        applyIfAnd = { "ReduceFieldZeroing", "false", "RecoverInitStores", "false" })
    static int testRequireNonNull(int x) {
        Value v = new Value(x);
        NNHolder h = new NNHolder(v);
        blackhole();
        return h.val.x;
    }

    // --- Chained guard checks (2 IfProj steps) ---
    // Both fields are behind requireNonNull guards.  The store to v2
    // is dominated by init_ctrl through 2 guard projections.

    static class DoubleNNHolder {
        Value v1;
        Value v2;

        DoubleNNHolder(Value a, Value b) {
            this.v1 = Objects.requireNonNull(a);
            this.v2 = Objects.requireNonNull(b);
        }
    }

    @Test
    @Arguments(values = { Argument.RANDOM_EACH })
    @IR(failOn = { IRNode.ALLOC })
    // Fix finds both stores through guard projections: all 3 SR in round 1.
    @IR(counts = { IRNode.ALLOC, "3" },
        phase = CompilePhase.EA_AFTER_PROPAGATE_NSR,
        applyIfAnd = { "ReduceFieldZeroing", "false", "RecoverInitStores", "true" })
    // Without fix, both Values are NSR.  Round 1 eliminates only
    // DoubleNNHolder.  Round 2 has the 2 remaining Values.
    @IR(counts = { IRNode.ALLOC, "2" },
        phase = CompilePhase.EA_AFTER_PROPAGATE_NSR,
        applyIfAnd = { "ReduceFieldZeroing", "false", "RecoverInitStores", "false" })
    static int testChainedChecks(int x) {
        Value a = new Value(x);
        Value b = new Value(x + 1);
        DoubleNNHolder h = new DoubleNNHolder(a, b);
        blackhole();
        return h.v1.x + h.v2.x;
    }

    // --- Field-guard pattern (RFZ-independent) ---
    // The null check is on a value loaded from a non-final static field
    // (GUARD), which the compiler cannot prove is non-null.  Unlike
    // requireNonNull on a freshly-allocated object (provably non-null),
    // IGVN cannot remove this guard even with RFZ on.  This matches
    // real-world patterns where stores are behind guards on values
    // the compiler cannot statically resolve.

    static class GuardHolder {
        Value val;

        GuardHolder(Value v, Object guard) {
            Objects.requireNonNull(guard);
            this.val = v;
        }
    }

    @Test
    @Arguments(values = { Argument.RANDOM_EACH })
    @IR(failOn = { IRNode.ALLOC })
    // Fix finds the store through the null-check guard regardless
    // of RFZ: both allocations SR in round 1 — 2 Allocates.
    @IR(counts = { IRNode.ALLOC, "2" },
        phase = CompilePhase.EA_AFTER_PROPAGATE_NSR,
        applyIf = { "RecoverInitStores", "true" })
    // Without fix, Value is NSR.  Round 2 has only Value — 1 Allocate.
    @IR(counts = { IRNode.ALLOC, "1" },
        phase = CompilePhase.EA_AFTER_PROPAGATE_NSR,
        applyIf = { "RecoverInitStores", "false" })
    static int testFieldGuard(int x) {
        Value v = new Value(x);
        GuardHolder h = new GuardHolder(v, GUARD);
        blackhole();
        return h.val.x;
    }

    // --- Two stores behind two field-guard checks (RFZ-independent) ---
    // Two separate static-field guards produce 2 IfProj steps from
    // init_ctrl.  GUARD and GUARD2 are different fields so the null
    // checks cannot be CSE'd.

    static class DoubleGuardHolder {
        Value v1;
        Value v2;

        DoubleGuardHolder(Value a, Value b, Object g1, Object g2) {
            Objects.requireNonNull(g1);
            this.v1 = a;
            Objects.requireNonNull(g2);
            this.v2 = b;
        }
    }

    @Test
    @Arguments(values = { Argument.RANDOM_EACH })
    @IR(failOn = { IRNode.ALLOC })
    // Fix finds both stores through the guards: all 3 SR in round 1.
    @IR(counts = { IRNode.ALLOC, "3" },
        phase = CompilePhase.EA_AFTER_PROPAGATE_NSR,
        applyIf = { "RecoverInitStores", "true" })
    // Without fix, both Values are NSR.  Round 2 has 2 Values.
    @IR(counts = { IRNode.ALLOC, "2" },
        phase = CompilePhase.EA_AFTER_PROPAGATE_NSR,
        applyIf = { "RecoverInitStores", "false" })
    static int testChainedFieldGuard(int x) {
        Value a = new Value(x);
        Value b = new Value(x + 1);
        DoubleGuardHolder h = new DoubleGuardHolder(a, b, GUARD, GUARD2);
        blackhole();
        return h.v1.x + h.v2.x;
    }

    // --- Call before store (CatchProj walk) ---
    // A non-inlined call inside the constructor creates a CatchProj
    // between init_ctrl and the store.  The fix walks through the
    // normal-return CatchProj to prove the store is unconditional.
    // RFZ cannot capture the store either (store's control is the
    // CatchProj, not init_ctrl), so this is RFZ-independent.

    static class CallHolder {
        Value val;

        CallHolder(Value v) {
            blackhole();
            this.val = v;
        }
    }

    @Test
    @Arguments(values = { Argument.RANDOM_EACH })
    @IR(failOn = { IRNode.ALLOC })
    @IR(counts = { IRNode.ALLOC, "2" },
        phase = CompilePhase.EA_AFTER_PROPAGATE_NSR,
        applyIf = { "RecoverInitStores", "true" })
    @IR(counts = { IRNode.ALLOC, "1" },
        phase = CompilePhase.EA_AFTER_PROPAGATE_NSR,
        applyIf = { "RecoverInitStores", "false" })
    static int testCallBeforeStore(int x) {
        Value v = new Value(x);
        CallHolder h = new CallHolder(v);
        blackhole();
        return h.val.x;
    }

    // --- Conditional store (negative) ---
    // The store is behind a normal if-else (not an uncommon trap guard).
    // @Warmup(0) prevents profiling so C2 keeps both branches as normal
    // paths instead of converting the biased branch into an uncommon trap.
    // The fix must NOT treat this as an unconditional initializing store.
    // Both fix-on and fix-off need iterative EA, so last-round alloc
    // count is the same (1).

    static class ConditionalHolder {
        Value val;

        ConditionalHolder(Value v, boolean init) {
            if (init) {
                this.val = v;
            }
        }
    }

    @Test
    @Warmup(0)
    @Arguments(values = { Argument.RANDOM_EACH, Argument.TRUE })
    @IR(failOn = { IRNode.ALLOC })
    @IR(counts = { IRNode.ALLOC, "1" },
        phase = CompilePhase.EA_AFTER_PROPAGATE_NSR,
        applyIfAnd = { "ReduceFieldZeroing", "false", "RecoverInitStores", "true" })
    @IR(counts = { IRNode.ALLOC, "1" },
        phase = CompilePhase.EA_AFTER_PROPAGATE_NSR,
        applyIfAnd = { "ReduceFieldZeroing", "false", "RecoverInitStores", "false" })
    static int testConditional(int x, boolean init) {
        Value v = new Value(x);
        ConditionalHolder h = new ConditionalHolder(v, init);
        blackhole();
        return h.val != null ? h.val.x : 0;
    }

}
