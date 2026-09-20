/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.menu;

import java.io.IOException;

import buildcraft.builders.internal.filler.legacy.IFillerPattern;
import buildcraft.builders.BCBuildersGuis;
import buildcraft.builders.filler.FillerType;
import buildcraft.builders.tile.TileFiller;
import buildcraft.core.marker.volume.WorldSavedDataVolumeBoxes;
import buildcraft.lib.gui.ContainerBCTile;
import buildcraft.lib.gui.slot.SlotBase;
import buildcraft.lib.statement.FullStatement;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class ContainerFiller extends ContainerBCTile<TileFiller> implements IContainerFilling {
    private final ItemHandlerSimple resources;
    private final FullStatement<IFillerPattern> patternStatementClient = new FullStatement<>(
        FillerType.INSTANCE,
        4,
        (statement, paramIndex) -> onStatementChange()
    );
    
	public ContainerFiller(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
		this(containerId, playerInventory, new ItemHandlerSimple(27), createLevelAccess(playerInventory, buf));
	}

    public ContainerFiller(int containerId, Inventory playerInventory, ItemHandlerSimple invResources, ContainerLevelAccess access) {
        super(BCBuildersGuis.MENU_FILLER.get(), playerInventory, containerId, access);

        resources = tile != null ? tile.invResources : invResources;

        addFullPlayerInventory(153);

        for (int sy = 0; sy < 3; sy++) {
            for (int sx = 0; sx < 9; sx++) {
            	addSlot(new SlotBase(resources, sx + sy * 9, sx * 18 + 8, sy * 18 + 85));
            }
        }

        init();
    }

    public ItemHandlerSimple getResources() {
        return resources;
    }

    @Override
    public Player getPlayer() {
        return playerInventory.player;
    }

    @Override
    public FullStatement<IFillerPattern> getPatternStatementClient() {
        return patternStatementClient;
    }

    @Override
    public FullStatement<IFillerPattern> getPatternStatement() {
        if (tile == null) {
            return patternStatementClient;
        }
        return tile.addon != null ? tile.addon.patternStatement : tile.patternStatement;
    }

    @Override
    public boolean isInverted() {
        if (tile == null) {
            return false;
        }
        return tile.addon != null ? tile.addon.inverted : tile.inverted;
    }

    @Override
    public boolean isLocked() {
        return tile != null && tile.isLocked();
    }

    @Override
    public void setInverted(boolean value) {
        if (tile == null) {
            return;
        }
        if (tile.addon != null) {
            tile.addon.inverted = value;
        } else {
            tile.inverted = value;
        }
    }

    @Override
    public void valuesChanged() {
        if (tile == null) {
            return;
        }
        if (tile.addon != null) {
            tile.addon.updateBuildingInfo();
            if (!playerInventory.player.level().isClientSide) {
                WorldSavedDataVolumeBoxes.get(getPlayer().level()).setDirty();
            }
        }
        if (!playerInventory.player.level().isClientSide) {
            tile.onStatementChange();
        }
    }

    @Override
    public void readMessage(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readMessage(id, buffer, side, ctx);
        IContainerFilling.super.readMessage(id, buffer, side, ctx);
    }
}
