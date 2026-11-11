package buildcraft.transport.net;

import net.minecraftforge.fml.DistExecutor;

import net.minecraftforge.api.distmarker.Dist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import buildcraft.lib.misc.MessageUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import buildcraft.lib.net.BCPacketContext;

public class MessageMultiPipeItem {

    /**
     * A self-contained movement notification: cargo, its identity, actual
     * server start tick and server send tick travel together in one packet.
     * No ItemStack cache round trip and no thread-local packet state.
     */
    public static void appendTracked(Level world, BlockPos pos, ItemStack stack, int stackCount,
        boolean toCenter, Direction side, DyeColor colour, int timeToDest,
        boolean pausedAtCenter, long visualTransitId, long serverStartTick) {
        if (stack == null || stack.isEmpty()) return;
        long serverSentTick = world.getGameTime();
        OrderedForgeItemPacketQueue.append(world, pos,
            new TravellingItemData(-1, stack, stackCount, toCenter, side, colour,
                timeToDest, pausedAtCenter, visualTransitId, serverStartTick, serverSentTick));
    }

    /** Existing source-compatible overload, used by third-party integrations. */
    public static void appendTracked(Level world, BlockPos pos, ItemStack stack, int stackCount,
        boolean toCenter, Direction side, DyeColor colour, int timeToDest,
        boolean pausedAtCenter, long visualTransitId) {
        if (stack == null || stack.isEmpty()) return;
        OrderedForgeItemPacketQueue.append(world, pos,
            new TravellingItemData(-1, stack, stackCount, toCenter, side, colour,
                timeToDest, pausedAtCenter, visualTransitId));
    }

    /** Cache-ID fallback for addons compiled against the earlier queue API. */
    @Deprecated
    public static void appendTracked(Level world, BlockPos pos, int stackId, int stackCount,
        boolean toCenter, Direction side, DyeColor colour, int timeToDest,
        boolean pausedAtCenter, long visualTransitId) {
        OrderedForgeItemPacketQueue.append(world, pos,
            new TravellingItemData(stackId, stackCount, toCenter, side, colour,
                timeToDest, pausedAtCenter, visualTransitId));
    }

    private static final int MAX_ITEMS_PER_PIPE = 10;
    private static final int MAX_POSITIONS = 4000;
    // Preserve the server's block-to-block announcement order within a batch.
    // HashMap iteration can reorder consecutive movements of the same cargo
    // at a pipe boundary, particularly during short gold-pipe segments.
    public final Map<BlockPos, List<TravellingItemData>> items = new LinkedHashMap<>();

    public MessageMultiPipeItem() {

    }

    public MessageMultiPipeItem(FriendlyByteBuf buf) {
        int blockCount = buf.readUnsignedShort();
        if (blockCount > MAX_POSITIONS) {
            throw new DecoderException("Invalid pipe item position count: " + blockCount);
        }
        for (int b = 0; b < blockCount; b++) {
            BlockPos pos = buf.readBlockPos();
            List<TravellingItemData> posItems = new ArrayList<>();
            items.put(pos, posItems);
            int itemCount = buf.readUnsignedByte();
            if (itemCount > MAX_ITEMS_PER_PIPE) {
                throw new DecoderException("Invalid pipe item count: " + itemCount);
            }
            for (int i = 0; i < itemCount; i++) {
                posItems.add(new TravellingItemData(buf));
            }
        }
    }

    public static void toBytes(MessageMultiPipeItem msg, FriendlyByteBuf buf) {
        int blockCount = Math.min(msg.items.size(), MAX_POSITIONS);
        buf.writeShort(blockCount);
        int blockIndex = 0;
        for (Entry<BlockPos, List<TravellingItemData>> entry : msg.items.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            List<TravellingItemData> list = entry.getValue();
            int itemCount = Math.min(list.size(), MAX_ITEMS_PER_PIPE);
            buf.writeByte(itemCount);
            for (int i = 0; i < itemCount; i++) {
                list.get(i).toBuffer(buf);
            }
            if (++blockIndex >= blockCount) {
                break;
            }
        }
    }

    public boolean append(BlockPos pos, int stackId, int stackCount, boolean toCenter, Direction side,
        DyeColor colour, int timeToDest, boolean pausedAtCenter) {
        return append(pos, new TravellingItemData(stackId, stackCount, toCenter, side, colour,
            timeToDest, pausedAtCenter));
    }

    /** Appends one complete event while preserving the existing packet limits. */
    public boolean append(BlockPos pos, TravellingItemData data) {
        List<TravellingItemData> list = items.get(pos);
        if (list == null) {
            if (items.size() >= MAX_POSITIONS) return false;
            list = new ArrayList<>();
            items.put(pos, list);
        }
        if (list.size() >= MAX_ITEMS_PER_PIPE) return false;
        list.add(data);
        return true;
    }

    public static class TravellingItemData {
        /** Full stack snapshot, or EMPTY only for legacy addon cache-ID packets. */
        public final ItemStack stack;
        public final int stackId;
        public final int stackCount;
        public final boolean toCenter;
        public final Direction side;
        public final @Nullable DyeColor colour;
        public final int timeToDest;
        public final boolean pausedAtCenter;
        public final long visualTransitId;
        /** Actual time on the authoritative server clock, not packet receipt time. */
        public final long serverStartTick;
        public final long serverSentTick;

