package buildcraft.factory;

import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCRegistryBinder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleRecipeSerializer;
import net.minecraft.world.level.Level;

/** The Minecraft recipe-system registration facade; the machine runtime reads the same JSON itself. */
public final class BCFactoryRecipeSerializers {
    private static final BCDeferredRegister<RecipeSerializer<?>> SERIALIZERS =
        BCDeferredRegister.create("minecraft:recipe_serializer", BCFactory.MODID);
    public static final BCRegistryEntry<SimpleRecipeSerializer<Placeholder>> DISTILLATION =
        SERIALIZERS.register("distillation", () -> new SimpleRecipeSerializer<>(id -> new Placeholder(id, false)));
    public static final BCRegistryEntry<SimpleRecipeSerializer<Placeholder>> HEAT_EXCHANGE =
        SERIALIZERS.register("heat_exchange", () -> new SimpleRecipeSerializer<>(id -> new Placeholder(id, true)));

    public static void registry(BCRegistryBinder binder) { SERIALIZERS.register(binder); }
    private BCFactoryRecipeSerializers() {}

    /** Never match an ordinary workbench: actual processing is done by API v2 machines. */
    public static final class Placeholder extends CustomRecipe {
        private final boolean exchanger;
        public Placeholder(ResourceLocation id, boolean exchanger) { super(id); this.exchanger = exchanger; }
        public boolean matches(CraftingContainer input, Level world) { return false; }
        public ItemStack assemble(CraftingContainer input) { return ItemStack.EMPTY; }
        public boolean canCraftInDimensions(int width, int height) { return false; }
        public RecipeSerializer<?> getSerializer() { return exchanger ? HEAT_EXCHANGE.get() : DISTILLATION.get(); }
    }
}
