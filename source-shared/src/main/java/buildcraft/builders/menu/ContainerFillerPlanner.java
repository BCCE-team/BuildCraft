/*
 * Copyright (c) the BuildCraft team.
 * SPDX-License-Identifier: MPL-2.0
 */
package buildcraft.builders.menu;

import java.io.IOException;
import java.util.UUID;
import java.util.stream.IntStream;

import buildcraft.builders.BCBuildersGuis;
import buildcraft.builders.addon.AddonFillerPlanner;
import buildcraft.builders.filler.FillerType;
import buildcraft.builders.internal.filler.legacy.IFillerPattern;
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
    /** Client must never use an eventually consistent Volume Box snapshot as its menu authority.
     * The server sends the lock status along with the pattern and inversion state.
     */
    private boolean clientLocked = true;
    /** Applying server state must not echo GUI change callbacks back to the server. */
    private boolean applyingServerUpdate;
    private int syncTicks;

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
        // Only server SavedData is authoritative. Client state arrives via NET_DATA
        // and is independent of the VolumeBox render/synchronization packets.
        return playerLevel().isClientSide ? null
            : WorldSavedDataVolumeBoxes.get(playerLevel()).getVolumeBoxFromId(volumeId);
    }

    private AddonFillerPlanner getAddon() {
        VolumeBox box = findVolumeBox();
        if (box == null) return null;
        return box.addons.get(addonSlot) instanceof AddonFillerPlanner planner ? planner : null;
    }

    @Override
    public boolean stillValid(Player player) {
        if (player != playerInventory.player) return false;
        // A GUI can open before the periodic VolumeBox render packet arrives.
        // Client validation against ClientVolumeBoxes produced a phantom menu.
        if (playerLevel().isClientSide) return true;
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
        if (playerLevel().isClientSide) return clientLocked;
        VolumeBox box = findVolumeBox();
        if (box == null || box.isEditing()) return true;
        return box.getLockTargetsStream().anyMatch(target ->
            target instanceof Lock.Target.TargetResize ||
                (target instanceof Lock.Target.TargetAddon locked && locked.slot == addonSlot));
    }

    @Override
    public void valuesChanged() {
        if (playerLevel().isClientSide) return;
        AddonFillerPlanner addon = getAddon();
        if (addon == null || isLocked()) return;
        addon.updateBuildingInfo();
        WorldSavedDataVolumeBoxes.get(playerLevel()).setDirty();
    }

    /** Redeliver the editor snapshot if the menu-opening packet raced the first
     * NET_DATA on the client; also propagate lock changes while the GUI stays open.
     */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (!playerLevel().isClientSide && ++syncTicks % 20 == 0 && stillValid(getPlayer())) {
            sendData();
        }
    }

    /** Menu-only protocol; never depend on async ClientVolumeBoxes for an editor.
     * Client -> server: selected pattern + parameters + inversion.
     * Server -> client: authoritative pattern + parameters + inversion + lock.
     */
    @Override
    public void sendData() {
        if (playerLevel().isClientSide) {
            sendMessage(NET_DATA, buffer -> {
                clientEditable.writeToBuffer(buffer);
                buffer.writeBoolean(clientInverted);
            });
        } else {
            AddonFillerPlanner addon = getAddon();
            if (addon == null) return;
            sendMessage(NET_DATA, buffer -> {
                addon.patternStatement.writeToBuffer(buffer);
                buffer.writeBoolean(addon.inverted);
                buffer.writeBoolean(isLocked());
            });
        }
    }

    @Override
    public void readMessage(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext context)
        throws IOException {
        super.readMessage(id, buffer, side, context);
        if (id != NET_DATA) return;
        if (side == BCNetworkSide.SERVER) {
            // Always consume the complete client payload, even for locked or stale menus.
            FullStatement<IFillerPattern> incoming = new FullStatement<>(FillerType.INSTANCE, 4, null);
            incoming.readFromBuffer(buffer);
            boolean inverted = buffer.readBoolean();
            if (stillValid(getPlayer()) && !isLocked()) {
                AddonFillerPlanner addon = getAddon();
                if (addon != null) {
                    addon.patternStatement.set(incoming.get());
                    IntStream.range(0, 4).forEach(i -> addon.patternStatement.set(i, incoming.get(i)));
                    addon.inverted = inverted;
                    valuesChanged();
                }
            }
            // Confirm the *real* world state, not the client's optimistic edit.
            sendData();
        } else if (side == BCNetworkSide.CLIENT) {
            applyingServerUpdate = true;
            try {
                serverSnapshot.readFromBuffer(buffer);
                clientInverted = buffer.readBoolean();
                clientLocked = buffer.readBoolean();
                clientEditable.set(serverSnapshot.get());
                IntStream.range(0, 4).forEach(i ->
                    clientEditable.set(i, serverSnapshot.get(i)));
            } finally {
                applyingServerUpdate = false;
            }
        }
    }

    @Override
    public void onStatementChange() {
        if (playerLevel().isClientSide && !applyingServerUpdate) {
            sendData();
        }
    }
}
