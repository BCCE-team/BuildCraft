/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.misc;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.tool.WrenchAdapter;
import buildcraft.api.v2.tool.WrenchUseContext;
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

    /** Notifies registered adapters and the wrench item after a successful interaction. */
    public static void wrenchUsed(Player player, InteractionHand hand, ItemStack stack, HitResult hit) {
        if (stack == null || stack.isEmpty()) return;
        WrenchUseContext context = new WrenchUseContext(player, hand, stack, hit);
        for (WrenchAdapter adapter : BuildCraftApi.registry(BuildCraftRegistries.WRENCH_ADAPTERS).values()) {
            try {
                if (adapter.recognizes(stack)) adapter.onUsed(context);
            } catch (RuntimeException ignored) { }
        }
        if (stack.getItem() instanceof IToolWrench wrench) {
            wrench.wrenchUsed(player, hand, stack, hit);
        }
    }
}
