// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.lucene;

import io.sharddeck.homoglyph.core.*;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.*;
import org.apache.lucene.util.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.io.IOException;
import org.apache.lucene.search.BoostAttribute;
import static org.junit.jupiter.api.Assertions.*;

class FilterTest {
    static final class Source extends TokenStream {
        String[] values; int cursor;
        final CharTermAttribute term = addAttribute(CharTermAttribute.class);
        final PositionIncrementAttribute position = addAttribute(PositionIncrementAttribute.class);
        final OffsetAttribute offset = addAttribute(OffsetAttribute.class);
        final TypeAttribute type = addAttribute(TypeAttribute.class);
        final PayloadAttribute payload = addAttribute(PayloadAttribute.class);
        boolean marked, failRead, failEnd, failReset, failClose; int payloadSize = 3;
        String tokenType = "test";
        int savedFlags, savedFrequency = 1, savedPositionLength = 1;
        Source(String... values) { this.values=values; }
        @Override public void reset() throws IOException { if(failReset) throw new IOException("injected reset"); cursor=0; }
        @Override public void end() throws IOException { super.end(); if(failEnd) throw new IOException("injected end"); }
        @Override public void close() throws IOException { if(failClose) throw new IOException("injected close"); }
        @Override public boolean incrementToken() throws IOException {
            if(failRead) throw new IOException("injected read");
            if (cursor == values.length) return false;
            clearAttributes(); term.append(values[cursor++]); position.setPositionIncrement(1); offset.setOffset(3,7); type.setType(tokenType);
            addAttribute(FlagsAttribute.class).setFlags(savedFlags);
            addAttribute(TermFrequencyAttribute.class).setTermFrequency(savedFrequency);
            addAttribute(PositionLengthAttribute.class).setPositionLength(savedPositionLength);
            payload.setPayload(new BytesRef(new byte[payloadSize]));
            addAttribute(KeywordAttribute.class).setKeyword(marked);
            return true;
        }
    }
    @Test void attributesSurviveConsumerMutation() throws Exception {
        Source source = new Source("о");
        DefaultEngine engine = new DefaultEngine(128*1024);
        try (HomoglyphTokenFilter filter = new HomoglyphTokenFilter(source,new Configuration(Map.of("preserve_original","true")),engine)) {
            filter.reset(); assertTrue(filter.incrementToken());
            assertEquals("о",filter.getAttribute(CharTermAttribute.class).toString());
            assertEquals(1,filter.getAttribute(PositionIncrementAttribute.class).getPositionIncrement());
            filter.getAttribute(PayloadAttribute.class).getPayload().bytes[0] = 99;
            filter.getAttribute(TypeAttribute.class).setType("mutated");
            filter.getAttribute(OffsetAttribute.class).setOffset(55,66);
            filter.getAttribute(BoostAttribute.class).setBoost(3.0f);
            assertTrue(filter.incrementToken());
            assertEquals("o",filter.getAttribute(CharTermAttribute.class).toString());
            assertEquals(0,filter.getAttribute(PositionIncrementAttribute.class).getPositionIncrement());
            assertEquals(0,filter.getAttribute(PayloadAttribute.class).getPayload().bytes[0]);
            assertEquals("test",filter.getAttribute(TypeAttribute.class).type());
            assertEquals(3,filter.getAttribute(OffsetAttribute.class).startOffset());
            assertEquals(1.0f,filter.getAttribute(BoostAttribute.class).getBoost());
            assertFalse(filter.incrementToken()); filter.end();
        }
        assertEquals(0,engine.borrowedWorkspaceBytes());
    }
    @Test void abandonmentKeywordAndReuse() throws Exception {
        Source source = new Source("о");
        try (HomoglyphTokenFilter filter = new HomoglyphTokenFilter(source,new Configuration(Map.of("preserve_original","true")))) {
            filter.reset(); assertTrue(filter.incrementToken()); filter.close();
            source.values=new String[]{"x"}; filter.reset(); assertTrue(filter.incrementToken());
            assertEquals("x",filter.getAttribute(CharTermAttribute.class).toString()); assertFalse(filter.incrementToken());
            source.values=new String[]{"о"}; source.marked=true; filter.reset(); assertTrue(filter.incrementToken());
            assertEquals("о",filter.getAttribute(CharTermAttribute.class).toString()); assertFalse(filter.incrementToken());
        }
    }
    @Test void removedTermsCarryPositions() throws Exception {
        Source source = new Source("\u200b","x");
        try (HomoglyphTokenFilter filter = new HomoglyphTokenFilter(source,new Configuration(Map.of("profile","unicode_search_v1")))) {
            filter.reset(); assertTrue(filter.incrementToken());
            assertEquals("x",filter.getAttribute(CharTermAttribute.class).toString());
            assertEquals(2,filter.getAttribute(PositionIncrementAttribute.class).getPositionIncrement());
            assertFalse(filter.incrementToken()); filter.end();
        }
    }
    public interface UnknownAttribute extends Attribute {}
    public static final class UnknownImpl extends AttributeImpl implements UnknownAttribute {
        @Override public void clear() {}
        @Override public void reflectWith(AttributeReflector reflector) {}
        @Override public void copyTo(AttributeImpl target) { throw new AssertionError("arbitrary attributes must not be cloned"); }
    }
    @Test void rejectUnboundedSnapshots() throws Exception {
        for (boolean unknown : new boolean[]{true,false}) {
            Source source = new Source("о");
            if (unknown) source.addAttributeImpl(new UnknownImpl()); else source.payloadSize=4097;
            DefaultEngine engine = new DefaultEngine(128*1024);
            try (HomoglyphTokenFilter filter = new HomoglyphTokenFilter(source,new Configuration(Map.of("preserve_original","true")),engine)) {
                filter.reset();
                assertEquals(unknown ? RejectionReason.UNSUPPORTED_ATTRIBUTE : RejectionReason.ATTRIBUTE_BUDGET_EXCEEDED,
                    assertThrows(AnalysisException.class,filter::incrementToken).reason());
            }
            assertEquals(0,engine.borrowedWorkspaceBytes());
        }
    }
    @Test void snapshotAdmissionFailureDoesNotLeakCoreWorkspace() throws Exception {
        DefaultEngine engine = new DefaultEngine(67000);
        try (HomoglyphTokenFilter filter=new HomoglyphTokenFilter(new Source("о"),new Configuration(Map.of("preserve_original","true")),engine)) {
            filter.reset();
            assertEquals(RejectionReason.MEMORY_BUDGET_EXCEEDED,assertThrows(AnalysisException.class,filter::incrementToken).reason());
            assertEquals(0,engine.borrowedWorkspaceBytes());
            assertEquals(1088,engine.budget.reservedBytes()); // Only idle pool capacity remains charged.
        }
    }
    @Test void sourceFailuresCleanUpAndRequireReset() throws Exception {
        for(String stage:List.of("read","end","reset","close")) {
            Source source=new Source("о"); DefaultEngine engine=new DefaultEngine(128*1024);
            HomoglyphTokenFilter filter=new HomoglyphTokenFilter(source,new Configuration(Map.of("preserve_original","true")),engine);
            filter.reset(); assertTrue(filter.incrementToken());
            switch(stage) {
                case "read":
                    assertTrue(filter.incrementToken()); source.failRead=true;
                    assertThrows(IOException.class,filter::incrementToken);
                    assertThrows(IllegalStateException.class,filter::incrementToken); break;
                case "end": source.failEnd=true; assertThrows(IOException.class,filter::end); break;
                case "reset": source.failReset=true; assertThrows(IOException.class,filter::reset); break;
                case "close": source.failClose=true; assertThrows(IOException.class,filter::close); break;
                default: throw new AssertionError(stage);
            }
            assertEquals(0,engine.borrowedWorkspaceBytes());
            assertEquals(1088,engine.budget.reservedBytes());
            source.failRead=source.failEnd=source.failReset=source.failClose=false;
            filter.reset(); assertTrue(filter.incrementToken()); filter.close();
        }
    }
    @Test void snapshotSizeBoundariesAndAllScalarAttributes() throws Exception {
        for(int delta:new int[]{-1,0,1}) for(boolean payloadBoundary:new boolean[]{false,true}) {
            Source source=new Source("о");
            source.payloadSize=payloadBoundary?4096+delta:3;
            source.tokenType="t".repeat(payloadBoundary?4:256+delta);
            source.savedFlags=17; source.savedFrequency=3; source.savedPositionLength=2;
            DefaultEngine engine=new DefaultEngine(128*1024);
            try(HomoglyphTokenFilter filter=new HomoglyphTokenFilter(source,new Configuration(Map.of("preserve_original","true")),engine)) {
                filter.reset();
                if(delta>0) assertEquals(RejectionReason.ATTRIBUTE_BUDGET_EXCEEDED,assertThrows(AnalysisException.class,filter::incrementToken).reason());
                else {
                    assertTrue(filter.incrementToken());
                    filter.getAttribute(FlagsAttribute.class).setFlags(99);
                    filter.getAttribute(TermFrequencyAttribute.class).setTermFrequency(9);
                    filter.getAttribute(PositionLengthAttribute.class).setPositionLength(9);
                    assertTrue(filter.incrementToken());
                    assertEquals(17,filter.getAttribute(FlagsAttribute.class).getFlags());
                    assertEquals(3,filter.getAttribute(TermFrequencyAttribute.class).getTermFrequency());
                    assertEquals(2,filter.getAttribute(PositionLengthAttribute.class).getPositionLength());
                    assertEquals(source.payloadSize,filter.getAttribute(PayloadAttribute.class).getPayload().length);
                }
            }
            assertEquals(0,engine.borrowedWorkspaceBytes());
            assertEquals(1088,engine.budget.reservedBytes());
        }
        Source unchanged=new Source("x"); unchanged.addAttributeImpl(new UnknownImpl());
        try(HomoglyphTokenFilter filter=new HomoglyphTokenFilter(unchanged,new Configuration(Map.of("preserve_original","true")))) {
            filter.reset(); assertTrue(filter.incrementToken()); assertFalse(filter.incrementToken());
        }
    }
    @Test void expansionPreservesAttributesAcrossManyOutputsAndAbandonment() throws Exception {
        Source source = new Source("m"); DefaultEngine engine = new DefaultEngine(128 * 1024);
        Configuration config = new Configuration(Map.of("profile", "unicode_expand_v1"));
        try (HomoglyphTokenFilter filter = new HomoglyphTokenFilter(source, config, engine)) {
            filter.reset(); java.util.Set<String> values = new java.util.HashSet<>(); int count = 0;
            while (filter.incrementToken()) {
                assertEquals(count == 0 ? 1 : 0, filter.getAttribute(PositionIncrementAttribute.class).getPositionIncrement());
                assertEquals(3, filter.getAttribute(OffsetAttribute.class).startOffset());
                assertEquals(7, filter.getAttribute(OffsetAttribute.class).endOffset());
                assertEquals("test", filter.getAttribute(TypeAttribute.class).type());
                assertEquals(0, filter.getAttribute(PayloadAttribute.class).getPayload().bytes[0]);
                assertEquals(1.0f, filter.getAttribute(BoostAttribute.class).getBoost());
                values.add(filter.getAttribute(CharTermAttribute.class).toString()); count++;
                filter.getAttribute(CharTermAttribute.class).setEmpty().append("corrupted");
                filter.getAttribute(PayloadAttribute.class).getPayload().bytes[0] = 99;
                filter.getAttribute(TypeAttribute.class).setType("corrupted");
                filter.getAttribute(OffsetAttribute.class).setOffset(20, 30);
                filter.getAttribute(BoostAttribute.class).setBoost(4.0f);
            }
            assertEquals(18, count); assertEquals(count, values.size()); assertTrue(values.containsAll(java.util.List.of("m", "rn", "𝐦")));
            filter.end(); assertEquals(0, engine.borrowedWorkspaceBytes());
            filter.reset(); assertTrue(filter.incrementToken()); filter.close();
            assertEquals(0, engine.borrowedWorkspaceBytes());
            source.values = new String[]{"é"}; filter.reset(); assertTrue(filter.incrementToken()); assertTrue(filter.incrementToken()); assertFalse(filter.incrementToken());
        }
    }
    @Test void expansionRejectsWholePlanAndSnapshotBeforeFirstToken() throws Exception {
        for (boolean snapshot : new boolean[]{false,true}) {
            Source source = new Source(snapshot ? "é" : "o😀".repeat(30));
            if (snapshot) source.payloadSize = 4097;
            DefaultEngine engine = new DefaultEngine(128 * 1024);
            try (HomoglyphTokenFilter filter = new HomoglyphTokenFilter(source, new Configuration(Map.of("profile", "unicode_expand_v1")), engine)) {
                filter.reset();
                assertEquals(snapshot ? RejectionReason.ATTRIBUTE_BUDGET_EXCEEDED : RejectionReason.OUTPUT_BUDGET_EXCEEDED,
                    assertThrows(AnalysisException.class, filter::incrementToken).reason());
                assertEquals(0, engine.borrowedWorkspaceBytes());
                source.values = new String[]{"é"}; source.payloadSize = 3;
                filter.reset(); assertTrue(filter.incrementToken()); assertTrue(filter.incrementToken()); assertFalse(filter.incrementToken());
            }
        }
    }
}
