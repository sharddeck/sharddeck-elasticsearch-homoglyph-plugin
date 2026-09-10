// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.benchmark;

import io.sharddeck.homoglyph.core.Configuration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.openjdk.jmh.annotations.*;

/** One operation consumes the complete fixed 20-document multilingual batch. */
@State(Scope.Thread)
@Fork(3)
@Warmup(iterations=5,time=2)
@Measurement(iterations=10,time=2)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class MultilingualBenchmark {
    @Param({"false","true"}) public boolean preserveOriginal;
    private Analyzer analyzer;
    private List<String> documents;
    @Setup public void setup() throws IOException {
        documents = new ArrayList<>();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/corpus/documents.tsv"),StandardCharsets.UTF_8))) {
            String line; while((line=reader.readLine())!=null) documents.add(line.substring(line.indexOf('\t')+1));
        }
        analyzer = newAnalyzer(preserveOriginal);
    }
    public static Analyzer newAnalyzer(boolean preserveOriginal) {
        return new Analyzer() {
            @Override protected TokenStreamComponents createComponents(String field) {
                Tokenizer source = new WhitespaceTokenizer();
                TokenStream filter = new io.sharddeck.homoglyph.lucene.HomoglyphTokenFilter(source,
                    new Configuration(Map.of("preserve_original", Boolean.toString(preserveOriginal))));
                return new TokenStreamComponents(source,filter);
            }
        };
    }
    @Benchmark public long documents() throws IOException {
        long hash=1;
        for(String document:documents) try(TokenStream stream=analyzer.tokenStream("text",document)) {
            CharTermAttribute term=stream.addAttribute(CharTermAttribute.class); stream.reset();
            while(stream.incrementToken()) {
                for(int i=0;i<term.length();i++) hash=31*hash+term.buffer()[i];
                hash=31*hash+term.length();
            }
            stream.end();
        }
        return hash;
    }
    @TearDown public void close() { analyzer.close(); }
}
