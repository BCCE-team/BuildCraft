/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.logic.routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure priority grouping used by pipe routing. Lower numeric values are visited first. */
public final class PriorityGroups {
    private PriorityGroups() {}

    public static List<int[]> group(int[] priorities, boolean[] allowed) {
        if (priorities == null || allowed == null || priorities.length != allowed.length) {
            throw new IllegalArgumentException("priorities and allowed must be non-null and have the same length");
        }

        int allowedCount = 0;
        for (boolean value : allowed) {
            if (value) allowedCount++;
        }
        if (allowedCount == 0) return List.of();

        int[] allAllowed = new int[allowedCount];
        int at = 0;
        for (int i = 0; i < allowed.length; i++) {
            if (allowed[i]) allAllowed[at++] = i;
        }
        if (allowedCount == 1 || allPrioritiesEqual(priorities)) {
            return List.of(allAllowed);
        }

        int[] ordered = Arrays.copyOf(priorities, priorities.length);
        Arrays.sort(ordered);
        List<int[]> result = new ArrayList<>();
        boolean haveLast = false;
        int last = 0;
        for (int current : ordered) {
            if (haveLast && current == last) continue;
            haveLast = true;
            last = current;

            int count = 0;
            for (int i = 0; i < priorities.length; i++) {
                if (allowed[i] && priorities[i] == current) count++;
            }
            if (count == 0) continue;

            int[] group = new int[count];
            int index = 0;
            for (int i = 0; i < priorities.length; i++) {
                if (allowed[i] && priorities[i] == current) group[index++] = i;
            }
            result.add(group);
        }
        return List.copyOf(result);
    }

    private static boolean allPrioritiesEqual(int[] priorities) {
        if (priorities.length < 2) return true;
        int value = priorities[0];
        for (int i = 1; i < priorities.length; i++) {
            if (priorities[i] != value) return false;
        }
        return true;
    }
}
