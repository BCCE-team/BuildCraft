/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.tile;

import buildcraft.api.v2.energy.MjAmount;

import java.io.IOException;

import javax.annotation.Nonnull;

import buildcraft.lib.platform.storage.MutableItemStorage;
import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.mj.IMjConnector;
import buildcraft.lib.internal.mj.IMjRedstoneReceiver;
import buildcraft.lib.internal.mj.MjCapabilityHelper;
import buildcraft.lib.tile.craft.IAutoCraft;
import buildcraft.lib.tile.craft.WorkbenchCrafting;
import buildcraft.lib.tile.item.ItemHandlerManager.EnumAccess;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.silicon.BCSiliconBlocks;
import buildcraft.silicon.container.ContainerAdvancedCraftingTable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
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
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;
import net.minecraftforge.network.NetworkHooks;

public class TileAdvancedCraftingTable extends TileLaserTableBase implements IAutoCraft, MenuProvider, IMjRedstoneReceiver {
    private static final long POWER_REQ = 500 * MjAmount.MICRO_MJ_PER_MJ;

    public final ItemHandlerSimple invBlueprint;
    public final ItemHandlerSimple invMaterials;
    public final ItemHandlerSimple invResults;
    private final WorkbenchCrafting crafting;

    public final ItemHandlerSimple resultClient = new ItemHandlerSimple(1);
    private int recipeSelectionIndexClient = -1;
    private int recipeSelectionCountClient;

    public TileAdvancedCraftingTable(BlockPos pos, BlockState state) {
    	super(BCSiliconBlocks.ADVANCED_CRAFTING_TABLE_TILE.get(), pos, state);
        invBlueprint = itemManager.addInvHandler("blueprint", 3 * 3, EnumAccess.PHANTOM);
        invMaterials = itemManager.addInvHandler("materials", 5 * 3, EnumAccess.INSERT, EnumPipePart.VALUES);
        invResults = itemManager.addInvHandler("result", 3 * 3, EnumAccess.EXTRACT, EnumPipePart.VALUES);
        crafting = new WorkbenchCrafting(3, 3, this, invBlueprint, invMaterials, invResults);
        caps.addProvider(new MjCapabilityHelper(this));
    }

    @Override
    public void saveAdditional(CompoundTag nbt) {
        super.saveAdditional(nbt);
        crafting.writeSelection(nbt);
    }

    @Override
    public void load(CompoundTag nbt) {
        super.load(nbt);
        crafting.readSelection(nbt);
    }

    @Override
    protected void onSlotChange(MutableItemStorage handler, int slot, @Nonnull ItemStack before,
        @Nonnull ItemStack after) {
        super.onSlotChange(handler, slot, before, after);
        //? if <1.20 {
        if (!ItemStack.isSame(before, after)) {
        //?} else {
        /*?
        if (!ItemStack.isSameItemSameTags(before, after)) {
        ?*/
        //?}
            crafting.onInventoryChange(handler);
        }
    }

    @Override
    public long getTarget() {
        return level.isClientSide ? POWER_REQ : crafting.canCraft() ? POWER_REQ : 0;
    }

    @Override
    public void update() {
        super.update();
        if (level.isClientSide) {
            return;
        }
        boolean didChange = crafting.tick();
        if (crafting.canCraft()) {
            if (power >= POWER_REQ) {
                if (crafting.craft()) {
                    // This is used for #hasWork(), to ensure that it doesn't return
                    // false for the one tick in between crafts.
                    power -= POWER_REQ;
                }
            }
        }
        if (didChange) {
            resultClient.setStackInSlot(0, crafting.getAssumedResult());
            sendNetworkGuiUpdate(NET_GUI_DATA);
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_GUI_DATA) {
                recipeSelectionIndexClient = buffer.readInt();
                recipeSelectionCountClient = buffer.readInt();
            }
        }
    }

    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_GUI_DATA) {
                resultClient.setStackInSlot(0, crafting.getAssumedResult());
                buffer.writeInt(crafting.getSelectedRecipeIndex());
                buffer.writeInt(crafting.getMatchingRecipeCount());
            }
        }
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
		return new ContainerAdvancedCraftingTable(id, inventory, invMaterials, invResults, invBlueprint, resultClient, ContainerLevelAccess.create(level, worldPosition));
	}

    @Override
    public Component getDisplayName() {
        return Component.translatable(this.getBlockState().getBlock().getDescriptionId());
    }

    public WorkbenchCrafting getWorkbenchCrafting() {
        return crafting;
    }

    public int getRecipeSelectionIndex() {
        return level != null && level.isClientSide ? recipeSelectionIndexClient : crafting.getSelectedRecipeIndex();
    }

    public int getRecipeSelectionCount() {
        return level != null && level.isClientSide ? recipeSelectionCountClient : crafting.getMatchingRecipeCount();
    }

    public boolean cycleRecipe(int delta) {
        if (level == null || level.isClientSide || !crafting.selectRecipe(delta)) {
            return false;
        }
        resultClient.setStackInSlot(0, crafting.getAssumedResult());
        setChanged();
        sendNetworkGuiUpdate(NET_GUI_DATA);
        return true;
    }


    @Override
    public boolean canConnect(@Nonnull IMjConnector other) {
        return true;
    }

    @Override
    public long getPowerRequested() {
        return getDirectPowerRequested();
    }

    @Override
    public long receivePower(long microJoules, FluidAction action) {
        return receiveDirectPower(microJoules, action);
    }

    // IAutoCraft

    @Override
    public ItemStack getCurrentRecipeOutput() {
        return crafting.getAssumedResult();
    }

    @Override
    public ItemHandlerSimple getInvBlueprint() {
        return invBlueprint;
    }
}
