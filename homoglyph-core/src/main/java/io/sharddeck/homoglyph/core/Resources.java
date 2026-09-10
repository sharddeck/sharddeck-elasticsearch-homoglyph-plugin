// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

import java.io.*;
import java.security.*;
import java.util.Arrays;

final class Resources {
    static byte[] read(String file, int limit, String expected) {
        try (InputStream input = Resources.class.getResourceAsStream("/io/sharddeck/homoglyph/data/" + file)) {
            if (input == null) throw new IOException("missing " + file);
            byte[] bytes = new byte[limit + 1]; int used = 0, n;
            while (used < bytes.length && (n = input.read(bytes, used, bytes.length - used)) != -1) used += n;
            if (used > limit) throw new IOException("oversized " + file);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes, 0, used);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            if (!hex.toString().equals(expected)) throw new IOException("checksum mismatch " + file);
            return Arrays.copyOf(bytes, used);
        } catch (IOException | NoSuchAlgorithmException e) { throw new IllegalStateException("homoglyph profile data invalid", e); }
    }
}
