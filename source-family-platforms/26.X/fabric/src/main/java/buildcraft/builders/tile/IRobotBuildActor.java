/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.builders.tile;

import buildcraft.lib.internal.area.IZone;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import com.mojang.authlib.GameProfile;

/**
 * Loader-neutral contract consumed by Builder/Construction Marker robot tasks.
 * The Robotics module adapts its EntityRobotBase to this interface; Builders itself
 * does not depend on a loader-specific robot implementation.
 */
public interface IRobotBuildActor {
    int getContainerSize();
    ItemStack getItem(int slot);
    ItemStack removeItem(int slot, int count);
    IZone getZoneToWork();

    IRobotBuildReservations getBuildReservations();

    /** May be null for legacy robots whose ownership is inherited from their station. */
    default GameProfile getOwnerProfile() {
        return null;
    }

    interface IRobotBuildReservations {
        boolean isTaken(BlockPos pos);
        boolean take(BlockPos pos, IRobotBuildActor robot);
        void release(BlockPos pos);
    }
}
