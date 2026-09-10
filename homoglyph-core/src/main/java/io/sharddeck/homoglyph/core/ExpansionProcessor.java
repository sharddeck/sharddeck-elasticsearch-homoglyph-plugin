// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

import java.util.Arrays;

/** Iterative bounded substitutions over a deterministic segmentation of the original token. */
final class ExpansionProcessor implements TokenProcessor {
    private final DefaultEngine engine;
    private final Limits limits;
    private final ExpansionData data = ExpansionData.INSTANCE;
    // Fixed-size planning and enumeration state; no arrays scale with the output count.
    private final long[] counts = new long[5], bytes = new long[5];
    private final int[] maxima = new int[5], positions = new int[4], ordinals = new int[4];
    private WorkspacePool.Workspace workspace;
    private char[] source, output;
    private int sourceOffset, inputLength, outputOffset, outputLength, originalBytes;
    private int planned, emitted, changed, maxChanges;
    private long inputTokens, inputUnits, outputTokens, outputBytes, metricTokens, metricBytes;
    private boolean failed;

    ExpansionProcessor(DefaultEngine engine, Limits limits) { this.engine = engine; this.limits = limits; }

    @Override public void prepare(char[] input, int offset, int length, boolean keyword) {
        if (failed) throw new IllegalStateException("reset required after analysis failure");
        finishToken();
        if (offset < 0 || length < 0 || offset > input.length - length) throw new IndexOutOfBoundsException();
        try {
            if (length > limits.maxInputUtf16Units()) throw engine.failure(RejectionReason.INPUT_TOO_LONG);
            if (inputTokens >= limits.maxInputTokensPerStream() || length > limits.maxInputUtf16UnitsPerStream() - inputUnits)
                throw engine.failure(RejectionReason.STREAM_BUDGET_EXCEEDED);
            inputTokens++; inputUnits += length;
            originalBytes = Utf8.length(input, offset, length);
            source = input; sourceOffset = offset; inputLength = length;
            if (keyword) { checkPlan(1, originalBytes, originalBytes); planned = 1; return; }
            if (length == 0) return;
            Arrays.fill(counts, 0); Arrays.fill(bytes, 0); Arrays.fill(maxima, -1);
            counts[0] = 1; maxima[0] = 0;
            int slots = 0, end = offset + length;
            for (int cursor = offset; cursor < end;) {
                int variant = data.match(input, cursor, end);
                int width, originalSize, alternatives = 0, alternativeBytes = 0, maximum = 0;
                if (variant >= 0) {
                    slots++; width = data.length(variant); originalSize = data.bytes(variant);
                    alternatives = data.alternatives(variant); alternativeBytes = data.alternativeBytes(variant);
                    maximum = data.maximumAlternativeBytes(variant);
                } else {
                    int cp = Character.codePointAt(input, cursor, end);
                    width = Character.charCount(cp); originalSize = Utf8.width(cp);
                }
                long total = 0;
                // Coefficients of the bounded substitution-count polynomial, updated in place.
                for (int k = limits.maxChangedPositions(); k >= 0; k--) {
                    long previous = k == 0 ? 0 : counts[k - 1];
                    long nextCount = counts[k] + previous * alternatives;
                    if (nextCount > limits.maxOutputTokensPerInput()) throw engine.failure(RejectionReason.OUTPUT_BUDGET_EXCEEDED);
                    long nextBytes = bytes[k] + counts[k] * originalSize;
                    int nextMaximum = counts[k] == 0 ? -1 : maxima[k] + originalSize;
                    if (previous != 0 && alternatives != 0) {
                        nextBytes += bytes[k - 1] * alternatives + previous * alternativeBytes;
                        nextMaximum = Math.max(nextMaximum, maxima[k - 1] + maximum);
                    }
                    counts[k] = nextCount; bytes[k] = nextBytes; maxima[k] = nextMaximum; total += nextCount;
                }
                if (total > limits.maxOutputTokensPerInput()) throw engine.failure(RejectionReason.OUTPUT_BUDGET_EXCEEDED);
                cursor += width;
            }
            int count = 0, maximum = 0; long totalBytes = 0;
            for (int k = 0; k <= limits.maxChangedPositions(); k++) {
                count += (int)counts[k]; totalBytes += bytes[k]; maximum = Math.max(maximum, maxima[k]);
            }
            checkPlan(count, maximum, totalBytes);
            planned = count;
            if (count == 1) return;
            workspace = engine.pool.borrow(length, maximum, slots);
            System.arraycopy(input, offset, workspace.original, 0, length); workspace.inputLength = length;
            for (int cursor = 0; cursor < length;) {
                int variant = data.match(workspace.original, cursor, length);
                if (variant >= 0) {
                    workspace.inputOffsets[workspace.slots] = cursor; workspace.variantIds[workspace.slots++] = variant;
                    cursor += data.length(variant);
                } else cursor += Character.charCount(Character.codePointAt(workspace.original, cursor, length));
            }
            changed = 1; maxChanges = Math.min(limits.maxChangedPositions(), slots);
            Arrays.fill(positions, 0); Arrays.fill(ordinals, 0);
        } catch (RuntimeException e) { fail(e); throw e; }
    }
    private void checkPlan(int count, int maximum, long bytes) {
        if (count > limits.maxOutputTokensPerInput() || maximum > limits.maxOutputUtf8Bytes() || bytes > limits.maxOutputBytesPerInput())
            throw engine.failure(RejectionReason.OUTPUT_BUDGET_EXCEEDED);
        if (count > limits.maxOutputTokensPerStream() - outputTokens || bytes > limits.maxOutputUtf8BytesPerStream() - outputBytes)
            throw engine.failure(RejectionReason.STREAM_BUDGET_EXCEEDED);
    }
    @Override public boolean increment() {
        if (failed) throw new IllegalStateException("reset required after analysis failure");
        if (!hasPending()) { finishToken(); return false; }
        try {
            int size = originalBytes;
            if (emitted == 0) {
                output = workspace == null ? source : workspace.original;
                outputOffset = workspace == null ? sourceOffset : 0; outputLength = inputLength;
            } else {
                int cursor = 0, length = 0;
                for (int k = 0; k < changed; k++) {
                    int slot = positions[k], start = workspace.inputOffsets[slot], variant = workspace.variantIds[slot];
                    System.arraycopy(workspace.original, cursor, workspace.output, length, start - cursor); length += start - cursor;
                    int alternative = data.alternative(variant, ordinals[k]);
                    length = data.append(alternative, workspace.output, length);
                    size += data.bytes(alternative) - data.bytes(variant); cursor = start + data.length(variant);
                }
                System.arraycopy(workspace.original, cursor, workspace.output, length, inputLength - cursor); length += inputLength - cursor;
                workspace.outputLength = Math.max(workspace.outputLength, length);
                output = workspace.output; outputOffset = 0; outputLength = length;
            }
            emitted++; outputTokens++; outputBytes += size;
            if (workspace != null) { metricTokens++; metricBytes += size; }
            if (emitted > 1 && emitted < planned) advance();
            return true;
        } catch (RuntimeException e) { fail(e); throw e; }
    }
    private void advance() {
        for (int k = changed - 1; k >= 0; k--) {
            if (++ordinals[k] < data.alternatives(workspace.variantIds[positions[k]])) return;
            ordinals[k] = 0;
        }
        for (int k = changed - 1; k >= 0; k--) if (positions[k] < workspace.slots - changed + k) {
            positions[k]++;
            for (int j = k + 1; j < changed; j++) positions[j] = positions[j - 1] + 1;
            return;
        }
        if (++changed > maxChanges) throw new IllegalStateException("expansion plan exhausted early");
        for (int k = 0; k < changed; k++) positions[k] = k;
    }
    private void fail(RuntimeException error) {
        if (error instanceof AnalysisException) engine.metrics.rejected(((AnalysisException)error).reason());
        finishToken(); failed = true;
    }
    @Override public boolean hasPending() { return emitted < planned; }
    @Override public int plannedOutputs() { return planned; }
    @Override public char[] buffer() { return output; }
    @Override public int offset() { return outputOffset; }
    @Override public int length() { return outputLength; }
    @Override public void finishToken() {
        if (workspace != null) { engine.pool.release(workspace); workspace = null; }
        if (metricTokens != 0) engine.metrics.emitted(metricTokens, metricBytes);
        metricTokens = metricBytes = 0;
        source = output = null; sourceOffset = inputLength = outputOffset = outputLength = originalBytes = 0;
        planned = emitted = changed = maxChanges = 0;
    }
    @Override public void reset() { finishToken(); inputTokens = inputUnits = outputTokens = outputBytes = 0; failed = false; }
    @Override public void close() { reset(); }
}
