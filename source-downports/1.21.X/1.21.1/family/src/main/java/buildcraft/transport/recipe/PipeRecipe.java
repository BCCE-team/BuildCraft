package buildcraft.transport.recipe;

import java.util.Locale;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import buildcraft.transport.BCTransportRecipes;
import buildcraft.transport.item.ItemPipeHolder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.StainedGlassBlock;

/**
 * Recreates the BC 8 pipe recipes while preserving the pipe colour stored on the
 * source stack. Base recipes accept colourless or stained glass; upgrades and
 * undo recipes retain the source pipe colour.
 */
public final class PipeRecipe implements CraftingRecipe {
    public enum Mode {
        BASE,
        UPGRADE,
        DOWNGRADE;

        private static final Codec<Mode> CODEC = Codec.STRING.xmap(Mode::read, Mode::serializedName);

        static Mode read(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "base" -> BASE;
                case "upgrade" -> UPGRADE;
                case "downgrade", "undo" -> DOWNGRADE;
                default -> throw new IllegalArgumentException("Unknown pipe recipe mode: " + value);
            };
        }

        String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Matches the compact legacy result object used by the 8.0.12 data files. */
    private static final Codec<ItemStack> RESULT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
        BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(ItemStack::getItem),
        Codec.INT.optionalFieldOf("count", 1).forGetter(ItemStack::getCount)
    ).apply(instance, PipeRecipe::resultStack));

    private static final MapCodec<PipeRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
        Codec.STRING.optionalFieldOf("group", "").forGetter(recipe -> recipe.group),
        Mode.CODEC.fieldOf("mode").forGetter(recipe -> recipe.mode),
        Ingredient.CODEC.optionalFieldOf("left", Ingredient.EMPTY).forGetter(recipe -> recipe.left),
        Ingredient.CODEC.optionalFieldOf("middle", Ingredient.EMPTY).forGetter(recipe -> recipe.middle),
        Ingredient.CODEC.optionalFieldOf("right", Ingredient.EMPTY).forGetter(recipe -> recipe.right),
        Ingredient.CODEC.optionalFieldOf("from", Ingredient.EMPTY).forGetter(recipe -> recipe.from),
        Ingredient.CODEC.optionalFieldOf("additional", Ingredient.EMPTY).forGetter(recipe -> recipe.additional),
        RESULT_CODEC.fieldOf("result").forGetter(recipe -> recipe.result)
    ).apply(instance, PipeRecipe::new));

    private static final StreamCodec<RegistryFriendlyByteBuf, PipeRecipe> STREAM_CODEC =
        StreamCodec.ofMember(PipeRecipe::writeToNetwork, PipeRecipe::readFromNetwork);

    private final String group;
    private final Mode mode;
    private final Ingredient left;
    private final Ingredient middle;
    private final Ingredient right;
    private final Ingredient from;
    private final Ingredient additional;
    private final ItemStack result;
    private final NonNullList<Ingredient> ingredients;

    private PipeRecipe(String group, Mode mode, Ingredient left, Ingredient middle,
        Ingredient right, Ingredient from, Ingredient additional, ItemStack result) {
        this.group = group == null ? "" : group;
        this.mode = mode;
        this.left = left;
        this.middle = middle;
        this.right = right;
        this.from = from;
        this.additional = additional;
        this.result = result.copy();
        this.ingredients = NonNullList.create();
        if (mode == Mode.BASE) {
            ingredients.add(left);
            ingredients.add(middle);
            ingredients.add(right);
        } else {
            ingredients.add(from);
            if (mode == Mode.UPGRADE) {
                ingredients.add(additional);
            }
        }
    }

    private static ItemStack resultStack(Item item, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Pipe recipe result count must be positive");
        }
        return new ItemStack(item, count);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return findMatch(input) != null;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        Match match = findMatch(input);
        if (match == null) {
            return ItemStack.EMPTY;
        }
        ItemStack output = result.copy();
        if (mode == Mode.BASE) {
            ItemPipeHolder.setPipeColor(output, colorFromGlass(match.colorSource));
        } else {
            ItemPipeHolder.copyPipeColor(match.colorSource, output);
        }
        return output;
    }

    @Nullable
    private Match findMatch(CraftingInput input) {
        if (mode == Mode.BASE) {
            return findBaseMatch(input);
        }

        ItemStack source = ItemStack.EMPTY;
        boolean foundAdditional = mode == Mode.DOWNGRADE;
        int nonEmpty = 0;
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            nonEmpty++;
            if (source.isEmpty() && from.test(stack)) {
                source = stack;
            } else if (mode == Mode.UPGRADE && !foundAdditional && additional.test(stack)) {
                foundAdditional = true;
            } else {
                return null;
            }
        }
        int expected = mode == Mode.UPGRADE ? 2 : 1;
        return nonEmpty == expected && !source.isEmpty() && foundAdditional ? new Match(source) : null;
    }

    @Nullable
    private Match findBaseMatch(CraftingInput input) {
        int width = input.width();
        int height = input.height();
        if (width < 3) {
            return null;
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x <= width - 3; x++) {
                Match direct = matchesBaseAt(input, x, y, false);
                if (direct != null) {
                    return direct;
                }
                Match mirrored = matchesBaseAt(input, x, y, true);
                if (mirrored != null) {
                    return mirrored;
                }
            }
        }
        return null;
    }

    @Nullable
    private Match matchesBaseAt(CraftingInput input, int startX, int startY, boolean mirrored) {
        ItemStack glass = ItemStack.EMPTY;
        for (int y = 0; y < input.height(); y++) {
            for (int x = 0; x < input.width(); x++) {
                ItemStack stack = input.getItem(x + y * input.width());
                Ingredient expected = Ingredient.EMPTY;
                if (y == startY && x >= startX && x < startX + 3) {
                    int patternX = mirrored ? 2 - (x - startX) : x - startX;
                    expected = patternX == 0 ? left : patternX == 1 ? middle : right;
                    if (patternX == 1) {
                        glass = stack;
                    }
                }
                if (expected == Ingredient.EMPTY) {
                    if (!stack.isEmpty()) {
                        return null;
                    }
                } else if (!expected.test(stack)) {
                    return null;
                }
            }
        }
        return glass.isEmpty() ? null : new Match(glass);
    }

    @Nullable
    private static DyeColor colorFromGlass(ItemStack stack) {
        if (stack.getItem() instanceof BlockItem blockItem
            && blockItem.getBlock() instanceof StainedGlassBlock stainedGlass) {
            return stainedGlass.getColor();
        }
        return null;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return mode == Mode.BASE ? width >= 3 && height >= 1 : width * height >= ingredients.size();
    }

    /**
     * JEI cannot infer that BASE pipe recipes are a 3x1 shaped recipe because this
     * serializer also represents shapeless upgrade and downgrade recipes.
     */
    public boolean hasShapedBasePattern() {
        return mode == Mode.BASE;
    }

    /** Static result used by recipe viewers. Runtime crafting may additionally copy pipe colour. */
    public ItemStack getDisplayResult() {
        return result.copy();
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return result.copy();
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        return ingredients;
    }

    @Override
    public String getGroup() {
        return group;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return BCTransportRecipes.PIPE.get();
    }

    @Override
    public CraftingBookCategory category() {
        return CraftingBookCategory.MISC;
    }

    private record Match(ItemStack colorSource) {
    }

    private static PipeRecipe readFromNetwork(RegistryFriendlyByteBuf buffer) {
        Mode mode = buffer.readEnum(Mode.class);
        String group = buffer.readUtf();
        Ingredient left = Ingredient.CONTENTS_STREAM_CODEC.decode(buffer);
        Ingredient middle = Ingredient.CONTENTS_STREAM_CODEC.decode(buffer);
        Ingredient right = Ingredient.CONTENTS_STREAM_CODEC.decode(buffer);
        Ingredient from = Ingredient.CONTENTS_STREAM_CODEC.decode(buffer);
        Ingredient additional = Ingredient.CONTENTS_STREAM_CODEC.decode(buffer);
        ItemStack result = ItemStack.STREAM_CODEC.decode(buffer);
        return new PipeRecipe(group, mode, left, middle, right, from, additional, result);
    }

    private void writeToNetwork(RegistryFriendlyByteBuf buffer) {
        buffer.writeEnum(mode);
        buffer.writeUtf(group);
        Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, left);
        Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, middle);
        Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, right);
        Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, from);
        Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, additional);
        ItemStack.STREAM_CODEC.encode(buffer, result);
    }

    public static final class Serializer implements RecipeSerializer<PipeRecipe> {
        @Override
        public MapCodec<PipeRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, PipeRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
