//? source if >=1.21.1
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.wire;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;

import com.mojang.serialization.Codec;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.signal.BuildCraftSignalChannels;
import buildcraft.api.v2.signal.SignalChannelType;
import buildcraft.api.v2.signal.SignalPort;
import buildcraft.api.v2.signal.SignalPortProvider;
import buildcraft.transport.internal.EnumWirePart;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.lib.net.BCNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import buildcraft.lib.compat.NbtCompat;

public class WorldSavedDataWireSystems extends SavedData {
    public static final String DATA_NAME = "buildcraft_wire_systems";
    public static final Codec<WorldSavedDataWireSystems> CODEC = CompoundTag.CODEC.xmap(
        WorldSavedDataWireSystems::new, WorldSavedDataWireSystems::saveToTag
    );
    public static final SavedDataType<WorldSavedDataWireSystems> TYPE =
        new SavedDataType<>(DATA_NAME, WorldSavedDataWireSystems::new, CODEC, null);
    public Level world;
    public final Map<WireSystem, Boolean> wireSystems = new HashMap<>();
    public boolean gatesChanged = true;
    public boolean structureChanged = true;
    public final List<WireSystem> changedSystems = new ArrayList<>();
    public final List<Player> changedPlayers = new ArrayList<>();

    private final Map<WireSystem.WireElement, List<WireSystem>> elementsToWireSystemsIndex = new HashMap<>();

    public WorldSavedDataWireSystems() {}

    private WorldSavedDataWireSystems(CompoundTag nbt) {
        this();
        readFromTag(nbt);
    }

    public void markStructureChanged() {
        structureChanged = true;
        gatesChanged = true;
    }

    public List<WireSystem> getWireSystemsWithElement(WireSystem.WireElement element) {
        List<WireSystem> wireSystemsWithElement = this.elementsToWireSystemsIndex.get(element);
        return wireSystemsWithElement != null ? new ArrayList<>(wireSystemsWithElement) : Collections.emptyList();
    }

    public List<WireSystem> getWireSystemsWithElementAsReadOnlyList(WireSystem.WireElement element) {
        return this.elementsToWireSystemsIndex.getOrDefault(element, Collections.emptyList());
    }

    public void removeWireSystem(WireSystem wireSystem) {
        wireSystems.remove(wireSystem);
        wireSystem.elements.forEach(elementIn -> {
            elementsToWireSystemsIndex.computeIfPresent(elementIn, (element, wireSystems) -> {
                wireSystems.remove(wireSystem);
                return wireSystems.isEmpty() ? null : wireSystems;
            });
        });
        markStructureChanged();
    }

    public void addWireSystem(WireSystem wireSystem, boolean powered) {
        if (this.wireSystems.put(wireSystem, powered) == null) {
            wireSystem.elements.forEach(systemElement -> {
                List<WireSystem> wireSystemsWithElement = this.elementsToWireSystemsIndex.computeIfAbsent(systemElement, unused -> new ArrayList<>());
                if (wireSystemsWithElement.contains(wireSystem)) {
                    throw new IllegalStateException();
                }
                wireSystemsWithElement.add(wireSystem);
            });
        }
    }

    public void buildAndAddWireSystem(WireSystem.WireElement element) {
        WireSystem wireSystem = new WireSystem(this, element);
        if(!wireSystem.isEmpty()) {
            this.addWireSystem(wireSystem, false);
            wireSystems.put(wireSystem, wireSystem.update(this));
        }
        markStructureChanged();
    }

    public void rebuildWireSystemsAround(IPipeHolder holder) {
        Arrays.stream(EnumWirePart.values())
                .flatMap(part -> WireSystem.getConnectedElementsOfElement(world, new WireSystem.WireElement(holder.getPipePos(), part)).stream())
                .distinct()
                .forEach(this::buildAndAddWireSystem);
    }

    public boolean isEmitterEmitting(WireSystem.WireElement element, DyeColor color) {
        if (!world.isLoaded(element.blockPos)) {
            BCLog.logger.warn("[transport.wire] Ghost loading " + element.blockPos + " to resolve an API2 signal endpoint!");
        }
        BlockEntity tile = world.getBlockEntity(element.blockPos);
        if (!(tile instanceof IPipeHolder holder)) return false;
        if (holder.getWireManager().isSignalOutputActive(element.emitterSide, color)) return true;
        SignalPort<Boolean> external = getExternalPort(element, color);
        return external != null && Boolean.TRUE.equals(external.publishedValue());
    }

