// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import io.sharddeck.homoglyph.core.*;
import java.util.*;
import java.util.concurrent.*;

/** Process-isolated resource stress. This is not a throughput benchmark. */
public final class MemoryStress {
    public static void main(String[] args) throws Exception {
        int duration = args.length == 0 ? 1800 : Integer.parseInt(args[0]);
        if (duration < 4) throw new IllegalArgumentException("duration must be at least four seconds");
        DefaultEngine engine = DefaultEngine.SHARED;
        try (TokenProcessor warm = engine.newProcessor(ProfileId.UNICODE_SEARCH_V1, Limits.DEFAULT, false)) {
            warm.prepare("héllo".toCharArray(),0,5); while(warm.increment()) {}
        }
        int[] concurrency = {1,8,32,128};
        for (int threads : concurrency) {
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(duration / 4);
            List<Callable<long[]>> tasks = new ArrayList<>();
            for(int t=0;t<threads;t++) tasks.add(()-> {
                long accepted=0,rejected=0;
                char[][] inputs = {"ordinary-text".toCharArray(), "о".repeat(64).toCharArray(), "m".repeat(4096).toCharArray(), "日本語 العربية नमस्ते".toCharArray(),
                    "x".repeat(4096).toCharArray(), ("о".repeat(7)+"x".repeat(600)).toCharArray(),
                    "é".repeat(12).toCharArray(), "rnrn".toCharArray(), "漢é😀".toCharArray(), "héllo😀".toCharArray(), ("a"+"\u0315\u0300".repeat(256)).toCharArray(), "x".repeat(4097).toCharArray(), "\ud800".toCharArray(), "\u200b".repeat(4096).toCharArray(), "\uFDFA".repeat(4096).toCharArray()};
                try(TokenProcessor preserved=engine.newProcessor(ProfileId.UNICODE_SEARCH_V1,Limits.DEFAULT,true);
                    TokenProcessor unicode=engine.newProcessor(ProfileId.UNICODE_SEARCH_V1,Limits.DEFAULT,false);
                    TokenProcessor expansion=engine.newProcessor(ProfileId.UNICODE_EXPAND_V1,new Limits(Map.of("max_changed_positions","4")),false)) {
                    int n=0;
                    while(System.nanoTime()<deadline) {
                        TokenProcessor p=(n/inputs.length)%3==0?preserved:(n/inputs.length)%3==1?unicode:expansion; char[] input=inputs[n%inputs.length]; p.reset();
                        try {
                            p.prepare(input,0,input.length);
                            if((n&3)==0) p.increment(); else while(p.increment()) {}
                            accepted++;
                        } catch(AnalysisException error) { rejected++; }
                        finally { p.reset(); }
                        n++;
                    }
                }
                return new long[]{accepted,rejected};
            });
            long accepted=0,rejected=0;
            try {
                for(Future<long[]> result:executor.invokeAll(tasks)) {
                    long[] counts=result.get(60,TimeUnit.SECONDS); accepted+=counts[0]; rejected+=counts[1];
                }
            } finally { executor.shutdownNow(); executor.awaitTermination(60,TimeUnit.SECONDS); }
            if(engine.borrowedWorkspaceBytes()!=0 || engine.budget.reservedBytes()>engine.budget.limitBytes()) throw new AssertionError("reservation leak");
            System.gc(); Thread.sleep(1000);
            long heap=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
            if (heap > 64L*1024*1024) throw new AssertionError("post-GC live heap exceeds retention envelope: "+heap);
            System.out.printf(Locale.ROOT,"{\"threads\":%d,\"accepted\":%d,\"rejected\":%d,\"heap_after_gc\":%d,\"reserved\":%d,\"borrowed\":%d,\"peak_reservation\":%d}%n",
                threads,accepted,rejected,heap,engine.budget.reservedBytes(),engine.borrowedWorkspaceBytes(),engine.budget.highWaterBytes());
            try(TokenProcessor recovery=engine.newProcessor(ProfileId.UNICODE_SEARCH_V1,Limits.DEFAULT,false)) {
                recovery.prepare(new char[]{'о'},0,1); int count=0; while(recovery.increment()) count++;
                if(count!=1) throw new AssertionError("recovery failed");
            }
            try(TokenProcessor recovery=engine.newProcessor(ProfileId.UNICODE_EXPAND_V1,Limits.DEFAULT,false)) {
                recovery.prepare(new char[]{'é'},0,1); int count=0; while(recovery.increment()) count++;
                if(count!=2) throw new AssertionError("expansion recovery failed");
            }
        }
    }
}
