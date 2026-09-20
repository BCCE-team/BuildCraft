/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.logic.request;

/** Pure quantity/progress rules for requester-style inventories. */
public final class RequestMath {
    private RequestMath() {}

    public static int sanitizeTemplateCount(int count, int maxStackSize) {
        int max = Math.min(maxStackSize, 64);
        if (count <= 0) return 1;
        return count > max ? max : count;
    }

    public static int missingAmount(int requested, int existing) {
        return Math.max(0, Math.max(0, requested) - Math.max(0, existing));
    }

    public static int acceptedAmount(int offered, int requested, int existing) {
        if (offered <= 0) return 0;
        return Math.min(offered, missingAmount(requested, existing));
    }

    public static boolean fulfilled(int requested, int existing) {
        return requested <= 0 || existing >= requested;
    }

    /** Vanilla-style 0..15 comparator strength across active requester slots. */
    public static int comparatorSignal(int[] requested, int[] existing, boolean[] matching) {
        if (requested == null || existing == null || matching == null
            || requested.length != existing.length || requested.length != matching.length) {
            throw new IllegalArgumentException("request arrays must be non-null and have identical lengths");
        }

        int countedSlots = 0;
        int nonEmptySlots = 0;
        float progress = 0.0F;
        for (int i = 0; i < requested.length; i++) {
            int need = requested[i];
            if (need <= 0) continue;
            countedSlots++;
            if (matching[i] && existing[i] > 0) {
                nonEmptySlots++;
                int satisfied = Math.min(existing[i], need);
                int safeNeed = Math.max(1, need);
                progress += (float) satisfied / (float) safeNeed;
            }
        }
        if (countedSlots == 0) return 0;
        progress /= countedSlots;
        return (int) Math.floor(progress * 14.0F) + (nonEmptySlots > 0 ? 1 : 0);
    }
}
