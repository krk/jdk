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
 * @test id=+OptSmallArrayCopy
 * @bug 8149758
 * @summary Inline small arraycopy with non-constant but bounded length
 * @key randomness
 * @library /test/lib /
 * @run driver ${test.main.class} -XX:+UnlockDiagnosticVMOptions -XX:+OptSmallArrayCopy
 */

/*
 * @test id=-OptSmallArrayCopy
 * @bug 8149758
 * @summary Inline small arraycopy with non-constant but bounded length
 * @key randomness
 * @library /test/lib /
 * @run driver ${test.main.class} -XX:+UnlockDiagnosticVMOptions -XX:-OptSmallArrayCopy
 */

package compiler.arraycopy;

import compiler.lib.ir_framework.*;
import java.lang.reflect.Array;
import java.util.Random;
import jdk.test.lib.Utils;

public class TestSmallArrayCopyVariableLength {

    static final Random RANDOM = Utils.getRandomInstance();

    static byte[] byteSrc = new byte[16];
    static short[] shortSrc = new short[16];
    static int[] intSrc = new int[16];
    static long[] longSrc = new long[16];

    public static void main(String[] args) {
        RANDOM.nextBytes(byteSrc);
        for (int i = 0; i < shortSrc.length; i++) {
            shortSrc[i] = (short) RANDOM.nextInt();
        }
        for (int i = 0; i < intSrc.length; i++) {
            intSrc[i] = RANDOM.nextInt();
        }
        for (int i = 0; i < longSrc.length; i++) {
            longSrc[i] = RANDOM.nextLong();
        }

        TestFramework framework = new TestFramework();
        framework.addFlags(args);
        framework.start();
    }

