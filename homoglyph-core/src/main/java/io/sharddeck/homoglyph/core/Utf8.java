// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

final class Utf8 {
    private Utf8() {}
    static int width(int cp) { return cp < 128 ? 1 : cp < 2048 ? 2 : cp < 65536 ? 3 : 4; }
    static int length(char[] text, int offset, int length) {
        int bytes = 0, end = offset + length;
        for (int i = offset; i < end; i++) {
            char c = text[i];
            if (Character.isHighSurrogate(c) && i + 1 < end && Character.isLowSurrogate(text[i + 1])) { bytes += 4; i++; }
            else {
                if (Character.isSurrogate(c)) throw new AnalysisException(RejectionReason.MALFORMED_INPUT);
                bytes += width(c);
            }
        }
        return bytes;
    }
}
