// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public final class Counters implements AnalysisMetrics {
    private final AtomicLongArray rejections = new AtomicLongArray(RejectionReason.values().length);
    private final AtomicLong outputs = new AtomicLong(), bytes = new AtomicLong(), cleanup = new AtomicLong();
    public void rejected(RejectionReason reason) { rejections.incrementAndGet(reason.ordinal()); }
    public void emitted(long tokens, long utf8Bytes) { outputs.addAndGet(tokens); bytes.addAndGet(utf8Bytes); }
    public void cleanupFailure() { cleanup.incrementAndGet(); }
    public Map<String, Long> snapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (RejectionReason reason : RejectionReason.values()) result.put(reason.name().toLowerCase(java.util.Locale.ROOT), rejections.get(reason.ordinal()));
        result.put("transformed_outputs", outputs.get());
        result.put("transformed_utf8_bytes", bytes.get()); result.put("cleanup_failures", cleanup.get());
        return java.util.Collections.unmodifiableMap(result);
    }
}
