package buildcraft.api.v2.recipe;

import java.util.List;
import java.util.Set;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

/** An addon-owned Assembly Table recipe, including multi-output and dynamic input recipes. */
public interface AssemblyRecipeDefinition extends RecipeDefinition {
    @Override default Kind kind() { return Kind.ASSEMBLY; }

    /** All producible outputs for these slots; implementations must not mutate the supplied stacks. */
    Set<ItemStack> outputs(NonNullList<ItemStack> available);

    /** Output previews for guidebooks and JEI, even when ingredients are absent. */
    Set<ItemStack> outputPreviews();

    /** Ingredients to consume for a chosen output; may depend on its data components. */
    List<CountedIngredient> requirements(ItemStack output);

    /** Energy in micro-MJ, not MJ or FE. Must be positive for a craftable recipe. */
    long requiredMicroJoules(ItemStack output);
}
