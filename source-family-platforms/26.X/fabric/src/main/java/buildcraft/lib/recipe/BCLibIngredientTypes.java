package buildcraft.lib.recipe;

import buildcraft.lib.BCLib;
import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.recipe.v1.ingredient.CustomIngredientSerializer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * Fabric native custom ingredient registry. The serializer ID is kept identical
 * to the NeoForge recipe ingredient ID buildcraftlib:strict_nbt.
 */
public final class BCLibIngredientTypes {
    public static final CustomIngredientSerializer<LegacyStrictNbtIngredient> STRICT_NBT =
        new CustomIngredientSerializer<>() {
            public Identifier getIdentifier() {
                return Identifier.fromNamespaceAndPath(BCLib.MODID, "strict_nbt");
            }

            public MapCodec<LegacyStrictNbtIngredient> getCodec() {
                return LegacyStrictNbtIngredient.CODEC;
            }

            public StreamCodec<RegistryFriendlyByteBuf, LegacyStrictNbtIngredient> getStreamCodec() {
                return LegacyStrictNbtIngredient.STREAM_CODEC;
            }
        };

    public static final CustomIngredientSerializer<ExactComponentsIngredient> EXACT_COMPONENTS =
        new CustomIngredientSerializer<>() {
            public Identifier getIdentifier() {
                return Identifier.fromNamespaceAndPath(BCLib.MODID, "exact_components");
            }

            public MapCodec<ExactComponentsIngredient> getCodec() {
                return ExactComponentsIngredient.CODEC;
            }

            public StreamCodec<RegistryFriendlyByteBuf, ExactComponentsIngredient> getStreamCodec() {
                return ExactComponentsIngredient.STREAM_CODEC;
            }
        };

    private static boolean registered;
    private BCLibIngredientTypes() {}

    public static synchronized void register() {
        if (registered) return;
        CustomIngredientSerializer.register(STRICT_NBT);
        CustomIngredientSerializer.register(EXACT_COMPONENTS);
        registered = true;
    }
}
