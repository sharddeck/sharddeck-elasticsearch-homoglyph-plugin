// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.lucene;

import io.sharddeck.homoglyph.core.*;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.*;
import org.apache.lucene.util.Attribute;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.search.BoostAttribute;

/** Bounded lifecycle-safe integration, compiled separately for each Lucene ABI. */
public final class HomoglyphTokenFilter extends TokenFilter {
    private static final Set<Class<? extends Attribute>> SUPPORTED = Set.of(
        CharTermAttribute.class, TermToBytesRefAttribute.class, PositionIncrementAttribute.class,
        PositionLengthAttribute.class, OffsetAttribute.class, TypeAttribute.class, FlagsAttribute.class,
        PayloadAttribute.class, KeywordAttribute.class, TermFrequencyAttribute.class, BoostAttribute.class);
    private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
    private final PositionIncrementAttribute position = addAttribute(PositionIncrementAttribute.class);
    private final PositionLengthAttribute positionLength = addAttribute(PositionLengthAttribute.class);
    private final OffsetAttribute offsets = addAttribute(OffsetAttribute.class);
    private final TypeAttribute type = addAttribute(TypeAttribute.class);
    private final FlagsAttribute flags = addAttribute(FlagsAttribute.class);
    private final PayloadAttribute payload = addAttribute(PayloadAttribute.class);
    private final KeywordAttribute keyword = addAttribute(KeywordAttribute.class);
    private final TermFrequencyAttribute frequency = addAttribute(TermFrequencyAttribute.class);
    private final BoostAttribute boost = addAttribute(BoostAttribute.class);
    private final DefaultEngine engine;
    private final TokenProcessor processor;
    private final Limits limits;
    private Snapshot snapshot;
    private MemoryBudget.Reservation snapshotReservation;
    private int skipped;
    private boolean first, failed;

    public HomoglyphTokenFilter(TokenStream input, Configuration configuration) {
        this(input, configuration, DefaultEngine.SHARED);
    }
    public HomoglyphTokenFilter(TokenStream input, Configuration configuration, DefaultEngine engine) {
        super(input); this.engine = engine; limits = configuration.limits;
        processor = engine.newProcessor(configuration.profile, limits, configuration.preserveOriginal);
    }
    @Override public boolean incrementToken() throws IOException {
        if (failed) throw new IllegalStateException("homoglyph: reset required after failure");
        try {
            while (true) {
                if (processor.hasPending()) {
                    if (processor.increment()) {
                        if (snapshot != null) snapshot.restore();
                        position.setPositionIncrement(first ? checkedPosition(position.getPositionIncrement(), skipped) : 0);
                        first = false; skipped = 0;
                        if (processor.buffer() != term.buffer() || processor.offset() != 0)
                            term.copyBuffer(processor.buffer(), processor.offset(), processor.length());
                        else term.setLength(processor.length());
                        if (!processor.hasPending()) processor.finishToken();
                        return true;
                    }
                }
                releaseSnapshot();
                if (!input.incrementToken()) { processor.finishToken(); return false; }
                processor.prepare(term.buffer(), 0, term.length(), keyword.isKeyword());
                first = true;
                if (processor.plannedOutputs() > 1) capture();
                if (!processor.hasPending()) skipped = checkedPosition(skipped, position.getPositionIncrement());
            }
        } catch (IOException | RuntimeException e) {
            failed = true; cleanup(); throw e;
        }
    }
    private int checkedPosition(int left, int right) {
        if (right < 0 || left < 0 || left > Integer.MAX_VALUE - right) throw failure(RejectionReason.STREAM_BUDGET_EXCEEDED);
        return left + right;
    }
    private AnalysisException failure(RejectionReason reason) {
        engine.metrics.rejected(reason); return new AnalysisException(reason);
    }
    private void capture() {
        Iterator<Class<? extends Attribute>> attributes = getAttributeClassesIterator();
        while (attributes.hasNext()) if (!SUPPORTED.contains(attributes.next())) throw failure(RejectionReason.UNSUPPORTED_ATTRIBUTE);
        BytesRef currentPayload = payload.getPayload(); String currentType = type.type();
        int size = currentPayload == null ? 0 : currentPayload.length;
        if (size < 0 || size > limits.maxPayloadBytes() || (currentType != null && currentType.length() > limits.maxTypeUtf16Units()))
            throw failure(RejectionReason.ATTRIBUTE_BUDGET_EXCEEDED);
        if (currentPayload != null && (currentPayload.bytes == null || currentPayload.offset < 0 || currentPayload.offset > currentPayload.bytes.length - size))
            throw failure(RejectionReason.ATTRIBUTE_BUDGET_EXCEEDED);
        snapshotReservation = engine.reserveTemporary(1024L + 2L * size + (currentType == null ? 0 : 2L * currentType.length()));
        if (snapshotReservation == null) throw failure(RejectionReason.MEMORY_BUDGET_EXCEEDED);
        snapshot = new Snapshot(currentPayload, currentType);
    }
    private final class Snapshot {
        private final int increment = position.getPositionIncrement(), posLength = positionLength.getPositionLength();
        private final int start = offsets.startOffset(), end = offsets.endOffset(), savedFlags = flags.getFlags(), termFrequency = frequency.getTermFrequency();
        private final boolean savedKeyword = keyword.isKeyword();
        private final float savedBoost = boost.getBoost();
        private final byte[] savedPayload, emissionBytes;
        private final BytesRef emissionPayload;
        private final String savedType;
        Snapshot(BytesRef source, String savedType) {
            this.savedType = savedType;
            savedPayload = source == null ? null : java.util.Arrays.copyOfRange(source.bytes, source.offset, source.offset + source.length);
            emissionBytes = source == null ? null : new byte[source.length];
            emissionPayload = source == null ? null : new BytesRef(emissionBytes);
        }
        void restore() {
            position.setPositionIncrement(increment); positionLength.setPositionLength(posLength);
            offsets.setOffset(start, end); flags.setFlags(savedFlags); frequency.setTermFrequency(termFrequency);
            keyword.setKeyword(savedKeyword);
            boost.setBoost(savedBoost);
            if (savedPayload != null) {
                System.arraycopy(savedPayload, 0, emissionBytes, 0, savedPayload.length);
                emissionPayload.bytes = emissionBytes; emissionPayload.offset = 0; emissionPayload.length = savedPayload.length;
            }
            payload.setPayload(emissionPayload); type.setType(savedType);
        }
    }
    private void releaseSnapshot() {
        snapshot = null;
        if (snapshotReservation != null) { snapshotReservation.close(); snapshotReservation = null; }
    }
    private void cleanup() { processor.close(); releaseSnapshot(); }
    @Override public void reset() throws IOException {
        cleanup(); skipped = 0; first = false; failed = true;
        super.reset(); failed = false;
    }
    @Override public void end() throws IOException {
        try { super.end(); position.setPositionIncrement(checkedPosition(position.getPositionIncrement(), skipped)); }
        finally { skipped = 0; cleanup(); }
    }
    @Override public void close() throws IOException {
        try { cleanup(); skipped = 0; }
        finally { super.close(); }
    }
}
