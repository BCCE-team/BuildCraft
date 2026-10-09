//? source if >=1.21.11
package buildcraft.lib.compat;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;

/** 1.21.11 facade for Ingredient factories whose signatures changed after 1.21.1. */
public final class IngredientCompat {
    // Vanilla 1.21.11 no longer permits a direct empty Ingredient. Keep a private sentinel
    // only for BuildCraft compatibility call sites that expect the empty ingredient constant.
    private static final Ingredient EMPTY_SENTINEL = Ingredient.of(Items.BARRIER);

    private IngredientCompat() {}

    public static Ingredient of(ItemLike item) {
        return item == null || item.asItem() == Items.AIR ? empty() : Ingredient.of((ItemLike) item);
    }

    /** Preserve the component-sensitive semantics of NeoForge 1.21.1 Ingredient.of(ItemStack). */
    public static Ingredient of(ItemStack stack) {
        return stack == null || stack.isEmpty() ? empty() : DataComponentIngredient.of(true, stack.copy());
    }

    public static Ingredient of(Stream<? extends ItemLike> stream) {
        List<? extends ItemLike> items = stream
            .filter(Objects::nonNull)
            .filter(item -> item.asItem() != Items.AIR)
            .toList();
        return items.isEmpty() ? empty() : Ingredient.of(items.stream());
    }

    /**
     * Build a real tag-backed ingredient. When the tag is already bound, use the registry's
     * canonical HolderSet. The fallback Named set still retains tag matching semantics during
     * early construction and will not silently turn the recipe into AIR/barrier.
     */
    @SuppressWarnings({"unchecked", "deprecation"})
    public static Ingredient of(TagKey<?> tag) {
        if (tag == null || !tag.isFor(BuiltInRegistries.ITEM.key())) return empty();
        TagKey<Item> itemTag = (TagKey<Item>) tag;
        HolderSet<Item> values = BuiltInRegistries.ITEM.get(itemTag)
            .map(set -> (HolderSet<Item>) set)
            .orElseGet(() -> HolderSet.emptyNamed(BuiltInRegistries.ITEM, itemTag));
        return Ingredient.of(values);
    }

    public static Ingredient empty() {
        return EMPTY_SENTINEL;
    }

    public static boolean isEmpty(Ingredient ingredient) {
        return ingredient == null || ingredient == EMPTY_SENTINEL;
    }

    /** Enumerate all currently bound alternatives for vanilla or NeoForge custom ingredients. */
    @SuppressWarnings("unchecked")
    public static ItemStack[] getItems(Ingredient ingredient) {
        if (isEmpty(ingredient)) return new ItemStack[0];

        // A fallback early-created tag ingredient can be unbound internally. Resolve its TagKey
        // through the live registry instead of dereferencing that temporary HolderSet.
        if (!ingredient.isCustom()) {
            HolderSet<Item> values = ingredient.getValues();
            var tag = values.unwrapKey();
            if (tag.isPresent()) {
                return getItems((TagKey<Item>) tag.get());
            }
        }

        return ingredient.items()
            .map(holder -> new ItemStack((Holder<Item>) holder))
            .toArray(ItemStack[]::new);
    }

    public static ItemStack[] getItems(TagKey<Item> tag) {
        if (tag == null) return new ItemStack[0];
        return BuiltInRegistries.ITEM.get(tag)
            .map(set -> set.stream().map(holder -> new ItemStack((Holder<Item>) holder)).toArray(ItemStack[]::new))
            .orElseGet(() -> new ItemStack[0]);
    }

    @SuppressWarnings("unchecked")
    public static Ingredient of(Object value) {
        if (value instanceof ItemStack stack) return of(stack);
        if (value instanceof ItemLike item) return of(item);
        if (value instanceof Stream<?> stream) return of((Stream<? extends ItemLike>) stream);
        if (value instanceof TagKey<?> tag) return of(tag);
        return empty();
    }
}
