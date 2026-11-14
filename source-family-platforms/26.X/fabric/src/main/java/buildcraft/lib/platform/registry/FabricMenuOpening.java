package buildcraft.lib.platform.registry;

import io.netty.buffer.Unpooled;
import java.util.Objects;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * A Fabric menu opening bridge for the original BCCE extra-data writer. Module
 * GUI open sites use this instead of NeoForge's openMenu(provider, byteBufWriter).
 * The server menu instance is delegated to the original MenuProvider.
 */
public final class FabricMenuOpening {
    private FabricMenuOpening() {}

    public static void open(ServerPlayer player, MenuProvider provider, Consumer<FriendlyByteBuf> writeExtra) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(writeExtra, "writeExtra");

        player.openMenu(new ExtendedMenuProvider<byte[]>() {
            public Component getDisplayName() { return provider.getDisplayName(); }

            public AbstractContainerMenu createMenu(int windowId, Inventory inventory, Player actor) {
                return provider.createMenu(windowId, inventory, actor);
            }

            public byte[] getScreenOpeningData(ServerPlayer recipient) {
                FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    writeExtra.accept(buffer);
                    int size = buffer.readableBytes();
                    if (size > PlatformMenus.MAX_OPENING_BYTES) {
                        throw new IllegalArgumentException("BuildCraft menu opening payload too large: " + size);
                    }
                    byte[] bytes = new byte[size];
                    buffer.getBytes(buffer.readerIndex(), bytes);
                    return bytes;
                } finally {
                    buffer.release();
                }
            }
        });
    }
}
