// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

/** One production instance per installed plugin classloader. */
public final class DefaultEngine implements HomoglyphEngine {
    public static final DefaultEngine SHARED = new DefaultEngine(Math.min(64L << 20, Runtime.getRuntime().maxMemory() / 128));
    public final BoundedMemoryBudget budget;
    public final Counters metrics = new Counters();
    final WorkspacePool pool;
    public DefaultEngine(long scratchBytes) {
        if (scratchBytes > Math.min(64L << 20, Runtime.getRuntime().maxMemory() / 128)) throw new IllegalArgumentException("scratch budget exceeds hard maximum");
        budget = new BoundedMemoryBudget(scratchBytes); pool = new WorkspacePool(budget);
    }
    @Override public TokenProcessor newProcessor(ProfileId profile, AnalysisLimits limits, boolean preserveOriginal) {
        if (!(limits instanceof Limits)) throw new IllegalArgumentException("Validated immutable Limits required");
        java.util.Objects.requireNonNull(profile);
        if (profile == ProfileId.UNICODE_EXPAND_V1) return new ExpansionProcessor(this, (Limits)limits);
        return new Processor(this, (Limits)limits, preserveOriginal);
    }
    public long borrowedWorkspaceBytes() { return pool.borrowedBytes(); }
    public MemoryBudget.Reservation reserveTemporary(long bytes) {
        MemoryBudget.Reservation reservation = budget.tryReserve(bytes);
        if (reservation != null) return reservation;
        // Cached capacity must not permanently block an otherwise admissible profile.
        pool.evictIdle();
        return budget.tryReserve(bytes);
    }
    AnalysisException failure(RejectionReason reason) { return new AnalysisException(reason); }
}
