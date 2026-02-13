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
package compiler.c2.irTests.scalarReplacement;

import jdk.incubator.vector.Float16;
import jdk.test.lib.Asserts;
import compiler.lib.ir_framework.*;
import compiler.lib.ir_framework.CompilePhase;

/*
 * @test
 * @bug 8375321
 * @summary Verify that Float16 boxing loop-carried Phi nodes are correctly
 *          eliminated by AggressiveUnboxing's split_through_phi via the
 *          is_boxlike_klass extension.
 * @modules jdk.incubator.vector
 * @library /test/lib /
 * @requires vm.debug == true & vm.flagless & vm.bits == 64 & vm.compiler2.enabled & vm.opt.final.EliminateAllocations
 * @run driver compiler.c2.irTests.scalarReplacement.Float16BoxingLoopPhiTests
 */
public class Float16BoxingLoopPhiTests {

    static final int LEN = 1024;
    static float[] floatInput;

    static {
        floatInput = new float[LEN];
        for (int i = 0; i < LEN; i++) {
            floatInput[i] = (float) (i + 1);
        }
    }

    public static void main(String[] args) {
        TestFramework framework = new TestFramework();

        // Float16 is in the jdk.incubator.vector module.
        // TraceReduceAllocationMerges enables VerifyReduceAllocationMerges
        // assertions in debug builds.

        Scenario compressedOops = new Scenario(0,
                "--add-modules=jdk.incubator.vector",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+ReduceAllocationMerges",
                "-XX:+TraceReduceAllocationMerges",
                "-XX:+UseCompressedOops",
                "-XX:+UseCompressedClassPointers");

        Scenario noCompressedOops = new Scenario(1,
                "--add-modules=jdk.incubator.vector",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+ReduceAllocationMerges",
                "-XX:+TraceReduceAllocationMerges",
                "-XX:-UseCompressedOops");

        framework.addScenarios(compressedOops, noCompressedOops).start();
    }

    // ---------------------- Positive Tests ---------------------- //
    // Float16.valueOf(float) always allocates (no cache).
    // With is_boxlike_klass, AggressiveUnboxing's split_through_phi
    // eliminates these boxing allocations.

    @Run(test = {"testFloat16MinReduction_C2",
                 "testFloat16SumReduction_C2"})
    public void runPositiveTests(RunInfo info) {
        float[] fa = floatInput;
        Asserts.assertEQ(testFloat16MinReduction_Interp(fa), testFloat16MinReduction_C2(fa));
        Asserts.assertEQ(testFloat16SumReduction_Interp(fa), testFloat16SumReduction_C2(fa));
    }

    // ---- testFloat16MinReduction ----
    // Mirrors the Float BoxingLoopPhiTests pattern: no intermediate boxing
    // for arr[i], only one Float16 allocation per loop iteration.

    @ForceInline
    static float testFloat16MinReduction(float[] arr) {
        Float16 acc = Float16.valueOf(65504.0f); // max finite float16
        for (int i = 0; i < arr.length; i++) {
            float av = acc.floatValue();
            acc = Float16.valueOf(Math.min(av, arr[i]));
        }
        return acc.floatValue();
    }

    @Test
    @IR(counts = { IRNode.ALLOC, ">= 1" }, phase = CompilePhase.INCREMENTAL_BOXING_INLINE)
    @IR(failOn = { IRNode.ALLOC })
    @IR(counts = { IRNode.LOAD_VECTOR_F,   "> 0",
                   IRNode.MIN_VF,          "> 0",
                   IRNode.MIN_REDUCTION_V, "> 0" },
        applyIfCPUFeatureOr = {"avx512_fp16", "true"})
    static float testFloat16MinReduction_C2(float[] arr) {
        return testFloat16MinReduction(arr);
    }

    @DontCompile
    static float testFloat16MinReduction_Interp(float[] arr) {
        return testFloat16MinReduction(arr);
    }

    // ---- testFloat16SumReduction ----

    @ForceInline
    static float testFloat16SumReduction(float[] arr) {
        Float16 acc = Float16.valueOf(0.0f);
        for (int i = 0; i < arr.length; i++) {
            float sum = acc.floatValue() + Float16.valueOf(arr[i]).floatValue();
            acc = Float16.valueOf(sum);
        }
        return acc.floatValue();
    }

    @Test
    @IR(counts = { IRNode.ALLOC, ">= 1" }, phase = CompilePhase.INCREMENTAL_BOXING_INLINE)
    @IR(failOn = { IRNode.ALLOC })
    @IR(counts = { IRNode.LOAD_VECTOR_F,   "> 0",
                   IRNode.ADD_VF,          "> 0",
                   IRNode.ADD_REDUCTION_VF, "> 0" },
        applyIfCPUFeatureOr = {"avx512_fp16", "true"})
    static float testFloat16SumReduction_C2(float[] arr) {
        return testFloat16SumReduction(arr);
    }

    @DontCompile
    static float testFloat16SumReduction_Interp(float[] arr) {
        return testFloat16SumReduction(arr);
    }

    // ---------------------- Negative Tests ---------------------- //

    static volatile Float16 escapedBox;

    @Run(test = "testFloat16BoxEscapes_C2")
    public void runNegativeTests(RunInfo info) {
        float[] arr = floatInput;
        Asserts.assertEQ(testFloat16BoxEscapes_Interp(arr), testFloat16BoxEscapes_C2(arr));
    }

    // ---- testFloat16BoxEscapes (negative: box stored to heap) ----

    @ForceInline
    static float testFloat16BoxEscapes(float[] arr) {
        Float16 res = Float16.valueOf(Float.MAX_VALUE);
        for (int i = 0; i < arr.length; i++) {
            float cur = Float16.valueOf(arr[i]).floatValue();
            float rv  = res.floatValue();
            res = Float16.valueOf(Math.min(rv, cur));
            escapedBox = res; // escapes to heap
        }
        return res.floatValue();
    }

    @Test
    @IR(counts = { IRNode.ALLOC, ">= 1" })
    static float testFloat16BoxEscapes_C2(float[] arr) {
        return testFloat16BoxEscapes(arr);
    }

    @DontCompile
    static float testFloat16BoxEscapes_Interp(float[] arr) {
        return testFloat16BoxEscapes(arr);
    }
}
