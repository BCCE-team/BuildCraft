/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.misc;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.lib.internal.tool.IToolWrench;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;

/** Internal gameplay bridge for API2 wrench detection plus built-in wrench callbacks. */
public final class WrenchUtil {
    private WrenchUtil() {
    }

    /** Returns true when the API2 wrench service recognizes the stack. */
    public static boolean isWrench(ItemStack stack) {
        return BuildCraftApi.service(BuildCraftServices.WRENCHES).isWrench(stack);
    }

    /** Notifies legacy interface-based wrenches after use; tag-only tools have no BuildCraft callback to invoke. */
    public static void wrenchUsed(Player player, InteractionHand hand, ItemStack stack, HitResult hit) {
        if (stack.getItem() instanceof IToolWrench wrench) {
            wrench.wrenchUsed(player, hand, stack, hit);
        }
    }
}
