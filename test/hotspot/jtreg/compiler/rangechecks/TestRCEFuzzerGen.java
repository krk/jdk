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

package compiler.rangechecks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import jdk.test.lib.Utils;

import compiler.lib.generators.Generators;
import compiler.lib.template_framework.Template;
import compiler.lib.template_framework.TemplateToken;
import static compiler.lib.template_framework.Template.scope;
import static compiler.lib.template_framework.Template.let;
import static compiler.lib.template_framework.Template.$;

/**
 * Test generation for {@link TestRCEFuzzer}.
 *
 * Dimensions fuzzed:
 *   - AccessKind:      what Java construct produces the range check
 *   - AddressingMode:  how the index is typed (int, long, ConvI2L variants)
 *   - ScaleForm:       how the IV is scaled (mul, shift, negate)
 *   - LoopShape:       int/long IV, forward/backward, stride
 *
 * The ConvI2L addressing modes are the main focus: they exercise the patterns
 * where an int loop IV is widened to long for MemorySegment / checkIndex(long).
 */
public class TestRCEFuzzerGen {
    static final Random RANDOM = Utils.getRandomInstance();

    // We always use int (4-byte) accesses to keep generated code simple.
    // The important dimensions are AddressingMode, ScaleForm, and AccessKind.
    static final int ACCESS_BYTE_SIZE = 4;

    // ---------------------------------------------------------------
    //  Enums
    // ---------------------------------------------------------------

    /** Whether the index is in element units or byte units. */
    enum IndexUnit { ELEMENT, BYTE }

    /** Whether the offset part of the index is int or long typed. */
    enum OffsetType { INT, LONG }

    /** What Java-level construct produces the range check. */
    enum AccessKind {
        ARRAY                   ("int[]",         "c0.length",    "c1[idx1]",                                         "c0[idx0] = v",                                         "",                                                                           IndexUnit.ELEMENT),
        CHECK_INDEX_INT         ("int[]",         "c0.length",    "c1[idx1]",                                         "c0[idx0] = v",                                         "Objects.checkIndex(idx0, c0.length);\n        Objects.checkIndex(idx1, c1.length);\n", IndexUnit.ELEMENT),
        CHECK_INDEX_LONG        ("MemorySegment", "c0.byteSize()", "c1.get(ValueLayout.JAVA_INT_UNALIGNED, idx1)",     "c0.set(ValueLayout.JAVA_INT_UNALIGNED, idx0, v)",      "Objects.checkIndex(idx0, c0.byteSize());\n        Objects.checkIndex(idx1, c1.byteSize());\n", IndexUnit.BYTE),
        MEMORY_SEGMENT_GET      ("MemorySegment", "c0.byteSize()", "c1.get(ValueLayout.JAVA_INT_UNALIGNED, idx1)",     "c0.set(ValueLayout.JAVA_INT_UNALIGNED, idx0, v)",      "",                                                                           IndexUnit.BYTE),
        MEMORY_SEGMENT_AT_INDEX ("MemorySegment", "c0.byteSize()", "c1.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx1)", "c0.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx0, v)", "",                                                                       IndexUnit.ELEMENT),

        ;

        final String containerType; // "int[]" or "MemorySegment"
        final String lengthExpr;    // for checkIndex bounds (unused if no explicit check)
        final String readExpr;      // read v from c1 at idx1
        final String writeExpr;     // write v to c0 at idx0
        final String checkLines;    // explicit checkIndex calls (empty if implicit)
        final IndexUnit indexUnit;  // whether index is in elements or bytes

        AccessKind(String containerType, String lengthExpr, String readExpr, String writeExpr, String checkLines, IndexUnit indexUnit) {
            this.containerType = containerType;
            this.lengthExpr = lengthExpr;
            this.readExpr = readExpr;
            this.writeExpr = writeExpr;
            this.checkLines = checkLines;
            this.indexUnit = indexUnit;
        }

