/*
 * Copyright (c) 2026 BuildCraft Community Edition contributors
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package buildcraft.builders.gui;

import java.util.ArrayList;
import java.util.List;

/** Segments a visible library entry name to colour matched letters without drawing highlight backgrounds. */
public final class ElectronicLibraryNameHighlight {
    private ElectronicLibraryNameHighlight() { }

    public record Segment(String text, boolean highlighted) { }

    public static List<Segment> segments(String displayedName, String searchQuery) {
        if (displayedName == null || displayedName.isEmpty()) {
            return List.of();
        }
        String query = searchQuery == null ? "" : searchQuery.strip();
        if (query.isEmpty()) {
            return List.of(new Segment(displayedName, false));
        }

        List<Segment> segments = new ArrayList<>();
        int from = 0;
        while (from < displayedName.length()) {
            int match = indexOfIgnoreCase(displayedName, query, from);
            if (match < 0) {
                segments.add(new Segment(displayedName.substring(from), false));
                break;
            }
            if (match > from) {
                segments.add(new Segment(displayedName.substring(from, match), false));
            }
            segments.add(new Segment(displayedName.substring(match, match + query.length()), true));
            from = match + query.length();
        }
        return segments;
    }

    private static int indexOfIgnoreCase(String text, String query, int from) {
        for (int i = from; i <= text.length() - query.length(); i++) {
            if (text.regionMatches(true, i, query, 0, query.length())) {
                return i;
            }
        }
        return -1;
    }
}
