// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.benchmark;

import io.sharddeck.homoglyph.core.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** A fresh fork per sample separates cold profile initialization from steady-state analysis. */
@State(Scope.Thread)
@Fork(5)
@Warmup(iterations=0)
@Measurement(iterations=1)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class InitializationBenchmark {
    @Param({"UNICODE_SEARCH_V1","UNICODE_EXPAND_V1"}) public String profile;
    @Benchmark public Object initializeProfile() {
        return DefaultEngine.SHARED.newProcessor(ProfileId.valueOf(profile),Limits.DEFAULT,false);
    }
}
