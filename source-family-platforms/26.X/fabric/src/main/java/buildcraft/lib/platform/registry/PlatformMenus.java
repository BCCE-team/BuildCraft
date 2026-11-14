package buildcraft.lib.platform.registry;

import io.netty.buffer.Unpooled;
import java.util.Objects;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

/**
 * Fabric 26.3 equivalent of NeoForge's IMenuTypeExtension.
 *
 * This is a byte-for-byte payload bridge: menu payloads are not restricted to
 * BlockPos because BuildCraft GUI menus can carry additional data.
 */
public final class PlatformMenus {
    public static final int MAX_OPENING_BYTES = 64 * 1024;
    private static final StreamCodec<FriendlyByteBuf, byte[]> EXTRA_DATA_CODEC = StreamCodec.of(
        (buffer, data) -> {
            if (data.length > MAX_OPENING_BYTES) throw new IllegalArgumentException("Menu opening payload too large");
            buffer.writeByteArray(data);
        },
        buffer -> buffer.readByteArray(MAX_OPENING_BYTES)
    );

    private PlatformMenus() {}

    public static <T extends AbstractContainerMenu> MenuType<T> create(BCMenuFactory<T> factory) {
        Objects.requireNonNull(factory, "factory");
        return new ExtendedMenuType<>((windowId, inventory, payload) -> {
            if (payload == null || payload.length > MAX_OPENING_BYTES) {
                throw new IllegalArgumentException("Invalid BuildCraft opening payload");
            }
            // The existing BCCE menu constructors consume FriendlyByteBuf, not a
            // loader-specific menu context. The payload is released after construction.
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
            try {
                return factory.create(windowId, inventory, buffer);
            } finally {
                buffer.release();
            }
        }, EXTRA_DATA_CODEC);
    }
}
