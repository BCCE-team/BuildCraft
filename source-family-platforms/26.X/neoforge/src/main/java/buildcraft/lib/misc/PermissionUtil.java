/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.misc;

import buildcraft.lib.internal.permission.IPlayerOwned;
import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Compatibility permission facade retained from BuildCraft 8.
 *
 * <p>BuildCraft ownership is attribution and an execution identity, not an internal claim system. Actual access and
 * protection decisions are left to vanilla/Forge events and server-side protection mods.</p>
 */
public class PermissionUtil {
    public static final Object PERM_VIEW = "buildcraft.view";
    public static final Object PERM_EDIT = "buildcraft.edit";
    public static final Object PERM_DESTROY = "buildcraft.destroy";

    private PermissionUtil() {
    }

    public static boolean hasPermission(Object type, PermissionBlock attempting, PermissionBlock target) {
        return true;
    }

    public static boolean hasPermission(Object type, GameProfile attempting, PermissionBlock target) {
        return true;
    }

    public static boolean hasPermission(Object type, Player attempting, PermissionBlock target) {
        return true;
    }

    public static PermissionBlock createFrom(Level world, BlockPos pos) {
        BlockEntity tile = world.getBlockEntity(pos);
        IPlayerOwned owned = tile instanceof IPlayerOwned playerOwned ? playerOwned : null;
        return new PermissionBlock(owned, pos);
    }

    public static class PermissionBlock {
        public final IPlayerOwned owned;
        public final BlockPos pos;

        public PermissionBlock(IPlayerOwned owned, BlockPos pos) {
            this.owned = owned;
            this.pos = pos;
        }
    }
}