        boolean usesArray() { return containerType.equals("int[]"); }
        boolean usesLongIndex() { return !usesArray(); }
        String idxType() { return usesLongIndex() ? "long" : "int"; }
        int indexSize() { return indexUnit == IndexUnit.BYTE ? ACCESS_BYTE_SIZE : 1; }
    }

    /** How the index expression is typed w.r.t. int-to-long conversion.
     *  This is the critical dimension for ConvI2L pattern coverage.      */
    enum AddressingMode {
        INT_ONLY,           // con + scale * i + invarScale * invar0              (all int)
        LONG_ONLY,          // conL + scaleL * i + invarScaleL * invar0           (all long, long iv)
        CONV_I2L_OUTER,     // (long)(con + scale * i + invarScale * invar0)      ConvI2L wraps full expr
        CONV_I2L_IV_ONLY,   // conL + (long)i * scaleL + invarScaleL * invar0     ConvI2L on iv only
        CONV_I2L_SCALE,     // (long)(scale * i) + conL + invarScaleL * invar0    ConvI2L on scaled iv
        CONV_I2L_SHIFT,     // (long)(i << shift) + conL + invarScaleL * invar0   ConvI2L on shifted iv
    }

    /** How the IV is scaled inside the index expression. */
    enum ScaleForm {
        MUL,            // scale * i
        SHIFT,          // i << log2(|scale|)       (power-of-2 scale)
        NEGATE_MUL,     // (0 - |scale| * i)        (negative scale via SubI)
        IDENTITY,       // scale = +-1, just i or -i
    }

    // ---------------------------------------------------------------
    //  LoopShape
    // ---------------------------------------------------------------

    record LoopShape(boolean isLongIv, boolean forward, int strideAbs) {
        static LoopShape forAccess(AccessKind kind) {
            boolean longIv = kind.usesArray() ? false : RANDOM.nextBoolean();
            boolean fwd = RANDOM.nextBoolean();
            int stride = sample(List.of(1, 1, 1, 2));
            return new LoopShape(longIv, fwd, stride);
        }

        String ivType() { return isLongIv ? "long" : "int"; }
    }

    // ---------------------------------------------------------------
    //  IndexExpr
    // ---------------------------------------------------------------

