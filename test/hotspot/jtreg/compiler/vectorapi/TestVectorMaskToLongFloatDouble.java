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

/*
 * @test
 * @bug 8375688
 * @key randomness
 * @library /test/lib /
 * @summary VectorMaskToLong constant folding fails for Float/Double under StressIncrementalInlining
 * @modules jdk.incubator.vector
 *
 * @run driver compiler.vectorapi.TestVectorMaskToLongFloatDouble
 */

package compiler.vectorapi;

import compiler.lib.ir_framework.*;
import jdk.incubator.vector.*;
import jdk.test.lib.Asserts;

public class TestVectorMaskToLongFloatDouble {
    static final VectorSpecies<Float> F_SPECIES = FloatVector.SPECIES_MAX;
    static final VectorSpecies<Double> D_SPECIES = DoubleVector.SPECIES_MAX;

    // --- All-ones mask ---

    @Test
    @IR(counts = { IRNode.VECTOR_MASK_TO_LONG, "= 0" },
        applyIfCPUFeatureOr = { "avx512", "true", "sve", "true", "rvv", "true" })
    @IR(counts = { IRNode.VECTOR_MASK_TO_LONG, "= 0" },
        applyIfCPUFeatureAnd = { "avx2", "true", "avx512", "false" })
    @IR(counts = { IRNode.VECTOR_MASK_TO_LONG, "= 0" },
        applyIfCPUFeatureAnd = { "asimd", "true", "sve", "false" })
    public static long testAllOnesFloat() {
        return VectorMask.fromLong(F_SPECIES, -1L).toLong();
    }

    @Test
    @IR(counts = { IRNode.VECTOR_MASK_TO_LONG, "= 0" },
        applyIfCPUFeatureOr = { "avx512", "true", "sve", "true", "rvv", "true" })
    @IR(counts = { IRNode.VECTOR_MASK_TO_LONG, "= 0" },
        applyIfCPUFeatureAnd = { "avx2", "true", "avx512", "false" })
    @IR(counts = { IRNode.VECTOR_MASK_TO_LONG, "= 0" },
        applyIfCPUFeatureAnd = { "asimd", "true", "sve", "false" })
    public static long testAllOnesDouble() {
        return VectorMask.fromLong(D_SPECIES, -1L).toLong();
    }

    // --- All-zeros mask ---

    @Test
    @IR(counts = { IRNode.VECTOR_MASK_TO_LONG, "= 0" },
        applyIfCPUFeatureOr = { "avx512", "true", "sve", "true", "rvv", "true" })
    @IR(counts = { IRNode.VECTOR_MASK_TO_LONG, "= 0" },
        applyIfCPUFeatureAnd = { "avx2", "true", "avx512", "false" })
    @IR(counts = { IRNode.VECTOR_MASK_TO_LONG, "= 0" },
        applyIfCPUFeatureAnd = { "asimd", "true", "sve", "false" })
    public static long testAllZerosFloat() {
        return VectorMask.fromLong(F_SPECIES, 0L).toLong();
    }

    @Test
    @IR(counts = { IRNode.VECTOR_MASK_TO_LONG, "= 0" },
        applyIfCPUFeatureOr = { "avx512", "true", "sve", "true", "rvv", "true" })
    @IR(counts = { IRNode.VECTOR_MASK_TO_LONG, "= 0" },
        applyIfCPUFeatureAnd = { "avx2", "true", "avx512", "false" })
    @IR(counts = { IRNode.VECTOR_MASK_TO_LONG, "= 0" },
        applyIfCPUFeatureAnd = { "asimd", "true", "sve", "false" })
    public static long testAllZerosDouble() {
        return VectorMask.fromLong(D_SPECIES, 0L).toLong();
    }

    // --- Verification ---

    @Check(test = "testAllOnesFloat")
    public static void checkAllOnesFloat(long result) {
        Asserts.assertEquals(-1L >>> (64 - F_SPECIES.length()), result);
    }

    @Check(test = "testAllOnesDouble")
    public static void checkAllOnesDouble(long result) {
        Asserts.assertEquals(-1L >>> (64 - D_SPECIES.length()), result);
    }

    @Check(test = "testAllZerosFloat")
    public static void checkAllZerosFloat(long result) {
        Asserts.assertEquals(0L, result);
    }

    @Check(test = "testAllZerosDouble")
    public static void checkAllZerosDouble(long result) {
        Asserts.assertEquals(0L, result);
    }

    public static void main(String[] args) {
        TestFramework testFramework = new TestFramework();
        testFramework.setDefaultWarmup(10000)
                     .addFlags("--add-modules=jdk.incubator.vector",
                               "-XX:+IgnoreUnrecognizedVMOptions",
                               "-XX:+UnlockDiagnosticVMOptions",
                               "-XX:-TieredCompilation",
                               "-XX:+StressIncrementalInlining",
                               "-XX:VerifyIterativeGVN=1110")
                     .start();
    }
}
