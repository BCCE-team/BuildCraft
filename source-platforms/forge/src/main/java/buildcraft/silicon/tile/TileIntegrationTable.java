/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.tile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.recipes.IngredientStack;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.recipe.CountedIngredient;
import buildcraft.api.v2.recipe.IntegrationRecipeDefinition;
import buildcraft.api.v2.recipe.MachineRecipeService;
import buildcraft.api.v2.recipe.RecipeMatch;
import buildcraft.lib.gui.ItemProvider;
import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.tile.item.ItemHandlerManager;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.silicon.BCSiliconBlocks;
import buildcraft.silicon.container.ContainerIntegrationTable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;
import net.minecraftforge.network.NetworkHooks;

public class TileIntegrationTable extends TileLaserTableBase implements MenuProvider{
	
	public TileIntegrationTable(BlockPos pos, BlockState state) {
		super(BCSiliconBlocks.INTERGRATION_TABLE_TILE.get(), pos, state);
	}

	public final ItemHandlerSimple invTarget = itemManager.addInvHandler(
        "target",
        1,
        ItemHandlerManager.EnumAccess.BOTH,
        EnumPipePart.VALUES
    );
    public final ItemHandlerSimple invToIntegrate = itemManager.addInvHandler(
        "toIntegrate",
        3 * 3 - 1,
        ItemHandlerManager.EnumAccess.BOTH,
        EnumPipePart.VALUES
    );
    public final ItemHandlerSimple invResult = itemManager.addInvHandler(
        "result",
        1,
        ItemHandlerManager.EnumAccess.EXTRACT,
        EnumPipePart.VALUES
    );
    public final ItemProvider invOutput = new ItemProvider((i) -> getOutput(), 1);
    public RecipeMatch<IntegrationRecipeDefinition> recipe;

    private boolean extract(CountedIngredient item, List<CountedIngredient> items, boolean simulate) {
        ItemStack targetStack = invTarget.getStackInSlot(0);
        if (targetStack.isEmpty() || !item.test(targetStack)) return false;
        List<IngredientStack> legacyItems = new ArrayList<>(items.size());
        for (CountedIngredient definition : items) {
            legacyItems.add(new IngredientStack(definition.ingredient(), definition.count()));
        }
        if (!extract(invToIntegrate, legacyItems, simulate, true)) return false;
        if (!simulate) {
            targetStack.setCount(targetStack.getCount() - item.count());
            invTarget.setStackInSlot(0, targetStack);
        }
        return true;
    }

    private static MachineRecipeService recipeService() {
        return BuildCraftApi.service(BuildCraftServices.MACHINE_RECIPES);
    }

    private boolean isSpaceEnough(ItemStack stack) {
        ItemStack output = invResult.getStackInSlot(0);
        return output.isEmpty() || (StackUtil.canMerge(stack, output) && stack.getCount() + output.getCount() <= stack.getMaxStackSize());
    }

    private void updateRecipe() {
        if (recipe != null) {
            IntegrationRecipeDefinition definition = recipe.recipe();
            ItemStack output = getOutput();
            if (!output.isEmpty() && extract(definition.centerIngredient(), definition.requirements(output), true)) {
                return;
            }
        }
        recipe = recipeService().findIntegration(invTarget.getStackInSlot(0), invToIntegrate.stacks).orElse(null);
    }

    public ItemStack getOutput() {
        return recipe != null
            ? recipe.recipe().output(invTarget.getStackInSlot(0), invToIntegrate.stacks)
            : ItemStack.EMPTY;
    }

    @Override
    public long getTarget() {
        ItemStack output = getOutput();
        return recipe != null && isSpaceEnough(output) ? recipe.recipe().requiredMicroJoules(output) : 0;
    }

    @Override
    public void update() {
        super.update();

        if (level.isClientSide) {
            return;
        }

        updateRecipe();

        long target = getTarget();
        if (target > 0 && power >= target) {
            ItemStack output = getOutput();
            IntegrationRecipeDefinition definition = recipe.recipe();
            if (extract(definition.centerIngredient(), definition.requirements(output), false)) {
                ItemStack result = invResult.getStackInSlot(0);
                if (!result.isEmpty()) {
                    result = result.copy();
                    result.setCount(result.getCount() + output.getCount());
                } else {
                    result = output.copy();
                }
                invResult.setStackInSlot(0, result);
                power -= target;
            }
        }

        sendNetworkGuiUpdate(NET_GUI_DATA);
    }
    
    @Override
	public void saveAdditional(CompoundTag nbt) {
		super.saveAdditional(nbt);
		if (recipe != null) {
			nbt.putString("recipe", recipe.id().toString());
		}
	}
    
	@Override
	public void load(CompoundTag nbt) {
		super.load(nbt);
        if (nbt.contains("recipe")) {
            recipe = lookupRecipe(nbt.getString("recipe"));
        } else {
            recipe = null;
        }
	}

    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);

        if (id == NET_GUI_DATA) {
            buffer.writeBoolean(recipe != null);
            if (recipe != null) {
                buffer.writeUtf(recipe.id().toString());
            }
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);

        if (id == NET_GUI_DATA) {
            if (buffer.readBoolean()) {
                recipe = lookupRecipe(buffer.readUtf());
            } else {
                recipe = null;
            }
        }
    }

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        super.getDebugInfo(left, right, side);
        left.add("recipe - " + recipe);
        left.add("target - " + getTarget());
    }

    private RecipeMatch<IntegrationRecipeDefinition> lookupRecipe(String name) {
        ResourceLocation id = new ResourceLocation(name);
        return recipeService().snapshot().resolved(id)
            .filter(resolved -> resolved.value() instanceof IntegrationRecipeDefinition)
            .map(resolved -> new RecipeMatch<>(
                resolved.id(), (IntegrationRecipeDefinition) resolved.value(), resolved.provenance()
            ))
            .orElse(null);
    }
    
	@Override
	public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {
		if(player instanceof ServerPlayer splayer) {
			NetworkHooks.openScreen(splayer, this, worldPosition);
		}
		return super.onActivated(player, hand, hit);
	}

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory inventory, Player p_39956_) {
		return new ContainerIntegrationTable(id, inventory, invTarget, invToIntegrate, invOutput, invResult, ContainerLevelAccess.create(level, worldPosition));
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable(this.getBlockState().getBlock().getDescriptionId());
	}
}
