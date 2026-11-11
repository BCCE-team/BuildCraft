/* Copyright (c) the BuildCraft team. SPDX-License-Identifier: MPL-2.0 */
package buildcraft.transport.net;

import java.util.ArrayList;
import java.util.List;
import buildcraft.lib.net.MessageManager;
import buildcraft.transport.BCTransport;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Sends movement segments in server creation order across chunks and positions. */
@EventBusSubscriber(modid = BCTransport.MODID)
public final class OrderedNeoForgeItemPacketQueue {
    private static final List<Batch> QUEUED = new ArrayList<>();
    private OrderedNeoForgeItemPacketQueue() { }

    private static final class Batch {
        final LevelChunk chunk;
        final MessageMultiPipeItem message = new MessageMultiPipeItem();
        BlockPos lastPosition;

        Batch(LevelChunk chunk) { this.chunk = chunk; }
        boolean append(BlockPos pos, MessageMultiPipeItem.TravellingItemData data) {
            // Do not let LinkedHashMap group A,B,A as A,A,B.
            if (!pos.equals(lastPosition) && message.items.containsKey(pos)) return false;
            if (!message.append(pos, data)) return false;
            lastPosition = pos;
            return true;
        }
    }

    public static void append(Level level, BlockPos pos, MessageMultiPipeItem.TravellingItemData data) {
        if (!(level instanceof ServerLevel server)) return;
        LevelChunk chunk = server.getChunkAt(pos);
        Batch last = QUEUED.isEmpty() ? null : QUEUED.get(QUEUED.size() - 1);
        if (last == null || last.chunk != chunk || !last.append(pos, data)) {
            Batch batch = new Batch(chunk);
            if (batch.append(pos, data)) QUEUED.add(batch);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (QUEUED.isEmpty()) return;
        List<Batch> pending = new ArrayList<>(QUEUED);
        QUEUED.clear();
        for (Batch batch : pending) MessageManager.sendToAllWatching(batch.message, batch.chunk);
    }
}