    @SuppressWarnings("unchecked")
    private SignalPort<Boolean> getExternalPort(WireSystem.WireElement element, DyeColor color) {
        BlockPos externalPos = element.blockPos.relative(element.emitterSide);
        if (!world.isLoaded(externalPos)) return null;
        BlockEntity blockEntity = world.getBlockEntity(externalPos);
        if (!(blockEntity instanceof SignalPortProvider provider)) return null;
        Identifier channelId = BuildCraftSignalChannels.id(color);
        SignalChannelType<?> expected = BuildCraftApi.registry(BuildCraftRegistries.SIGNAL_CHANNEL_TYPES).get(channelId);
        if (expected == null) return null;
        SignalPort<?> port = provider.signalPort(element.emitterSide.getOpposite(), channelId).orElse(null);
        if (port == null || port.channel() != expected) return null;
        return (SignalPort<Boolean>) port;
    }

    private void publishExternalSignal(WireSystem wireSystem, boolean powered) {
        for (WireSystem.WireElement element : wireSystem.elements) {
            if (element.type != WireSystem.WireElement.Type.EMITTER_SIDE) continue;
            SignalPort<Boolean> external = getExternalPort(element, wireSystem.color);
            if (external != null) external.receive(powered, OperationMode.EXECUTE);
        }
    }

    public void tick() {
        // Consume the current dirty flag before recalculating. If an update itself marks gates dirty again, that new
        // change remains set for the next tick instead of being accidentally cleared at the end of this one.
        boolean updateGates = gatesChanged;
        gatesChanged = false;
        if(updateGates) {
            wireSystems.replaceAll((wireSystem, oldPowered) -> {
                boolean newPowered = wireSystem.update(this);
                if (oldPowered != newPowered) {
                    changedSystems.add(wireSystem);
                }
                publishExternalSignal(wireSystem, newPowered);
                return newPowered;
            });
        }
        //to debug
        if(!world.isClientSide())
        ((ServerLevel)world).players().forEach(player -> {
            Map<Integer, WireSystem> changedWires = this.wireSystems.keySet().stream()
                    .filter(wireSystem -> wireSystem.isPlayerWatching(player) && (structureChanged || changedPlayers.contains(player)))
                    .collect(Collectors.toMap(WireSystem::getWiresHashCode, Function.identity()));
            if(!changedWires.isEmpty()) {
                BCNetwork.sendTo(new MessageWireSystems(changedWires), player);
            }
            Map<Integer, Boolean> hashesPowered = this.wireSystems.entrySet().stream()
                    .filter(systemPower ->
                            systemPower.getKey().isPlayerWatching(player) &&
                                    (structureChanged || changedSystems.contains(systemPower.getKey()) || changedPlayers.contains(player))
                    )
                    .map(systemPowered -> Pair.of(systemPowered.getKey().getWiresHashCode(), systemPowered.getValue()))
                    .collect(Collectors.toMap(Pair::getLeft, Pair::getRight));
            if(!hashesPowered.isEmpty()) {
                BCNetwork.sendTo(new MessageWireSystemsPowered(hashesPowered), player);
            }
        });
        if(structureChanged || !changedSystems.isEmpty()) {
            setDirty();
        }
        structureChanged = false;
        changedSystems.clear();
        changedPlayers.clear();
    }

    public CompoundTag saveToTag() {
        CompoundTag nbt = new CompoundTag();
        ListTag entriesList = new ListTag();
        for (Map.Entry<WireSystem, Boolean> system : wireSystems.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.put("wireSystem", system.getKey().writeToNBT());
            entry.putBoolean("powered", system.getValue());
            entriesList.add(entry);
        }
        nbt.put("entries", entriesList);
        return nbt;
    }

    private void readFromTag(CompoundTag nbt) {
        wireSystems.clear();
        elementsToWireSystemsIndex.clear();
        ListTag entriesList = NbtCompat.getList(nbt, "entries");
        for (int i = 0; i < entriesList.size(); i++) {
            CompoundTag entry = NbtCompat.getCompound(entriesList, i);
            addWireSystem(new WireSystem(NbtCompat.getCompound(entry, "wireSystem")), NbtCompat.getBoolean(entry, "powered"));
        }
        // A loaded network is authoritative persisted state, not a newly-built transient graph.
        structureChanged = false;
        gatesChanged = true;
        changedSystems.clear();
        changedPlayers.clear();
    }

    public static WorldSavedDataWireSystems get(Level world) {
        if (world.isClientSide()) {
            throw new UnsupportedOperationException("Attempted to get LevelSavedDataWireSystems on the client!");
        }
        WorldSavedDataWireSystems instance = ((ServerLevel) world).getDataStorage().computeIfAbsent(TYPE);
        instance.world = world;
        return instance;
    }
}
