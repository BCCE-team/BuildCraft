/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.builders.gui;

import java.util.List;
import java.util.Objects;

/**
 * A scrollable window over stable library entry keys. The viewport does not use
 * display names as identities: two blueprints with the same title remain distinct.
 *
 * <p>The entry currently at the top of the viewport is anchored when entries
 * are inserted, removed, or reordered. If it disappears, the old position is
 * clamped into the new range. Opening a new screen starts at the first entry.</p>
 */
public final class ElectronicLibraryViewport<K> {
    private final int visibleRows;
    private List<K> previousEntries = List.of();
    private K topEntry;
    private int firstIndex;

    public ElectronicLibraryViewport(int visibleRows) {
        if (visibleRows <= 0) {
            throw new IllegalArgumentException("visibleRows must be positive");
        }
        this.visibleRows = visibleRows;
    }

    public int visibleRows() {
        return visibleRows;
    }

    public int maxFirstIndex(int total) {
        return Math.max(0, total - visibleRows);
    }

    public void update(List<K> entries) {
        Objects.requireNonNull(entries, "entries");
        if (!previousEntries.equals(entries)) {
            if (topEntry != null) {
                int newIndex = entries.indexOf(topEntry);
                if (newIndex >= 0) {
                    firstIndex = newIndex;
                }
            }
            previousEntries = List.copyOf(entries);
        }
        firstIndex = clamp(firstIndex, 0, maxFirstIndex(entries.size()));
        topEntry = firstIndex < entries.size() ? entries.get(firstIndex) : null;
    }

    public int firstIndex(List<K> entries) {
        update(entries);
        return firstIndex;
    }

    public void scrollBy(List<K> entries, int rows) {
        update(entries);
        firstIndex = (int) Math.max(0L, Math.min((long) maxFirstIndex(entries.size()),
            (long) firstIndex + rows));
        topEntry = firstIndex < entries.size() ? entries.get(firstIndex) : null;
    }

    public void scrollToFraction(List<K> entries, double fraction) {
        update(entries);
        if (!Double.isFinite(fraction)) {
            return;
        }
        double clipped = Math.max(0.0, Math.min(1.0, fraction));
        firstIndex = (int) Math.round(clipped * maxFirstIndex(entries.size()));
        topEntry = firstIndex < entries.size() ? entries.get(firstIndex) : null;
    }

    /** Returns null for empty rows, including the unused bottom pixels of a GUI list. */
    public K entryAtRow(List<K> entries, int row) {
        update(entries);
        if (row < 0 || row >= visibleRows) {
            return null;
        }
        int index = firstIndex + row;
        return index < entries.size() ? entries.get(index) : null;
    }

    public int visibleCount(List<K> entries) {
        update(entries);
        return Math.min(visibleRows, entries.size() - firstIndex);
    }

    private static int clamp(int n, int low, int high) {
        return Math.max(low, Math.min(n, high));
    }
}