        /** Preserve the pre-BCU addon entry point; the old cache-ID fallback remains. */
        public TravellingItemData(int stackId, int stackCount, boolean toCenter, Direction side, DyeColor colour,
            int timeToDest, boolean pausedAtCenter) {
            this(stackId, ItemStack.EMPTY, stackCount, toCenter, side, colour, timeToDest, pausedAtCenter, 0L);
        }

        public TravellingItemData(int stackId, int stackCount, boolean toCenter, Direction side, DyeColor colour,
            int timeToDest, boolean pausedAtCenter, long visualTransitId) {
            this(stackId, ItemStack.EMPTY, stackCount, toCenter, side, colour, timeToDest, pausedAtCenter,
                visualTransitId);
        }

        public TravellingItemData(int stackId, ItemStack stack, int stackCount, boolean toCenter, Direction side,
            DyeColor colour, int timeToDest, boolean pausedAtCenter, long visualTransitId) {
            this(stackId, stack, stackCount, toCenter, side, colour, timeToDest,
                pausedAtCenter, visualTransitId,
                buildcraft.transport.client.ClientItemSegmentTimeline.NO_SERVER_TICK,
                buildcraft.transport.client.ClientItemSegmentTimeline.NO_SERVER_TICK);
        }

        public TravellingItemData(int stackId, ItemStack stack, int stackCount, boolean toCenter, Direction side,
            DyeColor colour, int timeToDest, boolean pausedAtCenter, long visualTransitId,
            long serverStartTick, long serverSentTick) {
            this.serverStartTick = serverStartTick;
            this.serverSentTick = serverSentTick;
            this.stackId = stackId;
            this.stack = stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
            this.stackCount = stackCount;
            this.toCenter = toCenter;
            this.side = side;
            this.colour = colour;
            this.timeToDest = timeToDest;
            this.pausedAtCenter = pausedAtCenter;
            this.visualTransitId = visualTransitId;
        }

        TravellingItemData(FriendlyByteBuf buf) {
            // The new wire format deliberately separates BCU-style inline stacks
            // from the compatibility fallback for older addon queue callers.
            if (buf.readBoolean()) {
                stack = buf.readItem();
                if (stack.isEmpty()) {
                    throw new DecoderException("Empty inline pipe cargo");
                }
                stackId = -1;
            } else {
                stack = ItemStack.EMPTY;
                stackId = buf.readVarInt();
                if (stackId < 0) {
                    throw new DecoderException("Invalid legacy pipe stack ID: " + stackId);
                }
            }
            stackCount = buf.readVarInt();
            if (stackCount <= 0 || stackCount > 1_000_000) {
                throw new DecoderException("Invalid travelling item stack count: " + stackCount);
            }
            toCenter = buf.readBoolean();
            side = buf.readEnum(Direction.class);
            colour = MessageUtil.readEnumOrNull(buf, DyeColor.class);
            timeToDest = buf.readVarInt();
            if (timeToDest < 0 || timeToDest > 1_200) {
                throw new DecoderException("Invalid travelling item travel time: " + timeToDest);
            }
            pausedAtCenter = buf.readBoolean();
            visualTransitId = buf.readVarLong();
            if (visualTransitId < 0L) {
                throw new DecoderException("Invalid visual cargo ID: " + visualTransitId);
            }
            if (buf.readBoolean()) {
                serverSentTick = buf.readVarLong();
                int startOffset = buf.readVarInt();
                // Most movements start on their send tick; encoding that small
                // delta instead of two fixed longs reduces bandwidth on quarries.
                if (serverSentTick < 0L || serverSentTick > Long.MAX_VALUE - 1_200L
                    || startOffset < -2_400 || startOffset > 1_200) {
                    throw new DecoderException("Invalid pipe server movement timestamp");
                }
                serverStartTick = serverSentTick + startOffset;
            } else {
                serverStartTick = buildcraft.transport.client.ClientItemSegmentTimeline.NO_SERVER_TICK;
                serverSentTick = buildcraft.transport.client.ClientItemSegmentTimeline.NO_SERVER_TICK;
            }
        }

        void toBuffer(FriendlyByteBuf buf) {
            boolean inline = !stack.isEmpty();
            buf.writeBoolean(inline);
            if (inline) buf.writeItem(stack);
            else buf.writeVarInt(stackId);
            buf.writeVarInt(stackCount);
            buf.writeBoolean(toCenter);
            buf.writeEnum(side);
            MessageUtil.writeEnumOrNull(buf, colour);
            buf.writeVarInt(Math.max(0, Math.min(1_200, timeToDest)));
            buf.writeBoolean(pausedAtCenter);
            buf.writeVarLong(visualTransitId);
            boolean hasServerTime = serverStartTick
                != buildcraft.transport.client.ClientItemSegmentTimeline.NO_SERVER_TICK
                && serverSentTick != buildcraft.transport.client.ClientItemSegmentTimeline.NO_SERVER_TICK;
            buf.writeBoolean(hasServerTime);
            if (hasServerTime) {
                buf.writeVarLong(serverSentTick);
                buf.writeVarInt((int) Math.max(-2_400L,
                    Math.min(1_200L, serverStartTick - serverSentTick)));
            }
        }
    }

    public static final BiConsumer<MessageMultiPipeItem, Supplier<BCPacketContext>> HANDLER = (message, ctx) -> {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MessageMultiPipeItemClientHandler.handle(message, ctx));
    };
}
