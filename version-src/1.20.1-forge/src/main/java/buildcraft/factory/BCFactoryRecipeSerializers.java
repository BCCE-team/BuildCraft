package buildcraft.factory;

import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCRegistryBinder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;

/** Register datapack recipe type ids without exposing machine recipes as crafting-table matches. */
public final class BCFactoryRecipeSerializers {
    private static final BCDeferredRegister<RecipeSerializer<?>> SERIALIZERS =
        BCDeferredRegister.create("minecraft:recipe_serializer", BCFactory.MODID);
    public static final BCRegistryEntry<SimpleCraftingRecipeSerializer<Placeholder>> DISTILLATION =
        SERIALIZERS.register("distillation", () -> new SimpleCraftingRecipeSerializer<>((id, cat) -> new Placeholder(id, cat, false)));
    public static final BCRegistryEntry<SimpleCraftingRecipeSerializer<Placeholder>> HEAT_EXCHANGE =
        SERIALIZERS.register("heat_exchange", () -> new SimpleCraftingRecipeSerializer<>((id, cat) -> new Placeholder(id, cat, true)));

    public static void registry(BCRegistryBinder binder) { SERIALIZERS.register(binder); }
    private BCFactoryRecipeSerializers() {}

    public static final class Placeholder extends CustomRecipe {
        private final boolean exchanger;
        public Placeholder(ResourceLocation id, CraftingBookCategory category, boolean exchanger) {
            super(id, category);
            this.exchanger = exchanger;
        }
        public boolean matches(CraftingContainer input, Level world) { return false; }
        public ItemStack assemble(CraftingContainer input, RegistryAccess access) { return ItemStack.EMPTY; }
        public boolean canCraftInDimensions(int width, int height) { return false; }
        public RecipeSerializer<?> getSerializer() { return exchanger ? HEAT_EXCHANGE.get() : DISTILLATION.get(); }
    }
}
