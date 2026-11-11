package buildcraft.transport.net;

import net.neoforged.fml.loading.FMLEnvironment;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import buildcraft.lib.misc.MessageUtil;
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.transport.client.ClientItemSegmentTimeline;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import buildcraft.lib.net.BCPacketContext;

public class MessageMultiPipeItem {

    /** Direct stack + authoritative timestamps. Legacy addon overload remains available. */
    public static void appendTracked(Level level, BlockPos pos, ItemStack stack, int stackCount,
        boolean toCenter, Direction side, DyeColor colour, int timeToDest,
        boolean pausedAtCenter, long visualTransitId, long startTick) {
        if (stack == null || stack.isEmpty()) return;
        OrderedNeoForgeItemPacketQueue.append(level, pos,
            new TravellingItemData(-1, stack, stackCount, toCenter, side,
                colour, timeToDest, pausedAtCenter, visualTransitId, startTick, level.getGameTime()));
    }

    /** Older integrations can still supply cached ItemStack identifiers. */
    @Deprecated
    public static void appendTracked(Level level, BlockPos pos, int stackId, int stackCount,
        boolean toCenter, Direction side, DyeColor colour, int timeToDest,
        boolean pausedAtCenter, long visualTransitId) {
        OrderedNeoForgeItemPacketQueue.append(level, pos,
            new TravellingItemData(stackId, ItemStack.EMPTY, stackCount, toCenter, side,
                colour, timeToDest, pausedAtCenter, visualTransitId,
                ClientItemSegmentTimeline.NO_SERVER_TICK,
                ClientItemSegmentTimeline.NO_SERVER_TICK));
    }

    private static final int MAX_ITEMS_PER_PIPE = 10;
    private static final int MAX_POSITIONS = 4000;
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
        return append(pos, new TravellingItemData(stackId, ItemStack.EMPTY, stackCount,
            toCenter, side, colour, timeToDest, pausedAtCenter, 0L,
            ClientItemSegmentTimeline.NO_SERVER_TICK,
            ClientItemSegmentTimeline.NO_SERVER_TICK));
    }

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
        public final int stackId;
        public final ItemStack stack;
        public final int stackCount;
        public final boolean toCenter;
        public final Direction side;
        public final @Nullable DyeColor colour;
        public final int timeToDest;
        public final boolean pausedAtCenter;
        public final long visualTransitId;
        public final long serverStartTick;
        public final long serverSentTick;

        public TravellingItemData(int stackId, int stackCount, boolean toCenter, Direction side,
            DyeColor colour, int timeToDest, boolean pausedAtCenter) {
            this(stackId, ItemStack.EMPTY, stackCount, toCenter, side, colour,
                timeToDest, pausedAtCenter, 0L,
                ClientItemSegmentTimeline.NO_SERVER_TICK,
                ClientItemSegmentTimeline.NO_SERVER_TICK);
        }

        public TravellingItemData(int stackId, ItemStack stack, int stackCount,
            boolean toCenter, Direction side, DyeColor colour, int timeToDest,
            boolean pausedAtCenter, long visualTransitId, long serverStartTick, long serverSentTick) {
            this.stackId = stackId;
            this.stack = stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
            this.stackCount = stackCount;
            this.toCenter = toCenter;
            this.side = side;
            this.colour = colour;
            this.timeToDest = timeToDest;
            this.pausedAtCenter = pausedAtCenter;
            this.visualTransitId = visualTransitId;
            this.serverStartTick = serverStartTick;
            this.serverSentTick = serverSentTick;
        }

        TravellingItemData(FriendlyByteBuf buf) {
            if (buf.readBoolean()) {
                stack = ItemStackUtil.readOptional(buf);
                if (stack.isEmpty()) throw new DecoderException("Empty inline pipe cargo");
                stackId = -1;
            } else {
                stack = ItemStack.EMPTY;
                stackId = buf.readVarInt();
                if (stackId < 0) throw new DecoderException("Negative legacy stack ID");
            }
            stackCount = buf.readVarInt();
            if (stackCount <= 0 || stackCount > 1_000_000)
                throw new DecoderException("Invalid travelling item stack count: " + stackCount);
            toCenter = buf.readBoolean();
            side = buf.readEnum(Direction.class);
            colour = MessageUtil.readEnumOrNull(buf, DyeColor.class);
            timeToDest = buf.readVarInt();
            if (timeToDest < 0 || timeToDest > 1_200)
                throw new DecoderException("Invalid travelling item travel time: " + timeToDest);
            pausedAtCenter = buf.readBoolean();
            visualTransitId = buf.readVarLong();
            if (visualTransitId < 0) throw new DecoderException("Invalid visual cargo ID");
            if (buf.readBoolean()) {
                serverSentTick = buf.readVarLong();
                int offset = buf.readVarInt();
                if (serverSentTick < 0 || serverSentTick >= Long.MAX_VALUE - 1_200L
                    || offset < -2_400 || offset > 1_200)
                    throw new DecoderException("Invalid movement timestamps");
                serverStartTick = serverSentTick + offset;
            } else {
                serverSentTick = ClientItemSegmentTimeline.NO_SERVER_TICK;
                serverStartTick = ClientItemSegmentTimeline.NO_SERVER_TICK;
            }
        }

        void toBuffer(FriendlyByteBuf buf) {
            boolean inline = !stack.isEmpty();
            buf.writeBoolean(inline);
            if (inline) ItemStackUtil.writeOptional(buf, stack);
            else buf.writeVarInt(stackId);
            buf.writeVarInt(stackCount);
            buf.writeBoolean(toCenter);
            buf.writeEnum(side);
            MessageUtil.writeEnumOrNull(buf, colour);
            buf.writeVarInt(Math.max(0, Math.min(1_200, timeToDest)));
            buf.writeBoolean(pausedAtCenter);
            buf.writeVarLong(visualTransitId);
            boolean hasServerTime = serverStartTick != ClientItemSegmentTimeline.NO_SERVER_TICK
                && serverSentTick != ClientItemSegmentTimeline.NO_SERVER_TICK;
            buf.writeBoolean(hasServerTime);
            if (hasServerTime) {
                buf.writeVarLong(serverSentTick);
                buf.writeVarInt((int) Math.max(-2_400L,
                    Math.min(1_200L, serverStartTick - serverSentTick)));
            }
        }
    }

    public static final BiConsumer<MessageMultiPipeItem, Supplier<BCPacketContext>> HANDLER = (message, ctx) -> {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientAccess.handle(message, ctx);
        }
    };

    @OnlyIn(Dist.CLIENT)
    private static final class ClientAccess {
        private static void handle(MessageMultiPipeItem message, Supplier<BCPacketContext> ctx) {
            MessageMultiPipeItemClientHandler.handle(message, ctx);
        }
    }
}
