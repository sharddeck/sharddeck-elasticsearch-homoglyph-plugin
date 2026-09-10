// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

import java.util.Arrays;

/** A fixed number of cached workspaces, all retained capacity charged to one budget. */
final class WorkspacePool {
    private final Workspace[] idle = new Workspace[128];
    private final MemoryBudget budget;
    private long borrowed;
    WorkspacePool(MemoryBudget budget) { this.budget = budget; }
    Workspace borrow(int input, int output) { return borrow(input, output, 0); }
    synchronized Workspace borrow(int input, int output, int expansionSlots) {
        int in = capacity(input), out = capacity(output), slots = expansionSlots == 0 ? 0 : capacity(expansionSlots);
        for (int i = 0; i < idle.length; i++) {
            Workspace w = idle[i];
            if (w != null && w.original.length == in && w.output.length == out && (w.variantIds == null ? 0 : w.variantIds.length) == slots) {
                idle[i] = null; borrowed += w.reservation.bytes(); return w;
            }
        }
        long bytes = 1024L + 2L * in + 2L * out + 8L * slots;
        MemoryBudget.Reservation reservation = budget.tryReserve(bytes);
        if (reservation == null) {
            // Evict idle capacity before rejecting; active work is never evicted.
            evictIdle();
            reservation = budget.tryReserve(bytes);
        }
        if (reservation == null) throw new AnalysisException(RejectionReason.MEMORY_BUDGET_EXCEEDED);
        try {
            Workspace w = new Workspace(in, out, slots, reservation); borrowed += bytes; return w;
        } catch (RuntimeException ex) { reservation.close(); throw ex; }
    }
    synchronized void release(Workspace w) {
        Arrays.fill(w.original, 0, w.inputLength, '\0'); Arrays.fill(w.output, 0, w.outputLength, '\0');
        if (w.variantIds != null) {
            Arrays.fill(w.variantIds, 0, w.slots, 0); Arrays.fill(w.inputOffsets, 0, w.slots, 0);
        }
        w.inputLength = 0; w.outputLength = 0; w.slots = 0; borrowed -= w.reservation.bytes();
        for (int i = 0; i < idle.length; i++) if (idle[i] == null) { idle[i] = w; return; }
        w.reservation.close();
    }
    synchronized long borrowedBytes() { return borrowed; }
    synchronized void evictIdle() {
        for (int i = 0; i < idle.length; i++) if (idle[i] != null) { idle[i].reservation.close(); idle[i] = null; }
    }
    private static int capacity(int requested) {
        int capacity = 16;
        while (capacity < requested) capacity *= 2;
        return capacity;
    }
    static final class Workspace {
        final char[] original, output;
        final int[] variantIds, inputOffsets;
        final MemoryBudget.Reservation reservation;
        int inputLength, outputLength, slots;
        Workspace(int in, int out, int slots, MemoryBudget.Reservation reservation) {
            original = new char[in]; output = new char[out];
            variantIds = slots == 0 ? null : new int[slots]; inputOffsets = slots == 0 ? null : new int[slots];
            this.reservation = reservation;
        }
    }
}
