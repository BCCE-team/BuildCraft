package buildcraft.factory;

import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCRegistryBinder;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;

/** Machine JSON is read by API v2; this serializer lets RecipeManager recognize its type. */
public final class BCFactoryRecipeSerializers {
    private static final BCDeferredRegister<RecipeSerializer<?>> SERIALIZERS =
        BCDeferredRegister.create("minecraft:recipe_serializer", BCFactory.MODID);
    public static final BCRegistryEntry<SimpleCraftingRecipeSerializer<Placeholder>> DISTILLATION =
        SERIALIZERS.register("distillation", () -> new SimpleCraftingRecipeSerializer<>(cat -> new Placeholder(cat, false)));
    public static final BCRegistryEntry<SimpleCraftingRecipeSerializer<Placeholder>> HEAT_EXCHANGE =
        SERIALIZERS.register("heat_exchange", () -> new SimpleCraftingRecipeSerializer<>(cat -> new Placeholder(cat, true)));

    public static void registry(BCRegistryBinder binder) { SERIALIZERS.register(binder); }
    private BCFactoryRecipeSerializers() {}

    public static final class Placeholder extends CustomRecipe {
        private final boolean exchanger;
        public Placeholder(CraftingBookCategory category, boolean exchanger) { super(category); this.exchanger = exchanger; }
        public boolean matches(CraftingInput input, Level world) { return false; }
        public ItemStack assemble(CraftingInput input, HolderLookup.Provider lookup) { return ItemStack.EMPTY; }
        public boolean canCraftInDimensions(int width, int height) { return false; }
        @Override public RecipeSerializer<? extends CustomRecipe> getSerializer() { return exchanger ? HEAT_EXCHANGE.get() : DISTILLATION.get(); }
    }
}
