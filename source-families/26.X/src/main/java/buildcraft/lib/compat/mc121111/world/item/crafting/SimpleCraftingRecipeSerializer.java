//? source if >=1.21.11
package buildcraft.lib.compat.mc121111.world.item.crafting;

import com.mojang.serialization.MapCodec;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;

/** Compatibility copy of the pre-1.21.11 simple custom recipe serializer. */
public class SimpleCraftingRecipeSerializer<T extends CraftingRecipe> implements RecipeSerializer<T> {
    @FunctionalInterface
    public interface Factory<T extends CraftingRecipe> {
        T create(CraftingBookCategory category);
    }

    private final MapCodec<T> codec;
    private final StreamCodec<RegistryFriendlyByteBuf, T> streamCodec;

    public SimpleCraftingRecipeSerializer(Factory<T> factory) {
        this.codec = CraftingBookCategory.CODEC
            .optionalFieldOf("category", CraftingBookCategory.MISC)
            .xmap(factory::create, recipe -> recipe.category());
        this.streamCodec = StreamCodec.of(
            (buf, recipe) -> CraftingBookCategory.STREAM_CODEC.encode(buf, recipe.category()),
            buf -> factory.create(CraftingBookCategory.STREAM_CODEC.decode(buf))
        );
    }

    public MapCodec<T> codec() {
        return codec;
    }

    public StreamCodec<RegistryFriendlyByteBuf, T> streamCodec() {
        return streamCodec;
    }
}
