//? source if >=1.21.11
package buildcraft.lib.platform.client;
import com.mojang.serialization.MapCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.client.color.item.ItemTintSource;

/** Minecraft's native model codecs; loader events do not leave the binding. */
public final class ClientModelProperties {
    private ClientModelProperties() {}
    @FunctionalInterface
    public interface Ranges { void register(Identifier id, MapCodec<? extends RangeSelectItemModelProperty> codec); }
    @FunctionalInterface
    public interface Tints { void register(Identifier id, MapCodec<? extends ItemTintSource> codec); }
}