    /**
     * Models: index = con + scale * iv + invarScale * invar0 + Sum(restScale_k * restInvar_k)
     *
     * The AddressingMode controls how int/long typing works in the generated Java expression.
     * The ScaleForm controls how scale*iv is represented (mul, shift, negate).
     *
     * The 'size' field represents how many units past the index value are consumed
     * by the access (1 for element-indexed, ACCESS_BYTE_SIZE for byte-offset).
     */
    public static record IndexExpr(
        AddressingMode addrMode,
        ScaleForm      scaleForm,
        int            scale,        // effective scale (may be negative)
        int            con,
        int            invarScale,
        int[]          invarRestScales,
        int            size
    ) {
        static IndexExpr random(AddressingMode addrMode, int numRest, int size, int strideAbs) {
            ScaleForm sf = (addrMode == AddressingMode.CONV_I2L_SHIFT)
                ? ScaleForm.SHIFT : sampleScaleForm();
            return random(addrMode, sf, numRest, size, strideAbs);
        }

        static IndexExpr random(AddressingMode addrMode, ScaleForm sf, int numRest, int size, int strideAbs) {
            int con = RANDOM.nextInt(-100_000, 100_000);

            int scale = switch (sf) {
                case SHIFT -> {
                    int shift = RANDOM.nextInt(0, 4);
                    int s = (1 << shift) * size;
                    yield RANDOM.nextBoolean() ? s : -s;
                }
                case IDENTITY -> RANDOM.nextBoolean() ? 1 : -1;
                case NEGATE_MUL -> -(randomAbsScale(size, strideAbs));
                case MUL -> {
                    int s = randomAbsScale(size, strideAbs);
                    yield RANDOM.nextBoolean() ? s : -s;
                }
            };

            int invarScale = randomAbsScale(size, 1);
            if (RANDOM.nextBoolean()) invarScale = -invarScale;

            int[] restScales = new int[numRest];
            for (int i = 0; i < numRest; i++) {
                restScales[i] = RANDOM.nextInt(-1, 2);
            }
            return new IndexExpr(addrMode, sf, scale, con, invarScale, restScales, size);
        }

        private static int randomAbsScale(int size, int strideAbs) {
            return switch (RANDOM.nextInt(8)) {
                case 0 -> RANDOM.nextInt(1, 4 * size + 1);
                default -> Math.max(1, size / strideAbs);
            };
        }

        /** Generates "new IndexExpr(con, scale, invarScale, new int[]{...}, size)" for runtime. */
        String generate() {
            return "new IndexExpr(" + con + ", " + scale + ", " + invarScale + ", new int[] {" +
                   Arrays.stream(invarRestScales)
                         .mapToObj(String::valueOf)
                         .collect(Collectors.joining(", ")) +
                   "}, " + size + ")";
        }

        /** Sum of abs(restScales) — worst-case contribution of rest invariants. */
        int err() {
            int sum = 0;
            for (int s : invarRestScales) sum += Math.abs(s);
            return sum;
        }

        /** Generate the index expression as a TemplateToken. */
        TemplateToken indexForAccess(String invar0, String[] invarRest) {
            // Determine the scale*iv sub-expression and whether offset is int or long.
            String scalePart;
            OffsetType offsetType;
            switch (addrMode) {
                case INT_ONLY -> {
                    scalePart = scaleExprInt();
                    offsetType = OffsetType.INT;
                }
                case LONG_ONLY -> {
                    scalePart = scale + "L * i";
                    offsetType = OffsetType.LONG;
                }
                case CONV_I2L_OUTER -> {
                    // Entire int expression wrapped: (long)(con + scale*i + invarScale*invar0 + rest)
                    return indexWrapped(invar0, invarRest);
                }
                case CONV_I2L_IV_ONLY -> {
                    scalePart = "(long)i * " + scale + "L";
                    offsetType = OffsetType.LONG;
                }
                case CONV_I2L_SCALE -> {
                    scalePart = "(long)(" + scaleExprInt() + ")";
                    offsetType = OffsetType.LONG;
                }
                case CONV_I2L_SHIFT -> {
                    int shift = Integer.numberOfTrailingZeros(Math.abs(scale));
                    scalePart = (scale > 0)
                        ? "(long)(i << " + shift + ")"
                        : "(long)(-(i << " + shift + "))";
                    offsetType = OffsetType.LONG;
                }
                default -> throw new RuntimeException("unreachable");
            }
            return indexFromParts(scalePart, offsetType, invar0, invarRest);
        }

        /** Common path: scalePart + con + invarScale*invar0 + rest */
        private TemplateToken indexFromParts(String scalePart, OffsetType ot, String invar0, String[] invarRest) {
            String L = (ot == OffsetType.LONG) ? "L" : "";
            String castInvar = (ot == OffsetType.LONG) ? "(long)" : "";
            return Template.make(() -> scope(
                let("scalePart", scalePart),
                let("con", con), let("invarScale", invarScale), let("invar0", invar0),
                let("L", L), let("castInvar", castInvar),
                "#{con}#L + #scalePart + #{invarScale}#L * #castInvar#invar0",
                (ot == OffsetType.LONG) ? longRestTerms(invarRest) : intRestTerms(invarRest)
            )).asToken();
        }

        /** CONV_I2L_OUTER: (long)(entire int expression) */
        private TemplateToken indexWrapped(String invar0, String[] invarRest) {
            return Template.make(() -> scope(
                let("con", con), let("scaleExpr", scaleExprInt()),
                let("invarScale", invarScale), let("invar0", invar0),
                "(long)(#con + #scaleExpr + #invarScale * #invar0",
                intRestTerms(invarRest),
                ")"
            )).asToken();
        }

        private String scaleExprInt() {
            return switch (scaleForm) {
                case MUL        -> scale + " * i";
                case SHIFT      -> {
                    int shift = Integer.numberOfTrailingZeros(Math.abs(scale));
                    yield (scale > 0 ? "" : "-(") + "(i << " + shift + ")" + (scale > 0 ? "" : ")");
                }
                case NEGATE_MUL -> "(0 - " + Math.abs(scale) + " * i)";
                case IDENTITY   -> (scale > 0 ? "i" : "(-i)");
            };
        }

        private Object intRestTerms(String[] invarRest) {
            return IntStream.range(0, invarRestScales.length)
                .filter(i -> invarRestScales[i] != 0)
                .mapToObj(i -> List.of(" + ", invarRestScales[i], " * ", invarRest[i]))
                .toList();
        }

        private Object longRestTerms(String[] invarRest) {
            return IntStream.range(0, invarRestScales.length)
                .filter(i -> invarRestScales[i] != 0)
                .mapToObj(i -> List.of(" + ", invarRestScales[i], "L * (long)", invarRest[i]))
                .toList();
        }
    }

