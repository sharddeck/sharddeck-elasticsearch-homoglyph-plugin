// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.benchmark;

import io.sharddeck.homoglyph.core.*;
import io.sharddeck.homoglyph.lucene.HomoglyphTokenFilter;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@Fork(3)
@Warmup(iterations=5,time=2)
@Measurement(iterations=10,time=2)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ExpansionBenchmark {
    @Param({"unmapped","single","sequence","multilingual","dense"}) public String corpus;
    @Param({"1","4"}) public int maxChanges;
    private String text;
    private TokenProcessor processor;
    private char[] input;
    private Analyzer analyzer;
    @Setup public void setup() {
        switch (corpus) {
            case "unmapped": text = "😀"; break;
            case "single": text = "é"; break;
            case "sequence": text = "rnrn"; break;
            case "multilingual": text = "漢字é"; break;
            case "dense": text = "é".repeat(12); break;
            default: throw new IllegalArgumentException(corpus);
        }
        Configuration configuration = new Configuration(Map.of("profile", "unicode_expand_v1", "max_changed_positions", Integer.toString(maxChanges)));
        processor = DefaultEngine.SHARED.newProcessor(configuration.profile,configuration.limits,configuration.preserveOriginal);
        input = text.toCharArray();
        analyzer = new Analyzer() {
            @Override protected TokenStreamComponents createComponents(String field) {
                Tokenizer tokenizer = new KeywordTokenizer();
                return new TokenStreamComponents(tokenizer,new HomoglyphTokenFilter(tokenizer,configuration));
            }
        };
    }
    @Benchmark public long coreTransformation() {
        processor.reset(); processor.prepare(input,0,input.length); long hash=1;
        while(processor.increment()) {
            char[] result=processor.buffer(); int offset=processor.offset(),length=processor.length();
            for(int i=0;i<length;i++) hash=hash*31+result[offset+i];
            hash=hash*31+length;
        }
        return hash;
    }
    @Benchmark public long reusedAnalyzer() throws java.io.IOException {
        long hash=1;
        try(TokenStream stream=analyzer.tokenStream("field",text)) {
            CharTermAttribute term=stream.addAttribute(CharTermAttribute.class); stream.reset();
            while(stream.incrementToken()) {
                for(int i=0;i<term.length();i++) hash=hash*31+term.buffer()[i];
                hash=hash*31+term.length();
            }
            stream.end();
        }
        return hash;
    }
    @TearDown public void close() { processor.close(); analyzer.close(); }
}
