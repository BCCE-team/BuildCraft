//? source if >=26.3
/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.factory.container;

import buildcraft.factory.BCFactoryGuis;
import buildcraft.factory.tile.TileAutoWorkbenchItems;
import buildcraft.lib.gui.ContainerBCTile;
import buildcraft.lib.gui.slot.SlotBase;
import buildcraft.lib.gui.slot.SlotDisplay;
import buildcraft.lib.gui.slot.SlotOutput;
import buildcraft.lib.gui.slot.SlotPhantom;
import buildcraft.lib.tile.item.IItemHandlerAdv;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import buildcraft.lib.gui.ItemProvider;

public class ContainerAutoCraftItems extends ContainerBCTile<TileAutoWorkbenchItems> {
    public static final int BUTTON_PREVIOUS_RECIPE = 0;
    public static final int BUTTON_NEXT_RECIPE = 1;

    public final SlotBase[] materialSlots;
    public final SlotBase[] filtterSlots;;
    /** The exact handlers backing this menu's synced slots (client and server instances differ). */
    public final IItemHandlerAdv blueprintInv;
    public final IItemHandlerAdv materialInv;
    
	public static ContainerAutoCraftItems create(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
		return new ContainerAutoCraftItems(containerId, playerInventory, buf.readInt(), createLevelAccess(playerInventory, buf));
	}
	
	ContainerAutoCraftItems(int containerId, Inventory playerInventory, int size, ContainerLevelAccess access){
		this(containerId, playerInventory, new ItemHandlerSimple(1), new ItemHandlerSimple(size), 
				new ItemHandlerSimple(size),new ItemHandlerSimple(size), new ItemHandlerSimple(1), access);
	}

    public ContainerAutoCraftItems(int containerId, Inventory player, IItemHandlerAdv invResult, IItemHandlerAdv invBlueprint, 
    		IItemHandlerAdv invMaterialFilter, IItemHandlerAdv invMaterials, ItemProvider resultClient, ContainerLevelAccess access) {
        super(BCFactoryGuis.MENU_AUTOWORK_BENCH_ITEM.get(), player, containerId, access);
        blueprintInv = invBlueprint;
        materialInv = invMaterials;

        addSlot(new SlotOutput(invResult, 0, 124, 35));
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
            	addSlot(new SlotPhantom(invBlueprint, x + y * 3, 30 + x * 18, 17 + y * 18, false));
            }
        }
        materialSlots = new SlotBase[9];
        filtterSlots = new SlotBase[9];
        for (int x = 0; x < 9; x++) {
            // hide the filter slots, but still sync them
        	addSlot(filtterSlots[x] = new SlotPhantom(invMaterialFilter, x, -1000000, -1000000));
        	addSlot(materialSlots[x] = new SlotBase(invMaterials, x, 8 + x * 18, 84));
        }
        addSlot(new SlotDisplay(resultClient, 0, 93, 27));

        addFullPlayerInventory(115);
    }

    public boolean clickMenuButton(Player player, int id) {
        if (player.isSpectator() || tile == null) {
            return false;
        }
        if (id == BUTTON_PREVIOUS_RECIPE) {
            return tile.cycleRecipe(-1);
        }
        if (id == BUTTON_NEXT_RECIPE) {
            return tile.cycleRecipe(1);
        }
        return super.clickMenuButton(player, id);
    }
}
