/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.menu;

import java.io.IOException;

import buildcraft.builders.BCBuildersGuis;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.tile.TileElectronicLibrary;
import buildcraft.lib.gui.ContainerBCTile;
import buildcraft.lib.gui.MenuBC_Neptune;
import buildcraft.lib.gui.slot.SlotBase;
import buildcraft.lib.gui.slot.SlotOutput;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class ContainerElectronicLibrary extends ContainerBCTile<TileElectronicLibrary> {
    private static final IdAllocator IDS = MenuBC_Neptune.IDS.makeChild("electronic_library");
    private static final int ID_SELECTED = IDS.allocId("SELECTED");
    
	public ContainerElectronicLibrary(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
		this(containerId, playerInventory, new ItemHandlerSimple(1), new ItemHandlerSimple(1), 
				new ItemHandlerSimple(1), new ItemHandlerSimple(1), createLevelAccess(playerInventory, buf));
	}

    public ContainerElectronicLibrary(int containerId, Inventory playerInventory, ItemHandlerSimple invDownOut,
    		ItemHandlerSimple invDownIn, ItemHandlerSimple invUpIn, ItemHandlerSimple invUpOut, ContainerLevelAccess access) {
        super(BCBuildersGuis.MENU_ELIBRARY.get(), playerInventory, containerId, access);
        addFullPlayerInventory(148);

        addSlot(new SlotOutput(invDownOut, 0, 175, 67));
        addSlot(new SlotDownloadInput(invDownIn, 0, 219, 67));

        addSlot(new SlotUploadInput(invUpIn, 0, 175, 89));
        addSlot(new SlotOutput(invUpOut, 0, 219, 89));
    }

    /**
     * The 8.0.30 and current Library atlases position the inventory slots differently.
     * This is client-only: a GUI switch must relocate the actual Slot objects so that
     * rendering, hover detection and click targets all use the selected atlas layout.
     * Slot order and menu IDs are preserved for server synchronization.
     */
    public void applyClientGuiLayout(boolean legacy) {
        if (slots.size() < 40) return;

        int inventoryY = legacy ? 138 : 148;
        for (int i = 0; i < 36; i++) {
            int inventoryIndex = i < 27 ? i + 9 : i - 27;
            int column = i % 9;
            int rowY = i < 27 ? inventoryY + (i / 9) * 18 : inventoryY + 58;
            replaceClientSlot(i, new Slot(playerInventory, inventoryIndex, 8 + column * 18, rowY));
        }

        int downloadY = legacy ? 57 : 67;
        int uploadY = legacy ? 79 : 89;
        SlotBase downloadOutput = (SlotBase) slots.get(36);
        SlotBase downloadInput = (SlotBase) slots.get(37);
        SlotBase uploadInput = (SlotBase) slots.get(38);
        SlotBase uploadOutput = (SlotBase) slots.get(39);
        replaceClientSlot(36, new SlotOutput(downloadOutput.itemHandler, downloadOutput.handlerIndex, 175, downloadY));
        // SlotBase exposes IItemHandlerAdv, but these two specialized slot
        // constructors require ItemHandlerSimple (as supplied by this menu's constructor).
        // Keep their item validation rules when relocating the client slots.
        replaceClientSlot(37, new SlotDownloadInput((ItemHandlerSimple) downloadInput.itemHandler,
            downloadInput.handlerIndex, 219, downloadY));
        replaceClientSlot(38, new SlotUploadInput((ItemHandlerSimple) uploadInput.itemHandler,
            uploadInput.handlerIndex, 175, uploadY));
        replaceClientSlot(39, new SlotOutput(uploadOutput.itemHandler, uploadOutput.handlerIndex, 219, uploadY));
    }

    private void replaceClientSlot(int menuIndex, Slot replacement) {
        // AbstractContainerMenu.addSlot normally assigns this index. Since the
        // menu is already open, keep the same slot IDs when changing positions.
        replacement.index = menuIndex;
        slots.set(menuIndex, replacement);
    }

    private static class SlotDownloadInput extends SlotBase {
        SlotDownloadInput(ItemHandlerSimple handler, int slotIndex, int posX, int posY) {
            super(handler, slotIndex, posX, posY);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return TileElectronicLibrary.isUsedSnapshot(stack) && super.mayPlace(stack);
        }
    }

    private static class SlotUploadInput extends SlotBase {
        SlotUploadInput(ItemHandlerSimple handler, int slotIndex, int posX, int posY) {
            super(handler, slotIndex, posX, posY);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return TileElectronicLibrary.isCleanSnapshot(stack) && super.mayPlace(stack);
        }
    }

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    public void sendSelectedToServer(Snapshot.Key selected) {
        sendMessage(ID_SELECTED, buffer -> {
            buffer.writeBoolean(selected != null);
            if (selected != null) {
                selected.writeToByteBuf(buffer);
            }
        });
    }

    @Override
    public void readMessage(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readMessage(id, buffer, side, ctx);
        if (side == BCNetworkSide.SERVER) {
            if (id == ID_SELECTED) {
                if (buffer.readBoolean()) {
                    tile.selected = new Snapshot.Key(buffer);
                } else {
                    tile.selected = null;
                }
                tile.sendNetworkUpdate(TileBC_Neptune.NET_RENDER_DATA);
            }
        }
    }

    
    
}
