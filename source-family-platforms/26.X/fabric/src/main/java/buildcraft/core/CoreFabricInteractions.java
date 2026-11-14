/*
 * Copyright (c) 2026 the BuildCraft Community Edition contributors.
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.core;

import buildcraft.core.item.ItemPaintbrush_BC8;
import buildcraft.core.item.CushionPaintEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;

/** NeoForge's useBlock=false / useItem=true priority translated to Fabric. */
public final class CoreFabricInteractions {
    private static boolean registered;

    private CoreFabricInteractions() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        UseEntityCallback.EVENT.register(CushionPaintEvents::onInteract);
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            // Vanilla block use would consume a click on signs, beds and
            // shulkers before the paintbrush can perform its custom action.
            if (player.isSpectator() || !player.mayBuild() || !level.mayInteract(player, hit.getBlockPos())) {
                return InteractionResult.PASS;
            }
            ItemStack stack = player.getItemInHand(hand);
            if (!ItemPaintbrush_BC8.shouldOverrideBlockUse(stack, level, hit.getBlockPos())) {
                return InteractionResult.PASS;
            }
            return ((ItemPaintbrush_BC8) stack.getItem()).useOn(new UseOnContext(player, hand, hit));
        });
    }
}