    // ---------------------------------------------------------------
    //  TestCase — a complete generated test
    // ---------------------------------------------------------------

    public record TestCase(
        AccessKind  accessKind,
        LoopShape   loopShape,
        IndexExpr   idx0,
        IndexExpr   idx1,
        int         containerByteSize,
        int         numInvarRest
    ) {
        /**
         * Create a test case with all structural dimensions fixed.
         * Only continuous values (con, invarScale, invarRest) are randomized.
         */
        public static TestCase make(AccessKind kind, AddressingMode addrMode,
                                    ScaleForm scaleForm, boolean forward, int strideAbs) {
            boolean longIv = (addrMode == AddressingMode.LONG_ONLY);
            var loop = new LoopShape(longIv, forward, strideAbs);
            int nElem = Generators.G.safeRestrict(Generators.G.ints(), 18_000, 20_000).next();
            int byteSize = nElem * ACCESS_BYTE_SIZE;
            int nRest = RANDOM.nextInt(4);
            int idxSize = kind.indexSize();
            var i0 = IndexExpr.random(addrMode, scaleForm, nRest, idxSize, strideAbs);
            var i1 = IndexExpr.random(addrMode, scaleForm, nRest, idxSize, strideAbs);
            return new TestCase(kind, loop, i0, i1, byteSize, nRest);
        }

        /** All valid (AddressingMode x ScaleForm) combinations for one AccessKind. */
        public static List<TestCase> allCombinations(AccessKind kind, boolean forward, int strideAbs) {
            List<TestCase> cases = new ArrayList<>();
            for (var addrMode : validAddressingModes(kind)) {
                for (var scaleForm : validScaleForms(addrMode)) {
                    cases.add(make(kind, addrMode, scaleForm, forward, strideAbs));
                }
            }
            return cases;
        }

        private static List<AddressingMode> validAddressingModes(AccessKind kind) {
            if (kind.usesArray()) {
                return List.of(AddressingMode.INT_ONLY);
            }
            return List.of(
                AddressingMode.LONG_ONLY,
                AddressingMode.CONV_I2L_OUTER,
                AddressingMode.CONV_I2L_IV_ONLY,
                AddressingMode.CONV_I2L_SCALE,
                AddressingMode.CONV_I2L_SHIFT);
        }

        private static List<ScaleForm> validScaleForms(AddressingMode addrMode) {
            if (addrMode == AddressingMode.CONV_I2L_SHIFT) {
                return List.of(ScaleForm.SHIFT);
            }
            return List.of(ScaleForm.MUL, ScaleForm.SHIFT, ScaleForm.NEGATE_MUL, ScaleForm.IDENTITY);
        }

        /** Container size in the units used by the index (elements or bytes). */
        int containerIndexSize() {
            return (accessKind.indexUnit == IndexUnit.BYTE)
                ? containerByteSize
                : containerByteSize / ACCESS_BYTE_SIZE;
        }

        // --- Code generation ---

        /** Descriptive scenario name encoding the key dimensions. */
        String scenario() {
            return accessKind.name()
                + "_" + idx0.addrMode().name()
                + "_" + idx0.scaleForm().name()
                + (loopShape.forward() ? "_fwd" : "_bwd")
                + "_s" + loopShape.strideAbs();
        }

        public TemplateToken generate() {
            String desc = scenario();
            var testTemplate = Template.make(() -> {
                String c0 = $("c0");
                String c1 = $("c1");
                String ix0 = $("ix0");
                String ix1 = $("ix1");
                String testName = $("test_" + desc);
                String refName = $("ref_" + desc);
                String[] invarRest = new String[numInvarRest];
                for (int i = 0; i < numInvarRest; i++) {
                    invarRest[i] = $("ir" + i);
                }
                return scope(
                    generateFields(c0, c1, ix0, ix1, invarRest),
                    generateRunner(testName, refName, c0, c1, ix0, ix1, invarRest),
                    "@Test\n",
                    generateIRRules(),
                    generateTestMethod(testName, invarRest),
                    "@DontCompile\n",
                    generateTestMethod(refName, invarRest),
                    "\n"
                );
            });
            return testTemplate.asToken();
        }

        // --- Sub-generators ---

        private TemplateToken generateFields(String c0, String c1, String ix0, String ix1, String[] invarRest) {
            return Template.make(() -> scope(
                let("ivType", loopShape.ivType()),
                let("cSize", containerByteSize / ACCESS_BYTE_SIZE),
                let("cByteSize", containerByteSize),
                let("c0", c0), let("c1", c1),
                let("ix0", ix0), let("ix1", ix1),
                // Invariant rest fields
                IntStream.range(0, numInvarRest).mapToObj(i ->
                    List.of("private static #ivType ", invarRest[i], " = 0;\n")
                ).toList(),
                // Container fields
                (accessKind.usesArray()
                 ?  """
                    private static int[] original_#c0  = new int[#cSize];
                    private static int[] test_#c0      = new int[#cSize];
                    private static int[] reference_#c0 = new int[#cSize];
                    private static int[] original_#c1  = new int[#cSize];
                    private static int[] test_#c1      = new int[#cSize];
                    private static int[] reference_#c1 = new int[#cSize];
                    """
                 :  """
                    private static MemorySegment original_#c0  = MemorySegment.ofArray(new byte[#cByteSize]);
                    private static MemorySegment test_#c0      = MemorySegment.ofArray(new byte[#cByteSize]);
                    private static MemorySegment reference_#c0 = MemorySegment.ofArray(new byte[#cByteSize]);
                    private static MemorySegment original_#c1  = MemorySegment.ofArray(new byte[#cByteSize]);
                    private static MemorySegment test_#c1      = MemorySegment.ofArray(new byte[#cByteSize]);
                    private static MemorySegment reference_#c1 = MemorySegment.ofArray(new byte[#cByteSize]);
                    """
                ),
                // IndexExpr fields
                let("ix0Gen", idx0.generate()),
                let("ix1Gen", idx1.generate()),
                """
                private static IndexExpr #ix0 = #{ix0Gen};
                private static IndexExpr #ix1 = #{ix1Gen};
                """
            )).asToken();
        }

        private TemplateToken generateRunner(String testName, String refName, String c0, String c1, String ix0, String ix1, String[] invarRest) {
            return Template.make(() -> scope(
                let("c0", c0), let("c1", c1),
                let("ix0", ix0), let("ix1", ix1),
                let("testMethod", testName),
                let("refMethod", refName),
                let("cast", loopShape.isLongIv() ? "(long)" : ""),
                let("containerIndexSize", containerIndexSize()),
                let("minIvRange", loopShape.strideAbs() * 1000),
                """
                private static int $iterations = 0;

                @Run(test = "#testMethod")
                @Warmup(200)
                public static void $run(RunInfo info) {
                    if ($iterations == 0) {
                """,
                generateFillOriginals(c1),
                """
                    }
                    $iterations++;
                    int reps = info.isWarmUp() ? 5 : 3;
                    for (int rep = 0; rep < reps; rep++) {
                """,
                generateContainerInit(c0, c1),
                generateBoundsComputation(ix0, ix1, invarRest),
                """
                        var result   = #testMethod(test_#c0, test_#c1, #cast ivLo, #cast ivHi, #cast invar0_0, #cast invar0_1);
                        var expected = #refMethod(reference_#c0, reference_#c1, #cast ivLo, #cast ivHi, #cast invar0_0, #cast invar0_1);
                        Verify.checkEQ(result, expected);

                        if (!info.isWarmUp()) {
                """,
                generateOOBVerification(testName, refName, c0, c1),
                """
                        }
                    }
                }
                """
            )).asToken();
        }

        private TemplateToken generateFillOriginals(String c1) {
            return Template.make(() -> scope(
                let("c1", c1),
                (accessKind.usesArray()
                 ?  """
                            for (int j = 0; j < original_#c1.length; j++) original_#c1[j] = j * 13 + 11;
                    """
                 :  """
                            for (int j = 0; j < (int)(original_#c1.byteSize() / 4); j++)
                                original_#c1.set(ValueLayout.JAVA_INT_UNALIGNED, (long)(j * 4), j * 13 + 11);
                    """
                )
            )).asToken();
        }

        private TemplateToken generateContainerInit(String c0, String c1) {
            return Template.make(() -> scope(
                let("c0", c0), let("c1", c1),
                let("cSize", containerByteSize / ACCESS_BYTE_SIZE),
                (accessKind.usesArray()
                 ?  """
                        System.arraycopy(original_#c0, 0, test_#c0,      0, #cSize);
                        System.arraycopy(original_#c0, 0, reference_#c0, 0, #cSize);
                        System.arraycopy(original_#c1, 0, test_#c1,      0, #cSize);
                        System.arraycopy(original_#c1, 0, reference_#c1, 0, #cSize);
                    """
                 :  """
                        test_#c0.copyFrom(original_#c0);      reference_#c0.copyFrom(original_#c0);
                        test_#c1.copyFrom(original_#c1);      reference_#c1.copyFrom(original_#c1);
                    """
                )
            )).asToken();
        }

        private TemplateToken generateBoundsComputation(String ix0, String ix1, String[] invarRest) {
            return Template.make(() -> scope(
                let("ix0", ix0), let("ix1", ix1),
                let("containerIndexSize", containerIndexSize()),
                let("minIvRange", loopShape.strideAbs() * 1000),
                """
                        int ivLo = RANDOM.nextInt(-1000, 1000);
                        int ivHi = ivLo + 100000;
                        var range = new IndexExpr.Range(0, #containerIndexSize);
                        int invar0_0 = #ix0.invar0ForIvLo(range, ivLo);
                        ivHi = Math.min(ivHi, #ix0.ivHiForInvar0(range, invar0_0));
                        int invar0_1 = #ix1.invar0ForIvLo(range, ivLo);
                        ivHi = Math.min(ivHi, #ix1.ivHiForInvar0(range, invar0_1));
                        if (ivLo + #minIvRange > ivHi) {
                            throw new RuntimeException("iv range too small: " + ivLo + " " + ivHi);
                        }
                """,
                IntStream.range(0, numInvarRest).mapToObj(i ->
                    List.of("                        ", invarRest[i], " = RANDOM.nextInt(-1, 2);\n")
                ).toList()
            )).asToken();
        }

        private TemplateToken generateOOBVerification(String testName, String refName, String c0, String c1) {
            // Minimum extra IV steps to guarantee OOB beyond the err safety margin.
            int minExtra = Math.max(
                Math.ceilDiv(2 * idx0.err() + idx0.size(), Math.abs(idx0.scale())),
                Math.ceilDiv(2 * idx1.err() + idx1.size(), Math.abs(idx1.scale()))) + 1;
            String castPfx = loopShape.isLongIv() ? "(long)" : "";
            return Template.make(() -> scope(
                let("c0", c0), let("c1", c1),
                let("testMethod", testName), let("refMethod", refName),
                let("strideAbs", loopShape.strideAbs()),
                let("cast", castPfx),
                let("cSize", containerByteSize / ACCESS_BYTE_SIZE),
                let("minExtra", minExtra),
                """
                            int oobIvHi = ivHi + RANDOM.nextInt(#minExtra, #minExtra + 20) * #strideAbs;
                """,
                generateContainerReInit("reference", c0, c1),
                """
                            Throwable refEx = null;
                            try {
                                #refMethod(reference_#c0, reference_#c1, #cast ivLo, #cast oobIvHi, #cast invar0_0, #cast invar0_1);
                            } catch (Exception e) { refEx = e; }
                            if (refEx == null) {
                                throw new RuntimeException("bounds computation bug: reference did not throw on OOB");
                            }
                """,
                generateContainerReInit("test", c0, c1),
                """
                            Throwable testEx = null;
                            try {
                                #testMethod(test_#c0, test_#c1, #cast ivLo, #cast oobIvHi, #cast invar0_0, #cast invar0_1);
                            } catch (Exception e) { testEx = e; }
                            if (testEx == null) {
                                throw new RuntimeException("RCE bug: compiled method did NOT throw on OOB input!");
                            }
                            Verify.checkEQ(
                                new Object[]{test_#c0, test_#c1},
                                new Object[]{reference_#c0, reference_#c1});
                """
            )).asToken();
        }

        private TemplateToken generateContainerReInit(String prefix, String c0, String c1) {
            return Template.make(() -> scope(
                let("pc0", prefix + "_" + c0),
                let("pc1", prefix + "_" + c1),
                let("c0", c0), let("c1", c1),
                let("cSize", containerByteSize / ACCESS_BYTE_SIZE),
                (accessKind.usesArray()
                 ?  """
                            System.arraycopy(original_#c0, 0, #pc0, 0, #cSize);
                            System.arraycopy(original_#c1, 0, #pc1, 0, #cSize);
                    """
                 :  """
                            #pc0.copyFrom(original_#c0);
                            #pc1.copyFrom(original_#c1);
                    """
                )
            )).asToken();
        }

        private TemplateToken generateIRRules() {
            // Verify RCE eliminated range checks. Only applies when RCE-related flags are on.
            // Failures here are potential C2 bugs — patterns RCE should handle but doesn't.
            return Template.make(() -> scope(
                """
                @IR(failOn = {IRNode.RANGE_CHECK_TRAP},
                    phase = {CompilePhase.AFTER_RANGE_CHECK_ELIMINATION},
                    applyIfAnd = {"UseLoopPredicate", "true", "RangeCheckElimination", "true"},
                    applyIfPlatform = {"64-bit", "true"})
                """
            )).asToken();
        }

        private TemplateToken generateTestMethod(String methodName, String[] invarRest) {
            return Template.make(() -> scope(
                let("methodName", methodName),
                let("containerType", accessKind.containerType),
                let("ivType", loopShape.ivType()),
                let("strideAbs", loopShape.strideAbs()),
                "public static Object #methodName(#containerType c0, #containerType c1, #ivType ivLo, #ivType ivHi, #ivType invar0_0, #ivType invar0_1) {\n",
                "    long sum = 0;\n",
                (loopShape.forward()
                 ?  "    for (#ivType i = ivLo; i < ivHi; i += #strideAbs) {\n"
                 :  "    for (#ivType i = ivHi - #strideAbs; i >= ivLo; i -= #strideAbs) {\n"
                ),
                generateLoopBody(invarRest),
                """
                    }
                    return new Object[] { c0, c1, sum };
                }
                """
            )).asToken();
        }

        private TemplateToken generateLoopBody(String[] invarRest) {
            return Template.make(() -> scope(
                let("idxType", accessKind.idxType()),
                let("check", accessKind.checkLines),
                let("read", accessKind.readExpr),
                let("write", accessKind.writeExpr),
                "        #idxType idx1 = ", idx1.indexForAccess("invar0_1", invarRest), ";\n",
                "        #idxType idx0 = ", idx0.indexForAccess("invar0_0", invarRest), ";\n",
                "        #check",
                "        int v = #read;\n",
                "        #write;\n",
                "        sum += v;\n"
            )).asToken();
        }
    }

