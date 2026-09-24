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
import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

public enum FacadeSwapRecipe implements CraftingRecipe, IRecipeViewable.IViewableGrid {
    INSTANCE;

    private static final int TIME_GAP = 500;
    private static final ChangingItemStack[] INPUTS = { null };
    private static ChangingItemStack outputs;

    public static final ResourceLocation ID = new ResourceLocation(BCSilicon.MODID, "special/facade_swap");
    public static final RecipeSerializer<FacadeSwapRecipe> SERIALIZER = new RecipeSerializer<>() {
        @Override
        public FacadeSwapRecipe fromJson(ResourceLocation id, JsonObject json) {
            return INSTANCE;
        }

        @Override
        public FacadeSwapRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
            return INSTANCE;
        }

        @Override
        public void toNetwork(FriendlyByteBuf buffer, FacadeSwapRecipe recipe) {
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

    @Override
    public boolean matches(CraftingContainer inventory, Level level) {
        if (!BCSiliconConfig.enableFacades) {
            return false;
        }
        return !assemble(inventory, level.registryAccess()).isEmpty();
    }

    @Override
    public ItemStack assemble(CraftingContainer inventory, RegistryAccess registryAccess) {
        if (!BCSiliconConfig.enableFacades) {
            return StackUtil.EMPTY;
        }
        ItemStack input = StackUtil.EMPTY;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
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

    @Override
    public ItemStack getResultItem(RegistryAccess registryAccess) {
        return StackUtil.EMPTY;
    }

    @Override
    public ChangingItemStack[] getRecipeInputs() {
        if (!BCSiliconConfig.enableFacades) {
            return new ChangingItemStack[0];
        }
        if (INPUTS[0] == null) {
            genRecipes();
        }
        return INPUTS;
    }

    @Override
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

    @Override
    public int getRecipeWidth() {
        return 1;
    }

    @Override
    public int getRecipeHeight() {
        return 1;
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width >= 1 && height >= 1;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return BCSiliconRecipes.FACADE_SWAP_SERIALIZER.get();
    }

    @Override
    public RecipeType<CraftingRecipe> getType() {
        return RecipeType.CRAFTING;
    }

    @Override
    public CraftingBookCategory category() {
        return CraftingBookCategory.MISC;
    }
}
