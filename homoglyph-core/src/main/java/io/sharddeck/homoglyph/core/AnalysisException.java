// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

/** Bounded, text-free error surfaced through the host operation's error channel. */
public final class AnalysisException extends IllegalArgumentException {
    private final RejectionReason reason;
    public AnalysisException(RejectionReason reason) {
        super("homoglyph: " + reason.name().toLowerCase(java.util.Locale.ROOT));
        this.reason = reason;
    }
    public RejectionReason reason() { return reason; }
}
