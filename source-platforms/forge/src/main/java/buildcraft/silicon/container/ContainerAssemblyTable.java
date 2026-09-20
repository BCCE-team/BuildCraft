/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.container;

import buildcraft.lib.gui.ContainerBCTile;
import buildcraft.lib.gui.slot.SlotBase;
import buildcraft.lib.gui.slot.SlotDisplay;
import buildcraft.lib.tile.item.IItemHandlerAdv;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.silicon.BCSiliconGuis;
import buildcraft.silicon.tile.TileAssemblyTable;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraftforge.items.IItemHandler;

public class ContainerAssemblyTable extends ContainerBCTile<TileAssemblyTable> {
	
	public ContainerAssemblyTable(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
		this(containerId, playerInventory, new ItemHandlerSimple(12), new ItemHandlerSimple(12), createLevelAccess(playerInventory, buf));
	}
	
    public ContainerAssemblyTable(int containerId, Inventory playerInventory, IItemHandlerAdv invResources, IItemHandler display, ContainerLevelAccess access) {
        super(BCSiliconGuis.MENU_ASSEMBLY_TABLE.get(), playerInventory, containerId, access);
        addFullPlayerInventory(123);

        for(int y = 0; y < 4; y++) {
            for(int x = 0; x < 3; x++) {
                addSlot(new SlotBase(invResources, x + y * 3, 8 + x * 18, 36 + y * 18));
            }
        }

        for(int y = 0; y < 4; y++) {
            for(int x = 0; x < 3; x++) {
                addSlot(new SlotDisplay(display, x + y * 3, 116 + x * 18, 36 + y * 18));
            }
        }
    }

}
