/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.pipe;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.automation.AutomationResult;
import buildcraft.api.v2.automation.StripesContext;
import buildcraft.api.v2.automation.StripesHandler;
import buildcraft.api.v2.automation.StripesOutput;
import buildcraft.api.v2.permission.AutomationActor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Runtime dispatcher for Stripes. Handler ownership and ordering are now API2 registry data. */
public enum StripesRegistry {
    INSTANCE;

    public boolean handleItem(
        Level world,
        BlockPos pos,
        Direction direction,
        ItemStack stack,
        Player player,
        StripesOutput output
    ) {
        return dispatch(world, pos, direction, stack, player, output);
    }

    public boolean handleBlock(
        Level world,
        BlockPos pos,
        Direction direction,
        Player player,
        StripesOutput output
    ) {
        return dispatch(world, pos, direction, ItemStack.EMPTY, player, output);
    }

    private boolean dispatch(
        Level world,
        BlockPos pos,
        Direction direction,
        ItemStack stack,
        Player player,
        StripesOutput output
    ) {
        AutomationActor actor = player == null
            ? AutomationActor.unknown()
            : AutomationActor.player(player.getUUID(), player.getGameProfile().getName());
        List<StripesHandler> handlers = new ArrayList<>(BuildCraftApi.registry(BuildCraftRegistries.STRIPES_HANDLERS).values());
        handlers.sort(Comparator.comparingInt(StripesHandler::priority).reversed());
        for (StripesHandler handler : handlers) {
            // Each handler receives a fresh working stack. Mutating the context and returning PASS
            // cannot leak partial state into the next handler.
            StripesContext context = new StripesContext(
                world, pos, direction, stack, actor, OperationMode.EXECUTE, player, output
            );
            AutomationResult result = handler.activate(context);
            if (result.status() == AutomationResult.Status.PASS) {
                continue;
            }
            // DENIED/FAILED/RETRY stop this handler chain but are not successful
            // actions. Do not write back a rejected handler's working stack.
            if (result.status() != AutomationResult.Status.SUCCESS) {
                return false;
            }
            if (player != null && !stack.isEmpty()) {
                player.getInventory().setItem(player.getInventory().selected, context.stack());
            }
            return true;
        }
        return false;
    }
}
