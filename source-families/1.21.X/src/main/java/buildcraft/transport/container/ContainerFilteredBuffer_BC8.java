//? source if >=1.21.11
/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.transport.container;

import buildcraft.lib.gui.ContainerBCTile;
import buildcraft.lib.gui.slot.SlotBase;
import buildcraft.lib.gui.slot.SlotPhantom;
import buildcraft.lib.tile.item.IItemHandlerAdv;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.transport.BCTransportGuis;
import buildcraft.transport.BCTransportSprites;
import buildcraft.transport.tile.TileFilteredBuffer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.InventoryMenu;

public class ContainerFilteredBuffer_BC8 extends ContainerBCTile<TileFilteredBuffer> {
	
	public ContainerFilteredBuffer_BC8(int containerId, Inventory inv, FriendlyByteBuf buf) {
		this(containerId, inv, new ItemHandlerSimple(9), new ItemHandlerSimple(9), createLevelAccess(inv, buf));
	}
	
    public ContainerFilteredBuffer_BC8(int containerId, Inventory inventory, IItemHandlerAdv invFilter, IItemHandlerAdv invMain, ContainerLevelAccess access) {
        super(BCTransportGuis.MENU_FILTERED_BUFFER.get(), inventory, containerId, access);
        addFullPlayerInventory(86);

        for (int i = 0; i < 9; i++) {
            // Filtered Buffer filter slots
            addSlot(new SlotPhantom(invFilter, i, 8 + i * 18, 27) {
				public Identifier getNoItemIcon() {
                    return BCTransportSprites.FILTERED_BUFFER_EMPTY_SLOT_GUI;
                }


                public boolean canAdjustCount() {
                    return false;
                }
            });
            // Filtered Buffer inventory slots
            addSlot(new SlotBase(invMain, i, 8 + i * 18, 61));
        }
    }
}
