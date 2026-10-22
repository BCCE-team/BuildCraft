/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.plug;



import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.internal.pluggable.PluggableDefinition;
import buildcraft.transport.internal.pluggable.PluggableModelKey;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.transport.BCTransportItems;
import buildcraft.transport.client.model.key.KeyPlugBlocker;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.VoxelShape;

public class PluggableBlocker extends PipePluggable {
    private static final VoxelShape[] BOXES = new VoxelShape[6];

    private static final ResourceLocation ADVANCEMENT_PLACE_PLUG = new ResourceLocation(
        "buildcrafttransport:plugging_the_gap"
    );

    static {
        double ll = 2D;
        double lu = 4D;
        double ul = 12D;
        double uu = 14D;

        double min = 4D;
        double max = 12D;

        BOXES[Direction.DOWN.get3DDataValue()] = Block.box(min, ll, min, max, lu, max);
        BOXES[Direction.UP.get3DDataValue()] = Block.box(min, ul, min, max, uu, max);
        BOXES[Direction.NORTH.get3DDataValue()] = Block.box(min, min, ll, max, max, lu);
        BOXES[Direction.SOUTH.get3DDataValue()] = Block.box(min, min, ul, max, max, uu);
        BOXES[Direction.WEST.get3DDataValue()] = Block.box(ll, min, min, lu, max, max);
        BOXES[Direction.EAST.get3DDataValue()] = Block.box(ul, min, min, uu, max, max);
    }

    public PluggableBlocker(PluggableDefinition definition, IPipeHolder holder, Direction side) {
        super(definition, holder, side);
    }

    @Override
    public VoxelShape getBoundingBox() {
        return BOXES[side.get3DDataValue()];
    }

    @Override
    public boolean isBlocking() {
        return true;
    }

    @Override
    public ItemStack getPickStack() {
        return new ItemStack(BCTransportItems.plugBlocker.get());
    }

    @Override
    public void onPlacedBy(Player player) {
        super.onPlacedBy(player);
        if (!holder.getPipeWorld().isClientSide && holder.getPipe().isConnected(side)) {
            AdvancementUtil.unlockAdvancement(player, ADVANCEMENT_PLACE_PLUG);
        }
    }

    @Override
    public PluggableModelKey getModelRenderKey(RenderType layer) {
        if (layer == RenderType.cutout()) return new KeyPlugBlocker(side);
        return null;
    }
}