    @Test
    @IR(applyIf = { "OptSmallArrayCopy", "true" }, failOn = "\\bArrayCopy\\b", phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    @IR(applyIf = { "OptSmallArrayCopy", "false" }, counts = { "\\bArrayCopy\\b", ">= 1" }, phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    static int[] testIntMask1(int len) {
        int n = len & 0x1;
        int[] dst = new int[n];
        System.arraycopy(intSrc, 0, dst, 0, n);
        return dst;
    }

    @Test
    @IR(applyIf = { "OptSmallArrayCopy", "true" }, failOn = "\\bArrayCopy\\b", phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    @IR(applyIf = { "OptSmallArrayCopy", "false" }, counts = { "\\bArrayCopy\\b", ">= 1" }, phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    static int[] testIntMask3(int len) {
        int n = len & 0x3;
        int[] dst = new int[n];
        System.arraycopy(intSrc, 0, dst, 0, n);
        return dst;
    }

    @Test
    @IR(applyIf = { "OptSmallArrayCopy", "true" }, failOn = "\\bArrayCopy\\b", phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    @IR(applyIf = { "OptSmallArrayCopy", "false" }, counts = { "\\bArrayCopy\\b", ">= 1" }, phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    static long[] testLongMask3(int len) {
        int n = len & 0x3;
        long[] dst = new long[n];
        System.arraycopy(longSrc, 0, dst, 0, n);
        return dst;
    }

    @Test
    @IR(applyIf = { "OptSmallArrayCopy", "true" }, failOn = "\\bArrayCopy\\b", phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    @IR(applyIf = { "OptSmallArrayCopy", "false" }, counts = { "\\bArrayCopy\\b", ">= 1" }, phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    static int[] testIntMask7(int len) {
        int n = len & 0x7;
        int[] dst = new int[n];
        System.arraycopy(intSrc, 0, dst, 0, n);
        return dst;
    }

    @Test
    @IR(applyIf = { "OptSmallArrayCopy", "true" }, failOn = "\\bArrayCopy\\b", phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    @IR(applyIf = { "OptSmallArrayCopy", "false" }, counts = { "\\bArrayCopy\\b", ">= 1" }, phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    static byte[] testByteMask7(int len) {
        int n = len & 0x7;
        byte[] dst = new byte[n];
        System.arraycopy(byteSrc, 0, dst, 0, n);
        return dst;
    }

    @Test
    @IR(applyIf = { "OptSmallArrayCopy", "true" }, failOn = "\\bArrayCopy\\b", phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    @IR(applyIf = { "OptSmallArrayCopy", "false" }, counts = { "\\bArrayCopy\\b", ">= 1" }, phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    static short[] testShortMask7(int len) {
        int n = len & 0x7;
        short[] dst = new short[n];
        System.arraycopy(shortSrc, 0, dst, 0, n);
        return dst;
    }

    @Test
    @IR(applyIf = { "OptSmallArrayCopy", "true" }, failOn = "\\bArrayCopy\\b", phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    @IR(applyIf = { "OptSmallArrayCopy", "false" }, counts = { "\\bArrayCopy\\b", ">= 1" }, phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    static byte[] testByteMask1Plus1(int len) {
        int n = (len & 0x1) + 1;
        byte[] dst = new byte[n];
        System.arraycopy(byteSrc, 0, dst, 0, n);
        return dst;
    }

    @Test
    @IR(applyIf = { "OptSmallArrayCopy", "true" }, failOn = "\\bArrayCopy\\b", phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    @IR(applyIf = { "OptSmallArrayCopy", "false" }, counts = { "\\bArrayCopy\\b", ">= 1" }, phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    static int[] testIntMask3Plus2(int len) {
        int n = (len & 0x3) + 2;
        int[] dst = new int[n];
        System.arraycopy(intSrc, 0, dst, 0, n);
        return dst;
    }

    @Test
    @IR(applyIf = { "OptSmallArrayCopy", "true" }, failOn = "\\bArrayCopy\\b", phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    @IR(applyIf = { "OptSmallArrayCopy", "false" }, counts = { "\\bArrayCopy\\b", ">= 1" }, phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    static int[] testIntMask7ZeroLength(int len) {
        int n = len & 0x7;
        int[] dst = new int[n];
        System.arraycopy(intSrc, 0, dst, 0, n);
        return dst;
    }

    @Test
    @IR(counts = { "\\bArrayCopy\\b", ">= 1" }, phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    static int[] testIntMask15NotInlined(int len) {
        int n = len & 0xF;
        int[] dst = new int[n];
        System.arraycopy(intSrc, 0, dst, 0, n);
        return dst;
    }

    @Test
    @IR(applyIf = { "OptSmallArrayCopy", "true" }, failOn = "\\bArrayCopy\\b", phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    @IR(applyIf = { "OptSmallArrayCopy", "false" }, counts = { "\\bArrayCopy\\b", ">= 1" }, phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    static int[] testIntMask7CopyOf(int len) {
        int n = len & 0x7;
        return java.util.Arrays.copyOf(intSrc, n);
    }

    @Test
    @IR(applyIf = { "OptSmallArrayCopy", "true" }, failOn = "\\bArrayCopy\\b", phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    @IR(applyIf = { "OptSmallArrayCopy", "false" }, counts = { "\\bArrayCopy\\b", ">= 1" }, phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    static int[] testIntMask7CopyOfRange(int len) {
        int n = len & 0x7;
        return java.util.Arrays.copyOfRange(intSrc, 0, n);
    }

    @Run(test = "testIntMask1")
    void runIntMask1() {
        for (int len = 0; len <= 1; len++) {
            verify(testIntMask1(len), intSrc, len);
        }
    }

    @Run(test = {
        "testIntMask3",
        "testLongMask3",
    })
    void runMask3() {
        for (int len = 0; len <= 3; len++) {
            verify(testIntMask3(len), intSrc, len);
            verify(testLongMask3(len), longSrc, len);
        }
    }

    @Run(test = { 
        "testIntMask7",
        "testIntMask7CopyOf",
        "testIntMask7CopyOfRange",
        "testByteMask7",
        "testShortMask7",
    })
    void runMask7() {
        for (int len = 0; len <= 7; len++) {
            verify(testIntMask7(len), intSrc, len);
            verify(testIntMask7CopyOf(len), intSrc, len);
            verify(testIntMask7CopyOfRange(len), intSrc, len);
            verify(testByteMask7(len), byteSrc, len);
            verify(testShortMask7(len), shortSrc, len);
        }
    }

    @Run(test = "testByteMask1Plus1")
    void runByteMask1Plus1() {
        for (int len = 0; len <= 1; len++) {
            int expected = (len & 0x1) + 1;
            verify(testByteMask1Plus1(len), byteSrc, expected);
        }
    }

    @Run(test = "testIntMask3Plus2")
    void runIntMask3Plus2() {
        for (int len = 0; len <= 3; len++) {
            int expected = (len & 0x3) + 2;
            verify(testIntMask3Plus2(len), intSrc, expected);
        }
    }

    @Run(test = "testIntMask7ZeroLength")
    void runIntMask7ZeroLength() {
        int[] result = testIntMask7ZeroLength(0);
        if (result.length != 0)
            throw new RuntimeException("Expected empty array");
        result = testIntMask7ZeroLength(8);
        if (result.length != 0)
            throw new RuntimeException("Expected empty array for 8 & 0x7");
    }

    @Run(test = "testIntMask15NotInlined")
    void runIntMask15NotInlined() {
        for (int len = 0; len <= 8; len++) {
            verify(testIntMask15NotInlined(len), intSrc, len);
        }
    }

    static void verify(Object dst, Object src, int expectedLen) {
        int actualLen = Array.getLength(dst);
        if (actualLen != expectedLen) {
            throw new RuntimeException("Wrong length: " + actualLen + " expected " + expectedLen);
        }
        for (int i = 0; i < expectedLen; i++) {
            Object dv = Array.get(dst, i);
            Object sv = Array.get(src, i);
            if (!dv.equals(sv)) {
                throw new RuntimeException("Wrong value at " + i + ": " + dv + " expected " + sv);
            }
        }
    }
}
