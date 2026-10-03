/* Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.robotics.container;

import buildcraft.lib.gui.ContainerBCTile;
import buildcraft.lib.gui.MenuBC_Neptune;
import buildcraft.lib.gui.slot.SlotBase;
import buildcraft.lib.gui.slot.SlotPhantom;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.tile.item.IItemHandlerAdv;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.robotics.BCRoboticsGuis;
import buildcraft.robotics.tile.TileRequester;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class ContainerRequester extends ContainerBCTile<TileRequester> {
    private static final IdAllocator IDS = MenuBC_Neptune.IDS.makeChild("requester");

    private final ItemHandlerSimple fallbackInv;
    private final ItemHandlerSimple fallbackRequests;
    private final ItemHandlerSimple requestInventory;

    public ContainerRequester(int containerId, Inventory playerInventory, FriendlyByteBuf buffer) {
        this(containerId, playerInventory, null, createLevelAccess(playerInventory, buffer));
    }

    public ContainerRequester(int containerId, Inventory playerInventory, TileRequester tile, ContainerLevelAccess access) {
        super(BCRoboticsGuis.MENU_REQUESTER.get(), playerInventory, containerId, access);

        TileRequester actualTile = this.tile != null ? this.tile : tile;
        fallbackInv = new ItemHandlerSimple(TileRequester.NB_ITEMS);
        fallbackRequests = new ItemHandlerSimple(TileRequester.NB_ITEMS);

        IItemHandlerAdv inv = actualTile != null ? actualTile.inv : fallbackInv;
        requestInventory = actualTile != null ? actualTile.requests : fallbackRequests;

        for (int x = 0; x < 4; ++x) {
            for (int y = 0; y < 5; ++y) {
                int index = x * 5 + y;
                addSlot(new SlotRequesterTemplate(requestInventory, index, 9 + x * 18, 7 + y * 18));
            }
        }

        for (int x = 0; x < 4; ++x) {
            for (int y = 0; y < 5; ++y) {
                int index = x * 5 + y;
                addSlot(new SlotRequesterInventory(inv, index, 117 + x * 18, 7 + y * 18, actualTile));
            }
        }

        addFullPlayerInventory(19, 101);
    }

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    @Override
    public void clicked(int slotId, int dragType, ContainerInput clickType, Player player) {
        if (slotId >= 0 && slotId < slots.size()) {
            Slot slot = slots.get(slotId);
            if (slot instanceof SlotRequesterTemplate templateSlot) {
                handleTemplateClick(templateSlot, clickType);
                return;
            }
        }
        super.clicked(slotId, dragType, clickType, player);
    }

    private void handleTemplateClick(SlotRequesterTemplate slot, ContainerInput clickType) {
        if (clickType == ContainerInput.CLONE) {
            if (playerInventory.player.getAbilities().instabuild && getCarried().isEmpty() && slot.hasItem()) {
                setCarried(slot.getItem().copy());
            }
            return;
        }

        if (clickType != ContainerInput.PICKUP && clickType != ContainerInput.PICKUP_ALL
            && clickType != ContainerInput.QUICK_MOVE) {
            return;
        }

        ItemStack carried = clickType == ContainerInput.QUICK_MOVE ? ItemStack.EMPTY : getCarried();
        ItemStack toSet = carried.isEmpty() ? ItemStack.EMPTY : carried.copy();
        if (tile != null) {
            tile.setRequest(slot.handlerIndex, toSet);
        } else {
            requestInventory.setStackInSlot(slot.handlerIndex, toSet);
        }
        slot.setChanged();
    }

    private static class SlotRequesterTemplate extends SlotPhantom {
        public SlotRequesterTemplate(IItemHandlerAdv itemHandler, int slotIndex, int posX, int posY) {
            super(itemHandler, slotIndex, posX, posY, false);
        }

        @Override
        public int getMaxStackSize() {
            return 64;
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return Math.min(64, stack.getMaxStackSize());
        }
    }

    private static class SlotRequesterInventory extends SlotBase {
        private final TileRequester requester;

        public SlotRequesterInventory(IItemHandlerAdv itemHandler, int slotIndex, int posX, int posY,
                TileRequester requester) {
            super(itemHandler, slotIndex, posX, posY);
            this.requester = requester;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return requester == null ? super.mayPlace(stack) : requester.canSetRealSlot(handlerIndex, stack);
        }
    }
}
