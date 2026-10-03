/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.menu;

import java.util.List;
import java.util.stream.Collectors;

import buildcraft.builders.BCBuildersGuis;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.tile.TileBuilder;
import buildcraft.lib.gui.ContainerBCTile;
import buildcraft.lib.gui.slot.SlotBase;
import buildcraft.lib.gui.slot.SlotDisplay;
import buildcraft.lib.gui.widget.WidgetFluidTank;
import buildcraft.lib.tile.item.IItemHandlerAdv;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

public class ContainerBuilder extends ContainerBCTile<TileBuilder> {
    public final List<WidgetFluidTank> widgetTanks;
    private int snapshotSlotIndex = -1;
   
    
	public ContainerBuilder(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
		this(containerId, playerInventory, new ItemHandlerSimple(1), new ItemHandlerSimple(27),
				new ItemHandlerSimple(24), DataSlot.standalone(), createLevelAccess(playerInventory, buf));
	}


    public ContainerBuilder(int containerId, Inventory playerInventory, IItemHandlerAdv invSnapshot, IItemHandlerAdv invResources, 
    		IItemHandler invRequire, DataSlot setting, ContainerLevelAccess access) {
    	super(BCBuildersGuis.MENU_BUILDER.get(), playerInventory, containerId, access);

        addFullPlayerInventory(140);

        snapshotSlotIndex = slots.size();
        addSlot(new SlotBase(invSnapshot, 0, 80, 27));

        for (int sy = 0; sy < 3; sy++) {
            for (int sx = 0; sx < 9; sx++) {
            	addSlot(new SlotBase(invResources, sx + sy * 9, 8 + sx * 18, 72 + sy * 18));
            }
        }
        widgetTanks = tile.getTankManager().stream()
                .map(tank -> new WidgetFluidTank(this, tank))
                .map(this::addWidget)
                .collect(Collectors.toList());
        
		addDataSlot(setting);

        for(int y = 0; y < 6; y++) {
            for(int x = 0; x < 4; x++) {
            	addSlot(new SlotDisplay(invRequire, x + y * 4, 179 + x * 18, 18 + y * 18));
            }
        }
    }

    @Override
    public void clicked(int slotId, int dragType, ContainerInput clickType, net.minecraft.world.entity.player.Player player) {
        ItemStack before = getSnapshotSlotStack();
        super.clicked(slotId, dragType, clickType, player);
        applyInsertedSnapshotSettings(before, player);
    }

    @Override
    public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int index) {
        ItemStack before = getSnapshotSlotStack();
        ItemStack result = super.quickMoveStack(player, index);
        applyInsertedSnapshotSettings(before, player);
        return result;
    }

    private ItemStack getSnapshotSlotStack() {
        if (snapshotSlotIndex < 0 || snapshotSlotIndex >= slots.size()) {
            return ItemStack.EMPTY;
        }
        return slots.get(snapshotSlotIndex).getItem().copy();
    }

    private void applyInsertedSnapshotSettings(ItemStack before, net.minecraft.world.entity.player.Player player) {
        ItemStack after = getSnapshotSlotStack();
        if (!ItemStack.matches(before, after) && after.getItem() instanceof ItemSnapshot
            && ItemSnapshot.getHeader(after) != null) {
            tile.applySnapshotSettingsFromInsertedItem(after, player);
        }
    }
}

