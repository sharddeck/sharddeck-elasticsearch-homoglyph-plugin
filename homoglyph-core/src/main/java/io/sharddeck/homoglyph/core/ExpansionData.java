// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

import java.io.*;
import java.util.Arrays;

/** Pinned, prefix-free equivalence classes and a compact longest-match trie. */
final class ExpansionData {
    static final ExpansionData INSTANCE = new ExpansionData();
    static final String SHA256 = "2827d2755532eb1642f88d1b9911fb002834cacbf0bdaa014d68ba1b5dadf188";
    private final int[] groupStarts, variantOffsets, variantGroups, variantBytes;
    private final int[] groupBytes, maximumBytes, secondBytes, maximumVariant;
    private final int[] edgeStarts, terminals, targets, ascii = new int[128];
    private final char[] text, labels;
    private final int maxLength;

    private ExpansionData() {
        byte[] bytes = Resources.read("unicode-expansions.bin", 1048576, SHA256);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic = in.readInt(), groups = in.readInt(), variants = in.readInt(), characters = in.readInt(), nodes = in.readInt();
            maxLength = in.readInt();
            require(magic == 0x48474531 && groups == 15462 && variants == 34214 && characters == 76995
                && nodes == 34891 && maxLength == 18, "expansion header");
            groupStarts = integers(in, groups + 1); variantOffsets = integers(in, variants + 1);
            require(groupStarts[0] == 0 && groupStarts[groups] == variants && variantOffsets[0] == 0
                && variantOffsets[variants] == characters, "expansion offsets");
            text = new char[characters]; for (int i = 0; i < characters; i++) text[i] = in.readChar();
            variantGroups = new int[variants]; variantBytes = new int[variants];
            groupBytes = new int[groups]; maximumBytes = new int[groups]; secondBytes = new int[groups]; maximumVariant = new int[groups];
            for (int group = 0; group < groups; group++) {
                require(groupStarts[group + 1] - groupStarts[group] >= 2 && groupStarts[group + 1] - groupStarts[group] <= 80, "expansion group size");
                for (int variant = groupStarts[group]; variant < groupStarts[group + 1]; variant++) {
                    int length = variantOffsets[variant + 1] - variantOffsets[variant];
                    require(length >= 1 && length <= maxLength, "expansion variant length");
                    int size = Utf8.length(text, variantOffsets[variant], length);
                    variantGroups[variant] = group; variantBytes[variant] = size; groupBytes[group] += size;
                    if (size > maximumBytes[group]) {
                        secondBytes[group] = maximumBytes[group]; maximumBytes[group] = size; maximumVariant[group] = variant;
                    } else if (size > secondBytes[group]) secondBytes[group] = size;
                }
            }
            edgeStarts = integers(in, nodes + 1); terminals = integers(in, nodes);
            require(edgeStarts[0] == 0 && edgeStarts[nodes] == nodes - 1, "expansion trie offsets");
            labels = new char[nodes - 1]; targets = new int[nodes - 1];
            for (int edge = 0; edge < labels.length; edge++) {
                labels[edge] = in.readChar(); targets[edge] = in.readInt();
                require(targets[edge] == edge + 1, "expansion trie topology");
            }
            for (int node = 0; node < nodes; node++) {
                require(edgeStarts[node] <= edgeStarts[node + 1] && terminals[node] >= -1 && terminals[node] < variants, "expansion trie node");
                for (int edge = edgeStarts[node]; edge < edgeStarts[node + 1]; edge++)
                    require(targets[edge] > node && (edge == edgeStarts[node] || labels[edge - 1] < labels[edge]), "expansion trie edges");
            }
            require(terminals[0] == -1 && in.read() == -1, "expansion trailing data");
            Arrays.fill(ascii, -1);
            for (int edge = edgeStarts[0]; edge < edgeStarts[1]; edge++) if (labels[edge] < 128) ascii[labels[edge]] = targets[edge];
        } catch (IOException | AnalysisException e) { throw new IllegalStateException("Invalid expansion data", e); }
    }
    private static int[] integers(DataInputStream input, int length) throws IOException {
        int[] result = new int[length]; for (int i = 0; i < length; i++) result[i] = input.readInt(); return result;
    }
    private static void require(boolean valid, String message) throws IOException { if (!valid) throw new IOException(message); }
    int match(char[] input, int offset, int end) {
        int node = 0, best = -1;
        for (int i = offset, stop = Math.min(end, offset + maxLength); i < stop; i++) {
            char label = input[i];
            if (node == 0 && label < 128) node = ascii[label];
            else {
                int edge = Arrays.binarySearch(labels, edgeStarts[node], edgeStarts[node + 1], label);
                node = edge < 0 ? -1 : targets[edge];
            }
            if (node < 0) break;
            if (terminals[node] >= 0) best = terminals[node];
        }
        return best;
    }
    int variants() { return variantGroups.length; }
    int length(int variant) { return variantOffsets[variant + 1] - variantOffsets[variant]; }
    int bytes(int variant) { return variantBytes[variant]; }
    int alternatives(int variant) { int g = variantGroups[variant]; return groupStarts[g + 1] - groupStarts[g] - 1; }
    int alternativeBytes(int variant) { return groupBytes[variantGroups[variant]] - variantBytes[variant]; }
    int maximumAlternativeBytes(int variant) {
        int g = variantGroups[variant]; return maximumVariant[g] == variant ? secondBytes[g] : maximumBytes[g];
    }
    int alternative(int variant, int ordinal) {
        int result = groupStarts[variantGroups[variant]] + ordinal;
        return result >= variant ? result + 1 : result;
    }
    int append(int variant, char[] destination, int offset) {
        int length = length(variant); System.arraycopy(text, variantOffsets[variant], destination, offset, length); return offset + length;
    }
}
