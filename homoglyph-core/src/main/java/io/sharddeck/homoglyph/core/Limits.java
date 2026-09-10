// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

import java.util.Map;

/** Validated immutable settings. No setting can disable or raise the hard ceilings. */
public final class Limits implements AnalysisLimits {
    public static final Limits DEFAULT = new Limits(Map.of());
    private final int perInputTokens, changes, input, output, perInput, payload, type, inputTokens, inputUnits, outputTokens, outputBytes;
    public Limits(Map<String, String> settings) {
        perInputTokens = read(settings, "max_output_tokens_per_input", 1024);
        changes = read(settings, "max_changed_positions", 1, 4);
        input = read(settings, "max_input_utf16_units", 4096);
        output = read(settings, "max_output_utf8_bytes", 16384);
        perInput = read(settings, "max_output_bytes_per_input", 65536);
        payload = read(settings, "max_payload_bytes", 4096);
        type = read(settings, "max_type_utf16_units", 256);
        inputTokens = read(settings, "max_input_tokens_per_stream", 65536);
        inputUnits = read(settings, "max_input_utf16_units_per_stream", 8388608);
        outputTokens = read(settings, "max_output_tokens_per_stream", 65536);
        outputBytes = read(settings, "max_output_utf8_bytes_per_stream", 8388608);
    }
    private static int read(Map<String, String> settings, String key, int ceiling) {
        return read(settings, key, ceiling, ceiling);
    }
    private static int read(Map<String, String> settings, String key, int defaultValue, int ceiling) {
        String raw = settings.get(key);
        int value;
        try { value = raw == null ? defaultValue : Integer.parseInt(raw); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("homoglyph: invalid " + key); }
        if (value < 1 || value > ceiling) throw new IllegalArgumentException("homoglyph: " + key + " must be between 1 and " + ceiling);
        return value;
    }
    public int maxOutputTokensPerInput() { return perInputTokens; }
    public int maxChangedPositions() { return changes; }
    public int maxInputUtf16Units() { return input; }
    public int maxOutputUtf8Bytes() { return output; }
    public long maxOutputBytesPerInput() { return perInput; }
    public int maxPayloadBytes() { return payload; }
    public int maxTypeUtf16Units() { return type; }
    public long maxInputTokensPerStream() { return inputTokens; }
    public long maxInputUtf16UnitsPerStream() { return inputUnits; }
    public long maxOutputTokensPerStream() { return outputTokens; }
    public long maxOutputUtf8BytesPerStream() { return outputBytes; }
}
