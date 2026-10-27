package buildcraft.factory;

import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCRegistryBinder;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/** Dummy native serializers: custom BuildCraft machines evaluate these JSON files, never the crafting grid. */
public final class BCFactoryRecipeSerializers {
    private static final BCDeferredRegister<RecipeSerializer<?>> SERIALIZERS =
        BCDeferredRegister.create("minecraft:recipe_serializer", BCFactory.MODID);
    public static final BCRegistryEntry<RecipeSerializer<Placeholder>> DISTILLATION =
        SERIALIZERS.register("distillation", () -> Placeholder.distillationSerializer());
    public static final BCRegistryEntry<RecipeSerializer<Placeholder>> HEAT_EXCHANGE =
        SERIALIZERS.register("heat_exchange", () -> Placeholder.heatSerializer());

    public static void registry(BCRegistryBinder binder) { SERIALIZERS.register(binder); }
    private BCFactoryRecipeSerializers() {}

    public static final class Placeholder extends CustomRecipe {
        private static final Placeholder DISTILL = new Placeholder(false);
        private static final Placeholder HEAT = new Placeholder(true);
        private static final RecipeSerializer<Placeholder> DISTILL_SERIALIZER = new RecipeSerializer<>(
            MapCodec.unit(DISTILL), StreamCodec.<RegistryFriendlyByteBuf, Placeholder>unit(DISTILL));
        private static final RecipeSerializer<Placeholder> HEAT_SERIALIZER = new RecipeSerializer<>(
            MapCodec.unit(HEAT), StreamCodec.<RegistryFriendlyByteBuf, Placeholder>unit(HEAT));
        private final boolean exchanger;
        private Placeholder(boolean exchanger) { super(); this.exchanger = exchanger; }
        public static RecipeSerializer<Placeholder> distillationSerializer() { return DISTILL_SERIALIZER; }
        public static RecipeSerializer<Placeholder> heatSerializer() { return HEAT_SERIALIZER; }
        public boolean matches(CraftingInput input, Level world) { return false; }
        public ItemStack assemble(CraftingInput input) { return ItemStack.EMPTY; }
        public boolean canCraftInDimensions(int width, int height) { return false; }
        public RecipeSerializer<? extends CustomRecipe> getSerializer() {
            return exchanger ? HEAT_EXCHANGE.get() : DISTILLATION.get();
        }
    }
}
