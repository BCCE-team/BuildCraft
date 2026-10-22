/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.logic.distribution;

import java.math.BigInteger;

/**
 * Overflow-safe sequential proportional allocation.
 *
 * <p>The caller adds every eligible demand first, then visits the same entries in allocation order. Each call removes
 * that entry's weighted demand from the denominator even if the receiver later rejects the offered amount. This mirrors
 * BuildCraft's pipe allocation semantics while keeping the arithmetic independent of Minecraft and loader APIs.</p>
 */
public final class WeightedAllocation {
    private BigInteger remaining = BigInteger.ZERO;

    public void add(long demand, long weight) {
        remaining = remaining.add(weighted(demand, weight));
    }

    public boolean hasDemand() {
        return remaining.signum() > 0;
    }

    public long offer(long available, long demand, long weight) {
        BigInteger term = weighted(demand, weight);
        if (available <= 0 || term.signum() <= 0 || remaining.signum() <= 0) {
            return 0;
        }
        long offered = BigInteger.valueOf(available).multiply(term).divide(remaining).longValue();
        remaining = remaining.subtract(term);
        if (offered <= 0) return 0;
        return Math.min(available, offered);
    }

    public int offerInt(int available, long demand, long weight) {
        return (int) offer(Math.max(0, available), demand, weight);
    }

    private static BigInteger weighted(long demand, long weight) {
        if (demand <= 0 || weight <= 0) return BigInteger.ZERO;
        return BigInteger.valueOf(demand).multiply(BigInteger.valueOf(weight));
    }
}
