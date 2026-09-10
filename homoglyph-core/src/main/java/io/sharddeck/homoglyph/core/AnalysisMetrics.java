// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

/** Token-boundary instrumentation; implementations must use bounded cardinality. */
public interface AnalysisMetrics {
    void rejected(RejectionReason reason);
    void emitted(long tokens, long utf8Bytes);
    void cleanupFailure();
}

