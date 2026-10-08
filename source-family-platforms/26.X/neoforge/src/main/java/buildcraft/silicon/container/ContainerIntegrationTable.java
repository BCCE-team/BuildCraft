//? source if >=26.3
/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.silicon.container;

import buildcraft.lib.gui.ContainerBCTile;
import buildcraft.lib.gui.slot.SlotBase;

import buildcraft.lib.gui.slot.SlotOutput;
import buildcraft.lib.tile.item.IItemHandlerAdv;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.silicon.BCSiliconGuis;
import buildcraft.silicon.tile.TileIntegrationTable;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerLevelAccess;
import buildcraft.lib.gui.ItemProvider;
import buildcraft.silicon.compat.SiliconDisplayContainer263;

public class ContainerIntegrationTable extends ContainerBCTile<TileIntegrationTable> {
    public static final int[] SLOT_X = { 44, 44, 69, 69, 69, 44, 19, 19, 19 };
    public static final int[] SLOT_Y = { 49, 24, 24, 49, 74, 74, 74, 49, 24 };

    public ContainerIntegrationTable(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        this(containerId, playerInventory, new ItemHandlerSimple(1), new ItemHandlerSimple(8),
                new ItemProvider(index -> net.minecraft.world.item.ItemStack.EMPTY, 1), new ItemHandlerSimple(1), createLevelAccess(playerInventory, buf));
    }

    public ContainerIntegrationTable(int containerId, Inventory playerInventory, IItemHandlerAdv invTarget,
            IItemHandlerAdv invToIntegrate, ItemProvider invOutput, IItemHandlerAdv invResult, ContainerLevelAccess access) {
        super(BCSiliconGuis.MENU_INTEGRATION_TABLE.get(), playerInventory, containerId, access);

        addSlot(new SlotBase(invTarget, 0, SLOT_X[0], SLOT_Y[0]));
        for (int i = 1; i < SLOT_X.length; i++) {
            addSlot(new SlotBase(invToIntegrate, i - 1, SLOT_X[i], SLOT_Y[i]));
        }

        addSlot(new SiliconDisplayContainer263(invOutput).slot(0, 101, 36));
        addSlot(new SlotOutput(invResult, 0, 138, 49));

        addFullPlayerInventory(109);
    }
}
