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
 * @summary Verify that user-defined box-like classes (final, single primitive
 *          field, app class loader) are correctly handled by
 *          is_boxlike_klass and AggressiveUnboxing's split_through_phi.
 * @library /test/lib /
 * @requires vm.debug == true & vm.flagless & vm.bits == 64 & vm.compiler2.enabled & vm.opt.final.EliminateAllocations
 * @run driver compiler.c2.irTests.scalarReplacement.CustomBoxLoopPhiTests
 */
public class CustomBoxLoopPhiTests {

    // A user-defined box-like class: final, single primitive field.
    // Loaded by the app class loader.  Matches is_boxlike_klass().
    static final class MyFloatBox {
        private final float value;
        MyFloatBox(float v) { this.value = v; }
        float floatValue() { return value; }
        static MyFloatBox valueOf(float v) { return new MyFloatBox(v); }
    }

    static final int LEN = 1024;
    static float[] testFloatArray;

    static {
        testFloatArray = new float[LEN];
        for (int i = 0; i < LEN; i++) {
            testFloatArray[i] = (float) (i + 1);
        }
    }

    public static void main(String[] args) {
        TestFramework framework = new TestFramework();

        Scenario compressedOops = new Scenario(0,
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+ReduceAllocationMerges",
                "-XX:+TraceReduceAllocationMerges",
                "-XX:+UseCompressedOops",
                "-XX:+UseCompressedClassPointers");

        Scenario noCompressedOops = new Scenario(1,
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+ReduceAllocationMerges",
                "-XX:+TraceReduceAllocationMerges",
                "-XX:-UseCompressedOops");

        framework.addScenarios(compressedOops, noCompressedOops).start();
    }

    // ---------------------- Positive Tests ---------------------- //

    @Run(test = {"testCustomBoxMinReduction_C2",
                 "testCustomBoxSumReduction_C2"})
    public void runPositiveTests(RunInfo info) {
        float[] fa = testFloatArray;
        Asserts.assertEQ(testCustomBoxMinReduction_Interp(fa), testCustomBoxMinReduction_C2(fa));
        Asserts.assertEQ(testCustomBoxSumReduction_Interp(fa), testCustomBoxSumReduction_C2(fa));
    }

    // ---- testCustomBoxMinReduction ----

    @ForceInline
    static float testCustomBoxMinReduction(float[] arr) {
        MyFloatBox acc = MyFloatBox.valueOf(Float.MAX_VALUE);
        for (int i = 0; i < arr.length; i++) {
            acc = MyFloatBox.valueOf(Math.min(acc.floatValue(), arr[i]));
        }
        return acc.floatValue();
    }

    @Test
    @IR(counts = { IRNode.ALLOC, ">= 1" }, phase = CompilePhase.INCREMENTAL_BOXING_INLINE)
    @IR(failOn = { IRNode.ALLOC })
    @IR(counts = { IRNode.LOAD_VECTOR_F,  "> 0",
                   IRNode.MIN_VF,         "> 0",
                   IRNode.MIN_REDUCTION_V, "> 0" },
        applyIfCPUFeatureOr = {"avx", "true", "asimd", "true"})
    static float testCustomBoxMinReduction_C2(float[] arr) {
        return testCustomBoxMinReduction(arr);
    }

    @DontCompile
    static float testCustomBoxMinReduction_Interp(float[] arr) {
        return testCustomBoxMinReduction(arr);
    }

    // ---- testCustomBoxSumReduction ----

    @ForceInline
    static float testCustomBoxSumReduction(float[] arr) {
        MyFloatBox acc = MyFloatBox.valueOf(0.0f);
        for (int i = 0; i < arr.length; i++) {
            acc = MyFloatBox.valueOf(acc.floatValue() + arr[i]);
        }
        return acc.floatValue();
    }

    @Test
    @IR(counts = { IRNode.ALLOC, ">= 1" }, phase = CompilePhase.INCREMENTAL_BOXING_INLINE)
    @IR(failOn = { IRNode.ALLOC })
    @IR(counts = { IRNode.LOAD_VECTOR_F,   "> 0",
                   IRNode.ADD_VF,          "> 0",
                   IRNode.ADD_REDUCTION_VF, "> 0" },
        applyIfCPUFeatureOr = {"avx", "true", "asimd", "true"})
    static float testCustomBoxSumReduction_C2(float[] arr) {
        return testCustomBoxSumReduction(arr);
    }

    @DontCompile
    static float testCustomBoxSumReduction_Interp(float[] arr) {
        return testCustomBoxSumReduction(arr);
    }

    // ---------------------- Negative Tests ---------------------- //

    static volatile MyFloatBox escapedBox;

    @Run(test = "testCustomBoxEscapes_C2")
    public void runNegativeTests(RunInfo info) {
        float[] arr = testFloatArray;
        Asserts.assertEQ(testCustomBoxEscapes_Interp(arr), testCustomBoxEscapes_C2(arr));
    }

    // ---- testCustomBoxEscapes (negative: box stored to heap) ----

    @ForceInline
    static float testCustomBoxEscapes(float[] arr) {
        MyFloatBox res = MyFloatBox.valueOf(Float.MAX_VALUE);
        for (int i = 0; i < arr.length; i++) {
            res = MyFloatBox.valueOf(Math.min(res.floatValue(), arr[i]));
            escapedBox = res; // escapes to heap
        }
        return res.floatValue();
    }

    @Test
    @IR(counts = { IRNode.ALLOC, ">= 1" })
    static float testCustomBoxEscapes_C2(float[] arr) {
        return testCustomBoxEscapes(arr);
    }

    @DontCompile
    static float testCustomBoxEscapes_Interp(float[] arr) {
        return testCustomBoxEscapes(arr);
    }
}
