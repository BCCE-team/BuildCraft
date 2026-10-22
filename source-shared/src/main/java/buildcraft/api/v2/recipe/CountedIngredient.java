package buildcraft.api.v2.recipe;

import java.util.Objects;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ItemLike;

/** Typed replacement for the legacy IngredientStack raw-Object API. */
public final class CountedIngredient {
    private final Ingredient ingredient;
    private final int count;
    private final ItemStack exact;

    private CountedIngredient(Ingredient ingredient, int count) {
        this(ingredient, count, null);
    }

    private CountedIngredient(Ingredient ingredient, int count, ItemStack exact) {
        this.ingredient = Objects.requireNonNull(ingredient, "ingredient");
        if (count <= 0) throw new IllegalArgumentException("count must be > 0");
        this.count = count;
        this.exact = exact == null ? null : exact.copy();
    }

    public static CountedIngredient of(Ingredient ingredient, int count) {
        return new CountedIngredient(ingredient, count);
    }

    public static CountedIngredient of(ItemLike item, int count) {
        return of(Ingredient.of(Objects.requireNonNull(item, "item")), count);
    }

    public static CountedIngredient of(ItemStack stack, int count) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) throw new IllegalArgumentException("stack must not be empty");
        return new CountedIngredient(Ingredient.of(stack.copy()), count, stack);
    }

    public static CountedIngredient of(TagKey<Item> tag, int count) {
        return of(Ingredient.of(Objects.requireNonNull(tag, "tag")), count);
    }

    public Ingredient ingredient() { return ingredient; }
    public int count() { return count; }

    /** Item-only test, for aggregate input accounting across multiple slots. */
    public boolean matchesItem(ItemStack stack) {
        return stack != null && !stack.isEmpty() && ingredient.test(stack)
            && (exact == null || sameItemAndData(exact, stack));
    }

    public boolean test(ItemStack stack) {
        return matchesItem(stack) && stack.getCount() >= count;
    }

    /** Exact item plus NBT/data-components comparison, compatible with each source family. */
    public static boolean sameItemAndData(ItemStack first, ItemStack second) {
        if (first == null || second == null) return false;
        //? if <1.21 {
        return ItemStack.isSameItemSameTags(first, second);
        //?} else {
        /*?
        return ItemStack.isSameItemSameComponents(first, second);
        ?*/
        //?}
    }
}
