// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.adapter.esstable9;
import org.elasticsearch.plugin.settings.*;
@AnalysisSettings
public interface HomoglyphSettings {
    @StringSetting(path = "profile", defaultValue = "unicode_search_v1") String profile();
    @BooleanSetting(path = "preserve_original", defaultValue = false) boolean preserveOriginal();
    @IntSetting(path = "max_output_tokens_per_input", defaultValue = 1024) int maxOutputTokensPerInput();
    @IntSetting(path = "max_changed_positions", defaultValue = 1) int maxChangedPositions();
    @IntSetting(path = "max_input_utf16_units", defaultValue = 4096) int maxInputUtf16Units();
    @IntSetting(path = "max_output_utf8_bytes", defaultValue = 16384) int maxOutputUtf8Bytes();
    @IntSetting(path = "max_output_bytes_per_input", defaultValue = 65536) int maxOutputBytesPerInput();
    @IntSetting(path = "max_payload_bytes", defaultValue = 4096) int maxPayloadBytes();
    @IntSetting(path = "max_type_utf16_units", defaultValue = 256) int maxTypeUtf16Units();
    @IntSetting(path = "max_input_tokens_per_stream", defaultValue = 65536) int maxInputTokensPerStream();
    @IntSetting(path = "max_input_utf16_units_per_stream", defaultValue = 8388608) int maxInputUtf16UnitsPerStream();
    @IntSetting(path = "max_output_tokens_per_stream", defaultValue = 65536) int maxOutputTokensPerStream();
    @IntSetting(path = "max_output_utf8_bytes_per_stream", defaultValue = 8388608) int maxOutputUtf8BytesPerStream();
}
