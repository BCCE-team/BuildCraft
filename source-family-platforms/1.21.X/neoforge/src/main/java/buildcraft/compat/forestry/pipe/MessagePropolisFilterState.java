//? source if <1.21.11
package buildcraft.compat.forestry.pipe;

import buildcraft.lib.net.BCPacketContext;
import buildcraft.lib.net.MessageManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Server-authoritative filter state broadcasts for all open Apiarist's Pipe menus. */
public record MessagePropolisFilterState(BlockPos position, CompoundTag data) {
    public MessagePropolisFilterState(RegistryFriendlyByteBuf buf) {
        this(buf.readBlockPos(), readTag(buf));
    }

    private static CompoundTag readTag(RegistryFriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        return tag == null ? new CompoundTag() : tag;
    }

    public void toBytes(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(position);
        buf.writeNbt(data);
    }

    public static final BiConsumer<MessagePropolisFilterState, Supplier<BCPacketContext>> HANDLER =
        (message, context) -> context.get().enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) message.applyClient();
        });

    private void applyClient() {
        try {
            Class.forName("buildcraft.compat.forestry.pipe.client.ForestryCompatClient")
                .getMethod("applyFilterState", BlockPos.class, CompoundTag.class)
                .invoke(null, position, data);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // The client may have closed the menu while the state packet was in flight.
        }
    }

    public static void sendToViewers(ServerLevel level, BlockPos pos, CompoundTag tag) {
        MessagePropolisFilterState message = new MessagePropolisFilterState(pos, tag);
        for (ServerPlayer player : level.players()) {
            if (player.containerMenu instanceof ContainerPropolisPipe menu && menu.hasSamePipe(pos)) {
                MessageManager.sendTo(message, player);
            }
        }
    }
}
