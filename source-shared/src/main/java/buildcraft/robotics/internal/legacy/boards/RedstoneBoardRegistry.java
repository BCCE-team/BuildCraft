/**
 * Copyright (c) 2011-2017, SpaceToad and the BuildCraft Team
 * http://www.mod-buildcraft.com
 *
 * The BuildCraft API is distributed under the terms of the MIT License.
 */
package buildcraft.robotics.internal.legacy.boards;

import java.util.Collection;
import java.util.function.Consumer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

public abstract class RedstoneBoardRegistry {
    public static RedstoneBoardRegistry instance;

    /**
     * Register a redstone board type. The energy cost is measured in whole BuildCraft MJ.
     * Legacy 1.7 recipe costs must be converted first using the preserved 10 legacy units = 1 MJ scale.
     */
    public abstract void registerBoardType(RedstoneBoardNBT<?> redstoneBoardNBT, int energyCost);

    /** Deprecated 1.7 compatibility entry point. Prefer {@link #registerBoardType(RedstoneBoardNBT, int)}. */
    @Deprecated
    public abstract void registerBoardClass(RedstoneBoardNBT<?> redstoneBoardNBT, float probability);

    public abstract void setEmptyRobotBoard(RedstoneBoardRobotNBT redstoneBoardNBT);

    public abstract RedstoneBoardRobotNBT getEmptyRobotBoard();

    public abstract RedstoneBoardNBT<?> getRedstoneBoard(CompoundTag nbt);

    public abstract RedstoneBoardNBT<?> getRedstoneBoard(String id);
    public abstract void registerSprites(Consumer<ResourceLocation> spriteRegistrar);

    /** Compatibility alias for callers using the legacy method name. */
    @Deprecated
    public void registerIcons(Object iconRegister) {
        registerSprites(location -> {});
    }

    public abstract Collection<RedstoneBoardNBT<?>> getAllBoardNBTs();

    public abstract int getEnergyCost(RedstoneBoardNBT<?> board);
}
