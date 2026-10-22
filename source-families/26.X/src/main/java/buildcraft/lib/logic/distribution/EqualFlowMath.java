/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.logic.distribution;

/** Pure integer helpers for BuildCraft's equal fluid-flow split. */
public final class EqualFlowMath {
    private EqualFlowMath() {}

    /**
     * Computes the amount offered to one path using BuildCraft's equal-flow split rule.
     * Positive paths receive at least one unit while total availability remains positive.
     */
    public static int share(int pathAvailable, int perPathLimit, int pathCount, int totalAvailable) {
        if (pathAvailable <= 0 || perPathLimit <= 0 || pathCount <= 0 || totalAvailable <= 0) return 0;
        long aggregateLimit = (long) perPathLimit * pathCount;
        double fraction = Math.min(aggregateLimit, (long) totalAvailable) / (double) aggregateLimit;
        int amount = (int) Math.min((double) pathAvailable, pathAvailable * fraction);
        return Math.min(pathAvailable, Math.max(1, amount));
    }
}
