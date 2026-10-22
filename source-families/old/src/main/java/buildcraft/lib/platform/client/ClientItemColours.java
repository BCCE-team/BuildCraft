package buildcraft.lib.platform.client;
import net.minecraft.client.color.item.ItemColor;
import net.minecraft.world.level.ItemLike;

/** Pre-item-model tint registration, isolated from loader events. */
@FunctionalInterface
public interface ClientItemColours { void register(ItemColor colour, ItemLike... items); }
