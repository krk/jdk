/*
 * Copyright Amazon.com Inc. or its affiliates. All Rights Reserved.
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
 * @bug 8378594
 * @summary Fuzz test for Range Check Elimination correctness
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @requires vm.compiler2.enabled
 * @compile ../../compiler/lib/ir_framework/TestFramework.java
 * @compile ../../compiler/lib/generators/Generators.java
 * @compile ../../compiler/lib/verify/Verify.java
 * @run driver/timeout=600 ${test.main.class}
 */

package compiler.rangechecks;

import java.util.Collections;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Random;

import jdk.test.lib.Utils;

import compiler.lib.compile_framework.*;
import compiler.lib.template_framework.TemplateToken;
import compiler.lib.template_framework.library.TestFrameworkClass;

/**
 * Fuzz test driver for Range Check Elimination.
 *
 * For each AccessKind, enumerates all structural combinations
 * (AddressingMode x ScaleForm x direction x stride), samples ~20% by default,
 * and runs them under 4 different randomized VM flag combinations (scenarios).
 *
 * Set -DFULL=true to run ALL combinations (no sampling). This is slower but
 * gives complete coverage of every structural pattern.
 *
 * Test generation is in {@link TestRCEFuzzerGen}.
 */
public class TestRCEFuzzer {
    private static final Random RANDOM = Utils.getRandomInstance();
    private static final String PKG = "compiler.rangechecks.templated";
    private static final int NUM_FLAG_SCENARIOS = 4;
    private static final double SAMPLE_RATIO = 0.2;
    private static final boolean FULL = Boolean.getBoolean("FULL");

    public static void main(String[] args) {
        CompileFramework comp = new CompileFramework();

        long t0 = System.nanoTime();
        for (var kind : TestRCEFuzzerGen.AccessKind.values()) {
            String className = PKG + ".RCEFuzzer_" + kind.name();
            comp.addJavaSourceCode(className, generateForKind(comp, kind));
        }

        long t1 = System.nanoTime();
        comp.compile();

        long t2 = System.nanoTime();
        for (var kind : TestRCEFuzzerGen.AccessKind.values()) {
            String className = PKG + ".RCEFuzzer_" + kind.name();
            for (int s = 0; s < NUM_FLAG_SCENARIOS; s++) {
                String[] flags = randomFlags();
                comp.invoke(className, "main", new Object[] {flags});
            }
        }

        long t3 = System.nanoTime();
        System.out.println("Code Generation:  " + (t1 - t0) * 1e-9f);
        System.out.println("Code Compilation: " + (t2 - t1) * 1e-9f);
        System.out.println("Running Tests:    " + (t3 - t2) * 1e-9f);
    }

    static String[] randomFlags() {
        return new String[] {
            "-XX:" + randomPlusMinus(5, 1) + "UseLoopPredicate",
            "-XX:" + randomPlusMinus(5, 1) + "RangeCheckElimination",
            "-XX:" + randomPlusMinus(5, 1) + "ShortRunningLongLoop",
            "-XX:" + randomPlusMinus(1, 1) + "UseCompactObjectHeaders",
        };
    }

    static String generateForKind(CompileFramework comp, TestRCEFuzzerGen.AccessKind kind) {
        List<TestRCEFuzzerGen.TestCase> all = new ArrayList<>();
        for (boolean forward : new boolean[] {true, false}) {
            for (int stride : new int[] {1, 2}) {
                all.addAll(TestRCEFuzzerGen.TestCase.allCombinations(kind, forward, stride));
            }
        }

        List<TestRCEFuzzerGen.TestCase> selected;
        if (FULL) {
            selected = all;
        } else {
            Collections.shuffle(all, RANDOM);
            int sampleSize = Math.max(1, (int) (all.size() * SAMPLE_RATIO));
            selected = all.subList(0, sampleSize);
        }

        System.out.println(kind.name() + ": " + selected.size() + "/" + all.size()
                         + " combinations" + (FULL ? " (FULL)" : " (sampled)"));

        List<TemplateToken> tests = new ArrayList<>();
        tests.add(TestRCEFuzzerGen.generateRuntimeHelpers());
        for (var tc : selected) {
            tests.add(tc.generate());
        }

        return TestFrameworkClass.render(
            PKG, "RCEFuzzer_" + kind.name(),
            Set.of("compiler.lib.generators.*",
                   "compiler.lib.verify.*",
                   "java.lang.foreign.*",
                   "java.util.Objects",
                   "java.util.Random",
                   "jdk.test.lib.Utils"),
            comp.getEscapedClassPathOfCompiledClasses(),
            tests);
    }

    static String randomPlusMinus(int plus, int minus) {
        return (RANDOM.nextInt(plus + minus) < plus) ? "+" : "-";
    }
}
