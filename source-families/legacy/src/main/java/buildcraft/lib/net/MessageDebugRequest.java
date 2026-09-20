/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.net;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.lib.item.ItemDebugger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import buildcraft.lib.net.BCPacketContext;

public class MessageDebugRequest {
	private static final double MAX_INTERACTION_DISTANCE_SQR = 64.0D;
	private BlockPos pos;
	private Direction side;

	@SuppressWarnings("unused")
	public MessageDebugRequest() {
	}

	public MessageDebugRequest(BlockPos pos, Direction side) {
		this.pos = pos;
		this.side = side;
	}

	public static void toBytes(MessageDebugRequest msg, FriendlyByteBuf buffer) {
		buffer.writeBlockPos(msg.pos);
		buffer.writeEnum(msg.side);
	}

	public MessageDebugRequest(FriendlyByteBuf buffer) {
		pos = buffer.readBlockPos();
		side = buffer.readEnum(Direction.class);
	}

	public static final BiConsumer<MessageDebugRequest, Supplier<BCPacketContext>> HANDLER = (message, ctx) -> {
		ctx.get().enqueueWork(() -> {
			ServerPlayer player = ctx.get().getSender();
			if(player == null||ctx.get().side()!=BCNetworkSide.SERVER)
				return;
			if (!ItemDebugger.isShowDebugInfo(player)) { 
				MessageManager.sendTo(new MessageDebugResponse(), player);
				return;
			}
            // Debug packets are never a remote-inspection API: the sender must be near an already-loaded tile.
            //? if <1.20 {
            if (!player.level.hasChunkAt(message.pos)
                || player.distanceToSqr(message.pos.getX() + 0.5D, message.pos.getY() + 0.5D, message.pos.getZ() + 0.5D)
                    > MAX_INTERACTION_DISTANCE_SQR) return;
            BlockEntity tile = player.level.getBlockEntity(message.pos);
            //?} else {
            /*?
            if (!player.level().hasChunkAt(message.pos)
                || player.distanceToSqr(message.pos.getX() + 0.5D, message.pos.getY() + 0.5D, message.pos.getZ() + 0.5D)
                    > MAX_INTERACTION_DISTANCE_SQR) return;
            BlockEntity tile = player.level().getBlockEntity(message.pos);
            ?*/
            //?}
			if (tile instanceof IDebuggable) {
				List<String> left = new ArrayList<>();
				List<String> right = new ArrayList<>();
				((IDebuggable) tile).getDebugInfo(left, right, message.side);
				MessageManager.sendTo(new MessageDebugResponse(left, right), player);
			}
		});
		ctx.get().setPacketHandled(true);
	};
}
