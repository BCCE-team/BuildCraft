//? source if >=1.21.11
package buildcraft.lib.compat;

import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.compat.minecraft.registry.BCRegistrationScope;
import java.util.function.Supplier;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Registration compatibility helpers for MC/NeoForge versions that require the
 * registry id to be stamped onto Block/Item properties before construction.
 */
public final class RegistryCompat {

    private RegistryCompat() {
    }

    public static <T extends Block> BCRegistryEntry<T> registerBlock(
        BCDeferredRegister<Block> register,
        String path,
        Supplier<? extends T> factory
    ) {
        return register.register(path, id -> BCRegistrationScope.block(ResourceKey.create(Registries.BLOCK, id), factory));
    }

    public static <T extends Item> BCRegistryEntry<T> registerItem(
        BCDeferredRegister<Item> register,
        String path,
        Supplier<? extends T> factory
    ) {
        return register.register(path, id -> BCRegistrationScope.item(ResourceKey.create(Registries.ITEM, id), factory));
    }

    public static BlockBehaviour.Properties blockProperties(BlockBehaviour.Properties properties) {
        return BCRegistrationScope.blockProperties(properties);
    }

    public static Item.Properties itemProperties(Item.Properties properties) {
        return BCRegistrationScope.itemProperties(properties);
    }
}
