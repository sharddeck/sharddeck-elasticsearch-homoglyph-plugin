// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

import java.util.Map;
import java.util.Set;

public final class Configuration {
    private static final Set<String> KEYS = Set.of("type", "profile", "preserve_original", "max_output_tokens_per_input", "max_changed_positions",
        "max_input_utf16_units", "max_output_utf8_bytes", "max_output_bytes_per_input", "max_payload_bytes",
        "max_type_utf16_units", "max_input_tokens_per_stream", "max_input_utf16_units_per_stream",
        "max_output_tokens_per_stream", "max_output_utf8_bytes_per_stream");
    public final ProfileId profile;
    public final Limits limits;
    public final boolean preserveOriginal;
    public Configuration(Map<String, String> settings) {
        for (String key : settings.keySet()) if (!KEYS.contains(key)) throw new IllegalArgumentException("homoglyph: unknown setting " + key);
        String id = settings.getOrDefault("profile", "unicode_search_v1");
        if (id.equals("unicode_search_v1")) profile = ProfileId.UNICODE_SEARCH_V1;
        else if (id.equals("unicode_expand_v1")) profile = ProfileId.UNICODE_EXPAND_V1;
        else throw new IllegalArgumentException("homoglyph: unknown profile");
        String preserve = settings.getOrDefault("preserve_original", "false");
        if (!preserve.equals("true") && !preserve.equals("false")) throw new IllegalArgumentException("homoglyph: preserve_original must be true or false");
        preserveOriginal = Boolean.parseBoolean(preserve);
        limits = new Limits(settings);
    }
}
