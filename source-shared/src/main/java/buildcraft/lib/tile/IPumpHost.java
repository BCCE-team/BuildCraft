/*
 * Copyright (c) 2026 the BuildCraft Community Edition contributors
 * SPDX-License-Identifier: MPL-2.0
 */
package buildcraft.lib.tile;

import javax.annotation.Nullable;

import com.mojang.authlib.GameProfile;
import buildcraft.lib.internal.mj.MjBattery;
import net.minecraft.core.BlockPos;

/**
 * Internal link between a miner and a Pump installed directly on top of it.
 * The miner publishes a cached obstructing fluid cell without forcing chunk loads;
 * the Pump drains that pool instead of boring through the host block.
 */
public interface IPumpHost {
    /** A high-viscosity fluid blocking mining, or null when none is known. */
    @Nullable BlockPos getBlockingFluid();

    /** The host's actual MJ store; the Pump may borrow only while the host is idle. */
    MjBattery getBattery();

    boolean hasWork();

    @Nullable GameProfile getOwner();

    /** Wake a finished miner's MJ receiver while its Pump needs energy. */
    default void onPumpWantsPower() { }

    /** Tell the miner to rescan previously inaccessible columns after successful drainage. */
    void onBlockingFluidDrained();
}
