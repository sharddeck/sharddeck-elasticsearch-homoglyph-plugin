// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExpansionProcessorTest {
    private List<String> tokens(DefaultEngine engine, String text, Map<String, String> settings) {
        Configuration configuration = new Configuration(withProfile(settings));
        try (TokenProcessor processor = engine.newProcessor(configuration.profile, configuration.limits, configuration.preserveOriginal)) {
            char[] input = text.toCharArray();
            processor.prepare(input, 0, input.length);
            java.util.ArrayList<String> result = new java.util.ArrayList<>();
            while (processor.increment()) result.add(new String(processor.buffer(), processor.offset(), processor.length()));
            return result;
        }
    }

    private Map<String, String> withProfile(Map<String, String> settings) {
        java.util.HashMap<String, String> result = new java.util.HashMap<>(settings);
        result.put("profile", "unicode_expand_v1");
        return result;
    }

    @Test void emitsDeterministicSingleAndMultiSpanVariants() {
        DefaultEngine engine = new DefaultEngine(1024 * 1024);
        assertEquals(List.of("é", "é"), tokens(engine, "é", Map.of()));
        assertEquals(List.of("éñ", "éñ", "éñ"), tokens(engine, "éñ", Map.of()));
        assertEquals(List.of("éñ", "éñ", "éñ", "éñ"),
            tokens(engine, "éñ", Map.of("max_changed_positions", "2")));
        assertEquals(tokens(engine, "m", Map.of()), tokens(engine, "m", Map.of()));
        assertEquals(18, tokens(engine, "m", Map.of()).size());
        assertEquals(1, tokens(engine, "😀", Map.of()).size());
        assertEquals(0, engine.borrowedWorkspaceBytes());
    }

    @Test void wholePlanIsCheckedBeforeFirstEmission() {
        DefaultEngine engine = new DefaultEngine(1024 * 1024);
        assertEquals(RejectionReason.OUTPUT_BUDGET_EXCEEDED,
            assertThrows(AnalysisException.class, () -> tokens(engine, "é", Map.of("max_output_tokens_per_input", "1"))).reason());
        assertEquals(RejectionReason.OUTPUT_BUDGET_EXCEEDED,
            assertThrows(AnalysisException.class, () -> tokens(engine, "é", Map.of("max_output_utf8_bytes", "2"))).reason());
        assertEquals(RejectionReason.OUTPUT_BUDGET_EXCEEDED,
            assertThrows(AnalysisException.class, () -> tokens(engine, "o😀".repeat(30), Map.of())).reason());
        assertEquals(0, engine.borrowedWorkspaceBytes());
    }

    @Test void malformedKeywordAndStreamLimitsRemainBounded() {
        DefaultEngine engine = new DefaultEngine(1024 * 1024);
        for (boolean keyword : new boolean[]{false, true}) {
            try (TokenProcessor processor = engine.newProcessor(ProfileId.UNICODE_EXPAND_V1, Limits.DEFAULT, false)) {
                char[] malformed = "\ud800".toCharArray();
                assertEquals(RejectionReason.MALFORMED_INPUT,
                    assertThrows(AnalysisException.class, () -> processor.prepare(malformed, 0, malformed.length, keyword)).reason());
            }
        }
        try (TokenProcessor processor = engine.newProcessor(ProfileId.UNICODE_EXPAND_V1,
                new Limits(Map.of("max_output_tokens_per_stream", "1")), false)) {
            assertEquals(RejectionReason.STREAM_BUDGET_EXCEEDED,
                assertThrows(AnalysisException.class, () -> processor.prepare("é".toCharArray(), 0, 1)).reason());
        }
        assertEquals(0, engine.borrowedWorkspaceBytes());
    }

    @Test void failureResetAndMemoryAdmissionReleaseState() {
        DefaultEngine engine = new DefaultEngine(1024);
        try (TokenProcessor processor = engine.newProcessor(ProfileId.UNICODE_EXPAND_V1, Limits.DEFAULT, false)) {
            assertEquals(RejectionReason.MEMORY_BUDGET_EXCEEDED,
                assertThrows(AnalysisException.class, () -> processor.prepare("é".toCharArray(), 0, 1)).reason());
            assertEquals(0, engine.borrowedWorkspaceBytes());
            assertThrows(IllegalStateException.class, () -> processor.prepare("é".toCharArray(), 0, 1));
            processor.reset();
            processor.prepare("😀".toCharArray(), 0, 2);
            assertTrue(processor.increment());
            assertFalse(processor.increment());
        }
        assertEquals(0, engine.borrowedWorkspaceBytes());
        assertEquals(0, engine.budget.reservedBytes());
    }

    @Test void preserveOriginalDoesNotDuplicateIntrinsicExpansionOriginal() {
        DefaultEngine engine = new DefaultEngine(1024 * 1024);
        assertEquals(List.of("é", "é"), tokens(engine, "é", Map.of("preserve_original", "true")));
    }
}
