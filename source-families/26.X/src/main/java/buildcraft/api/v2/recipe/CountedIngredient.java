package buildcraft.api.v2.recipe;

import java.util.Objects;

import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ItemLike;

/** Loader-neutral counted ingredient used by BuildCraft recipe APIs. */
public final class CountedIngredient {
    private final Ingredient ingredient;
    private final TagKey<Item> tag;
    private final ItemStack exactStack;
    private final int count;

    private CountedIngredient(Ingredient ingredient, TagKey<Item> tag, ItemStack exactStack, int count) {
        this.ingredient = Objects.requireNonNull(ingredient, "ingredient");
        this.tag = tag;
        this.exactStack = exactStack;
        if (count <= 0) throw new IllegalArgumentException("count must be > 0");
        this.count = count;
    }

    public static CountedIngredient of(Ingredient ingredient, int count) {
        return new CountedIngredient(ingredient, null, null, count);
    }

    public static CountedIngredient of(ItemLike item, int count) {
        return of(Ingredient.of(Objects.requireNonNull(item, "item")), count);
    }

    public static CountedIngredient of(ItemStack stack, int count) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) throw new IllegalArgumentException("stack must not be empty");
        ItemStack exactStack = stack.copy();
        return new CountedIngredient(Ingredient.of(stack.getItem()), null, exactStack, count);
    }

    @SuppressWarnings("unchecked")
    public static CountedIngredient of(TagKey<Item> tag, int count) {
        TagKey<Item> itemTag = Objects.requireNonNull(tag, "tag");
        // Ingredient needs a display/serialization-side backing set,
        // but matching remains tag-key based so datapack tag rebinding is observed.
        HolderSet<Item> values = BuiltInRegistries.ITEM.get(itemTag)
            .map(set -> (HolderSet<Item>) set)
            .orElseGet(() -> HolderSet.emptyNamed(BuiltInRegistries.ITEM, itemTag));
        return new CountedIngredient(Ingredient.of(values), itemTag, null, count);
    }

    public Ingredient ingredient() { return ingredient; }
    public int count() { return count; }

    public boolean test(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getCount() < count) return false;
        if (exactStack != null) return ItemStack.isSameItemSameComponents(stack, exactStack);
        if (tag != null) return stack.is(tag);
        return ingredient.test(stack);
    }
}
