/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.robotics.zone;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

import buildcraft.lib.misc.MessageUtil;
import buildcraft.robotics.container.ContainerZonePlanner;
import buildcraft.robotics.tile.TileZonePlanner;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import buildcraft.lib.net.BCPacketContext;

public class MessageZoneMapRequest {
    private static final int MAX_CHUNK_DISTANCE = Math.max(1, TileZonePlanner.RESOLUTION / 16);

    private ZonePlannerMapChunkKey key;

    @SuppressWarnings("unused")
    public MessageZoneMapRequest() {
    }

    public MessageZoneMapRequest(ZonePlannerMapChunkKey key) {
        this.key = key;
    }

    public MessageZoneMapRequest(FriendlyByteBuf buf) {
        key = new ZonePlannerMapChunkKey(buf);
    }

    public void toBytes(FriendlyByteBuf buf) {
        key.toBytes(buf);
    }

    public static final BiConsumer<MessageZoneMapRequest, Supplier<BCPacketContext>> HANDLER = (message, ctx) -> {
        BCPacketContext context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.containerMenu instanceof ContainerZonePlanner menu)
                    || menu.tile == null || !menu.stillValid(player)) {
                return;
            }

            //? if <1.20 {
            if (message.key.dimensionalId != player.level.dimension().location().hashCode()) {
            //?} else {
            /*?
            if (message.key.dimensionalId != player.level().dimension().location().hashCode()) {
            ?*/
            //?}
                return;
            }

            int expectedLevel = Math.max(0,
                    player.blockPosition().getY() / ZonePlannerMapChunkKey.LEVEL_HEIGHT);
            if (message.key.level != expectedLevel) {
                return;
            }

            ChunkPos plannerChunk = new ChunkPos(menu.tile.getBlockPos());
            if (Math.abs(message.key.chunkPos.x - plannerChunk.x) > MAX_CHUNK_DISTANCE
                    || Math.abs(message.key.chunkPos.z - plannerChunk.z) > MAX_CHUNK_DISTANCE) {
                return;
            }

            ZonePlannerMapChunk mapChunk;
            //? if <1.20 {
            if (player.level.getChunkSource().getChunkNow(
            //?} else {
            /*?
            if (player.level().getChunkSource().getChunkNow(
            ?*/
            //?}
                    message.key.chunkPos.x, message.key.chunkPos.z) == null) {
                mapChunk = new ZonePlannerMapChunk();
            } else {
                //? if <1.20 {
                mapChunk = ZonePlannerMapDataServer.INSTANCE.getChunk(player.level, message.key);
                //?} else {
                /*?
                mapChunk = ZonePlannerMapDataServer.INSTANCE.getChunk(player.level(), message.key);
                ?*/
                //?}
                if (mapChunk == null) {
                    mapChunk = new ZonePlannerMapChunk();
                }
            }

            MessageUtil.sendReturnMessage(
                    context,
                    new MessageZoneMapResponse(message.key, mapChunk)
            );
        });
        context.setPacketHandled(true);
    };
}
