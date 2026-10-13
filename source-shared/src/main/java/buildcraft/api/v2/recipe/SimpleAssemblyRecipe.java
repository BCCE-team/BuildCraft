package buildcraft.api.v2.recipe;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

/** A fixed-output Assembly Table definition; complex addons may implement the interface directly. */
public final class SimpleAssemblyRecipe implements AssemblyRecipeDefinition {
    private final List<CountedIngredient> requirements;
    private final ItemStack output;
    private final long requiredMicroJoules;

    public SimpleAssemblyRecipe(List<CountedIngredient> requirements, ItemStack output, long requiredMicroJoules) {
        this.requirements = List.copyOf(Objects.requireNonNull(requirements, "requirements"));
        if (this.requirements.isEmpty()) throw new IllegalArgumentException("At least one ingredient is required");
        this.output = Objects.requireNonNull(output, "output").copy();
        if (this.output.isEmpty()) throw new IllegalArgumentException("Assembly output may not be empty");
        if (requiredMicroJoules <= 0) throw new IllegalArgumentException("Assembly power cost must be positive");
        this.requiredMicroJoules = requiredMicroJoules;
    }

    @Override public Set<ItemStack> outputs(NonNullList<ItemStack> available) {
        return canCraft(available) ? Set.of(output.copy()) : Set.of();
    }

    @Override public Set<ItemStack> outputPreviews() { return Set.of(output.copy()); }
    @Override public List<CountedIngredient> requirements(ItemStack output) {
        return sameOutput(output) ? requirements : List.of();
    }
    @Override public long requiredMicroJoules(ItemStack output) {
        return sameOutput(output) ? requiredMicroJoules : 0;
    }

    /** Simulate consumption without mutating the table slots or incorrectly reusing one input. */
    public boolean canCraft(NonNullList<ItemStack> available) {
        Objects.requireNonNull(available, "available");
        return AssemblyIngredients.remaining(requirements, available).isPresent();
    }

    private boolean sameOutput(ItemStack candidate) {
        return candidate != null && !candidate.isEmpty() && candidate.getCount() == output.getCount()
            && CountedIngredient.sameItemAndData(candidate, output);
    }
}
