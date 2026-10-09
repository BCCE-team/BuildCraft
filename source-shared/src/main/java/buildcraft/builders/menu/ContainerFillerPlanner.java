/*
 * Copyright (c) the BuildCraft team.
 * SPDX-License-Identifier: MPL-2.0
 */
package buildcraft.builders.menu;

import java.io.IOException;
import java.util.UUID;

import buildcraft.builders.BCBuildersGuis;
import buildcraft.builders.addon.AddonFillerPlanner;
import buildcraft.builders.filler.FillerType;
import buildcraft.builders.internal.filler.legacy.IFillerPattern;
import buildcraft.core.marker.volume.ClientVolumeBoxes;
import buildcraft.core.marker.volume.EnumAddonSlot;
import buildcraft.core.marker.volume.Lock;
import buildcraft.core.marker.volume.VolumeBox;
import buildcraft.core.marker.volume.WorldSavedDataVolumeBoxes;
import buildcraft.lib.gui.MenuBC_Neptune;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;
import buildcraft.lib.statement.FullStatement;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/** Independent Filler Planner editor: it is attached to a Volume Box, not a tile entity. */
public final class ContainerFillerPlanner extends MenuBC_Neptune implements IContainerFilling {
    public final UUID volumeId;
    public final EnumAddonSlot addonSlot;

    private final FullStatement<IFillerPattern> serverSnapshot = new FullStatement<>(FillerType.INSTANCE, 4, null);
    private final FullStatement<IFillerPattern> clientEditable = new FullStatement<>(FillerType.INSTANCE, 4,
        (statement, parameter) -> onStatementChange());
    private boolean clientInverted;
    /** Applying server state must not echo GUI change callbacks back to the server. */
    private boolean applyingServerUpdate;

    private Level playerLevel() {
        //? if <1.20 {
        return playerInventory.player.level;
        //?} else {
        return playerInventory.player.level();
        //?}
    }

    public ContainerFillerPlanner(int id, Inventory inventory, FriendlyByteBuf buf) {
        this(id, inventory, buf.readUUID(), buf.readEnum(EnumAddonSlot.class));
    }

    public ContainerFillerPlanner(int id, Inventory inventory, UUID volumeId, EnumAddonSlot slot) {
        super(inventory, BCBuildersGuis.MENU_FILLER_PLANNER.get(), id);
        this.volumeId = volumeId;
        this.addonSlot = slot;
        // The client widget sends its changes using FullStatement.postSetFromGui.
        clientEditable.canInteract = true;
        if (!playerLevel().isClientSide) {
            init();
        }
    }

    private VolumeBox findVolumeBox() {
        Level level = playerLevel();
        if (level.isClientSide) {
            return ClientVolumeBoxes.INSTANCE.volumeBoxes.stream()
                .filter(box -> box.id.equals(volumeId)).findFirst().orElse(null);
        }
        return WorldSavedDataVolumeBoxes.get(level).getVolumeBoxFromId(volumeId);
    }

    private AddonFillerPlanner getAddon() {
        VolumeBox box = findVolumeBox();
        if (box == null) return null;
        return box.addons.get(addonSlot) instanceof AddonFillerPlanner planner ? planner : null;
    }

    @Override
    public boolean stillValid(Player player) {
        if (player != playerInventory.player) return false;
        VolumeBox box = findVolumeBox();
        if (box == null || !(box.addons.get(addonSlot) instanceof AddonFillerPlanner)) return false;
        return box.world == playerLevel() &&
            box.addons.get(addonSlot).getBoundingBox().getCenter().distanceToSqr(player.getEyePosition()) <= 64;
    }

    @Override
    public Player getPlayer() { return playerInventory.player; }

    @Override
    public FullStatement<IFillerPattern> getPatternStatementClient() { return clientEditable; }

    @Override
    public FullStatement<IFillerPattern> getPatternStatement() {
        AddonFillerPlanner addon = getAddon();
        return !playerLevel().isClientSide && addon != null ? addon.patternStatement : serverSnapshot;
    }

    @Override
    public boolean isInverted() {
        AddonFillerPlanner addon = getAddon();
        return !playerLevel().isClientSide && addon != null ? addon.inverted : clientInverted;
    }

    @Override
    public void setInverted(boolean value) {
        if (playerLevel().isClientSide) {
            clientInverted = value;
        } else {
            AddonFillerPlanner addon = getAddon();
            if (addon != null && !isLocked()) addon.inverted = value;
        }
    }

    @Override
    public boolean isLocked() {
        VolumeBox box = findVolumeBox();
        if (box == null) return true;
        return box.getLockTargetsStream().anyMatch(target ->
            target instanceof Lock.Target.TargetResize ||
                (target instanceof Lock.Target.TargetAddon slot && slot.slot == addonSlot));
    }

    @Override
    public void valuesChanged() {
        if (playerLevel().isClientSide) return;
        AddonFillerPlanner addon = getAddon();
        if (addon == null || isLocked()) return;
        addon.updateBuildingInfo();
        WorldSavedDataVolumeBoxes.get(playerLevel()).setDirty();
    }

    @Override
    public void readMessage(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext context)
        throws IOException {
        super.readMessage(id, buffer, side, context);
        if (side == BCNetworkSide.SERVER && id == NET_DATA && (!stillValid(getPlayer()) || isLocked())) {
            // Consume untrusted input but do not apply ANY change while the addon is locked or out of range.
            FullStatement<IFillerPattern> ignored = new FullStatement<>(FillerType.INSTANCE, 4, null);
            ignored.readFromBuffer(buffer);
            buffer.readBoolean();
            sendData();
            return;
        }
        boolean serverSnapshotUpdate = side == BCNetworkSide.CLIENT && id == NET_DATA;
        if (serverSnapshotUpdate) applyingServerUpdate = true;
        try {
            IContainerFilling.super.readMessage(id, buffer, side, context);
        } finally {
            if (serverSnapshotUpdate) applyingServerUpdate = false;
        }
    }

    @Override
    public void onStatementChange() {
        if (playerLevel().isClientSide && !applyingServerUpdate) {
            sendData();
        }
    }
}
