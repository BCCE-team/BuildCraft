/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */
package buildcraft.core.marker.volume;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.net.MessageManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.phys.AABB;

public class WorldSavedDataVolumeBoxes extends SavedData {
    private static final String DATA_NAME = "buildcraft_volume_boxes";

    public final Level world;
    public final List<VolumeBox> volumeBoxes = new ArrayList<>();
    private final Map<UUID, CompoundTag> lastSyncedState = new HashMap<>();
    private long lastFullSyncTick = Long.MIN_VALUE;
    private int lastPlayerCount = -1;

    private WorldSavedDataVolumeBoxes(Level world) {
        this.world = world;
    }

    private WorldSavedDataVolumeBoxes(Level world, CompoundTag nbt) {
        this(world);
        NBTUtilBC.readCompoundList(nbt.get("volumeBoxes"))
            .map(volumeBoxTag -> new VolumeBox(world, volumeBoxTag))
            .forEach(volumeBoxes::add);
    }

    public VolumeBox getVolumeBoxAt(BlockPos pos) {
        return volumeBoxes.stream().filter(volumeBox -> volumeBox.box.contains(pos)).findFirst().orElse(null);
    }

    public void addVolumeBox(BlockPos pos) {
        volumeBoxes.add(new VolumeBox(world, pos));
        setDirty();
    }

    public VolumeBox getVolumeBoxFromId(UUID id) {
        return volumeBoxes.stream().filter(volumeBox -> volumeBox.id.equals(id)).findFirst().orElse(null);
    }

    public VolumeBox getCurrentEditing(Player player) {
        return volumeBoxes.stream().filter(volumeBox -> volumeBox.isEditingBy(player)).findFirst().orElse(null);
    }

    public void tick() {
        AtomicBoolean dirty = new AtomicBoolean(false);
        volumeBoxes.stream().filter(VolumeBox::isEditing).forEach(volumeBox -> {
            Player player = volumeBox.getPlayer(world);
            if (player == null) {
                volumeBox.pauseEditing();
                dirty.set(true);
            } else {
                AABB oldAabb = volumeBox.box.getBoundingBox();
                volumeBox.box.reset();
                volumeBox.box.extendToEncompass(volumeBox.getHeld());
                BlockPos lookingAt = BlockPos.containing(
                    player.getEyePosition().add(player.getLookAngle().scale(volumeBox.getDist())));
                volumeBox.box.extendToEncompass(lookingAt);
                if (!volumeBox.box.getBoundingBox().equals(oldAabb)) {
                    dirty.set(true);
                }
            }
        });
        for (VolumeBox volumeBox : volumeBoxes) {
            List<Lock> locksToRemove = new ArrayList<>(volumeBox.locks).stream()
                .filter(lock -> !lock.cause.stillWorks(world))
                .collect(Collectors.toList());
            if (!locksToRemove.isEmpty()) {
                volumeBox.locks.removeAll(locksToRemove);
                dirty.set(true);
            }
        }
        if (dirty.get()) {
            setDirty();
        }
    }

    @Override
    public void setDirty() {
        super.setDirty();
        if (!(world instanceof ServerLevel serverLevel)) {
            return;
        }

        Map<UUID, CompoundTag> currentState = new HashMap<>();
        List<VolumeBox> changed = new ArrayList<>();
        for (VolumeBox volumeBox : volumeBoxes) {
            CompoundTag state = volumeBox.writeToNBT();
            currentState.put(volumeBox.id, state.copy());
            if (!state.equals(lastSyncedState.get(volumeBox.id))) {
                changed.add(volumeBox);
            }
        }
        List<UUID> removed = lastSyncedState.keySet().stream()
            .filter(id -> !currentState.containsKey(id))
            .collect(Collectors.toList());

        long now = serverLevel.getGameTime();
        int playerCount = serverLevel.players().size();
        boolean fullSync = lastSyncedState.isEmpty() || playerCount != lastPlayerCount
            || lastFullSyncTick == Long.MIN_VALUE || now - lastFullSyncTick >= 100;
        if (fullSync) {
            MessageManager.sendToDimension(new MessageVolumeBoxes(volumeBoxes), world.dimension());
            lastFullSyncTick = now;
        } else if (!changed.isEmpty() || !removed.isEmpty()) {
            MessageManager.sendToDimension(MessageVolumeBoxes.delta(changed, removed), world.dimension());
        }

        lastSyncedState.clear();
        lastSyncedState.putAll(currentState);
        lastPlayerCount = playerCount;
    }

    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        nbt.put("volumeBoxes", NBTUtilBC.writeObjectList(volumeBoxes.stream().map(VolumeBox::writeToNBT)));
        return nbt;
    }

    public static WorldSavedDataVolumeBoxes get(Level world) {
        if (!(world instanceof ServerLevel serverLevel)) {
            throw new IllegalArgumentException("Tried to access volume-box saved data on the client");
        }
        DimensionDataStorage storage = serverLevel.getDataStorage();
        SavedData.Factory<WorldSavedDataVolumeBoxes> factory = new SavedData.Factory<>(
            () -> new WorldSavedDataVolumeBoxes(world),
            (nbt, registries) -> new WorldSavedDataVolumeBoxes(world, nbt),
            DataFixTypes.LEVEL
        );
        return storage.computeIfAbsent(factory, DATA_NAME);
    }
}
