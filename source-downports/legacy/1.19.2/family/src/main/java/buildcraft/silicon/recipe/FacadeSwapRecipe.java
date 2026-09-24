/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.recipe;

import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.recipe.ChangingItemStack;
import buildcraft.lib.recipe.IRecipeViewable;
import buildcraft.silicon.BCSilicon;
import buildcraft.silicon.BCSiliconConfig;
import buildcraft.silicon.BCSiliconItems;
import buildcraft.silicon.item.ItemPluggableFacade;
import buildcraft.silicon.plug.FacadeBlockStateInfo;
import buildcraft.silicon.plug.FacadeInstance;
import buildcraft.silicon.plug.FacadeStateManager;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SimpleRecipeSerializer;
import net.minecraft.world.level.Level;

public enum FacadeSwapRecipe implements CraftingRecipe, IRecipeViewable.IViewableGrid {
    INSTANCE;

    private static final int TIME_GAP = 500;

    private static final ChangingItemStack[] INPUTS = { null };
    private static ChangingItemStack OUTPUTS;
    
    public static final ResourceLocation ID = new ResourceLocation(BCSilicon.MODID, "special/facade_swap");
    public static final RecipeSerializer<FacadeSwapRecipe> SERIALIZER = new SimpleRecipeSerializer<>((a)->INSTANCE);
    

    public static void genRecipes() {
        NonNullList<ItemStack> list1 = NonNullList.create();
        NonNullList<ItemStack> list2 = NonNullList.create();
        for (FacadeBlockStateInfo info : FacadeStateManager.validFacadeStates.values()) {
            if (info.isVisible) {
                ItemStack stack = createFacade(info, false);
                ItemStack stackHollow = createFacade(info, true);
                list1.add(stack);
                list1.add(stackHollow);
                list2.add(stackHollow);
                list2.add(stack);
            }
        }
        if (!list1.isEmpty()) {
            INPUTS[0] = new ChangingItemStack(list1);
            INPUTS[0].setTimeGap(TIME_GAP);

            OUTPUTS = new ChangingItemStack(list2);
            OUTPUTS.setTimeGap(TIME_GAP);
        }
    }

    @Override
    public boolean matches(CraftingContainer inv, Level world) {
        if (!BCSiliconConfig.enableFacades) {
            return false;
        }
        return !assemble(inv).isEmpty();
    }

    @Override
    public ItemStack assemble(CraftingContainer inv) {
        if (!BCSiliconConfig.enableFacades) {
            return StackUtil.EMPTY;
        }
        ItemStack stackIn = StackUtil.EMPTY;
        for (int s = 0; s < inv.getContainerSize(); s++) {
            ItemStack stack = inv.getItem(s);
            if (!stack.isEmpty()) {
                if (stackIn.isEmpty()) {
                    stackIn = stack;
                } else {
                    return StackUtil.EMPTY;
                }
            }
        }
        if (stackIn.getItem() != BCSiliconItems.PLUG_FACADE_ITEM.get()) {
            return StackUtil.EMPTY;
        }
        FacadeInstance states = ItemPluggableFacade.getStates(stackIn);
        states = states.withSwappedIsHollow();
        return BCSiliconItems.PLUG_FACADE_ITEM.get().createItemStack(states);
    }

    @Override
    public ItemStack getResultItem() {
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
        if (OUTPUTS == null) {
            genRecipes();
        }
        return OUTPUTS;
    }

    private static ItemStack createFacade(FacadeBlockStateInfo info, boolean isHollow) {
        FacadeInstance state = FacadeInstance.createSingle(info, isHollow);
        return BCSiliconItems.PLUG_FACADE_ITEM.get().createItemStack(state);
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
		return SERIALIZER;
	}

	@Override
	public RecipeType<?> getType() {
		return RecipeType.CRAFTING;
	}
}
