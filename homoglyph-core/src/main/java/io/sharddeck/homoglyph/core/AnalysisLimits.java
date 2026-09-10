// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

/** Validated immutable limits, configurable downward from the safety contract. */
public interface AnalysisLimits {
    int maxOutputTokensPerInput();
    int maxChangedPositions();
    int maxInputUtf16Units();
    int maxOutputUtf8Bytes();
    long maxOutputBytesPerInput();
    int maxPayloadBytes();
    int maxTypeUtf16Units();
    long maxInputTokensPerStream();
    long maxInputUtf16UnitsPerStream();
    long maxOutputTokensPerStream();
    long maxOutputUtf8BytesPerStream();
}

