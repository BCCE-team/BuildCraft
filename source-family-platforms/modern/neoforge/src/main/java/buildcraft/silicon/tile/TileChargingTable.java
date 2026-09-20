//? source if >=1.21.1
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.tile;

import buildcraft.api.v2.energy.MjAmount;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.mj.IMjConnector;
import buildcraft.lib.internal.mj.IMjRedstoneReceiver;
import buildcraft.lib.internal.mj.MjCapabilityHelper;
import buildcraft.lib.tile.item.ItemHandlerManager;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.lib.platform.storage.EnergyStorage;
import buildcraft.lib.platform.storage.PlatformStorage;
import buildcraft.silicon.BCSiliconBlocks;
import buildcraft.silicon.container.ContainerChargingTable;
import net.minecraft.core.BlockPos;
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
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

public class TileChargingTable extends TileLaserTableBase implements MenuProvider, IMjRedstoneReceiver {
    public final ItemHandlerSimple invCharge;

    public TileChargingTable(BlockPos pos, BlockState state) {
        super(BCSiliconBlocks.CHARGING_TABLE_TILE.get(), pos, state);
        invCharge = itemManager.addInvHandler("charge", 1, ItemHandlerManager.EnumAccess.BOTH, EnumPipePart.VALUES);
        invCharge.setLimitedInsertor(1);
        invCharge.setChecker((slot, stack) -> isChargeable(stack));
        caps.addProvider(new MjCapabilityHelper(this));
    }

    private static boolean isChargeable(ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        EnergyStorage energy = PlatformStorage.energy(stack);
        return energy != null && energy.canReceive() && energy.getMaxEnergyStored() > 0;
    }

    private static int getEnergyRequested(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        EnergyStorage energy = PlatformStorage.energy(stack);
        return energy == null ? 0 : Math.max(0, energy.getMaxEnergyStored() - energy.getEnergyStored());
    }

    public long getTarget() {
        long requested = getEnergyRequested(invCharge.getStackInSlot(0));
        return Math.min(requested, Integer.MAX_VALUE) * MjAmount.MICRO_MJ_PER_MJ;
    }

    public void update() {
        super.update();
        if (level.isClientSide()) {
            return;
        }

        ItemStack stack = invCharge.getStackInSlot(0);
        if (stack.isEmpty() || power < MjAmount.MICRO_MJ_PER_MJ) {
            return;
        }

        EnergyStorage energy = PlatformStorage.energy(stack);
        if (energy != null) {
            chargeItem(stack, energy);
        }
    }

    private void chargeItem(ItemStack stack, EnergyStorage energy) {
        if (!energy.canReceive()) {
            return;
        }
        int available = (int) Math.min(Integer.MAX_VALUE, power / MjAmount.MICRO_MJ_PER_MJ);
        if (available <= 0) {
            return;
        }
        int accepted = energy.receiveEnergy(available, false);
        if (accepted > 0) {
            power -= accepted * MjAmount.MICRO_MJ_PER_MJ;
            invCharge.setStackInSlot(0, stack);
            sendNetworkGuiUpdate(NET_GUI_DATA);
        }
    }


    public boolean canConnect(@Nonnull IMjConnector other) {
        return true;
    }

    public long getPowerRequested() {
        return getDirectPowerRequested();
    }

    public long receivePower(long microJoules, FluidAction action) {
        return receiveDirectPower(microJoules, action);
    }

    public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(this, buffer -> buffer.writeBlockPos(worldPosition));
        }
        return InteractionResult.SUCCESS;
    }

    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new ContainerChargingTable(id, inventory, invCharge, ContainerLevelAccess.create(level, worldPosition));
    }

    public Component getDisplayName() {
        return Component.translatable(this.getBlockState().getBlock().getDescriptionId());
    }
}
