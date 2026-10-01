//? source if >=1.21.1
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.recipe;

import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.recipe.ChangingItemStack;
import buildcraft.lib.recipe.IRecipeViewable;
import buildcraft.silicon.BCSiliconConfig;
import buildcraft.silicon.BCSiliconItems;
import buildcraft.silicon.BCSiliconRecipes;
import buildcraft.silicon.item.ItemPluggableFacade;
import buildcraft.silicon.plug.FacadeBlockStateInfo;
import buildcraft.silicon.plug.FacadeInstance;
import buildcraft.silicon.plug.FacadeStateManager;
import buildcraft.silicon.BCSilicon;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.NonNullList;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.PlacementInfo;

public enum FacadeSwapRecipe implements CraftingRecipe, IRecipeViewable.IViewableGrid {
    INSTANCE;

    private static final int TIME_GAP = 500;
    private static final ChangingItemStack[] INPUTS = { null };
    private static ChangingItemStack outputs;

    public static final Identifier ID = Identifier.fromNamespaceAndPath(BCSilicon.MODID, "special/facade_swap");
    private static final MapCodec<FacadeSwapRecipe> CODEC = MapCodec.unit(INSTANCE);
    private static final StreamCodec<RegistryFriendlyByteBuf, FacadeSwapRecipe> STREAM_CODEC =
        StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<FacadeSwapRecipe> SERIALIZER = new RecipeSerializer<>() {
        public MapCodec<FacadeSwapRecipe> codec() {
            return CODEC;
        }

        public StreamCodec<RegistryFriendlyByteBuf, FacadeSwapRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    };

    public static void genRecipes() {
        NonNullList<ItemStack> inputs = NonNullList.create();
        NonNullList<ItemStack> outputStacks = NonNullList.create();
        for (FacadeBlockStateInfo info : FacadeStateManager.validFacadeStates.values()) {
            if (info.isVisible) {
                ItemStack stack = createFacade(info, false);
                ItemStack hollow = createFacade(info, true);
                inputs.add(stack);
                inputs.add(hollow);
                outputStacks.add(hollow);
                outputStacks.add(stack);
            }
        }
        if (!inputs.isEmpty()) {
            INPUTS[0] = new ChangingItemStack(inputs);
            INPUTS[0].setTimeGap(TIME_GAP);
            outputs = new ChangingItemStack(outputStacks);
            outputs.setTimeGap(TIME_GAP);
        }
    }

    public boolean matches(CraftingInput inventory, Level level) {
        if (!BCSiliconConfig.enableFacades) {
            return false;
        }
        return !assemble(inventory, level.registryAccess()).isEmpty();
    }

    public ItemStack assemble(CraftingInput inventory, HolderLookup.Provider registries) {
        if (!BCSiliconConfig.enableFacades) {
            return StackUtil.EMPTY;
        }
        ItemStack input = StackUtil.EMPTY;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                if (input.isEmpty()) {
                    input = stack;
                } else {
                    return StackUtil.EMPTY;
                }
            }
        }
        if (input.getItem() != BCSiliconItems.PLUG_FACADE_ITEM.get()) {
            return StackUtil.EMPTY;
        }
        FacadeInstance states = ItemPluggableFacade.getStates(input).withSwappedIsHollow();
        return BCSiliconItems.PLUG_FACADE_ITEM.get().createItemStack(states);
    }

    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return StackUtil.EMPTY;
    }

    public ChangingItemStack[] getRecipeInputs() {
        if (!BCSiliconConfig.enableFacades) {
            return new ChangingItemStack[0];
        }
        if (INPUTS[0] == null) {
            genRecipes();
        }
        return INPUTS;
    }

    public ChangingItemStack getRecipeOutputs() {
        if (!BCSiliconConfig.enableFacades) {
            return new ChangingItemStack(net.minecraft.core.NonNullList.<ItemStack>create());
        }
        if (outputs == null) {
            genRecipes();
        }
        return outputs;
    }

    private static ItemStack createFacade(FacadeBlockStateInfo info, boolean isHollow) {
        return BCSiliconItems.PLUG_FACADE_ITEM.get()
            .createItemStack(FacadeInstance.createSingle(info, isHollow));
    }

    public int getRecipeWidth() {
        return 1;
    }

    public int getRecipeHeight() {
        return 1;
    }

    public boolean canCraftInDimensions(int width, int height) {
        return width >= 1 && height >= 1;
    }

    public RecipeSerializer<? extends CraftingRecipe> getSerializer() {
        return BCSiliconRecipes.FACADE_SWAP_SERIALIZER.get();
    }

    public RecipeType<CraftingRecipe> getType() {
        return RecipeType.CRAFTING;
    }

    public PlacementInfo placementInfo() {
        return PlacementInfo.NOT_PLACEABLE;
    }

    public CraftingBookCategory category() {
        return CraftingBookCategory.MISC;
    }
}
