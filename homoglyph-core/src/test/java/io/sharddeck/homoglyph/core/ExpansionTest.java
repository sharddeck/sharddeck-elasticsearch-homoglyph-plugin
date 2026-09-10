// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

import com.ibm.icu.text.SpoofChecker;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ExpansionTest {
    private final ExpansionData data = ExpansionData.INSTANCE;
    private DefaultEngine engine() { return new DefaultEngine(1024 * 1024); }
    private List<String> expand(DefaultEngine engine, String input, Map<String,String> values) {
        Map<String,String> settings = new HashMap<>(values); settings.put("profile", "unicode_expand_v1");
        Configuration config = new Configuration(settings);
        try (TokenProcessor processor = engine.newProcessor(config.profile, config.limits, config.preserveOriginal)) {
            processor.prepare(input.toCharArray(), 0, input.length());
            int planned = processor.plannedOutputs();
            List<String> result = new ArrayList<>();
            while (processor.increment()) result.add(new String(processor.buffer(), processor.offset(), processor.length()));
            assertEquals(planned, result.size()); assertEquals(result.size(), new HashSet<>(result).size());
            return result;
        }
    }
    private String spelling(int variant) {
        char[] chars = new char[data.length(variant)]; data.append(variant, chars, 0); return new String(chars);
    }
    private String key(SpoofChecker checker, String input) {
        for (int i = 0; i < 16; i++) {
            String next = checker.getSkeleton(input); if (next.equals(input)) return input; input = next;
        }
        throw new AssertionError("nonconvergent skeleton");
    }
    @Test void everyPinnedSpellingMatchesItsTrieAndIcuClass() {
        SpoofChecker checker = new SpoofChecker.Builder().build();
        assertEquals(34214, data.variants());
        for (int id = 0; id < data.variants(); id++) {
            String text = spelling(id);
            assertEquals(id, data.match(text.toCharArray(), 0, text.length()));
            assertEquals(text.getBytes(StandardCharsets.UTF_8).length, data.bytes(id));
            int peer = data.alternative(id, 0);
            assertEquals(key(checker, text), key(checker, spelling(peer)), text);
            List<String> group = new ArrayList<>(); group.add(text);
            for (int n = 0; n < data.alternatives(id); n++) {
                int other = data.alternative(id, n); assertNotEquals(id, other); group.add(spelling(other));
            }
            Collections.sort(group);
            for (int n = 1; n < group.size(); n++) assertFalse(group.get(n).startsWith(group.get(n - 1)));
        }
    }
    @Test void exactOrderingAcrossChangedSpansAndWidths() {
        DefaultEngine engine = engine();
        assertEquals(List.of("éñ", "éñ", "éñ"), expand(engine, "éñ", Map.of()));
        assertEquals(List.of("éñ", "éñ", "éñ", "éñ"), expand(engine, "éñ", Map.of("max_changed_positions", "2")));
        assertEquals(List.of("漢", "漢", "漢"), expand(engine, "漢", Map.of()));
        assertEquals(List.of("😀é😀", "😀é😀"), expand(engine, "😀é😀", Map.of()));
        assertEquals(List.of("é", "é"), expand(engine, "é", Map.of()));
        assertEquals(0, engine.borrowedWorkspaceBytes());
    }
    @Test void multiScalarAndSupplementarySpellingsAreBidirectional() {
        DefaultEngine engine = engine();
        List<String> values = expand(engine, "m", Map.of());
        assertEquals(18, values.size()); assertEquals("m", values.get(0)); assertTrue(values.contains("rn")); assertTrue(values.contains("𝐦"));
        for (String value : values) {
            List<String> expanded = expand(engine, value, Map.of());
            assertEquals(value, expanded.get(0)); assertEquals(new HashSet<>(values), new HashSet<>(expanded));
        }
        assertTrue(expand(engine, "om", Map.of()).contains("orn"));
    }
    @Test void longestMatchPreventsOverlappingDecompositions() {
        DefaultEngine engine = engine();
        assertEquals(18, expand(engine, "rn", Map.of()).size());
        List<String> expanded = expand(engine, "rnrn", Map.of("max_changed_positions", "2"));
        assertEquals(18 * 18, expanded.size()); assertTrue(expanded.contains("mm"));
        assertEquals(35, expand(engine, "rnrn", Map.of()).size());
    }
    @Test void originalIsIntrinsicAndDefaultNormalizationIsUnchanged() {
        DefaultEngine engine = engine();
        assertEquals(expand(engine, "é", Map.of()), expand(engine, "é", Map.of("preserve_original", "true")));
        assertEquals(ProfileId.UNICODE_SEARCH_V1, new Configuration(Map.of()).profile);
        assertEquals(List.of("😀"), expand(engine, "😀", Map.of()));
        assertEquals(List.of(), expand(engine, "", Map.of()));
        assertEquals(List.of("\u200b"), expand(engine, "\u200b", Map.of()));
    }
    @Test void planCountAndByteBoundariesRejectBeforeEmission() {
        DefaultEngine engine = engine();
        for (int limit : new int[]{3,4,5}) {
            Map<String,String> settings = Map.of("max_changed_positions", "2", "max_output_tokens_per_input", "" + limit);
            if (limit < 4) assertEquals(RejectionReason.OUTPUT_BUDGET_EXCEEDED,
                assertThrows(AnalysisException.class, () -> expand(engine, "éé", settings)).reason());
            else assertEquals(4, expand(engine, "éé", settings).size());
        }
        for (int limit : new int[]{19,20,21}) {
            Map<String,String> settings = Map.of("max_changed_positions", "2", "max_output_bytes_per_input", "" + limit);
            if (limit < 20) assertThrows(AnalysisException.class, () -> expand(engine, "éé", settings));
            else assertEquals(4, expand(engine, "éé", settings).size());
        }
        for (int limit : new int[]{5,6,7}) {
            Map<String,String> settings = Map.of("max_changed_positions", "2", "max_output_utf8_bytes", "" + limit);
            if (limit < 6) assertThrows(AnalysisException.class, () -> expand(engine, "éé", settings));
            else assertEquals(4, expand(engine, "éé", settings).size());
        }
        assertEquals(794, expand(engine, "é".repeat(12), Map.of("max_changed_positions", "4")).size());
        for (String text : List.of("é".repeat(13), "o".repeat(4096))) {
            assertEquals(RejectionReason.OUTPUT_BUDGET_EXCEEDED, assertThrows(AnalysisException.class,
                () -> expand(engine, text, Map.of("max_changed_positions", "4"))).reason());
            assertEquals(0, engine.borrowedWorkspaceBytes());
        }
        try (TokenProcessor p = engine.newProcessor(ProfileId.UNICODE_EXPAND_V1, new Limits(Map.of("max_output_tokens_per_input", "1")), false)) {
            assertThrows(AnalysisException.class, () -> p.prepare(new char[]{'é'}, 0, 1));
            assertEquals(0, p.plannedOutputs()); assertFalse(p.hasPending()); assertThrows(IllegalStateException.class, p::increment);
        }
    }
    @Test void literalTailIsIncludedInTheOutputPlan() {
        DefaultEngine engine = engine();
        assertThrows(AnalysisException.class, () -> expand(engine, "é" + "😀".repeat(2047), Map.of("max_output_utf8_bytes", "8190")));
        assertEquals(0, engine.borrowedWorkspaceBytes());
        assertEquals(2, expand(engine, "é" + "😀".repeat(2047), Map.of("max_output_utf8_bytes", "8191")).size());
    }
    @Test void keywordAndMalformedInputHaveTheSameSafetyLimits() {
        DefaultEngine engine = engine();
        try (TokenProcessor p = engine.newProcessor(ProfileId.UNICODE_EXPAND_V1, Limits.DEFAULT, false)) {
            p.prepare(new char[]{'é'}, 0, 1, true); assertTrue(p.increment()); assertEquals('é', p.buffer()[p.offset()]); assertFalse(p.increment());
            for (boolean keyword : new boolean[]{false,true}) {
                p.reset(); assertEquals(RejectionReason.MALFORMED_INPUT,
                    assertThrows(AnalysisException.class, () -> p.prepare(new char[]{'\ud800'}, 0, 1, keyword)).reason());
                p.reset(); char[] big = "x".repeat(4097).toCharArray();
                assertEquals(RejectionReason.INPUT_TOO_LONG, assertThrows(AnalysisException.class, () -> p.prepare(big, 0, big.length, keyword)).reason());
            }
        }
    }
    @Test void offsetsAndBorrowedInputAreIsolatedForMultiOutput() {
        DefaultEngine engine = engine(); char[] input = "!é?".toCharArray();
        try (TokenProcessor p = engine.newProcessor(ProfileId.UNICODE_EXPAND_V1, Limits.DEFAULT, false)) {
            p.prepare(input, 1, 1); Arrays.fill(input, 'x');
            assertTrue(p.increment()); assertEquals("é", new String(p.buffer(), p.offset(), p.length()));
            assertTrue(p.increment()); assertEquals("é", new String(p.buffer(), p.offset(), p.length())); assertFalse(p.increment());
        }
    }
    @Test void streamLimitsCleanupAndRecovery() {
        DefaultEngine engine = engine();
        for (String setting : List.of("max_output_tokens_per_stream", "max_output_utf8_bytes_per_stream")) {
            Limits limits = new Limits(Map.of(setting, setting.contains("tokens") ? "3" : "9"));
            try (TokenProcessor p = engine.newProcessor(ProfileId.UNICODE_EXPAND_V1, limits, false)) {
                p.prepare(new char[]{'é'}, 0, 1); while (p.increment()) {}
                assertEquals(RejectionReason.STREAM_BUDGET_EXCEEDED, assertThrows(AnalysisException.class, () -> p.prepare(new char[]{'é'}, 0, 1)).reason());
                assertEquals(0, engine.borrowedWorkspaceBytes());
                p.reset(); p.prepare(new char[]{'é'}, 0, 1); assertTrue(p.increment());
                p.reset(); assertEquals(0, engine.borrowedWorkspaceBytes());
            }
        }
    }
    @Test void exactAdmissionAndAbandonmentDoNotLeak() {
        DefaultEngine engine = new DefaultEngine(1216);
        try (TokenProcessor held = engine.newProcessor(ProfileId.UNICODE_EXPAND_V1, Limits.DEFAULT, false)) {
            held.prepare(new char[]{'é'}, 0, 1); assertEquals(1216, engine.borrowedWorkspaceBytes());
            assertEquals(RejectionReason.MEMORY_BUDGET_EXCEEDED, assertThrows(AnalysisException.class, () -> expand(engine, "é", Map.of())).reason());
        }
        assertEquals(0, engine.borrowedWorkspaceBytes()); assertEquals(List.of("é", "é"), expand(engine, "é", Map.of()));
    }
    @Test void concurrencyKeepsReservationsBounded() throws Exception {
        DefaultEngine engine = new DefaultEngine(16384); ExecutorService workers = Executors.newFixedThreadPool(32);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < 64; t++) tasks.add(() -> {
                try (TokenProcessor p = engine.newProcessor(ProfileId.UNICODE_EXPAND_V1, new Limits(Map.of("max_changed_positions", "4")), false)) {
                    char[] input = "é".repeat(8).toCharArray();
                    for (int n = 0; n < 100; n++) {
                        p.reset();
                        try { p.prepare(input, 0, input.length); if ((n & 3) == 0) p.increment(); else while (p.increment()) {} }
                        catch (AnalysisException error) { assertEquals(RejectionReason.MEMORY_BUDGET_EXCEEDED, error.reason()); }
                    }
                }
                return null;
            });
            for (Future<Void> result : workers.invokeAll(tasks)) result.get();
        } finally { workers.shutdownNow(); }
        assertEquals(0, engine.borrowedWorkspaceBytes()); assertTrue(engine.budget.highWaterBytes() <= 16384);
        assertEquals(2, expand(engine, "é", Map.of()).size());
    }
    @Test void expansionSettingsCannotDisableCeilings() {
        for (Map<String,String> values : List.of(Map.of("max_output_tokens_per_input", "1025"), Map.of("max_output_tokens_per_input", "0"),
                Map.of("max_changed_positions", "0"), Map.of("max_changed_positions", "5"), Map.of("max_changed_positions", "2147483648")))
            assertThrows(IllegalArgumentException.class, () -> new Configuration(values));
    }
    @Test void smallPlansMatchAnIndependentExhaustiveOracle() {
        String[][] rules = {{"é", "é"}, {"ñ", "ñ"}, {"漢", "漢", "漢"}, {"😀"}};
        Random random = new Random(1734214); DefaultEngine engine = engine();
        for (int run = 0; run < 100; run++) {
            String[][] selected = new String[5][]; StringBuilder input = new StringBuilder();
            for (int i = 0; i < selected.length; i++) { selected[i] = rules[random.nextInt(rules.length)]; input.append(selected[i][0]); }
            int changes = 1 + random.nextInt(4); Set<String> expected = new HashSet<>();
            enumerate(selected, 0, changes, "", expected);
            assertEquals(expected, new HashSet<>(expand(engine, input.toString(), Map.of("max_changed_positions", "" + changes))));
        }
    }
    private void enumerate(String[][] rules, int position, int remaining, String prefix, Set<String> result) {
        if (position == rules.length) { result.add(prefix); return; }
        for (int i = 0; i < rules[position].length; i++) if (i == 0 || remaining > 0)
            enumerate(rules, position + 1, remaining - (i == 0 ? 0 : 1), prefix + rules[position][i], result);
    }
}
