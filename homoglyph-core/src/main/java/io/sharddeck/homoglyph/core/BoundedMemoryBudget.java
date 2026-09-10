// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class BoundedMemoryBudget implements MemoryBudget {
    private final long limit;
    private final AtomicLong reserved = new AtomicLong(), peak = new AtomicLong();
    public BoundedMemoryBudget(long limit) {
        if (limit < 0) throw new IllegalArgumentException("negative memory budget");
        this.limit = limit;
    }
    public Reservation tryReserve(long bytes) {
        if (bytes < 1) throw new IllegalArgumentException("invalid reservation size");
        long current;
        do {
            current = reserved.get();
            if (bytes > limit - current) return null;
        } while (!reserved.compareAndSet(current, current + bytes));
        peak.accumulateAndGet(current + bytes, Math::max);
        return new Ticket(bytes);
    }
    public long reservedBytes() { return reserved.get(); }
    public long highWaterBytes() { return peak.get(); }
    public long limitBytes() { return limit; }
    private final class Ticket implements Reservation {
        private final long bytes;
        private final AtomicBoolean closed = new AtomicBoolean();
        Ticket(long bytes) { this.bytes = bytes; }
        public long bytes() { return bytes; }
        public void close() { if (closed.compareAndSet(false, true)) reserved.addAndGet(-bytes); }
    }
}
