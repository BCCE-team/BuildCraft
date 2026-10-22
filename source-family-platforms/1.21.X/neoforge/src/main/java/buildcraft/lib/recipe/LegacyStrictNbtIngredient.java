//? source if >=1.21.1
/*
 * Copyright (c) 2017-2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.recipe;

import java.util.Objects;
import java.util.stream.Stream;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import buildcraft.lib.misc.ItemStackUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.crafting.ICustomIngredient;
import net.neoforged.neoforge.common.crafting.IngredientType;
import buildcraft.lib.compat.NbtCompat;
import net.minecraft.core.Holder;

/**
 * NeoForge 1.21.1 equivalent of Forge's legacy {@code forge:nbt} ingredient.
 *
 * <p>The 1.19.2 reference recipes use {@code StrictNBTIngredient}: item, damage and
 * the complete legacy share tag must match exactly, while stack count is ignored.
 * Gate variants store byte-valued fields in that tag, so parsing the original SNBT
 * string is intentional; converting it through JSON data components would erase
 * those NBT numeric types and subtly change strict matching.</p>
 */
public final class LegacyStrictNbtIngredient implements ICustomIngredient {
    public static final MapCodec<LegacyStrictNbtIngredient> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
        BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(LegacyStrictNbtIngredient::item),
        Codec.INT.optionalFieldOf("count", 1).forGetter(LegacyStrictNbtIngredient::count),
        Codec.STRING.optionalFieldOf("nbt", "").forGetter(LegacyStrictNbtIngredient::legacyNbt)
    ).apply(instance, LegacyStrictNbtIngredient::new));

    private final Item item;
    private final int count;
    private final String legacyNbt;
    private final ItemStack displayStack;

    private LegacyStrictNbtIngredient(Item item, int count, String legacyNbt) {
        if (count <= 0) {
            throw new IllegalArgumentException("Legacy strict-NBT ingredient count must be positive");
        }
        this.item = Objects.requireNonNull(item, "item");
        this.count = count;
        this.legacyNbt = legacyNbt == null ? "" : legacyNbt;

        CompoundTag parsed = parseTag(this.legacyNbt);
        ItemStack stack = new ItemStack(item, count);

        int damage = 0;
        if (NbtCompat.contains(parsed, "Damage", 99) && stack.isDamageableItem()) {
            damage = Math.max(0, NbtCompat.getInt(parsed, "Damage"));
            parsed.remove("Damage");
            stack.set(DataComponents.DAMAGE, damage);
        }
        ItemStackUtil.setCustomData(stack, parsed);
        this.displayStack = stack;
    }

    private static CompoundTag parseTag(String snbt) {
        if (snbt == null || snbt.isBlank()) {
            return new CompoundTag();
        }
        try {
            return NbtCompat.parseTag(snbt);
        } catch (CommandSyntaxException exception) {
            throw new IllegalArgumentException("Invalid legacy strict-NBT ingredient tag: " + snbt, exception);
        }
    }

    private Item item() {
        return item;
    }

    private int count() {
        return count;
    }

    private String legacyNbt() {
        return legacyNbt;
    }

    public boolean test(ItemStack input) {
        if (input == null || input.isEmpty() || input.getItem() != item) {
            return false;
        }
        // ItemStack count is deliberately ignored, matching Forge 1.19.2 StrictNBTIngredient.
        // Comparing the complete component set rejects extra names/enchants/custom data just as
        // an extra legacy stack tag would violate strict NBT comparison.
        return ItemStack.isSameItemSameComponents(input, displayStack);
    }

    public Stream<Holder<Item>> items() {
        return Stream.of(item.builtInRegistryHolder());
    }

    public boolean isSimple() {
        return false;
    }

    public IngredientType<?> getType() {
        return BCLibIngredientTypes.STRICT_NBT.get();
    }
}
