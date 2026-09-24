/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.energy.menu;

import buildcraft.energy.BCEnergyGuis;
import buildcraft.energy.tile.TileEngineIron_BC8;
import buildcraft.lib.gui.ContainerBCTile;
import buildcraft.lib.gui.widget.WidgetFluidTank;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class ContainerEngineIron_BC8 extends ContainerBCTile<TileEngineIron_BC8> {
    public final WidgetFluidTank widgetTankFuel;
    public final WidgetFluidTank widgetTankCoolant;
    public final WidgetFluidTank widgetTankResidue;
    
	public ContainerEngineIron_BC8(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
		this(containerId, playerInventory, createLevelAccess(playerInventory, buf));
	}

    public ContainerEngineIron_BC8(int containerId, Inventory playerInventory, ContainerLevelAccess access) {
        super(BCEnergyGuis.MENU_IRON.get(), playerInventory, containerId, access);

        addFullPlayerInventory(95);

        widgetTankFuel = addWidget(new WidgetFluidTank(this, tile.tankFuel));
        widgetTankCoolant = addWidget(new WidgetFluidTank(this, tile.tankCoolant));
        widgetTankResidue = addWidget(new WidgetFluidTank(this, tile.tankResidue));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        if (player.level.isClientSide) {
            return ItemStack.EMPTY;
        }

        ItemStack before = slot.getItem().copy();
        ItemStack after = tile.tankFuel.transferStackToTank(player, slot.getItem());
        if (ItemStack.matches(after, before)) {
            after = tile.tankCoolant.transferStackToTank(player, slot.getItem());
        }
        if (ItemStack.matches(after, before)) {
            after = tile.tankResidue.transferStackToTank(player, slot.getItem());
        }

        if (!ItemStack.matches(after, before)) {
            slot.set(after);
            slot.setChanged();
            broadcastFullState();
            return before;
        }
        return ItemStack.EMPTY;
    }
}
