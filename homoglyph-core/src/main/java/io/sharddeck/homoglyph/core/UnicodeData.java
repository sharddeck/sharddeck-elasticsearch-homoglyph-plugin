// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UScript;
import com.ibm.icu.text.Normalizer2;
import com.ibm.icu.text.SpoofChecker;
import java.io.*;
import java.util.Arrays;

/** Shared immutable ICU normalization and expansion-bound data. */
final class UnicodeData {
    static final UnicodeData INSTANCE = new UnicodeData();
    private final Normalizer2 fold = Normalizer2.getNFKCCasefoldInstance(), nfd = Normalizer2.getNFDInstance();
    private final SpoofChecker spoof = new SpoofChecker.Builder().build();
    private final int[] cps, normalization, skeleton;
    private final boolean[] identityAscii = new boolean[128];
    private UnicodeData() {
        if (!UCharacter.getUnicodeVersion().toString().equals("17.0.0.0")) throw new IllegalStateException("Unexpected ICU Unicode version");
        byte[] bytes = Resources.read("unicode-bounds.bin", 300000,
            "9d82d15961457ef0d2ce5c53753ca4c81ad9d686ed78324334bfe6e086557304");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != 0x48474231) throw new IOException("invalid bounds magic");
            int count = input.readInt();
            if (count != 20937) throw new IOException("invalid bounds count");
            cps = new int[count]; normalization = new int[count]; skeleton = new int[count];
            for (int i = 0; i < count; i++) {
                cps[i] = input.readInt(); normalization[i] = input.readInt(); skeleton[i] = input.readInt();
                if ((i > 0 && cps[i] <= cps[i - 1]) || cps[i] > 0x10ffff || normalization[i] < 0 || normalization[i] > 64 || skeleton[i] < 0 || skeleton[i] > 128)
                    throw new IOException("invalid Unicode bounds");
            }
            if (input.read() != -1) throw new IOException("trailing bounds data");
        } catch (IOException e) { throw new IllegalStateException(e); }
        for (int c = 0; c < 128; c++) identityAscii[c] = key(Character.toString((char)c)).equals(Character.toString((char)c));
    }
    boolean identityAscii(char[] input, int offset, int length) {
        for (int i = offset; i < offset + length; i++) if (input[i] > 127 || !identityAscii[input[i]]) return false;
        return length != 0;
    }
    long reservation(char[] input, int offset, int length) {
        long n = 0, s = 0;
        for (int i = offset, end = offset + length; i < end;) {
            int cp = Character.codePointAt(input, i, end), width = Character.charCount(cp); i += width;
            int row = Arrays.binarySearch(cps, cp);
            n += row < 0 ? width : normalization[row]; s += row < 0 ? width : skeleton[row];
        }
        // 64 bytes per input/intermediate unit plus 64 KiB fixed allowance;
        // includes concurrent Strings, builder growth and ICU reordering scratch.
        return 65536L + 64L * (length + n + s);
    }
    String key(String input) {
        String decomposed = nfd.normalize(fold.normalize(input));
        StringBuilder stripped = new StringBuilder(decomposed.length());
        boolean latin = false;
        for (int i = 0; i < decomposed.length();) {
            int cp = decomposed.codePointAt(i); i += Character.charCount(cp);
            int type = UCharacter.getType(cp);
            boolean mark = type == UCharacter.NON_SPACING_MARK || type == UCharacter.COMBINING_SPACING_MARK || type == UCharacter.ENCLOSING_MARK;
            if (!mark) latin = UScript.getScript(cp) == UScript.LATIN;
            if (!(latin && type == UCharacter.NON_SPACING_MARK)) stripped.appendCodePoint(cp);
        }
        return spoof.getSkeleton(stripped);
    }
}
