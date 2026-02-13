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

import jdk.test.lib.Asserts;
import compiler.lib.ir_framework.*;
import compiler.lib.ir_framework.CompilePhase;

/*
 * @test
 * @bug 8375321
 * @summary Verifies that boxing loop-carried Phi nodes using Float/Double
 *          (non-cached box types whose valueOf always allocates) are correctly
 *          eliminated and auto-vectorized by AggressiveUnboxing's
 *          split_through_phi pipeline.
 * @library /test/lib /
 * @requires vm.debug == true & vm.flagless & vm.bits == 64 & vm.compiler2.enabled & vm.opt.final.EliminateAllocations
 * @run driver compiler.c2.irTests.scalarReplacement.BoxingLoopPhiTests
 */
public class BoxingLoopPhiTests {

    static float[] testFloatArray;
    static double[] testDoubleArray;

    static {
        testFloatArray = new float[1024];
        testDoubleArray = new double[1024];
        for (int i = 0; i < 1024; i++) {
            testFloatArray[i] = (float) i;
            testDoubleArray[i] = (double) i;
        }
    }

    public static void main(String[] args) {
        TestFramework framework = new TestFramework();

        // AggressiveUnboxing's split_through_phi handles Float/Double boxes.
        // Both compressed and uncompressed oops are tested.
        // TraceReduceAllocationMerges enables VerifyReduceAllocationMerges
        // assertions in debug builds.

        Scenario scenario0 = new Scenario(0, "-XX:+UnlockDiagnosticVMOptions",
                                             "-XX:+ReduceAllocationMerges",
                                             "-XX:+TraceReduceAllocationMerges",
                                             "-XX:+UseCompressedOops",
                                             "-XX:+UseCompressedClassPointers",
                                             "-XX:-DetectBoxlike",
                                            "-XX:VerifyIterativeGVN=1110");

        Scenario scenario1 = new Scenario(1, "-XX:+UnlockDiagnosticVMOptions",
                                             "-XX:+ReduceAllocationMerges",
                                             "-XX:+TraceReduceAllocationMerges",
                                             "-XX:-UseCompressedOops",
                                             "-XX:-DetectBoxlike",
                                            "-XX:VerifyIterativeGVN=1110");

        framework.addScenarios(scenario0, scenario1).start();
    }

    // ---------------------- Positive Tests ---------------------- //
    // All positive tests use Float or Double (non-cached box types whose
    // valueOf always allocates).  AggressiveUnboxing's split_through_phi
    // handles these boxes.  Vectorization checks are gated on CPU features.

    @Run(test = {"testFloatMinReduction_C2",
                 "testFloatMaxReduction_C2",
                 "testDoubleMinReduction_C2",
                 "testDoubleMaxReduction_C2"
                })
    public void runPositiveTests(RunInfo info) {
        float[] fa = testFloatArray;
        double[] da = testDoubleArray;

        Asserts.assertEQ(testFloatMinReduction_Interp(fa),  testFloatMinReduction_C2(fa));
        Asserts.assertEQ(testFloatMaxReduction_Interp(fa),  testFloatMaxReduction_C2(fa));
        Asserts.assertEQ(testDoubleMinReduction_Interp(da), testDoubleMinReduction_C2(da));
        Asserts.assertEQ(testDoubleMaxReduction_Interp(da), testDoubleMaxReduction_C2(da));
    }

    // ---- testFloatMinReduction ----
    // Float.valueOf always allocates (no cache). Uses min accumulation.

    @ForceInline
    static float testFloatMinReduction(float[] arr) {
        Float acc = Float.MAX_VALUE;
        for (int i = 0; i < arr.length; i++) {
            acc = Float.valueOf(Math.min(acc, arr[i]));
        }
        return acc;
    }

    @Test
    @IR(counts = { IRNode.ALLOC, ">= 1" }, phase = CompilePhase.INCREMENTAL_BOXING_INLINE)
    @IR(failOn = { IRNode.ALLOC })
    @IR(counts = { IRNode.LOAD_VECTOR_F,  "> 0",
                   IRNode.MIN_VF,         "> 0",
                   IRNode.MIN_REDUCTION_V, "> 0" },
        applyIfCPUFeatureOr = {"avx", "true", "asimd", "true"})
    static float testFloatMinReduction_C2(float[] arr) {
        return testFloatMinReduction(arr);
    }

    @DontCompile
    static float testFloatMinReduction_Interp(float[] arr) {
        return testFloatMinReduction(arr);
    }

    // ---- testFloatMaxReduction ----
    // Float.valueOf always allocates (no cache). Uses max accumulation.
    // Under compressed oops, the loop Phi generates EncodeP users at
    // safepoints that must be tolerated by VerifyReduceAllocationMerges
    // after the loop Phi inputs are nulled.

    @ForceInline
    static float testFloatMaxReduction(float[] arr) {
        Float acc = -Float.MAX_VALUE;
        for (int i = 0; i < arr.length; i++) {
            acc = Float.valueOf(Math.max(acc, arr[i]));
        }
        return acc;
    }

