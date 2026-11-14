package buildcraft.lib.recipe;

import buildcraft.lib.misc.ItemStackUtil;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.recipe.v1.ingredient.CustomIngredient;
import net.fabricmc.fabric.api.recipe.v1.ingredient.CustomIngredientSerializer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Fabric counterpart of NeoForge DataComponentIngredient.of(true, stack):
 * full item ID and full resolved Data Components equality, count ignored.
 *
 * Fabric's built-in components(ItemStack) helper checks a component subset,
 * so it cannot replace the strict BCCE recipe contract.
 */
public final class ExactComponentsIngredient implements CustomIngredient {
    public static final MapCodec<ExactComponentsIngredient> CODEC =
        RecordCodecBuilder.mapCodec(instance -> instance.group(
            ItemStack.CODEC.fieldOf("stack").forGetter(ExactComponentsIngredient::template)
        ).apply(instance, ExactComponentsIngredient::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, ExactComponentsIngredient> STREAM_CODEC =
        StreamCodec.of((buffer, value) -> ItemStack.STREAM_CODEC.encode(buffer, value.template),
            buffer -> new ExactComponentsIngredient(ItemStack.STREAM_CODEC.decode(buffer)));

    private final ItemStack template;

    public ExactComponentsIngredient(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) throw new IllegalArgumentException("Cannot match an empty ItemStack");
        this.template = stack.copy();
        this.template.setCount(1);
    }

    private ItemStack template() { return template.copy(); }

    public boolean test(ItemStack candidate) {
        return candidate != null && !candidate.isEmpty()
            && ItemStack.isSameItemSameComponents(template, candidate);
    }

    public Stream<Holder<Item>> items() {
        return Stream.of(template.getItem().builtInRegistryHolder());
    }

    public boolean requiresTesting() { return true; }

    public CustomIngredientSerializer<?> getSerializer() {
        return BCLibIngredientTypes.EXACT_COMPONENTS;
    }
}
