// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

/** Engine boundary; implementations share immutable data and one memory budget. */
public interface HomoglyphEngine {
    TokenProcessor newProcessor(ProfileId profile, AnalysisLimits limits, boolean preserveOriginal);
}

