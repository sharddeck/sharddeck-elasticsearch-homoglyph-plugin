// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

/** Shared admission boundary; reserve capacity before allocation, without waiting. */
public interface MemoryBudget {
    /** Return null immediately when the requested capacity cannot be admitted. */
    Reservation tryReserve(long bytes);

    long reservedBytes();

    interface Reservation extends AutoCloseable {
        long bytes();

        @Override
        void close();
    }
}

