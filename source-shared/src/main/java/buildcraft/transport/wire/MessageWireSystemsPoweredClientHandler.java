/*
 * Client-only handler extracted from MessageWireSystemsPowered for dist-safety.
 */
package buildcraft.transport.wire;

import java.util.Map;
import java.util.function.Supplier;

import buildcraft.transport.wire.WireManager;
import buildcraft.transport.internal.pipe.IPipeHolder;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import buildcraft.lib.net.BCPacketContext;
public class MessageWireSystemsPoweredClientHandler {
    public static void handle(MessageWireSystemsPowered message, Supplier<BCPacketContext> ctx) {
        ctx.get().enqueueWork(() -> {
            Level level = Minecraft.getInstance().level;
            if (level == null) {
                return;
            }
            for (Map.Entry<Integer, Boolean> hashPowered : message.hashesPowered.entrySet()) {
                WireSystem system = ClientWireSystems.INSTANCE.wireSystems.get(hashPowered.getKey());
                if (system == null) {
                    continue;
                }
                boolean powered = hashPowered.getValue();
                for (WireSystem.WireElement element : system.elements) {
                    if (element.type != WireSystem.WireElement.Type.WIRE_PART) {
                        continue;
                    }
                    BlockEntity tile = level.getBlockEntity(element.blockPos);
                    if (tile instanceof IPipeHolder) {
                        IPipeHolder holder = (IPipeHolder) tile;
                        WireManager wireManager = holder.getWireManager();
                        if (wireManager.getColorOfPart(element.wirePart) != null) {
                            if (powered) {
                                wireManager.poweredClient.add(element.wirePart);
                            } else {
                                wireManager.poweredClient.remove(element.wirePart);
                            }
                        }
                    }
                }
            }
        });
    }
}
