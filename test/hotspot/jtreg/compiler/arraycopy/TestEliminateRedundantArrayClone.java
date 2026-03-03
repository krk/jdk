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

package compiler.arraycopy;

import compiler.lib.ir_framework.*;
import jdk.internal.misc.Unsafe;
import java.lang.foreign.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/*
 * @test
 * @bug 8364418
 * @summary C2: Eliminate redundant clone of non-escaping array.
 *          When a freshly-allocated non-escaping array is cloned and the
 *          original is dead after the clone, the clone copy can be eliminated
 *          by replacing the clone destination with the source everywhere.
 *          This is the pattern that Arrays.copyOfRange(original, 0, original.length)
 *          compiles to when fully inlined (its fast path calls original.clone()).
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @requires vm.compiler2.enabled
 * @run driver compiler.arraycopy.TestEliminateRedundantArrayClone
 */
public class TestEliminateRedundantArrayClone {

    static final Unsafe UNSAFE = Unsafe.getUnsafe();
    static final byte[] SOURCE = "Hello, World!".getBytes();
    static final int[] INT_SOURCE = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13};
    static final String[] STRING_SOURCE = {"Hello", ",", " ", "World", "!"};
    static final MemorySegment NATIVE_SOURCE;
    static {
        NATIVE_SOURCE = Arena.ofAuto().allocateFrom("Hello, World!",
                                                     StandardCharsets.ISO_8859_1);
    }

    public static void main(String[] args) {
        TestFramework framework = new TestFramework(TestEliminateRedundantArrayClone.class);
        framework.addFlags("-XX:-TieredCompilation",
                           "-XX:+UnlockDiagnosticVMOptions",
                           "--add-modules", "java.base",
                           "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
                           // Force inlining of Unsafe.copyMemory and its helpers
                           // so that copyMemory0 is intrinsified into an
                           // unsafe_arraycopy CallLeaf.  Without this, the checks
                           // method remains a CallStaticJava that takes the source
                           // array as a real argument, causing the optimization to
                           // bail (non-debug call arg).
                           "-XX:CompileCommand=inline,jdk.internal.misc.Unsafe::*");
        framework.addScenarios(
            // Scenarios 0-1: base scenarios — IR for direct-clone patterns,
            // correctness for library-call patterns (copyOfRange not inlined).
            new Scenario(0, "-XX:+EliminateRedundantClone"),
            new Scenario(1, "-XX:-EliminateRedundantClone"),
            // Scenarios 2-3: forced inlining of copyOfRange via CompileCommand.
            // Without it, copyOfRange (82 bytes) is rejected as "too big"
            // (MaxInlineSize=35) or "already compiled into a big method"
            // (InlineSmallCode).  MaxInlineSize=100 is set as a sentinel
            // for applyIfAnd to scope IR rules to these scenarios only.
            new Scenario(2, "-XX:+EliminateRedundantClone",
                            "-XX:CompileCommand=inline,java.util.Arrays::copyOfRange",
                            "-XX:MaxInlineSize=100"),
            new Scenario(3, "-XX:-EliminateRedundantClone",
                            "-XX:CompileCommand=inline,java.util.Arrays::copyOfRange",
                            "-XX:MaxInlineSize=100")
        );
        framework.start();
    }

    @DontInline
    static int getLen() { return 13; }

    @DontInline
    static int blackhole(int x) { return x; }

    @DontInline
    static byte[] identity(byte[] a) { return a; }

    @DontInline
    static int sideEffect() { return 42; }

    static byte[] sink = new byte[13];
    static int counter;

    // ---------------------------------------------------------------
    // Pattern 1: Direct clone of a non-escaping byte[], use .length.
    //
    // Before macro expansion:
    //   disabled: 2 ArrayCopy (fill + clone)
    //   enabled:  1 ArrayCopy (fill only — clone eliminated during IGVN)
    // ---------------------------------------------------------------
    @Test
    @IR(counts = {"ArrayCopy", "2"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION,
        applyIf = {"EliminateRedundantClone", "false"})
    @IR(counts = {"ArrayCopy", "1"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION,
        applyIf = {"EliminateRedundantClone", "true"})
    static int testDirectClone() {
        int len = getLen();
        byte[] original = new byte[len];
        System.arraycopy(SOURCE, 0, original, 0, len);
        byte[] cloned = original.clone();
        return blackhole(cloned.length);
    }

    @Check(test = "testDirectClone")
    static void checkDirectClone(int result) {
        if (result != 13) {
            throw new RuntimeException("testDirectClone: expected 13 but got " + result);
        }
    }

    // ---------------------------------------------------------------
    // Pattern 2: Direct clone of a non-escaping byte[], use element.
    //
    // Same IR counts as pattern 1.  The clone ArrayCopy is eliminated
    // but the fill ArrayCopy and source allocation remain because the
    // array content is actually read.
    // ---------------------------------------------------------------
    @Test
    @IR(counts = {"ArrayCopy", "2"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION,
        applyIf = {"EliminateRedundantClone", "false"})
    @IR(counts = {"ArrayCopy", "1"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION,
        applyIf = {"EliminateRedundantClone", "true"})
    static int testDirectCloneReadElement() {
        int len = getLen();
        byte[] original = new byte[len];
        System.arraycopy(SOURCE, 0, original, 0, len);
        byte[] cloned = original.clone();
        return blackhole(cloned[0]);
    }

    @Check(test = "testDirectCloneReadElement")
    static void checkDirectCloneReadElement(int result) {
        if (result != (int) 'H') {
            throw new RuntimeException("testDirectCloneReadElement: expected " + (int) 'H' + " but got " + result);
        }
    }

    // ---------------------------------------------------------------
    // Pattern 3: Store-filled array then direct clone.
    //
    // The fill is individual StoreB nodes (not an ArrayCopy), so
    // before macro expansion there is only the clone ArrayCopy.
    //
    // Before macro expansion:
    //   disabled: 1 ArrayCopy (clone only; fill is stores)
    //   enabled:  0 ArrayCopy (clone eliminated)
    // ---------------------------------------------------------------
    @Test
    @IR(counts = {"ArrayCopy", "1"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION,
        applyIf = {"EliminateRedundantClone", "false"})
    @IR(counts = {"ArrayCopy", "0"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION,
        applyIf = {"EliminateRedundantClone", "true"})
    static int testStoreFillThenClone() {
        int len = getLen();
        byte[] bytes = new byte[len];
        bytes[0] = (byte) 'H';
        byte[] cloned = bytes.clone();
        return blackhole(cloned[0]);
    }

    @Check(test = "testStoreFillThenClone")
    static void checkStoreFillThenClone(int result) {
        if (result != (int) 'H') {
            throw new RuntimeException("testStoreFillThenClone: expected " + (int) 'H' + " but got " + result);
        }
    }

    // ---------------------------------------------------------------
    // Pattern 4: Clone via Arrays.copyOfRange.
    //
    // Arrays.copyOfRange(original, 0, original.length) fast-path calls
    // original.clone().  copyOfRange is 82 bytecodes — too large for
    // unconditional inlining (MaxInlineSize=35 default).  With
    // WhiteBox-forced compilation the call-site frequency data is
    // absent (count=-1), so C2 never considers it "hot" for
    // FreqInlineSize.  Scenarios 2-3 set MaxInlineSize=100 to force
    // inlining, enabling IR verification.
    //
    // Before macro expansion (when inlined):
    //   disabled: 2 ArrayCopy (fill + clone)
    //   enabled:  1 ArrayCopy (fill only — clone eliminated)
    // ---------------------------------------------------------------
    @Test
    @IR(counts = {"ArrayCopy", "2"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION,
        applyIfAnd = {"EliminateRedundantClone", "false", "MaxInlineSize", ">= 100"})
    @IR(counts = {"ArrayCopy", "1"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION,
        applyIfAnd = {"EliminateRedundantClone", "true", "MaxInlineSize", ">= 100"})
    static int testCopyOfRange() {
        int len = getLen();
        byte[] original = new byte[len];
        System.arraycopy(SOURCE, 0, original, 0, len);
        byte[] copy = Arrays.copyOfRange(original, 0, original.length);
        return blackhole(copy.length);
    }

    @Check(test = "testCopyOfRange")
    static void checkCopyOfRange(int result) {
        if (result != 13) {
            throw new RuntimeException("testCopyOfRange: expected 13 but got " + result);
        }
    }

    // ---------------------------------------------------------------
    // Pattern 5: Fill via MemorySegment.copy (Unsafe.copyMemory) then
    //            new String(bytes, charset) which clones via copyOfRange
    //            (correctness test).
    //
    // This is the real-world pattern from JDK-8362893.  The full
    // inlining chain (MemorySegment -> Unsafe -> String -> copyOfRange
    // -> clone) is too deep for reliable IR verification, so we only
    // verify correctness here.
    // ---------------------------------------------------------------
    @Test
    static int testCopyStringBytes() {
        int len = getLen();
        byte[] bytes = new byte[len];
        MemorySegment.copy(NATIVE_SOURCE, JAVA_BYTE, 0, bytes, 0, len);
        return blackhole(new String(bytes, StandardCharsets.ISO_8859_1).length());
    }

    @Check(test = "testCopyStringBytes")
    static void checkCopyStringBytes(int result) {
        if (result != 13) {
            throw new RuntimeException("testCopyStringBytes: expected 13 but got " + result);
        }
    }

    // ---------------------------------------------------------------
    // Gap 1: CallLeaf stub fill + direct clone.
    //
    // Unsafe.copyMemory → unsafe_arraycopy CallLeaf (not an ArrayCopy
    // node).  The optimization must recognize CallLeaf as a valid fill.
    //
    // Before macro expansion:
    //   disabled: 1 ArrayCopy (clone only; fill is CallLeaf)
    //   enabled:  0 ArrayCopy (clone eliminated)
    // ---------------------------------------------------------------
    @Test
    @IR(counts = {"ArrayCopy", "1"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION,
        applyIf = {"EliminateRedundantClone", "false"})
    @IR(counts = {"ArrayCopy", "0"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION,
        applyIf = {"EliminateRedundantClone", "true"})
    static int testUnsafeFillThenClone() {
        int len = getLen();
        byte[] original = new byte[len];
        UNSAFE.copyMemory(SOURCE, Unsafe.ARRAY_BYTE_BASE_OFFSET,
                          original, Unsafe.ARRAY_BYTE_BASE_OFFSET, len);
        byte[] cloned = original.clone();
        return blackhole(cloned[0]);
    }

    @Check(test = "testUnsafeFillThenClone")
    static void checkUnsafeFillThenClone(int result) {
        if (result != (int) 'H') {
            throw new RuntimeException("testUnsafeFillThenClone: expected " + (int) 'H' + " but got " + result);
        }
    }

    // ---------------------------------------------------------------
    // Gap 2: Non-byte array type (int[]).
    //
    // Verifies the optimization works for int[] (not just byte[]).
    //
    // Before macro expansion:
    //   disabled: 2 ArrayCopy (fill + clone)
    //   enabled:  1 ArrayCopy (fill only — clone eliminated)
    // ---------------------------------------------------------------
    @Test
    @IR(counts = {"ArrayCopy", "2"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION,
        applyIf = {"EliminateRedundantClone", "false"})
    @IR(counts = {"ArrayCopy", "1"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION,
        applyIf = {"EliminateRedundantClone", "true"})
    static int testIntArrayClone() {
        int len = getLen();
        int[] original = new int[len];
        System.arraycopy(INT_SOURCE, 0, original, 0, len);
        int[] cloned = original.clone();
        return blackhole(cloned[0]);
    }

    @Check(test = "testIntArrayClone")
    static void checkIntArrayClone(int result) {
        if (result != 1) {
            throw new RuntimeException("testIntArrayClone: expected 1 but got " + result);
        }
    }

    // ---------------------------------------------------------------
    // Gap 3: Post-clone write to source — bail out.
    //
    // A store to source AFTER the clone is not a pre-clone fill, so
    // the optimization must bail out.
    //
    // IR is identical for both flag values (2 ArrayCopy = fill + clone).
    // ---------------------------------------------------------------
    @Test
    @IR(counts = {"ArrayCopy", "2"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    static int testPostCloneWriteBailout() {
        int len = getLen();
        byte[] original = new byte[len];
        System.arraycopy(SOURCE, 0, original, 0, len);
        byte[] cloned = original.clone();
        original[0] = (byte) 'X';          // post-clone write → bail
        return blackhole(cloned[0]);
    }

    @Check(test = "testPostCloneWriteBailout")
    static void checkPostCloneWriteBailout(int result) {
        if (result != (int) 'H') {
            throw new RuntimeException("testPostCloneWriteBailout: expected " + (int) 'H' + " but got " + result);
        }
    }

    // ---------------------------------------------------------------
    // Gap 4: Source escapes via non-inlined method call — bail out.
    //
    // When source is passed to a non-inlined method, EA marks it as
    // ArgEscape.  The non-escaping check bails.
    //
    // IR is identical for both flag values (2 ArrayCopy = fill + clone).
    // ---------------------------------------------------------------
    @Test
    @IR(counts = {"ArrayCopy", "2"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    static int testSourceEscapesBailout() {
        int len = getLen();
        byte[] original = new byte[len];
        System.arraycopy(SOURCE, 0, original, 0, len);
        byte[] escaped = identity(original);  // source escapes
        byte[] cloned = original.clone();
        return blackhole(cloned[0] + escaped.length);
    }

    @Check(test = "testSourceEscapesBailout")
    static void checkSourceEscapesBailout(int result) {
        if (result != (int) 'H' + 13) {
            throw new RuntimeException("testSourceEscapesBailout: expected " + ((int) 'H' + 13) + " but got " + result);
        }
    }

    // ---------------------------------------------------------------
    // Gap 5: Source used as src of a second arraycopy — bail out.
    //
    // Source is used as Src (not Dest) of another ArrayCopy, which
    // is not a fill into source.
    //
    // IR: 3 ArrayCopy (fill + clone + second copy) for both flag values.
    // ---------------------------------------------------------------
    @Test
    @IR(counts = {"ArrayCopy", "3"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    static int testSourceReusedAsArraycopySrcBailout() {
        int len = getLen();
        byte[] original = new byte[len];
        System.arraycopy(SOURCE, 0, original, 0, len);
        byte[] cloned = original.clone();
        System.arraycopy(original, 0, sink, 0, len);  // original as src
        return blackhole(cloned[0]);
    }

    @Check(test = "testSourceReusedAsArraycopySrcBailout")
    static void checkSourceReusedAsArraycopySrcBailout(int result) {
        if (result != (int) 'H') {
            throw new RuntimeException("testSourceReusedAsArraycopySrcBailout: expected " + (int) 'H' + " but got " + result);
        }
    }

    // ---------------------------------------------------------------
    // Gap 6: Load from source array — bail out.
    //
    // A load from source via AddP is not a store/ArrayCopy/CallLeaf,
    // so the optimization bails.
    //
    // IR is identical for both flag values (2 ArrayCopy = fill + clone).
    // ---------------------------------------------------------------
    @Test
    @IR(counts = {"ArrayCopy", "2"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    static int testLoadFromSourceBailout() {
        int len = getLen();
        byte[] original = new byte[len];
        System.arraycopy(SOURCE, 0, original, 0, len);
        int val = original[0];                  // load from source → bail
        byte[] cloned = original.clone();
        return blackhole(cloned[0] + val);
    }

    @Check(test = "testLoadFromSourceBailout")
    static void checkLoadFromSourceBailout(int result) {
        if (result != (int) 'H' + (int) 'H') {
            throw new RuntimeException("testLoadFromSourceBailout: expected " + ((int) 'H' + (int) 'H') + " but got " + result);
        }
    }

    // ---------------------------------------------------------------
    // Gap 7: String[] (oop array) clone — correctness only.
    //
    // With G1 (default), String[] clone → CloneOopArray, which
    // is_clone_array() returns false for.  The optimization is never
    // attempted.  GC-dependent, so correctness-only (no IR check).
    // ---------------------------------------------------------------
    @Test
    static int testOopArrayClone() {
        String[] original = STRING_SOURCE.clone();
        String[] cloned = original.clone();
        return blackhole(cloned[0].length());
    }

    @Check(test = "testOopArrayClone")
    static void checkOopArrayClone(int result) {
        if (result != 5) {
            throw new RuntimeException("testOopArrayClone: expected 5 but got " + result);
        }
    }

    // ---------------------------------------------------------------
    // Gap 1+: Unrecognized use of source (Phi node) — bail out.
    //
    // A Phi merging source with another array is not ArrayCopy,
    // SafePoint, AddP, or MemBar — the use-validation loop does not
    // recognize it.  The counter alternates the branch direction
    // during warm-up so profiling cannot fold the condition.
    //
    // IR is identical for both flag values (2 ArrayCopy = fill + clone).
    // ---------------------------------------------------------------
    @Test
    @IR(counts = {"ArrayCopy", "2"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION)
    static int testPhiUseBailout() {
        int len = getLen();
        byte[] original = new byte[len];
        System.arraycopy(SOURCE, 0, original, 0, len);
        byte[] ref = ((counter++ & 1) == 0) ? original : sink;
        byte[] cloned = original.clone();
        return blackhole(cloned[0] + ref[0]);
    }

    @Check(test = "testPhiUseBailout")
    static void checkPhiUseBailout(int result) {
        // ref alternates: original[0]='H'(72) or sink[0]=0
        if (result != (int) 'H' && result != (int) 'H' + (int) 'H') {
            throw new RuntimeException("testPhiUseBailout: expected " + (int) 'H' + " or " + ((int) 'H' + (int) 'H') + " but got " + result);
        }
    }

    // ---------------------------------------------------------------
    // Gap 2+: Multiple arraycopy fills then clone.
    //
    // Two System.arraycopy calls fill different ranges of the same
    // source array.  Both are recognized as fills (src as Dest at
    // line 602), and the clone is still eliminated.
    //
    // Before macro expansion:
    //   disabled: 3 ArrayCopy (fill1 + fill2 + clone)
    //   enabled:  2 ArrayCopy (fill1 + fill2 — clone eliminated)
    // ---------------------------------------------------------------
    @Test
    @IR(counts = {"ArrayCopy", "3"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION,
        applyIf = {"EliminateRedundantClone", "false"})
    @IR(counts = {"ArrayCopy", "2"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION,
        applyIf = {"EliminateRedundantClone", "true"})
    static int testMultipleFillsThenClone() {
        int len = getLen();
        int half = len / 2;
        byte[] original = new byte[len];
        System.arraycopy(SOURCE, 0, original, 0, half);
        System.arraycopy(SOURCE, half, original, half, len - half);
        byte[] cloned = original.clone();
        return blackhole(cloned[0]);
    }

    @Check(test = "testMultipleFillsThenClone")
    static void checkMultipleFillsThenClone(int result) {
        if (result != (int) 'H') {
            throw new RuntimeException("testMultipleFillsThenClone: expected " + (int) 'H' + " but got " + result);
        }
    }

    // ---------------------------------------------------------------
    // Gap 5+: SafePoint with debug-only reference — allowed.
    //
    // A non-inlined call between fill and clone creates a SafePoint
    // that has 'original' in JVM debug state (it is live across the
    // call).  Because original is NOT a real argument of the call,
    // has_non_debug_use(src) returns false and the optimization
    // correctly proceeds.
    //
    // Before macro expansion:
    //   disabled: 2 ArrayCopy (fill + clone)
    //   enabled:  1 ArrayCopy (fill only — clone eliminated)
    // ---------------------------------------------------------------
    @Test
    @IR(counts = {"ArrayCopy", "2"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION,
        applyIf = {"EliminateRedundantClone", "false"})
    @IR(counts = {"ArrayCopy", "1"},
        phase = CompilePhase.BEFORE_MACRO_EXPANSION,
        applyIf = {"EliminateRedundantClone", "true"})
    static int testSafePointDebugOnlyAllowed() {
        int len = getLen();
        byte[] original = new byte[len];
        System.arraycopy(SOURCE, 0, original, 0, len);
        int dummy = sideEffect();  // SafePoint: original in debug info only
        byte[] cloned = original.clone();
        return blackhole(cloned[0] + dummy);
    }

    @Check(test = "testSafePointDebugOnlyAllowed")
    static void checkSafePointDebugOnlyAllowed(int result) {
        if (result != (int) 'H' + 42) {
            throw new RuntimeException("testSafePointDebugOnlyAllowed: expected " + ((int) 'H' + 42) + " but got " + result);
        }
    }
}
