package buildcraft.transport.net;

import net.neoforged.fml.loading.FMLEnvironment;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.HashMap;
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
import buildcraft.lib.net.BCPacketContext;

public class MessageMultiPipeItem {

    private static final int MAX_ITEMS_PER_PIPE = 10;
    private static final int MAX_POSITIONS = 4000;
    public final Map<BlockPos, List<TravellingItemData>> items = new HashMap<>();

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
        DyeColor colour, int timeToDest) {
        List<TravellingItemData> list = items.get(pos);
        if (list == null) {
            if (items.size() >= MAX_POSITIONS) {
                return false;
            }
            list = new ArrayList<>();
            items.put(pos, list);
        }
        if (list.size() >= MAX_ITEMS_PER_PIPE) {
            return false;
        }
        list.add(new TravellingItemData(stackId, stackCount, toCenter, side, colour, timeToDest));
        return true;
    }

    public static class TravellingItemData {
        public final int stackId;
        public final int stackCount;
        public final boolean toCenter;
        public final Direction side;
        public final @Nullable DyeColor colour;
        public final int timeToDest;

        public TravellingItemData(int stackId, int stackCount, boolean toCenter, Direction side, DyeColor colour,
            int timeToDest) {
            this.stackId = stackId;
            this.stackCount = stackCount;
            this.toCenter = toCenter;
            this.side = side;
            this.colour = colour;
            this.timeToDest = timeToDest;
        }

        TravellingItemData(FriendlyByteBuf buf) {
            stackId = buf.readVarInt();
            stackCount = buf.readVarInt();
            if (stackCount <= 0 || stackCount > 1_000_000) {
                throw new DecoderException("Invalid travelling item stack count: " + stackCount);
            }
            toCenter = buf.readBoolean();
            side = buf.readEnum(Direction.class);
            colour = MessageUtil.readEnumOrNull(buf, DyeColor.class);
            timeToDest = buf.readVarInt();
            if (timeToDest < 0 || timeToDest > 1_000_000) {
                throw new DecoderException("Invalid travelling item travel time: " + timeToDest);
            }
        }

        void toBuffer(FriendlyByteBuf buf) {
            buf.writeVarInt(stackId);
            buf.writeVarInt(stackCount);
            buf.writeBoolean(toCenter);
            buf.writeEnum(side);
            MessageUtil.writeEnumOrNull(buf, colour);
            buf.writeVarInt(timeToDest);
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
