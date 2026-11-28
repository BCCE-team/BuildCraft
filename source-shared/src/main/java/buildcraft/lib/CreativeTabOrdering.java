/* Copyright (c) 2026 BuildCraft Community Edition contributors; MPL 2.0. */
package buildcraft.lib;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Stable placement of entries relative to an existing creative-tab item. */
public final class CreativeTabOrdering {
    private CreativeTabOrdering() { }

    /** Move all variants of moving directly after the last anchor variant, without reordering other entries. */
    public static <T> void moveAfter(List<T> entries, Function<T, String> id, String anchor, String moving) {
        if (anchor.equals(moving) || entries.stream().noneMatch(entry -> anchor.equals(id.apply(entry)))) return;
        List<T> moved = new ArrayList<>();
        entries.removeIf(entry -> {
            if (!moving.equals(id.apply(entry))) return false;
            moved.add(entry);
            return true;
        });
        if (moved.isEmpty()) return;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (anchor.equals(id.apply(entries.get(i)))) {
                entries.addAll(i + 1, moved);
                return;
            }
        }
    }
}