    // ---------------------------------------------------------------
    //  Runtime helpers (embedded in generated class)
    // ---------------------------------------------------------------

    public static TemplateToken generateRuntimeHelpers() {
        return Template.make(() -> scope(
            """
            private static final Random RANDOM = Utils.getRandomInstance();

            public static record IndexExpr(int con, int scale, int invarScale, int[] invarRestScales, int size) {
                public IndexExpr {
                    if (scale == 0 || invarScale == 0) {
                        throw new RuntimeException("Bad scales: " + scale + " " + invarScale);
                    }
                }

                public static record Range(int lo, int hi) {
                    public Range {
                        if (lo >= hi) { throw new RuntimeException("Bad range: " + lo + " " + hi); }
                    }
                }

                public int err() {
                    int sum = 0;
                    for (int s : invarRestScales) { sum += Math.abs(s); }
                    return sum;
                }

                public int invar0ForIvLo(Range range, int ivLo) {
                    if (scale > 0) {
                        int rhs = range.lo() - con - ivLo * scale + err();
                        int invar0 = (invarScale > 0)
                            ? Math.floorDiv(rhs + invarScale - 1, invarScale)
                            : Math.floorDiv(rhs, invarScale);
                        if (range.lo() > con + ivLo * scale + invar0 * invarScale - err()) {
                            throw new RuntimeException("sanity check failed (1)");
                        }
                        return invar0;
                    } else {
                        int rhs = range.hi() - con - ivLo * scale - err() - size();
                        int invar0 = (invarScale > 0)
                            ? Math.floorDiv(rhs, invarScale)
                            : Math.floorDiv(rhs + invarScale + 1, invarScale);
                        if (range.hi() < con + ivLo * scale + invar0 * invarScale + err() + size()) {
                            throw new RuntimeException("sanity check failed (2)");
                        }
                        return invar0;
                    }
                }

                public int ivHiForInvar0(Range range, int invar0) {
                    if (scale > 0) {
                        int rhs = range.hi() - con - invar0 * invarScale - err() - size();
                        int ivHi = Math.floorDiv(rhs, scale);
                        if (range.hi() < con + ivHi * scale + invar0 * invarScale + err() + size()) {
                            throw new RuntimeException("sanity check failed (3)");
                        }
                        return ivHi;
                    } else {
                        int rhs = range.lo() - con - invar0 * invarScale + err();
                        int ivHi = Math.floorDiv(rhs, scale);
                        if (range.lo() > con + ivHi * scale + invar0 * invarScale - err()) {
                            throw new RuntimeException("sanity check failed (4)");
                        }
                        return ivHi;
                    }
                }
            }
            """
        )).asToken();
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    public static <T> T sample(List<T> list) {
        return list.get(RANDOM.nextInt(list.size()));
    }

    static ScaleForm sampleScaleForm() {
        return sample(List.of(
            ScaleForm.MUL, ScaleForm.MUL, ScaleForm.MUL,
            ScaleForm.SHIFT, ScaleForm.NEGATE_MUL, ScaleForm.IDENTITY));
    }

    static AddressingMode sampleConvI2LMode() {
        return sample(List.of(
            AddressingMode.CONV_I2L_OUTER,
            AddressingMode.CONV_I2L_OUTER,
            AddressingMode.CONV_I2L_IV_ONLY,
            AddressingMode.CONV_I2L_SCALE,
            AddressingMode.CONV_I2L_SHIFT));
    }
}
