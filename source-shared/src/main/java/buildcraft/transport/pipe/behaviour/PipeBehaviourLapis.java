/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.pipe.behaviour;

import java.io.IOException;
import java.util.Collections;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.IPipeHolder.PipeMessageReceiver;
import buildcraft.transport.internal.pipe.PipeBehaviour;
import buildcraft.transport.internal.pipe.PipeEventActionActivate;
import buildcraft.transport.internal.pipe.PipeEventHandler;
import buildcraft.transport.internal.pipe.PipeEventItem;
import buildcraft.transport.internal.pipe.PipeEventStatement;
import buildcraft.lib.misc.EntityUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.transport.BCTransportStatements;
import buildcraft.transport.statements.ActionPipeColor;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class PipeBehaviourLapis extends PipeBehaviour {
    private DyeColor colour = DyeColor.WHITE;

    public PipeBehaviourLapis(IPipe pipe) {
        super(pipe);
    }

    public PipeBehaviourLapis(IPipe pipe, CompoundTag nbt) {
        super(pipe, nbt);
        colour = NBTUtilBC.readEnum(nbt.get("colour"), DyeColor.class);
        if (colour == null) {
            colour = DyeColor.WHITE;
        }
    }

    @Override
    public CompoundTag writeToNbt() {
        CompoundTag nbt = super.writeToNbt();
        nbt.put("colour", NBTUtilBC.writeEnum(colour));
        return nbt;
    }

    @Override
    public void writePayload(FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(buffer, side);
        if (side == BCNetworkSide.SERVER) {
            buffer.writeByte(colour.getId());
        }
    }

    @Override
    public void readPayload(FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT) {
            colour = DyeColor.byId(buffer.readUnsignedByte());
        }
    }

    @Override
    public int getTextureIndex(Direction face) {
        return colour.getId();
    }

    @Override
    public boolean onPipeActivate(Player player, BlockHitResult trace, Level level, EnumPipePart part) {
        if (level.isClientSide()) {
            return EntityUtil.getWrenchHand(player) != null;
        }
        if (EntityUtil.getWrenchHand(player) != null) {
            EntityUtil.activateWrench(player, trace);
            int n = colour.getId() + (player.isSteppingCarefully() ? 15 : 1);
            colour = DyeColor.byId(n & 15);
            pipe.getHolder().scheduleNetworkUpdate(PipeMessageReceiver.BEHAVIOUR);
            return true;
        }
        return false;
    }

    @PipeEventHandler
    public void onReachCenter(PipeEventItem.ReachCenter reachCenter) {
        reachCenter.colour = colour;
    }

    @PipeEventHandler
    public static void addActions(PipeEventStatement.AddActionInternal event) {
        Collections.addAll(event.actions, BCTransportStatements.ACTION_PIPE_COLOUR);
    }

    @PipeEventHandler
    public void onActionActivated(PipeEventActionActivate event) {
        if (event.action instanceof ActionPipeColor) {
            ActionPipeColor action = ((ActionPipeColor) event.action);
            if (this.colour != action.color) {
                this.colour = action.color;
                pipe.getHolder().scheduleNetworkUpdate(PipeMessageReceiver.BEHAVIOUR);
            }
        }
    }
}
