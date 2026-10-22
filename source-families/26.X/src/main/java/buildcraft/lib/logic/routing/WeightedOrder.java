/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.logic.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Pure weighted-without-replacement ordering. Input iteration order is the deterministic tie/fallback order. */
public final class WeightedOrder {
    private WeightedOrder() {}

    public static <T> List<T> order(Map<T, Long> source, LongSupplier randomLong) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(randomLong, "randomLong");

        LinkedHashMap<T, Long> remaining = new LinkedHashMap<>();
        source.forEach((key, weight) -> {
            if (key != null && weight != null && weight > 0) remaining.put(key, weight);
        });
        if (remaining.isEmpty()) return List.of();

        List<T> ordered = new ArrayList<>(remaining.size());
        while (!remaining.isEmpty()) {
            long total = 0;
            for (long weight : remaining.values()) {
                total = saturatingAdd(total, weight);
            }

            long choice = Math.floorMod(randomLong.getAsLong(), total);
            long cursor = 0;
            T selected = remaining.keySet().iterator().next();
            for (Map.Entry<T, Long> entry : remaining.entrySet()) {
                long weight = entry.getValue();
                long next = saturatingAdd(cursor, weight);
                if (next == Long.MAX_VALUE || choice < next) {
                    selected = entry.getKey();
                    break;
                }
                cursor = next;
            }
            ordered.add(selected);
            remaining.remove(selected);
        }
        return List.copyOf(ordered);
    }

    private static long saturatingAdd(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }
}
