// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

final class Processor implements TokenProcessor {
    private final DefaultEngine engine;
    private final Limits limits;
    private final boolean preserve;
    private final UnicodeData unicode;
    private WorkspacePool.Workspace workspace;
    private MemoryBudget.Reservation unicodeReservation;
    private char[] source, output;
    private int sourceOffset, inputLength, outputOffset, outputLength;
    private int planned, originalByteLength, unicodeByteLength;
    private boolean originalPending, transformedPending, passThrough, failed;
    private long inputTokens, inputUnits, outputTokens, outputBytes, metricTokens, metricBytes;

    Processor(DefaultEngine engine, Limits limits, boolean preserve) {
        this.engine = engine; this.limits = limits; this.preserve = preserve;
        unicode = UnicodeData.INSTANCE;
    }
    @Override public void prepare(char[] input, int offset, int length, boolean keyword) {
        if (failed) throw new IllegalStateException("reset required after analysis failure");
        finishToken();
        if (offset < 0 || length < 0 || offset > input.length - length) throw new IndexOutOfBoundsException();
        try {
            if (length > limits.maxInputUtf16Units()) throw engine.failure(RejectionReason.INPUT_TOO_LONG);
            if (inputTokens >= limits.maxInputTokensPerStream() || length > limits.maxInputUtf16UnitsPerStream() - inputUnits)
                throw engine.failure(RejectionReason.STREAM_BUDGET_EXCEEDED);
            inputTokens++; inputUnits += length;
            source = input; sourceOffset = offset; inputLength = length;
            int originalBytes = Utf8.length(input, offset, length);
            originalByteLength = originalBytes;
            if (length == 0 && !keyword) return;
            if (keyword || unicode.identityAscii(input, offset, length)) { original(originalBytes); return; }
            prepareUnicode(originalBytes);
        } catch (RuntimeException e) {
            if (e instanceof AnalysisException) engine.metrics.rejected(((AnalysisException)e).reason());
            finishToken(); failed = true; throw e;
        }
    }
    private void original(int bytes) {
        checkPlan(1, bytes, bytes); passThrough = true; originalPending = true; planned = 1;
    }
    private void prepareUnicode(int originalBytes) {
        long capacity = unicode.reservation(source, sourceOffset, inputLength);
        if (capacity > 2L * 1024 * 1024) throw engine.failure(RejectionReason.MEMORY_BUDGET_EXCEEDED);
        unicodeReservation = engine.reserveTemporary(capacity);
        if (unicodeReservation == null) throw engine.failure(RejectionReason.MEMORY_BUDGET_EXCEEDED);
        String key = unicode.key(new String(source, sourceOffset, inputLength));
        boolean same = key.length() == inputLength;
        for (int i = 0; same && i < inputLength; i++) same = key.charAt(i) == source[sourceOffset + i];
        if (same) { unicodeReservation.close(); unicodeReservation = null; original(originalBytes); return; }
        char[] chars = key.toCharArray();
        int keyBytes = Utf8.length(chars, 0, chars.length);
        unicodeByteLength = keyBytes;
        int count = (chars.length == 0 ? 0 : 1) + (preserve ? 1 : 0);
        checkPlan(count, Math.max(keyBytes, preserve ? originalBytes : 0), keyBytes + (preserve ? originalBytes : 0L));
        if (count == 0) { unicodeReservation.close(); unicodeReservation = null; return; }
        if (chars.length == 0) { unicodeReservation.close(); unicodeReservation = null; original(originalBytes); return; }
        workspace = engine.pool.borrow(inputLength, chars.length);
        System.arraycopy(source, sourceOffset, workspace.original, 0, inputLength); workspace.inputLength = inputLength;
        System.arraycopy(chars, 0, workspace.output, 0, chars.length); workspace.outputLength = chars.length;
        transformedPending = true; planned = count; originalPending = preserve;
    }
    private void checkPlan(int count, int maximum, long bytes) {
        if (count > limits.maxOutputTokensPerInput() || maximum > limits.maxOutputUtf8Bytes() || bytes > limits.maxOutputBytesPerInput()) throw engine.failure(RejectionReason.OUTPUT_BUDGET_EXCEEDED);
        if (count > limits.maxOutputTokensPerStream() - outputTokens || bytes > limits.maxOutputUtf8BytesPerStream() - outputBytes)
            throw engine.failure(RejectionReason.STREAM_BUDGET_EXCEEDED);
    }
    @Override public boolean increment() {
        if (failed) throw new IllegalStateException("reset required after analysis failure");
        if (originalPending) {
            originalPending = false;
            output = workspace == null ? source : workspace.original;
            outputOffset = workspace == null ? sourceOffset : 0; outputLength = inputLength;
            recordOutput(originalByteLength); return true;
        }
        if (transformedPending) {
            transformedPending = false;
            output = workspace.output; outputOffset = 0; outputLength = workspace.outputLength;
            recordOutput(unicodeByteLength); return true;
        }
        finishToken(); return false;
    }
    private void recordOutput(int bytes) {
        outputTokens++; outputBytes += bytes;
        if (!passThrough) { metricTokens++; metricBytes += bytes; }
    }
    @Override public boolean hasPending() { return originalPending || transformedPending; }
    @Override public int plannedOutputs() { return planned; }
    @Override public char[] buffer() { return output; }
    @Override public int offset() { return outputOffset; }
    @Override public int length() { return outputLength; }
    @Override public void finishToken() {
        output = null; source = null;
        if (workspace != null) { engine.pool.release(workspace); workspace = null; }
        if (unicodeReservation != null) { unicodeReservation.close(); unicodeReservation = null; }
        if (metricTokens != 0) engine.metrics.emitted(metricTokens, metricBytes);
        metricTokens = metricBytes = 0; planned = 0;
        originalByteLength = unicodeByteLength = 0;
        originalPending = transformedPending = passThrough = false; outputLength = outputOffset = inputLength = sourceOffset = 0;
    }
    @Override public void reset() { finishToken(); inputTokens = inputUnits = outputTokens = outputBytes = 0; failed = false; }
    @Override public void close() { reset(); }
}
