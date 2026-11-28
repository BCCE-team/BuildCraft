package buildcraft.transport.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import buildcraft.lib.net.MessageManager;
import buildcraft.transport.net.MessageMultiPipeItem.TravellingItemData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

public class PipeItemMessageQueue {

    private static final Map<LevelChunk, List<MessageMultiPipeItem>> cachedPlayerPackets = new WeakHashMap<>();

    
    public static void serverTick() {
        for (Entry<LevelChunk, List<MessageMultiPipeItem>> entry : cachedPlayerPackets.entrySet()) {
        	LevelChunk chunk = entry.getKey();
        	for(MessageMultiPipeItem msg : entry.getValue()) {
        		MessageManager.sendToAllWatching(msg, chunk);
        	}
        }
        cachedPlayerPackets.clear();
    }

    public static void appendTravellingItem(Level world, BlockPos pos, int stackId, int stackCount, boolean toCenter,
        Direction side, @Nullable DyeColor colour, int timeToDest, boolean pausedAtCenter) {
        appendTracked(world, pos, new TravellingItemData(stackId, stackCount, toCenter,
            side, colour, timeToDest, pausedAtCenter));
    }

    public static void appendTracked(Level world, BlockPos pos, TravellingItemData data) {
        if (!(world instanceof ServerLevel server)) return;
        List<MessageMultiPipeItem> messages = cachedPlayerPackets.computeIfAbsent(
            server.getChunkAt(pos), ignored -> new ArrayList<>());
        MessageMultiPipeItem current = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        if (current == null || !current.append(pos, data)) {
            MessageMultiPipeItem next = new MessageMultiPipeItem();
            if (next.append(pos, data)) messages.add(next);
        }
    }
}
