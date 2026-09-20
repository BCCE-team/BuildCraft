//? source if >=1.21.1
package buildcraft.lib.recipe;

import java.util.Set;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.recipes.IngredientStack;
import buildcraft.silicon.BCSiliconItems;
import buildcraft.silicon.BCSiliconRecipes;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeBookCategories;

public abstract class AssemblyRecipeBasic implements Recipe<RecipeInput>, Comparable<AssemblyRecipeBasic> {
    protected Identifier name;

    /**
     * The outputs this recipe can generate with any of the given inputs.
     *
     * @param inputs current ingredients in the assembly table
     * @return all possible outputs, or an empty set if nothing can be assembled
     */
    public abstract Set<ItemStack> getOutputs(IItemHandlerModifiable inputs);

    /** Used to determine all outputs from this recipe for recipe previews. */
    public abstract Set<ItemStack> getOutputPreviews();

    /** Used to determine what items are consumed for the given output. */
    public abstract Set<IngredientStack> getInputsFor(@Nonnull ItemStack output);

    /** Used to determine how much micro-MJ is required for the given output. */
    public abstract long getRequiredMicroJoulesFor(@Nonnull ItemStack output);

    public boolean matches(RecipeInput input, Level level) {
        return !getOutputs(new RecipeInputItemHandler(input)).isEmpty();
    }

    /** Compatibility overload for pre-1.21 callers that still expose a vanilla container. */
    public boolean matches(Container container, Level level) {
        return matches(new ContainerRecipeInput(container), level);
    }

    public ItemStack assemble(RecipeInput input, HolderLookup.Provider registries) {
        Set<ItemStack> outputs = getOutputs(new RecipeInputItemHandler(input));
        return outputs.isEmpty() ? ItemStack.EMPTY : outputs.iterator().next().copy();
    }

    /** Compatibility overload for pre-1.21 callers that still expose a vanilla container. */
    public ItemStack assemble(Container container, HolderLookup.Provider registries) {
        return assemble(new ContainerRecipeInput(container), registries);
    }

    public ItemStack getToastSymbol() {
        return new ItemStack(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get());
    }

    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof AssemblyRecipeBasic other)) {
            return false;
        }
        return name.equals(other.name);
    }

    public int hashCode() {
        return name.hashCode();
    }

    public int compareTo(AssemblyRecipeBasic other) {
        return name.toString().compareTo(other.name.toString());
    }

    /**
     * Recipe ids live on RecipeHolder in 1.21.1. BuildCraft still stores the id on the recipe for its
     * assembly-table sync format, so this compatibility accessor remains part of the BuildCraft API.
     */
    public Identifier getId() {
        return name;
    }

    public RecipeType<? extends Recipe<RecipeInput>> getType() {
        return BCSiliconRecipes.ASSEMBLY_TYPE.get();
    }


    public PlacementInfo placementInfo() {
        return PlacementInfo.NOT_PLACEABLE;
    }

    public RecipeBookCategory recipeBookCategory() {
        return RecipeBookCategories.CRAFTING_MISC;
    }

    /** Minimal RecipeInput view over a legacy vanilla container. */
    private static final class ContainerRecipeInput implements RecipeInput {
        private final Container container;

        private ContainerRecipeInput(Container container) {
            this.container = container;
        }

        public ItemStack getItem(int slot) {
            return container.getItem(slot);
        }

        public int size() {
            return container.getContainerSize();
        }
    }

    /** Read-only IItemHandler view exposed to the BuildCraft assembly-recipe API. */
    private static final class RecipeInputItemHandler implements IItemHandlerModifiable {
        private final RecipeInput input;

        private RecipeInputItemHandler(RecipeInput input) {
            this.input = input;
        }

        public void setStackInSlot(int slot, ItemStack stack) {
            throw new UnsupportedOperationException("Assembly recipe input is read-only");
        }

        public int getSlots() {
            return input.size();
        }

        public ItemStack getStackInSlot(int slot) {
            return input.getItem(slot);
        }

        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }

        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        public int getSlotLimit(int slot) {
            return input.getItem(slot).getMaxStackSize();
        }

        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }
    }
}
