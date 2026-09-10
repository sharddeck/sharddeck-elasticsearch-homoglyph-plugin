// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import com.ibm.icu.text.Normalizer2;
import com.ibm.icu.text.SpoofChecker;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Offline Unicode expansion-bound generator; run with the pinned ICU jar. */
public final class GenerateData {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);
        Normalizer2 fold = Normalizer2.getNFKCCasefoldInstance();
        Normalizer2 nfd = Normalizer2.getNFDInstance();
        SpoofChecker spoof = new SpoofChecker.Builder().build();
        List<int[]> bounds = new ArrayList<>();
        SortedMap<String, SortedSet<String>> expansions = new TreeMap<>();
        for (int cp = 0; cp <= 0x10ffff; cp++) {
            if (cp >= 0xd800 && cp <= 0xdfff) continue;
            String original = new String(Character.toChars(cp));
            String confusable = expansionKey(spoof, original);
            if (!confusable.isEmpty() && !confusable.equals(original)) {
                SortedSet<String> group = expansions.computeIfAbsent(confusable, ignored -> new TreeSet<>());
                group.add(confusable); group.add(original);
            }
            String normalized = nfd.normalize(fold.normalize(original));
            String skeleton = spoof.getSkeleton(normalized);
            int n = normalized.length(), s = skeleton.length();
            if (n != original.length() || s != original.length()) bounds.add(new int[] {cp, n, s});
        }
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(directory.resolve("unicode-bounds.bin")))) {
            out.writeInt(0x48474231);
            out.writeInt(bounds.size());
            for (int[] row : bounds) for (int n : row) out.writeInt(n);
        }
        writeExpansions(directory, expansions, spoof);
        System.out.println("Unicode bound entries=" + bounds.size());
    }
    private static String expansionKey(SpoofChecker spoof, String input) throws IOException {
        for (int pass = 0; pass < 16; pass++) {
            String next = spoof.getSkeleton(input);
            if (next.equals(input)) return input;
            input = next;
        }
        throw new IOException("ICU skeleton failed to converge within 16 passes");
    }
    private static final class Trie {
        final SortedMap<Character, Trie> children = new TreeMap<>();
        int terminal = -1, id;
    }
    private static void writeExpansions(Path directory, SortedMap<String, SortedSet<String>> groups, SpoofChecker spoof) throws IOException {
        List<String> variants = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        Trie root = new Trie();
        int characters = 0, maxLength = 0, maxGroup = 0;
        for (Map.Entry<String, SortedSet<String>> entry : groups.entrySet()) {
            starts.add(variants.size());
            String previous = null;
            maxGroup = Math.max(maxGroup, entry.getValue().size());
            for (String variant : entry.getValue()) {
                if (!expansionKey(spoof, variant).equals(entry.getKey())) throw new IOException("unstable expansion class");
                if (previous != null && variant.startsWith(previous)) throw new IOException("non-prefix-free equivalence class");
                previous = variant;
                Trie node = root;
                for (int i = 0; i < variant.length(); i++) node = node.children.computeIfAbsent(variant.charAt(i), ignored -> new Trie());
                if (node.terminal != -1) throw new IOException("variant belongs to multiple classes");
                node.terminal = variants.size(); variants.add(variant);
                characters += variant.length(); maxLength = Math.max(maxLength, variant.length());
            }
        }
        starts.add(variants.size());
        List<Trie> nodes = new ArrayList<>(); nodes.add(root);
        for (int i = 0; i < nodes.size(); i++) for (Trie child : nodes.get(i).children.values()) {
            child.id = nodes.size(); nodes.add(child);
        }
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(directory.resolve("unicode-expansions.bin")))) {
            out.writeInt(0x48474531);
            out.writeInt(groups.size()); out.writeInt(variants.size()); out.writeInt(characters); out.writeInt(nodes.size()); out.writeInt(maxLength);
            for (int start : starts) out.writeInt(start);
            int offset = 0;
            for (String variant : variants) { out.writeInt(offset); offset += variant.length(); }
            out.writeInt(offset);
            for (String variant : variants) for (int i = 0; i < variant.length(); i++) out.writeChar(variant.charAt(i));
            int edge = 0;
            for (Trie node : nodes) { out.writeInt(edge); edge += node.children.size(); }
            out.writeInt(edge);
            for (Trie node : nodes) out.writeInt(node.terminal);
            for (Trie node : nodes) for (Map.Entry<Character, Trie> child : node.children.entrySet()) {
                out.writeChar(child.getKey()); out.writeInt(child.getValue().id);
            }
        }
        System.out.println("Expansion groups=" + groups.size() + "; variants=" + variants.size() + "; characters=" + characters
            + "; trie nodes=" + nodes.size() + "; max UTF-16 length=" + maxLength + "; max group=" + maxGroup);
    }
}
