package buildcraft.transport.recipe;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;

import buildcraft.transport.BCTransportRecipes;
import buildcraft.transport.item.ItemPipeHolder;
import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraftforge.common.crafting.CraftingHelper;

/**
 * Recreates the BC 8 pipe recipes while storing pipe colour in modern item NBT.
 * Base recipes accept colourless or stained glass; upgrades and undo recipes retain
 * the source pipe colour.
 */
public final class PipeRecipe implements CraftingRecipe {
    public enum Mode {
        BASE,
        UPGRADE,
        DOWNGRADE;

        static Mode read(String value) {
            return switch (value.toLowerCase()) {
                case "base" -> BASE;
                case "upgrade" -> UPGRADE;
                case "downgrade", "undo" -> DOWNGRADE;
                default -> throw new IllegalArgumentException("Unknown pipe recipe mode: " + value);
            };
        }
    }

    private final ResourceLocation id;
    private final String group;
    private final Mode mode;
    private final Ingredient left;
    private final Ingredient middle;
    private final Ingredient right;
    private final Ingredient from;
    private final Ingredient additional;
    private final ItemStack result;
    private final NonNullList<Ingredient> ingredients;

    private PipeRecipe(ResourceLocation id, String group, Mode mode, Ingredient left, Ingredient middle,
            Ingredient right, Ingredient from, Ingredient additional, ItemStack result) {
        this.id = id;
        this.group = group;
        this.mode = mode;
        this.left = left;
        this.middle = middle;
        this.right = right;
        this.from = from;
        this.additional = additional;
        this.result = result;
        this.ingredients = NonNullList.create();
        if (mode == Mode.BASE) {
            this.ingredients.add(left);
            this.ingredients.add(middle);
            this.ingredients.add(right);
        } else {
            this.ingredients.add(from);
            if (mode == Mode.UPGRADE) {
                this.ingredients.add(additional);
            }
        }
    }

    @Override
    public boolean matches(CraftingContainer container, Level level) {
        return findMatch(container) != null;
    }

    @Override
    public ItemStack assemble(CraftingContainer container) {
        Match match = findMatch(container);
        if (match == null) {
            return ItemStack.EMPTY;
        }
        ItemStack output = result.copy();
        if (mode == Mode.BASE) {
            DyeColor color = colorFromGlass(match.colorSource);
            ItemPipeHolder.setPipeColor(output, color);
        } else {
            ItemPipeHolder.copyPipeColor(match.colorSource, output);
        }
        return output;
    }

    @Nullable
    private Match findMatch(CraftingContainer container) {
        if (mode == Mode.BASE) {
            return findBaseMatch(container);
        }

        ItemStack source = ItemStack.EMPTY;
        boolean foundAdditional = mode == Mode.DOWNGRADE;
        int nonEmpty = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
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
    private Match findBaseMatch(CraftingContainer container) {
        int width = container.getWidth();
        int height = container.getHeight();
        if (width < 3) {
            return null;
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x <= width - 3; x++) {
                Match direct = matchesBaseAt(container, x, y, false);
                if (direct != null) {
                    return direct;
                }
                Match mirrored = matchesBaseAt(container, x, y, true);
                if (mirrored != null) {
                    return mirrored;
                }
            }
        }
        return null;
    }

    @Nullable
    private Match matchesBaseAt(CraftingContainer container, int startX, int startY, boolean mirrored) {
        ItemStack glass = ItemStack.EMPTY;
        for (int y = 0; y < container.getHeight(); y++) {
            for (int x = 0; x < container.getWidth(); x++) {
                ItemStack stack = container.getItem(x + y * container.getWidth());
                Ingredient expected = Ingredient.EMPTY;
                if (y == startY && x >= startX && x < startX + 3) {
                    int patternX = x - startX;
                    if (mirrored) {
                        patternX = 2 - patternX;
                    }
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
    public ItemStack getResultItem() {
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
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return BCTransportRecipes.PIPE.get();
    }

    private record Match(ItemStack colorSource) {
    }

    public static final class Serializer implements RecipeSerializer<PipeRecipe> {
        @Override
        public PipeRecipe fromJson(ResourceLocation id, JsonObject json) {
            String group = GsonHelper.getAsString(json, "group", "");
            Mode mode = Mode.read(GsonHelper.getAsString(json, "mode"));
            ItemStack result = CraftingHelper.getItemStack(GsonHelper.getAsJsonObject(json, "result"), true, true);
            return switch (mode) {
                case BASE -> new PipeRecipe(id, group, mode,
                        Ingredient.fromJson(json.get("left")),
                        Ingredient.fromJson(json.get("middle")),
                        Ingredient.fromJson(json.get("right")),
                        Ingredient.EMPTY, Ingredient.EMPTY, result);
                case UPGRADE -> new PipeRecipe(id, group, mode,
                        Ingredient.EMPTY, Ingredient.EMPTY, Ingredient.EMPTY,
                        Ingredient.fromJson(json.get("from")),
                        Ingredient.fromJson(json.get("additional")), result);
                case DOWNGRADE -> new PipeRecipe(id, group, mode,
                        Ingredient.EMPTY, Ingredient.EMPTY, Ingredient.EMPTY,
                        Ingredient.fromJson(json.get("from")), Ingredient.EMPTY, result);
            };
        }

        @Override
        public @Nullable PipeRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
            Mode mode = buffer.readEnum(Mode.class);
            String group = buffer.readUtf();
            Ingredient left = Ingredient.fromNetwork(buffer);
            Ingredient middle = Ingredient.fromNetwork(buffer);
            Ingredient right = Ingredient.fromNetwork(buffer);
            Ingredient from = Ingredient.fromNetwork(buffer);
            Ingredient additional = Ingredient.fromNetwork(buffer);
            ItemStack result = buffer.readItem();
            return new PipeRecipe(id, group, mode, left, middle, right, from, additional, result);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buffer, PipeRecipe recipe) {
            buffer.writeEnum(recipe.mode);
            buffer.writeUtf(recipe.group);
            recipe.left.toNetwork(buffer);
            recipe.middle.toNetwork(buffer);
            recipe.right.toNetwork(buffer);
            recipe.from.toNetwork(buffer);
            recipe.additional.toNetwork(buffer);
            buffer.writeItem(recipe.result);
        }
    }
}
