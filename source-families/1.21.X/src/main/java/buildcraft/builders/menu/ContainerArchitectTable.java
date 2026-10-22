/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.menu;

import java.io.IOException;

import buildcraft.builders.BCBuildersBlocks;
import buildcraft.builders.BCBuildersGuis;
import buildcraft.builders.tile.TileArchitectTable;
import buildcraft.lib.gui.ContainerBCTile;
import buildcraft.lib.gui.MenuBC_Neptune;
import buildcraft.lib.gui.slot.SlotBase;
import buildcraft.lib.gui.slot.SlotOutput;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.net.NetworkSecurity;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.tile.item.IItemHandlerAdv;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class ContainerArchitectTable extends ContainerBCTile<TileArchitectTable> {
    public static final int GUI_WIDTH = 256;
    public static final int GUI_HEIGHT = 166;
    public static final int PLAYER_INVENTORY_X = 88;
    public static final int PLAYER_INVENTORY_Y = 84;
    public static final int INPUT_SLOT_X = 135;
    public static final int INPUT_SLOT_Y = 35;
    public static final int OUTPUT_SLOT_X = 194;
    public static final int OUTPUT_SLOT_Y = 35;
    public static final int MAX_BLUEPRINT_NAME_LENGTH = 128;
    private static final IdAllocator IDS = MenuBC_Neptune.IDS.makeChild("architect_table");
    private static final int ID_NAME = IDS.allocId("NAME");
    private static final int ID_SETTINGS = IDS.allocId("SETTINGS");
    public String name = "";
    public final DataSlot setting;
    public final DataSlot creativePermission;
   // public final ContainerData deltaProgress;
    
	public ContainerArchitectTable(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
		this(containerId, playerInventory, new ItemHandlerSimple(1), new ItemHandlerSimple(1), DataSlot.standalone(), DataSlot.standalone(), createLevelAccess(playerInventory, buf));
	}

    public ContainerArchitectTable(int containerId, Inventory playerInventory, IItemHandlerAdv in, IItemHandlerAdv out,
        DataSlot setting, DataSlot creativePermission, ContainerLevelAccess access) {
		super(BCBuildersGuis.MENU_ARCHITECT_TABLE.get(), playerInventory, containerId, access);
        addFullPlayerInventory(PLAYER_INVENTORY_X, PLAYER_INVENTORY_Y);

        addSlot(new SlotBase(in, 0, INPUT_SLOT_X, INPUT_SLOT_Y));
        addSlot(new SlotOutput(out, 0, OUTPUT_SLOT_X, OUTPUT_SLOT_Y));
        
        this.setting = setting;
        this.creativePermission = creativePermission;
        addDataSlot(setting);
        addDataSlot(creativePermission);
        
//        this.deltaProgress = containerData;
       // addDataSlots(containerData);
        
    }
    
    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    public void sendNameToServer(String name) {
        String requested = name == null ? "" : name;
        String clean = requested.length() <= MAX_BLUEPRINT_NAME_LENGTH
            ? requested : requested.substring(0, MAX_BLUEPRINT_NAME_LENGTH);
        if (!this.name.equals(clean)) {
            sendMessage(ID_NAME, buffer -> buffer.writeUtf(clean, MAX_BLUEPRINT_NAME_LENGTH));
            this.name = clean;
        }
    }

    public void sendSettingsToServer(int settings) {
        sendMessage(ID_SETTINGS, buffer -> buffer.writeVarInt(settings));
        this.setting.set(settings);
    }

    @Override
    public void readMessage(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readMessage(id, buffer, side, ctx);
        if (side == BCNetworkSide.SERVER) {
            if (id == ID_NAME) {
                tile.name = buffer.readUtf(MAX_BLUEPRINT_NAME_LENGTH);
                tile.sendNetworkUpdate(TileBC_Neptune.NET_RENDER_DATA);
            } else if (id == ID_SETTINGS) {
                Player sender = ctx == null ? playerInventory.player : ctx.player();
                tile.setSnapshotSettingsFromPlayer(NetworkSecurity.requireRange(buffer.readVarInt(), 0, 0b111, "architect settings"), sender);
            }
        }
 /*       if (side == BCNetworkSide.CLIENT) {
            if (id == ID_NAME) {
                name = buffer.readUtf();
            }
        }*/
    }

	@Override
	public boolean stillValid(Player player) {
		return super.stillValid(access, player, BCBuildersBlocks.ARCHITECT.get());
	}

/*	@Override
	public void clientInit(FriendlyByteBuf data) {
		name = data.readUtf();
	}*/
}
