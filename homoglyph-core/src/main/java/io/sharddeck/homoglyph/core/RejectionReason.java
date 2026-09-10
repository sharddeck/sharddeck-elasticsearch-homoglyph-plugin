// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

/** Fixed-cardinality reasons for rejected analysis operations. */
public enum RejectionReason {
    INPUT_TOO_LONG,
    OUTPUT_BUDGET_EXCEEDED,
    STREAM_BUDGET_EXCEEDED,
    ATTRIBUTE_BUDGET_EXCEEDED,
    UNSUPPORTED_ATTRIBUTE,
    MEMORY_BUDGET_EXCEEDED,
    MALFORMED_INPUT
}

