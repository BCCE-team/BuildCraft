/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.item;

import buildcraft.lib.internal.mj.MjCapabilities;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import buildcraft.transport.internal.IItemPluggable;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pipe.PipeBehaviour;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.internal.pluggable.PluggableDefinition;
import buildcraft.transport.internal.pluggable.PluggableDefinition.IPluggableCreator;
import buildcraft.lib.misc.SoundUtil;
import buildcraft.transport.pipe.Pipe;

import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class ItemPluggableSimple extends Item implements IItemPluggable {

    private static final IPlacementPredicate ALWAYS_CAN = (item, h, s) -> true;

    /** Returns true if the {@link IPipeHolder}'s
     * {@link PipeBehaviour#getCapability(net.neoforged.neoforge.capabilities.BlockCapability, Direction)} returns a
     * non-null value for {@link MjCapabilities#CAP_REDSTONE_RECEIVER}. */
    public static final IPlacementPredicate PIPE_BEHAVIOUR_ACCEPTS_RS_POWER = (item, pipeHolder, side) -> {
        IPipe pipe = pipeHolder.getPipe();
        if (pipe != Pipe.EMPTY) {
            return pipe.getBehaviour().getCapability(MjCapabilities.CAP_REDSTONE_RECEIVER, side) != null;
        }
        return false;
    };

    private final PluggableDefinition definition;
    private final IPlacementPredicate canPlace;
    private final IPluggableCreator creator;

    public ItemPluggableSimple(PluggableDefinition definition, IPluggableCreator creator,
        @Nullable IPlacementPredicate canPlace, Item.Properties p) {
        super(p);
        this.definition = definition;
        this.creator = creator;
        if (creator == null) {
            throw new NullPointerException("Creator was null!");
        }
        this.canPlace = canPlace == null ? ALWAYS_CAN : canPlace;
    }

    public ItemPluggableSimple(PluggableDefinition definition, @Nullable IPlacementPredicate canPlace, Item.Properties p) {
        this(definition, definition.creator, canPlace, p);
    }

    public ItemPluggableSimple(PluggableDefinition definition, @Nonnull IPluggableCreator creator, Item.Properties p) {
        this(definition, creator, null, p);
    }

    public ItemPluggableSimple(PluggableDefinition definition, Item.Properties p) {
        this(definition, definition.creator, null, p);
    }

    public PipePluggable onPlace(@Nonnull ItemStack stack, IPipeHolder holder, Direction side, Player player,
        InteractionHand hand) {
        if (!canPlace.canPlace(stack, holder, side)) {
            return PipePluggable.EMPTY;
        }
        SoundUtil.playBlockPlace(holder.getPipeWorld(), holder.getPipePos());
        return creator.createSimplePluggable(definition, holder, side);
    }

    @FunctionalInterface
    public interface IPlacementPredicate {
        boolean canPlace(ItemStack stack, IPipeHolder holder, Direction side);
    }
}
