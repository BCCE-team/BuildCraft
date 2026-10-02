/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.logic.energy;

import java.math.BigInteger;

/** Overflow-safe arithmetic shared by MJ/FE transport implementations. */
public final class EnergyMath {
    private EnergyMath() {}

    public static long saturatingAdd(long a, long b) {
        if (b <= 0) return a;
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    public static int saturatingAdd(int a, int b) {
        if (b <= 0) return a;
        return a > Integer.MAX_VALUE - b ? Integer.MAX_VALUE : a + b;
    }

    public static long applyResistance(long input, long resistance, long scale) {
        if (input <= 0 || scale <= 0) return 0;
        if (resistance <= 0) return input;
        if (resistance >= scale) return 0;
        long retained = BigInteger.valueOf(input)
            .multiply(BigInteger.valueOf(scale - resistance))
            .divide(BigInteger.valueOf(scale))
            .longValue();
        return Math.max(1, retained);
    }

    public static long inputForDelivered(long delivered, long maxInput, long resistance, long scale) {
        if (delivered <= 0 || maxInput <= 0 || scale <= 0) return 0;
        if (resistance <= 0) return Math.min(delivered, maxInput);
        long retainedRatio = scale - resistance;
        if (retainedRatio <= 0) return maxInput;
        BigInteger numerator = BigInteger.valueOf(delivered).multiply(BigInteger.valueOf(scale));
        BigInteger denominator = BigInteger.valueOf(retainedRatio);
        long required = numerator.add(denominator).subtract(BigInteger.ONE).divide(denominator).longValue();
        return Math.min(required, maxInput);
    }
}