    @Test
    @IR(counts = { IRNode.ALLOC, ">= 1" }, phase = CompilePhase.INCREMENTAL_BOXING_INLINE)
    @IR(failOn = { IRNode.ALLOC })
    @IR(counts = { IRNode.LOAD_VECTOR_F,  "> 0",
                   IRNode.MAX_VF,         "> 0",
                   IRNode.MAX_REDUCTION_V, "> 0" },
        applyIfCPUFeatureOr = {"avx", "true", "asimd", "true"})
    static float testFloatMaxReduction_C2(float[] arr) {
        return testFloatMaxReduction(arr);
    }

    @DontCompile
    static float testFloatMaxReduction_Interp(float[] arr) {
        return testFloatMaxReduction(arr);
    }

    // ---- testDoubleMinReduction ----
    // Double.valueOf always allocates (no cache). Uses min accumulation.

    @ForceInline
    static double testDoubleMinReduction(double[] arr) {
        Double acc = Double.MAX_VALUE;
        for (int i = 0; i < arr.length; i++) {
            acc = Double.valueOf(Math.min(acc, arr[i]));
        }
        return acc;
    }

    @Test
    @IR(counts = { IRNode.ALLOC, ">= 1" }, phase = CompilePhase.INCREMENTAL_BOXING_INLINE)
    @IR(failOn = { IRNode.ALLOC })
    @IR(counts = { IRNode.LOAD_VECTOR_D,  "> 0",
                   IRNode.MIN_VD,         "> 0",
                   IRNode.MIN_REDUCTION_V, "> 0" },
        applyIfCPUFeatureOr = {"avx", "true", "asimd", "true"})
    static double testDoubleMinReduction_C2(double[] arr) {
        return testDoubleMinReduction(arr);
    }

    @DontCompile
    static double testDoubleMinReduction_Interp(double[] arr) {
        return testDoubleMinReduction(arr);
    }

    // ---- testDoubleMaxReduction ----
    // Double.valueOf always allocates (no cache). Uses max accumulation.

    @ForceInline
    static double testDoubleMaxReduction(double[] arr) {
        Double acc = -Double.MAX_VALUE;
        for (int i = 0; i < arr.length; i++) {
            acc = Double.valueOf(Math.max(acc, arr[i]));
        }
        return acc;
    }

    @Test
    @IR(counts = { IRNode.ALLOC, ">= 1" }, phase = CompilePhase.INCREMENTAL_BOXING_INLINE)
    @IR(failOn = { IRNode.ALLOC })
    @IR(counts = { IRNode.LOAD_VECTOR_D,  "> 0",
                   IRNode.MAX_VD,         "> 0",
                   IRNode.MAX_REDUCTION_V, "> 0" },
        applyIfCPUFeatureOr = {"avx", "true", "asimd", "true"})
    static double testDoubleMaxReduction_C2(double[] arr) {
        return testDoubleMaxReduction(arr);
    }

    @DontCompile
    static double testDoubleMaxReduction_Interp(double[] arr) {
        return testDoubleMaxReduction(arr);
    }

    // ---------------------- Negative Tests ---------------------- //

    static volatile Float escapedBox;

    @Run(test = {"testBoxEscapes_C2",
                 "testBoxAsCallArg_C2"
                })
    public void runNegativeTests(RunInfo info) {
        float[] arr = testFloatArray;

        Asserts.assertEQ(testBoxEscapes_Interp(arr),   testBoxEscapes_C2(arr));
        Asserts.assertEQ(testBoxAsCallArg_Interp(arr),  testBoxAsCallArg_C2(arr));
    }

    // ---- testBoxEscapes (negative: box stored to heap) ----

    @ForceInline
    static float testBoxEscapes(float[] arr) {
        Float res = Float.MAX_VALUE;
        for (int i = 0; i < arr.length; i++) {
            res = Float.valueOf(Math.min(res, arr[i]));
            escapedBox = res; // escapes to heap
        }
        return res;
    }

    @Test
    @IR(counts = { IRNode.ALLOC, ">= 1" })
    static float testBoxEscapes_C2(float[] arr) {
        return testBoxEscapes(arr);
    }

    @DontCompile
    static float testBoxEscapes_Interp(float[] arr) {
        return testBoxEscapes(arr);
    }

    // ---- testBoxAsCallArg (negative: box passed to non-inlined method) ----

    @DontInline
    static float consumeBox(Float val) {
        return val;
    }

    @ForceInline
    static float testBoxAsCallArg(float[] arr) {
        Float res = Float.MAX_VALUE;
        for (int i = 0; i < arr.length; i++) {
            res = Float.valueOf(Math.min(res, arr[i]));
            consumeBox(res); // non-inlined call prevents elimination
        }
        return res;
    }

    @Test
    @IR(counts = { IRNode.ALLOC, ">= 1" })
    static float testBoxAsCallArg_C2(float[] arr) {
        return testBoxAsCallArg(arr);
    }

    @DontCompile
    static float testBoxAsCallArg_Interp(float[] arr) {
        return testBoxAsCallArg(arr);
    }
}
