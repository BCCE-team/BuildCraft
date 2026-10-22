package buildcraft.transport;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.transport.recipe.PipeRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;

/** Recipe serializers owned by the transport module. */
public final class BCTransportRecipes {
    public static final BCDeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            BCDeferredRegister.create("minecraft:recipe_serializer", BCTransport.MODID);

    public static final BCRegistryEntry<RecipeSerializer<PipeRecipe>> PIPE =
            SERIALIZERS.register("pipe", PipeRecipe.Serializer::new);

    private BCTransportRecipes() {
    }

    public static void preInit(BCRegistryBinder modEventBus) {
        SERIALIZERS.register(modEventBus);
    }
}
