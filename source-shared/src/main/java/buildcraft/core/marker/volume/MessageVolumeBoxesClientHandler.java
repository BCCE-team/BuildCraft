/*
 * Client-only handler extracted from MessageVolumeBoxes so the dedicated server
 * never verifies/loads client classes (RuntimeDistCleaner dist-safety).
 */
package buildcraft.core.marker.volume;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import buildcraft.lib.internal.debug.BCLog;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import buildcraft.lib.net.BCPacketContext;
public class MessageVolumeBoxesClientHandler {
    private record DecodedVolumeBox(byte[] payload, VolumeBox box) {}

    public static void handle(MessageVolumeBoxes message, Supplier<BCPacketContext> ctx) {
        BCPacketContext context = ctx.get();
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                return;
            }
            Map<UUID, DecodedVolumeBox> updates = new LinkedHashMap<>();
            for (byte[] payload : message.buffers) {
                FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
                try {
                    VolumeBox volumeBox = new VolumeBox(mc.level, buffer);
                    updates.put(volumeBox.id, new DecodedVolumeBox(payload, volumeBox));
                } catch (IOException | RuntimeException e) {
                    BCLog.logger.warn("Dropped invalid volume box packet", e);
                    return;
                } finally {
                    buffer.release();
                }
            }

            if (message.replaceAll) {
                ClientVolumeBoxes.INSTANCE.volumeBoxes.removeIf(volumeBox -> !updates.containsKey(volumeBox.id));
            } else if (!message.removedIds.isEmpty()) {
                ClientVolumeBoxes.INSTANCE.volumeBoxes.removeIf(volumeBox -> message.removedIds.contains(volumeBox.id));
            }

            for (Map.Entry<UUID, DecodedVolumeBox> entry : updates.entrySet()) {
                VolumeBox existing = ClientVolumeBoxes.INSTANCE.volumeBoxes.stream()
                    .filter(volumeBox -> volumeBox.id.equals(entry.getKey()))
                    .findFirst()
                    .orElse(null);
                if (existing != null) {
                    FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(entry.getValue().payload()));
                    try {
                        existing.fromBytes(buffer);
                    } catch (IOException | RuntimeException io) {
                        BCLog.logger.warn("Dropped invalid volume box update", io);
                    } finally {
                        buffer.release();
                    }
                    continue;
                }

                VolumeBox added = entry.getValue().box();
                ClientVolumeBoxes.INSTANCE.volumeBoxes.add(added);
                for (Addon addon : added.addons.values()) {
                    if (addon != null) {
                        addon.onAdded();
                    }
                }
            }
        });
    }
}
