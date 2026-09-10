// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

/** Mutable per-stream state. Output views are borrowed until the next state change. */
public interface TokenProcessor extends AutoCloseable {
    void reset();
    default void prepare(char[] input, int offset, int length) { prepare(input, offset, length, false); }
    void prepare(char[] input, int offset, int length, boolean keyword);
    boolean increment();
    boolean hasPending();
    int plannedOutputs();
    char[] buffer();
    int offset();
    int length();
    /** Release a drained token after its last borrowed output has been consumed. */
    void finishToken();
    @Override void close();
}
