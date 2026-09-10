// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.adapter.es8;

import java.util.Map;
import java.util.HashMap;
import io.sharddeck.homoglyph.core.Configuration;
import io.sharddeck.homoglyph.lucene.HomoglyphTokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.indices.analysis.AnalysisModule.AnalysisProvider;
import org.elasticsearch.plugins.AnalysisPlugin;
import org.elasticsearch.plugins.Plugin;

public final class HomoglyphPlugin extends Plugin implements AnalysisPlugin {
    @Override public Map<String, AnalysisProvider<TokenFilterFactory>> getTokenFilters() {
        return Map.of("homoglyph", (indexSettings, environment, name, settings) -> {
            Map<String, String> values = new HashMap<>();
            for (String key : settings.keySet()) if (!key.equals("index.version.created") && !key.equals("index.number_of_replicas") && !key.equals("index.number_of_shards")) values.put(key, settings.get(key));
            Configuration configuration = new Configuration(values);
            return new TokenFilterFactory() {
                @Override public String name() { return name; }
                @Override public TokenStream create(TokenStream input) { return new HomoglyphTokenFilter(input, configuration); }
            };
        });
    }
}
