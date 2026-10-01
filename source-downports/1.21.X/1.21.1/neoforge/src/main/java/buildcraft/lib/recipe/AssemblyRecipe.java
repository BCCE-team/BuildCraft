package buildcraft.lib.recipe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import com.google.common.collect.ImmutableSet;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import buildcraft.lib.internal.core.BuildCraftAPI;
import buildcraft.lib.internal.recipes.IngredientStack;
import buildcraft.silicon.BCSiliconRecipes;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

public class AssemblyRecipe extends AssemblyRecipeBasic {
    private static final ResourceLocation UNNAMED_ID =
        ResourceLocation.fromNamespaceAndPath("buildcraftlib", "unnamed_assembly_recipe");

    private static final Codec<ItemStack> LEGACY_RESULT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
        BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(ItemStack::getItem),
        Codec.INT.optionalFieldOf("count", 1).forGetter(ItemStack::getCount),
        Codec.STRING.optionalFieldOf("nbt", "").forGetter(AssemblyRecipe::legacyCustomData)
    ).apply(instance, AssemblyRecipe::legacyStack));

    private static final MapCodec<AssemblyRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
        ResourceLocation.CODEC.optionalFieldOf("id", UNNAMED_ID).forGetter(AssemblyRecipe::getId),
        Codec.STRING.optionalFieldOf("group", "").forGetter(AssemblyRecipe::getGroup),
        Ingredient.CODEC.listOf().fieldOf("ingredients").forGetter(AssemblyRecipe::ingredientList),
        Codec.INT.listOf().fieldOf("ingredient_counts").forGetter(AssemblyRecipe::ingredientCountList),
        LEGACY_RESULT_CODEC.fieldOf("result").forGetter(recipe -> recipe.output),
        Codec.LONG.fieldOf("MJ").forGetter(recipe -> recipe.requiredMicroJoules)
    ).apply(instance, AssemblyRecipe::fromCodec));

    private static final StreamCodec<RegistryFriendlyByteBuf, AssemblyRecipe> STREAM_CODEC =
        StreamCodec.ofMember(AssemblyRecipe::writeToNetwork, AssemblyRecipe::readFromNetwork);

    final long requiredMicroJoules;
    final ImmutableSet<IngredientStack> requiredStacks;
    final ItemStack output;
    final String group;

    public AssemblyRecipe(ResourceLocation name, long requiredMicroJoules,
        ImmutableSet<IngredientStack> requiredStacks, @Nonnull ItemStack output, String group) {
        this.requiredMicroJoules = requiredMicroJoules;
        this.requiredStacks = ImmutableSet.copyOf(requiredStacks);
        this.output = output.copy();
        this.name = name;
        this.group = group == null ? "" : group;
    }

    public AssemblyRecipe(String name, long requiredMicroJoules,
        ImmutableSet<IngredientStack> requiredStacks, @Nonnull ItemStack output, String group) {
        this(BuildCraftAPI.nameToResourceLocation(name), requiredMicroJoules, requiredStacks, output, group);
    }

    public AssemblyRecipe(String name, long requiredMicroJoules,
        Set<IngredientStack> requiredStacks, @Nonnull ItemStack output, String group) {
        this(name, requiredMicroJoules, ImmutableSet.copyOf(requiredStacks), output, group);
    }

    @Override
    public long getRequiredMicroJoulesFor(ItemStack output) {
        return requiredMicroJoules;
    }

    @Override
    public Set<ItemStack> getOutputs(IItemHandlerModifiable inputs) {
        return hasRequiredInputs(inputs)
            ? ImmutableSet.of(output.copy())
            : ImmutableSet.of();
    }

    private boolean hasRequiredInputs(RecipeInput input) {
        for (IngredientStack required : requiredStacks) {
            boolean matched = false;
            for (int slot = 0; slot < input.size(); slot++) {
                ItemStack candidate = input.getItem(slot);
                if (!candidate.isEmpty()
                    && required.ingredient.test(candidate)
                    && candidate.getCount() >= required.count) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private boolean hasRequiredInputs(IItemHandlerModifiable input) {
        for (IngredientStack required : requiredStacks) {
            boolean matched = false;
            for (int slot = 0; slot < input.getSlots(); slot++) {
                ItemStack candidate = input.getStackInSlot(slot);
                if (!candidate.isEmpty()
                    && required.ingredient.test(candidate)
                    && candidate.getCount() >= required.count) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean matches(RecipeInput input, Level level) {
        return hasRequiredInputs(input);
    }

    @Override
    public ItemStack assemble(RecipeInput input, HolderLookup.Provider registries) {
        return hasRequiredInputs(input) ? output.copy() : ItemStack.EMPTY;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return output.copy();
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> ingredients = NonNullList.create();
        for (IngredientStack stack : requiredStacks) {
            for (int count = 0; count < stack.count; count++) {
                ingredients.add(stack.ingredient);
            }
        }
        return ingredients;
    }

    @Override
    public Set<IngredientStack> getInputsFor(ItemStack output) {
        return requiredStacks;
    }

    @Override
    public Set<ItemStack> getOutputPreviews() {
        return ImmutableSet.of();
    }

    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public String getGroup() {
        return group;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return BCSiliconRecipes.ASSEMBLY_SERIALIZER.get();
    }

    private List<Ingredient> ingredientList() {
        List<Ingredient> ingredients = new ArrayList<>(requiredStacks.size());
        for (IngredientStack stack : requiredStacks) {
            ingredients.add(stack.ingredient);
        }
        return ingredients;
    }

    private List<Integer> ingredientCountList() {
        List<Integer> counts = new ArrayList<>(requiredStacks.size());
        for (IngredientStack stack : requiredStacks) {
            counts.add(stack.count);
        }
        return counts;
    }


    private static ItemStack legacyStack(Item item, int count, String nbt) {
        ItemStack stack = new ItemStack(item, count);
        if (!nbt.isBlank()) {
            try {
                CompoundTag tag = TagParser.parseTag(nbt);
                if (tag.contains("Damage") && stack.isDamageableItem()) {
                    stack.set(DataComponents.DAMAGE, Math.max(0, tag.getInt("Damage")));
                    tag.remove("Damage");
                }
                if (!tag.isEmpty()) {
                    stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                }
            } catch (CommandSyntaxException exception) {
                throw new IllegalArgumentException("Invalid legacy assembly-recipe item NBT", exception);
            }
        }
        return stack;
    }

    private static String legacyCustomData(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = customData == null ? new CompoundTag() : customData.copyTag();
        if (stack.isDamageableItem() && stack.getDamageValue() > 0) {
            tag.putInt("Damage", stack.getDamageValue());
        }
        return tag.isEmpty() ? "" : tag.toString();
    }

    private static AssemblyRecipe fromCodec(ResourceLocation id, String group, List<Ingredient> ingredients,
        List<Integer> counts, ItemStack result, long requiredMicroJoules) {
        if (ingredients.size() != counts.size()) {
            throw new IllegalArgumentException("Assembly recipe ingredients and ingredient_counts have different sizes");
        }
        Set<IngredientStack> stacks = new HashSet<>();
        for (int index = 0; index < ingredients.size(); index++) {
            int count = counts.get(index);
            if (count <= 0) {
                throw new IllegalArgumentException("Assembly recipe ingredient count must be positive");
            }
            stacks.add(new IngredientStack(ingredients.get(index), count));
        }
        return new AssemblyRecipe(id, requiredMicroJoules, ImmutableSet.copyOf(stacks), result, group);
    }

    private static AssemblyRecipe readFromNetwork(RegistryFriendlyByteBuf buffer) {
        ResourceLocation id = buffer.readResourceLocation();
        String group = buffer.readUtf();
        int size = buffer.readVarInt();
        Set<IngredientStack> stacks = new HashSet<>();
        for (int index = 0; index < size; index++) {
            Ingredient ingredient = Ingredient.CONTENTS_STREAM_CODEC.decode(buffer);
            int count = buffer.readVarInt();
            stacks.add(new IngredientStack(ingredient, count));
        }
        ItemStack result = ItemStack.STREAM_CODEC.decode(buffer);
        long power = buffer.readLong();
        return new AssemblyRecipe(id, power, ImmutableSet.copyOf(stacks), result, group);
    }

    private void writeToNetwork(RegistryFriendlyByteBuf buffer) {
        buffer.writeResourceLocation(name);
        buffer.writeUtf(group);
        buffer.writeVarInt(requiredStacks.size());
        for (IngredientStack stack : requiredStacks) {
            Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, stack.ingredient);
            buffer.writeVarInt(stack.count);
        }
        ItemStack.STREAM_CODEC.encode(buffer, output);
        buffer.writeLong(requiredMicroJoules);
    }

    public static final class Serializer implements RecipeSerializer<AssemblyRecipe> {
        @Override
        public MapCodec<AssemblyRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, AssemblyRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
