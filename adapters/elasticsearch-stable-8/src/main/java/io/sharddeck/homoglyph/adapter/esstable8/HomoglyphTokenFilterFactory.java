// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.adapter.esstable8;
import java.util.HashMap;
import java.util.Map;
import io.sharddeck.homoglyph.core.Configuration;
import io.sharddeck.homoglyph.lucene.HomoglyphTokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.elasticsearch.plugin.Inject;
import org.elasticsearch.plugin.NamedComponent;
import org.elasticsearch.plugin.analysis.TokenFilterFactory;

@NamedComponent("homoglyph")
public final class HomoglyphTokenFilterFactory implements TokenFilterFactory {
    private final Configuration configuration;
    @Inject public HomoglyphTokenFilterFactory(HomoglyphSettings settings) {
        Map<String, String> values = new HashMap<>();
        values.put("profile", settings.profile());
        values.put("max_output_tokens_per_input", Integer.toString(settings.maxOutputTokensPerInput()));
        values.put("max_changed_positions", Integer.toString(settings.maxChangedPositions()));
        values.put("preserve_original", Boolean.toString(settings.preserveOriginal()));
        values.put("max_input_utf16_units", Integer.toString(settings.maxInputUtf16Units()));
        values.put("max_output_utf8_bytes", Integer.toString(settings.maxOutputUtf8Bytes()));
        values.put("max_output_bytes_per_input", Integer.toString(settings.maxOutputBytesPerInput()));
        values.put("max_payload_bytes", Integer.toString(settings.maxPayloadBytes()));
        values.put("max_type_utf16_units", Integer.toString(settings.maxTypeUtf16Units()));
        values.put("max_input_tokens_per_stream", Integer.toString(settings.maxInputTokensPerStream()));
        values.put("max_input_utf16_units_per_stream", Integer.toString(settings.maxInputUtf16UnitsPerStream()));
        values.put("max_output_tokens_per_stream", Integer.toString(settings.maxOutputTokensPerStream()));
        values.put("max_output_utf8_bytes_per_stream", Integer.toString(settings.maxOutputUtf8BytesPerStream()));
        configuration = new Configuration(values);
    }
    @Override public TokenStream create(TokenStream input) { return new HomoglyphTokenFilter(input, configuration); }
}
