/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.recipe;

import buildcraft.api.v2.energy.MjAmount;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import com.google.common.collect.ImmutableSet;
import com.mojang.serialization.MapCodec;

import buildcraft.lib.internal.recipes.IngredientStack;
import buildcraft.lib.misc.ItemStackKey;
import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.recipe.AssemblyRecipeBasic;
import buildcraft.lib.recipe.ChangingItemStack;
import buildcraft.lib.recipe.ChangingObject;
import buildcraft.lib.recipe.IRecipeViewable;
import buildcraft.silicon.BCSiliconConfig;
import buildcraft.silicon.BCSiliconItems;
import buildcraft.silicon.BCSiliconRecipes;
import buildcraft.silicon.item.ItemPluggableFacade;
import buildcraft.silicon.plug.FacadeBlockStateInfo;
import buildcraft.silicon.plug.FacadeInstance;
import buildcraft.silicon.plug.FacadePhasedState;
import buildcraft.silicon.plug.FacadeStateManager;
import buildcraft.transport.BCTransportItems;
import net.minecraft.core.NonNullList;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

public class FacadeAssemblyRecipes extends AssemblyRecipeBasic implements IRecipeViewable.IRecipePowered {
    public FacadeAssemblyRecipes(ResourceLocation name) {
        this.name = name;
    }

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("buildcraftsilicon", "special/facade");
    public static final ResourceLocation LEGACY_ID = ResourceLocation.fromNamespaceAndPath("buildcrafttransport", "facaderecipes");
    public static final FacadeAssemblyRecipes INSTANCE = new FacadeAssemblyRecipes(ID);

    private static final int TIME_GAP = 500;
    private static final long MJ_COST = 64 * MjAmount.MICRO_MJ_PER_MJ;
    private static final ChangingObject<Long> MJ_COSTS = new ChangingObject<>(new Long[] { MJ_COST });

    public static FacadeAssemblyRecipes getInstance(ResourceLocation location) {
        return new FacadeAssemblyRecipes(location);
    }

    public static ItemStack createFacadeStack(FacadeBlockStateInfo info, boolean isHollow) {
        ItemStack stack = BCSiliconItems.PLUG_FACADE_ITEM.get().createItemStack(FacadeInstance.createSingle(info, isHollow));
        stack.setCount(6);
        return stack;
    }

    @Override
    public ChangingItemStack[] getRecipeInputs() {
        if (!BCSiliconConfig.enableFacades) {
            return new ChangingItemStack[0];
        }
        ChangingItemStack[] inputs = new ChangingItemStack[2];
        inputs[0] = new ChangingItemStack(baseRequirementStack());
        NonNullList<ItemStack> list = NonNullList.create();
        for (FacadeBlockStateInfo info : FacadeStateManager.validFacadeStates.values()) {
            if (info.isVisible) {
                list.add(info.requiredStack);
                list.add(info.requiredStack);
            }
        }
        inputs[1] = new ChangingItemStack(list);
        inputs[1].setTimeGap(TIME_GAP);
        return inputs;
    }

    @Override
    public ChangingItemStack getRecipeOutputs() {
        if (!BCSiliconConfig.enableFacades) {
            return new ChangingItemStack(NonNullList.<ItemStack>create());
        }
        NonNullList<ItemStack> list = NonNullList.create();
        for (FacadeBlockStateInfo info : FacadeStateManager.validFacadeStates.values()) {
            if (info.isVisible) {
                list.add(createFacadeStack(info, false));
                list.add(createFacadeStack(info, true));
            }
        }
        ChangingItemStack changing = new ChangingItemStack(list);
        changing.setTimeGap(TIME_GAP);
        return changing;
    }

    @Override
    public ChangingObject<Long> getMjCost() {
        return MJ_COSTS;
    }

    @Override
    public Set<ItemStack> getOutputs(IItemHandlerModifiable inputs) {
        if (!BCSiliconConfig.enableFacades) {
            return Collections.emptySet();
        }
        if (!StackUtil.contains(baseRequirementStack(), inputs)) {
            return Collections.emptySet();
        }

        ArrayList<ItemStack> stacks = new ArrayList<>();
        int size = inputs.getSlots();
        for (int i = 0;i<size;i++) {
            ItemStack stack = inputs.getStackInSlot(i).copy();
            stack.setCount(1);
            List<FacadeBlockStateInfo> infos = FacadeStateManager.stackFacades.get(new ItemStackKey(stack));
            if (infos == null || infos.isEmpty()) {
                continue;
            }
            for (FacadeBlockStateInfo info : infos) {
                stacks.add(createFacadeStack(info, false));
                stacks.add(createFacadeStack(info, true));
            }
        }
        return ImmutableSet.copyOf(stacks);
    }

    private static ItemStack baseRequirementStack() {
        if (!BCTransportItems.PIPE_STRUCTURE.isBound()) {
            return new ItemStack(Blocks.COBBLESTONE_WALL);
        }
        return new ItemStack(BCTransportItems.PIPE_STRUCTURE.get(), 3);
    }

    @Override
    public Set<ItemStack> getOutputPreviews() {
        return Collections.emptySet();
    }

    @Override
    public Set<IngredientStack> getInputsFor(@Nonnull ItemStack output) {
        if (!BCSiliconConfig.enableFacades) {
            return Collections.emptySet();
        }
        FacadePhasedState state = ItemPluggableFacade.getStates(output).getCurrentStateForStack();
        ItemStack stateRequirement = state.stateInfo.requiredStack;
        IngredientStack ingredientType = new IngredientStack(Ingredient.of(stateRequirement));
        IngredientStack ingredientBase = new IngredientStack(Ingredient.of(baseRequirementStack()), 3);

        return ImmutableSet.of(ingredientType, ingredientBase);
    }

    @Override
    public long getRequiredMicroJoulesFor(@Nonnull ItemStack output) {
        return MJ_COST;
    }

    @Override
    public boolean canCraftInDimensions(int p_43999_, int p_44000_) {
        return true;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        if (!BCSiliconConfig.enableFacades) {
            return ItemStack.EMPTY;
        }
        return FacadeStateManager.validFacadeStates.values().stream()
            .filter(info -> info.isVisible && !info.requiredStack.isEmpty())
            .findFirst()
            .map(info -> createFacadeStack(info, false))
            .orElse(ItemStack.EMPTY);
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return BCSiliconRecipes.FACADE_SERIALIZER.get();
    }
    public static final class Serializer implements RecipeSerializer<FacadeAssemblyRecipes> {
        private static final MapCodec<FacadeAssemblyRecipes> CODEC = MapCodec.unit(INSTANCE);
        private static final StreamCodec<RegistryFriendlyByteBuf, FacadeAssemblyRecipes> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

        @Override
        public MapCodec<FacadeAssemblyRecipes> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, FacadeAssemblyRecipes> streamCodec() {
            return STREAM_CODEC;
        }
    }

}
