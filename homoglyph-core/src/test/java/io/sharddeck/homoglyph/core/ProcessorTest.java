// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcessorTest {
    private DefaultEngine engine() { return new DefaultEngine(1024 * 1024); }
    private List<String> tokens(DefaultEngine engine, String text, Map<String, String> settings) {
        Configuration c = new Configuration(settings);
        try (TokenProcessor p = engine.newProcessor(c.profile, c.limits, c.preserveOriginal)) {
            p.prepare(text.toCharArray(), 0, text.length());
            List<String> result = new ArrayList<>();
            while (p.increment()) result.add(new String(p.buffer(), p.offset(), p.length()));
            return result;
        }
    }
    @Test void defaultProfileAndNormalization() {
        DefaultEngine engine = engine();
        assertEquals(ProfileId.UNICODE_SEARCH_V1, new Configuration(Map.of()).profile);
        for (String text : List.of("tℯst😀", "heｌｌО", "microsoft", "日本語", "مرحبا", "नमस्ते")) {
            assertEquals(List.of(reference(text)), tokens(engine, text, Map.of()));
            assertEquals(tokens(engine, text, Map.of("profile", "unicode_search_v1")), tokens(engine, text, Map.of()));
        }
        assertEquals(0, engine.borrowedWorkspaceBytes());
    }
    @Test void originalAndNormalizedOrdering() {
        DefaultEngine engine = engine();
        for (String text : List.of("😀héllo😀", "microsoft", "pаypаl"))
            assertEquals(List.of(text, reference(text)), tokens(engine, text, Map.of("preserve_original", "true")));
        assertEquals(List.of("test"), tokens(engine, "test", Map.of("preserve_original", "true")));
    }
    @Test void inputAndOutputLimits() {
        DefaultEngine engine = engine();
        for (int n : new int[]{7,8,30,64,4095,4096})
            assertEquals(List.of("o".repeat(n)), tokens(engine, "о".repeat(n), Map.of()));
        assertEquals(List.of("rn".repeat(256)), tokens(engine, "m".repeat(256), Map.of()));
        assertEquals(RejectionReason.OUTPUT_BUDGET_EXCEEDED, assertThrows(AnalysisException.class,
            () -> tokens(engine, "m".repeat(256), Map.of("max_output_utf8_bytes", "511"))).reason());
        assertEquals(RejectionReason.INPUT_TOO_LONG, assertThrows(AnalysisException.class,
            () -> tokens(engine, "x".repeat(4097), Map.of())).reason());
        assertEquals(List.of("x".repeat(4096)), tokens(engine, "x".repeat(4096), Map.of()));
        assertEquals(0, engine.borrowedWorkspaceBytes());
    }
    @Test void malformedInputAlwaysRejects() {
        DefaultEngine engine = engine();
        for (boolean keyword : new boolean[]{false,true}) for (String text : List.of("\ud800", "tℯst\udc00"))
            try (TokenProcessor processor = engine.newProcessor(ProfileId.UNICODE_SEARCH_V1, Limits.DEFAULT, false)) {
                assertEquals(RejectionReason.MALFORMED_INPUT, assertThrows(AnalysisException.class,
                    () -> processor.prepare(text.toCharArray(), 0, text.length(), keyword)).reason());
            }
        assertEquals(0, engine.borrowedWorkspaceBytes());
    }
    @Test void unicodeGoldenAndReferenceParity() {
        DefaultEngine engine = engine(); Map<String,String> settings = Map.of("profile","unicode_search_v1");
        String[][] pairs = {{"paypal","pаypаl"},{"microsoft","rnicrosoft"},{"hello","heｌｌo"},{"héllo","he\u0301llo"},{"ss","ß"},{"test😀","tℯst😀"}};
        for (String[] pair : pairs) assertEquals(tokens(engine,pair[0],settings),tokens(engine,pair[1],settings));
        assertEquals(List.of(), tokens(engine,"\u200b",settings));
        assertEquals(List.of(), tokens(engine,"",settings));
        assertEquals(List.of("a"), tokens(engine,"a"+"\u0300".repeat(4095),settings));
        assertEquals(List.of("\u200b"), tokens(engine,"\u200b",Map.of("profile","unicode_search_v1","preserve_original","true")));
        Random random = new Random(713831);
        for (int n = 0; n < 5000; n++) {
            StringBuilder text = new StringBuilder();
            for (int k = 0; k < 16; k++) {
                int cp = random.nextInt(0x110000);
                if (cp >= 0xd800 && cp <= 0xdfff) cp = 'a';
                text.appendCodePoint(cp);
            }
            String expected = reference(text.toString());
            assertEquals(expected.isEmpty() ? List.of() : List.of(expected), tokens(engine,text.toString(),settings));
        }
        assertEquals(0,engine.borrowedWorkspaceBytes());
    }
    private String reference(String text) {
        com.ibm.icu.text.Normalizer2 fold = com.ibm.icu.text.Normalizer2.getNFKCCasefoldInstance();
        String decomposed = com.ibm.icu.text.Normalizer2.getNFDInstance().normalize(fold.normalize(text));
        StringBuilder result = new StringBuilder(); boolean latin = false;
        for (int cp : decomposed.codePoints().toArray()) {
            int t = com.ibm.icu.lang.UCharacter.getType(cp);
            if (t != 6 && t != 7 && t != 8) latin = com.ibm.icu.lang.UScript.getScript(cp) == com.ibm.icu.lang.UScript.LATIN;
            if (!(latin && t == 6)) result.appendCodePoint(cp);
        }
        return new com.ibm.icu.text.SpoofChecker.Builder().build().getSkeleton(result);
    }
    @Test void streamLimitsCleanupAndRecovery() {
        DefaultEngine engine = engine();
        TokenProcessor p = engine.newProcessor(ProfileId.UNICODE_SEARCH_V1, new Limits(Map.of("max_output_tokens_per_stream","2")), false);
        p.prepare(new char[] {'о'},0,1); assertTrue(p.increment());
        p.reset(); p.prepare(new char[] {'x'},0,1);
        assertTrue(p.increment()); assertEquals("x",new String(p.buffer(),p.offset(),p.length())); assertFalse(p.increment());
        p.prepare(new char[] {'x'},0,1); while(p.increment()) {}
        assertEquals(RejectionReason.STREAM_BUDGET_EXCEEDED,assertThrows(AnalysisException.class,()->p.prepare(new char[]{'x'},0,1)).reason());
        assertThrows(IllegalStateException.class,()->p.prepare(new char[]{'x'},0,1));
        p.reset(); p.prepare(new char[]{'x'},0,1); assertTrue(p.increment()); p.close();
        assertEquals(0,engine.borrowedWorkspaceBytes());
        DefaultEngine tiny = new DefaultEngine(1024);
        assertEquals(RejectionReason.MEMORY_BUDGET_EXCEEDED,assertThrows(AnalysisException.class,()->tokens(tiny,"о",Map.of())).reason());
        assertEquals(List.of("x"),tokens(tiny,"x",Map.of()));
        assertEquals(0,tiny.budget.reservedBytes());
    }
    @Test void concurrentAdmissionAndAbandonment() throws Exception {
        DefaultEngine engine = new DefaultEngine(128 * 1024);
        ExecutorService workers = Executors.newFixedThreadPool(32);
        try {
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int t = 0; t < 128; t++) jobs.add(()-> {
                try (TokenProcessor p = engine.newProcessor(ProfileId.UNICODE_SEARCH_V1,Limits.DEFAULT,false)) {
                    char[] input = ("о".repeat(7)+"x".repeat(32)).toCharArray();
                    for (int n = 0; n < 300; n++) {
                        p.reset();
                        try { p.prepare(input,0,input.length); p.increment(); }
                        catch (AnalysisException ex) { assertEquals(RejectionReason.MEMORY_BUDGET_EXCEEDED,ex.reason()); }
                    }
                }
                return null;
            });
            for (Future<Void> result : workers.invokeAll(jobs)) result.get();
        } finally { workers.shutdownNow(); }
        assertEquals(0,engine.borrowedWorkspaceBytes());
        assertTrue(engine.budget.reservedBytes() <= 128 * 1024);
        assertTrue(engine.budget.highWaterBytes() <= 128 * 1024);
        assertEquals(List.of("o"),tokens(engine,"о",Map.of()));
    }
    @Test void settingsCannotWeakenLimits() {
        for (Map<String,String> settings : List.of(Map.of("max_output_tokens_per_stream","65537"), Map.of("max_input_utf16_units","0"), Map.of("max_output_utf8_bytes","2147483648"), Map.of("unknown","x"),Map.of("profile","invalid")))
            assertThrows(IllegalArgumentException.class,()->new Configuration(settings));
    }
    @Test void byteAndStreamBoundariesAreCheckedBeforeEmission() {
        DefaultEngine engine = engine();
        for (int limit : new int[]{2,3,4}) {
            Map<String,String> settings = Map.of("max_output_bytes_per_input", Integer.toString(limit), "preserve_original", "true");
            if (limit < 3) assertEquals(RejectionReason.OUTPUT_BUDGET_EXCEEDED,
                assertThrows(AnalysisException.class,()->tokens(engine,"о",settings)).reason());
            else assertEquals(2,tokens(engine,"о",settings).size());
        }
        for (int limit : new int[]{3,4,5}) {
            Map<String,String> settings = Map.of("max_output_utf8_bytes",Integer.toString(limit));
            if (limit < 4) assertThrows(AnalysisException.class,()->tokens(engine,"😀",settings));
            else assertEquals(List.of("😀"),tokens(engine,"😀",settings));
        }
        for (String setting : List.of("max_input_tokens_per_stream","max_output_tokens_per_stream",
                "max_input_utf16_units_per_stream","max_output_utf8_bytes_per_stream")) {
            try (TokenProcessor p=engine.newProcessor(ProfileId.UNICODE_SEARCH_V1,new Limits(Map.of(setting,"2")),false)) {
                for(int i=0;i<2;i++) { p.prepare(new char[]{'x'},0,1); assertTrue(p.increment()); assertFalse(p.increment()); }
                assertEquals(RejectionReason.STREAM_BUDGET_EXCEEDED,
                    assertThrows(AnalysisException.class,()->p.prepare(new char[]{'x'},0,1)).reason());
            }
        }
        assertEquals(RejectionReason.MEMORY_BUDGET_EXCEEDED,assertThrows(AnalysisException.class,
            ()->tokens(engine,"\uFDFA".repeat(4096),Map.of("profile","unicode_search_v1"))).reason());
        assertEquals(0,engine.borrowedWorkspaceBytes());
    }
    @Test void admissionSaturationAndUnicodeRejectionReleaseReservations() {
        DefaultEngine engine = new DefaultEngine(UnicodeData.INSTANCE.reservation(new char[]{'о'},0,1) + 1088);
        try (TokenProcessor held=engine.newProcessor(ProfileId.UNICODE_SEARCH_V1,Limits.DEFAULT,false)) {
            held.prepare(new char[]{'о'},0,1);
            assertEquals(1088,engine.borrowedWorkspaceBytes());
            assertEquals(RejectionReason.MEMORY_BUDGET_EXCEEDED,
                assertThrows(AnalysisException.class,()->tokens(engine,"о",Map.of())).reason());
            assertEquals(List.of("x"),tokens(engine,"x",Map.of()));
        }
        assertEquals(List.of("o"),tokens(engine,"о",Map.of()));
        DefaultEngine unicodeEngine=engine();
        assertThrows(AnalysisException.class,()->tokens(unicodeEngine,"héllo",
            Map.of("profile","unicode_search_v1","max_output_utf8_bytes","1")));
        assertEquals(0,unicodeEngine.budget.reservedBytes());
        BoundedMemoryBudget budget=new BoundedMemoryBudget(Long.MAX_VALUE);
        MemoryBudget.Reservation reservation=budget.tryReserve(Long.MAX_VALUE);
        assertNull(budget.tryReserve(1)); reservation.close(); reservation.close();
        assertEquals(0,budget.reservedBytes());
    }
    @Test void idleCapacityCannotStarveUnicodeAdmission() {
        DefaultEngine engine=new DefaultEngine(100000);
        WorkspacePool.Workspace workspace = engine.pool.borrow(4096,16384);
        engine.pool.release(workspace);
        assertEquals(41984,engine.budget.reservedBytes());
        assertEquals(List.of("hello"),tokens(engine,"héllo",Map.of("profile","unicode_search_v1")));
        assertEquals(0,engine.borrowedWorkspaceBytes());
        assertTrue(engine.budget.reservedBytes()<41984);
    }
}
