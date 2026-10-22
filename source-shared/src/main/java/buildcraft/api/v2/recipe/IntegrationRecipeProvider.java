package buildcraft.api.v2.recipe;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

/** Lazy addon recipe provider for Integration Table recipes with data-dependent outputs. */
@FunctionalInterface
public interface IntegrationRecipeProvider extends RecipeDefinition {
    @Override default Kind kind() { return Kind.INTEGRATION; }

    /** Null means no match. Providers should not mutate the supplied stacks. */
    IntegrationRecipeDefinition recipeFor(ItemStack target, NonNullList<ItemStack> toIntegrate);
}
