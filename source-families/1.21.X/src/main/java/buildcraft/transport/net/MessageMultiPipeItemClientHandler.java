/*
 * Client-only handler extracted from MessageMultiPipeItem for dist-safety.
 */
package buildcraft.transport.net;

import java.util.List;
import java.util.Map.Entry;
import java.util.function.Supplier;

import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pipe.PipeFlow;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.pipe.flow.PipeFlowItems;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import buildcraft.lib.net.BCPacketContext;
public class MessageMultiPipeItemClientHandler {
    public static void handle(MessageMultiPipeItem message, Supplier<BCPacketContext> ctx) {
    	ctx.get().enqueueWork(() -> {       
    		Level world = Minecraft.getInstance().level;
                if (world == null) {
                    return;
                }
                for (Entry<BlockPos, List<MessageMultiPipeItem.TravellingItemData>> entry : message.items.entrySet()) {
                    BlockPos pos = entry.getKey();
                    BlockEntity tile = world.getBlockEntity(pos);
                    if (tile instanceof IPipeHolder) {
                        IPipe pipe = ((IPipeHolder) tile).getPipe();
                        if (pipe == Pipe.EMPTY) {
                            continue;
                        }
                        PipeFlow flow = pipe.getFlow();
                        if (flow instanceof PipeFlowItems) {
                            ((PipeFlowItems) flow).handleClientReceviedItems(entry.getValue());
                        }
                    }
                }
    		});
    	
        
    }
}
