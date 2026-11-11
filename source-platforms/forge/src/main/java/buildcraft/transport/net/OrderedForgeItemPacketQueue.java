/* Copyright (c) the BuildCraft team. SPDX-License-Identifier: MPL-2.0 */
package buildcraft.transport.net;

import java.util.ArrayList;
import java.util.List;

import buildcraft.lib.net.MessageManager;
import buildcraft.transport.BCTransport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Server-only, causally ordered Forge item-pipe packet dispatch.
 *
 * <p>The loader-neutral PipeItemMessageQueue groups entries in a WeakHashMap
 * keyed by chunk. Iterating that map changes the relative send order of packets
 * from different chunks. A single fast cargo crossing the shared border can
 * therefore have its next visual movement delivered before its previous one.
 * The legacy client chains movements by visualTransitId in receive order, so
 * the resulting timeline visibly jumps or pauses around those borders.</p>
 *
 * <p>Keep the original event insertion order across chunks while retaining
 * packet batching for <em>consecutive</em> events within the same chunk.
 * Do not regroup a later event for a previously visited block position:
 * MessageMultiPipeItem stores entries by BlockPos, so doing that would also
 * reorder A, B, A into A, A, B during packet serialization.</p>
 *
 * <p>The message codec and chunk-watching checks are unchanged. Only the
 * two Forge old targets use this dispatcher; NeoForge keeps its own queue.</p>
 */
@Mod.EventBusSubscriber(modid = BCTransport.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class OrderedForgeItemPacketQueue {
    private static final List<Batch> QUEUED = new ArrayList<>();

    private OrderedForgeItemPacketQueue() { }

    private static final class Batch {
        final LevelChunk chunk;
        final MessageMultiPipeItem message = new MessageMultiPipeItem();
        BlockPos lastPosition;

        Batch(LevelChunk chunk) {
            this.chunk = chunk;
        }

        boolean append(BlockPos pos, MessageMultiPipeItem.TravellingItemData data) {
            if (!pos.equals(lastPosition) && message.items.containsKey(pos)) {
                return false;
            }
            if (!message.append(pos, data)) return false;
            lastPosition = pos;
            return true;
        }
    }

    /** Called on the authoritative server thread by any packet source. */
    public static void append(Level world, BlockPos pos, MessageMultiPipeItem.TravellingItemData data) {
        if (!(world instanceof ServerLevel server)) return;
        LevelChunk chunk = server.getChunkAt(pos);
        Batch last = QUEUED.isEmpty() ? null : QUEUED.get(QUEUED.size() - 1);
        if (last == null || last.chunk != chunk || !last.append(pos, data)) {
            Batch next = new Batch(chunk);
            if (!next.append(pos, data)) return;
            QUEUED.add(next);
        }
    }

    /** Compatibility for the original eight-argument chunk queue. */
    public static void append(Level world, BlockPos pos, int stackId, int stackCount,
        boolean toCenter, Direction side, DyeColor colour, int timeToDest, boolean pausedAtCenter) {
        append(world, pos, new MessageMultiPipeItem.TravellingItemData(stackId, stackCount,
            toCenter, side, colour, timeToDest, pausedAtCenter));
    }

    /** Deliver every batch in the same global order as the server's events. */
    public static void flush() {
        if (QUEUED.isEmpty()) return;
        List<Batch> pending = new ArrayList<>(QUEUED);
        QUEUED.clear();
        for (Batch batch : pending) {
            MessageManager.sendToAllWatching(batch.message, batch.chunk);
        }
    }

    /** Registered on the FORGE bus on both 1.19.2 and 1.20.1. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) flush();
    }
}
