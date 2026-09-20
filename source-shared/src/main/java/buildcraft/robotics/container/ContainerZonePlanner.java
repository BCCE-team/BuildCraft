/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.robotics.container;

import java.io.IOException;

import buildcraft.lib.gui.ContainerBCTile;
import buildcraft.lib.gui.MenuBC_Neptune;
import buildcraft.lib.gui.slot.SlotBase;
import buildcraft.lib.gui.slot.SlotOutput;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.net.NetworkSecurity;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.robotics.BCRoboticsGuis;
import buildcraft.robotics.tile.TileZonePlanner;
import buildcraft.robotics.zone.ZonePlan;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerLevelAccess;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class ContainerZonePlanner extends ContainerBCTile<TileZonePlanner> {
    private static final IdAllocator IDS = MenuBC_Neptune.IDS.makeChild("zone_planner");
    private static final int ID_LOAD_AREA = IDS.allocId("LOAD_AREA");
    private static final int ID_SAVE_AREA = IDS.allocId("SAVE_AREA");
    private static final int ID_AREA_LOADED = IDS.allocId("AREA_LOADED");
    private static final int ID_SET_NAME = IDS.allocId("SET_NAME");

    private final ItemHandlerSimple fallbackInv;

    public ZonePlan currentAreaSelection = new ZonePlan();
    public int currentLayer = 0;
    public String mapName = "";

    public ContainerZonePlanner(int containerId, Inventory playerInventory, FriendlyByteBuf buffer) {
        this(containerId, playerInventory, null, createLevelAccess(playerInventory, buffer));
    }

    public ContainerZonePlanner(int containerId, Inventory playerInventory, TileZonePlanner tile,
            ContainerLevelAccess access) {
        super(BCRoboticsGuis.MENU_ZONE_PLANNER.get(), playerInventory, containerId, access);

        TileZonePlanner actualTile = this.tile != null ? this.tile : tile;
        fallbackInv = new ItemHandlerSimple(3);
        ItemHandlerSimple inv = actualTile != null ? actualTile.inv : fallbackInv;

        addSlot(new SlotBase(inv, 0, 233, 9));
        addSlot(new SlotOutput(inv, 1, 233, 57));
        addSlot(new SlotBase(inv, 2, 8, 125));

        addFullPlayerInventory(88, 146);

        if (actualTile != null) {
            currentLayer = actualTile.getCurrentSelectedArea();
            currentAreaSelection = new ZonePlan(actualTile.selectArea(currentLayer));
            mapName = actualTile.mapName;
        }
    }

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    public void loadArea(int index) {
        if (index < 0 || index >= TileZonePlanner.LAYER_COUNT) {
            return;
        }
        currentLayer = index;
        if (tile != null) {
            currentAreaSelection = new ZonePlan(tile.selectArea(index));
        }
        sendMessage(ID_LOAD_AREA, buffer -> buffer.writeByte(index));
    }

    public void saveArea(int index, ZonePlan area) {
        if (index < 0 || index >= TileZonePlanner.LAYER_COUNT || area == null) {
            return;
        }
        currentLayer = index;
        currentAreaSelection = new ZonePlan(area);
        if (tile != null) {
            tile.layers[index] = new ZonePlan(area);
        }
        sendMessage(ID_SAVE_AREA, buffer -> {
            buffer.writeByte(index);
            area.writeToByteBuf(buffer);
        });
    }

    public void sendNameToServer(String name) {
        String requested = name == null ? "" : name;
        String clean = requested.length() <= TileZonePlanner.MAX_MAP_NAME_LENGTH
            ? requested : requested.substring(0, TileZonePlanner.MAX_MAP_NAME_LENGTH);
        if (!clean.equals(mapName)) {
            mapName = clean;
            if (tile != null) {
                tile.mapName = clean;
            }
            sendMessage(ID_SET_NAME, buffer -> buffer.writeUtf(clean, TileZonePlanner.MAX_MAP_NAME_LENGTH));
        }
    }

    @Override
    public void readMessage(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readMessage(id, buffer, side, ctx);
        if (side == BCNetworkSide.SERVER) {
            if (id == ID_LOAD_AREA) {
                int index = NetworkSecurity.requireRange(buffer.readUnsignedByte(), 0, TileZonePlanner.LAYER_COUNT - 1,
                    "zone planner layer");
                if (tile != null) {
                    ZonePlan area = tile.selectArea(index);
                    sendMessage(ID_AREA_LOADED, out -> {
                        out.writeByte(index);
                        area.writeToByteBuf(out);
                    });
                }
            } else if (id == ID_SAVE_AREA) {
                int index = NetworkSecurity.requireRange(buffer.readUnsignedByte(), 0, TileZonePlanner.LAYER_COUNT - 1,
                    "zone planner layer");
                ZonePlan area = new ZonePlan().readFromByteBuf(buffer);
                if (tile != null) {
                    tile.selectArea(index);
                    tile.setArea(index, area);
                }
            } else if (id == ID_SET_NAME) {
                if (tile != null) {
                    tile.setMapName(buffer.readUtf(TileZonePlanner.MAX_MAP_NAME_LENGTH));
                }
            }
        } else if (side == BCNetworkSide.CLIENT) {
            if (id == ID_AREA_LOADED) {
                currentLayer = NetworkSecurity.requireRange(buffer.readUnsignedByte(), 0, TileZonePlanner.LAYER_COUNT - 1,
                    "zone planner layer");
                currentAreaSelection = new ZonePlan().readFromByteBuf(buffer);
                if (tile != null && currentLayer >= 0 && currentLayer < tile.layers.length) {
                    tile.layers[currentLayer] = new ZonePlan(currentAreaSelection);
                }
            }
        }
    }
}
