package buildcraft.api.v2.recipe;

import java.util.Objects;

import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;

/** Typed replacement for the legacy IngredientStack raw-Object API. */
public final class CountedIngredient {
    private final Ingredient ingredient;
    private final TagKey<Item> tag;
    private final ItemStack exactStack;
    private final int count;

    private CountedIngredient(Ingredient ingredient, TagKey<Item> tag, ItemStack exactStack, int count) {
        if (ingredient == null && tag == null) throw new NullPointerException("ingredient/tag");
        if (count <= 0) throw new IllegalArgumentException("count must be > 0");
        this.ingredient = ingredient;
        this.tag = tag;
        this.exactStack = exactStack;
        this.count = count;
    }

    public static CountedIngredient of(Ingredient ingredient, int count) {
        return new CountedIngredient(Objects.requireNonNull(ingredient, "ingredient"), null, null, count);
    }

    public static CountedIngredient of(ItemLike item, int count) {
        return of(Ingredient.of(Objects.requireNonNull(item, "item")), count);
    }

    /** Preserve the exact component-sensitive stack semantics used by the 1.21.1 API. */
    public static CountedIngredient of(ItemStack stack, int count) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) throw new IllegalArgumentException("stack must not be empty");
        ItemStack exact = stack.copy();
        return new CountedIngredient(DataComponentIngredient.of(true, exact), null, exact, count);
    }

    /** Keep the TagKey itself so addons may create this definition before datapack tags are bound. */
    public static CountedIngredient of(TagKey<Item> tag, int count) {
        return new CountedIngredient(null, Objects.requireNonNull(tag, "tag"), null, count);
    }

    @SuppressWarnings("deprecation")
    public Ingredient ingredient() {
        if (tag == null) return ingredient;
        HolderSet<Item> values = BuiltInRegistries.ITEM.get(tag)
            .map(set -> (HolderSet<Item>) set)
            .orElseGet(() -> HolderSet.emptyNamed(BuiltInRegistries.ITEM, tag));
        return Ingredient.of(values);
    }

    public int count() { return count; }

    public boolean test(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getCount() < count) return false;
        if (tag != null) return stack.is(tag);
        if (exactStack != null) return ItemStack.isSameItemSameComponents(stack, exactStack);
        return ingredient.test(stack);
    }
}
