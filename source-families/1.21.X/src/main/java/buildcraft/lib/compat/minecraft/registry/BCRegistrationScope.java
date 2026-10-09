//? source if >=1.21.1
package buildcraft.lib.compat.minecraft.registry;

import java.util.function.Supplier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
//? if >=1.21.11 {
import buildcraft.lib.compat.ItemNameKeys121111;
//? }

/**
 * Minecraft property/id stamping, independent of deferred-register implementations.
 * Nested factories restore the outer id instead of leaking or clearing its context.
 */
public final class BCRegistrationScope {
    private static final ThreadLocal<ResourceKey<Block>> BLOCK = new ThreadLocal<>();
    private static final ThreadLocal<ResourceKey<Item>> ITEM = new ThreadLocal<>();
    private BCRegistrationScope() {}

    public static <T extends Block> T block(ResourceKey<Block> id, Supplier<? extends T> factory) {
        ResourceKey<Block> previous = BLOCK.get();
        BLOCK.set(id);
        try { return factory.get(); }
        finally { if (previous == null) BLOCK.remove(); else BLOCK.set(previous); }
    }
    public static <T extends Item> T item(ResourceKey<Item> id, Supplier<? extends T> factory) {
        ResourceKey<Item> previous = ITEM.get();
        ITEM.set(id);
        try { return factory.get(); }
        finally { if (previous == null) ITEM.remove(); else ITEM.set(previous); }
    }
//? if >=1.21.11 {
    public static BlockBehaviour.Properties blockProperties(BlockBehaviour.Properties properties) {
        ResourceKey<Block> id = BLOCK.get();
        return id == null ? properties : properties.setId(id);
    }
    public static Item.Properties itemProperties(Item.Properties properties) {
        ResourceKey<Item> id = ITEM.get();
        if (id == null) return properties;
        properties.setId(id);
        String name = ItemNameKeys121111.key(id.identifier());
        // ItemByEnum intentionally reuses its builder: assign the current variant every time.
        return name == null ? properties : properties.overrideDescription(name);
    }
//? } else {
    public static BlockBehaviour.Properties blockProperties(BlockBehaviour.Properties properties) { return properties; }
    public static Item.Properties itemProperties(Item.Properties properties) { return properties; }
//? }
}
