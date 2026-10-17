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

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.signal.BuildCraftSignalChannels;
import buildcraft.api.v2.signal.SignalChannelType;
import buildcraft.api.v2.signal.SignalPort;
import buildcraft.api.v2.signal.SignalPortProvider;
import buildcraft.api.v2.pipe.PipeView;
import buildcraft.api.v2.pipe.PipeExternalEndpoint;
import buildcraft.transport.api2.PipeEndpointRuntime;
import buildcraft.transport.internal.EnumWirePart;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.lib.net.MessageManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

public class WorldSavedDataWireSystems extends SavedData {
    public static final String DATA_NAME = "buildcraft_wire_systems";
    public Level world;
    public final Map<WireSystem, Boolean> wireSystems = new HashMap<>();
    public boolean gatesChanged = true;
    public boolean structureChanged = true;
    public final List<WireSystem> changedSystems = new ArrayList<>();
    public final List<Player> changedPlayers = new ArrayList<>();

    private final Map<WireSystem.WireElement, List<WireSystem>> elementsToWireSystemsIndex = new HashMap<>();

    public WorldSavedDataWireSystems() {}

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
        if (!world.isLoaded(element.blockPos)) return false;
        BlockEntity tile = world.getBlockEntity(element.blockPos);
        if (!(tile instanceof IPipeHolder holder)) return false;
        if (holder.getWireManager().isSignalOutputActive(element.emitterSide, color)) return true;
        SignalPort<Boolean> external = getExternalPort(element, color);
        if (external == null) return false;
        try { return Boolean.TRUE.equals(external.publishedValue()); }
        catch (RuntimeException ex) {
            BCLog.logger.warn("External wire signal source failed at {}", element.blockPos, ex);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private SignalPort<Boolean> getExternalPort(WireSystem.WireElement element, DyeColor color) {
        if (!world.hasChunkAt(element.blockPos)) return null;
        BlockEntity source = world.getBlockEntity(element.blockPos);
        if (!(source instanceof IPipeHolder holder) || !(holder.getPipe() instanceof PipeView pipe)) return null;
        ResourceLocation channelId = BuildCraftSignalChannels.id(color);
        SignalChannelType<?> expected = BuildCraftApi.registry(BuildCraftRegistries.SIGNAL_CHANNEL_TYPES).get(channelId);
        if (expected == null) return null;
        try {
            SignalPort<?> attachment = PipeEndpointRuntime.attachmentPort(holder.getPluggable(element.emitterSide),
                plug -> plug.signalPort(channelId).map(port -> (SignalPort<?>) port)).orElse(null);
            if (attachment != null && attachment.channel() == expected) return (SignalPort<Boolean>) attachment;

            BlockPos externalPos = element.blockPos.relative(element.emitterSide);
            if (!world.isLoaded(externalPos)) return null;
            BlockEntity blockEntity = world.getBlockEntity(externalPos);
            if (blockEntity instanceof SignalPortProvider provider) {
                SignalPort<?> supplied = provider.signalPort(element.emitterSide.getOpposite(), channelId).orElse(null);
                if (supplied != null && supplied.channel() == expected) return (SignalPort<Boolean>) supplied;
            }
            PipeExternalEndpoint endpoint = PipeEndpointRuntime.find(world, pipe, element.emitterSide).orElse(null);
            if (endpoint == null) return null;
            SignalPort<?> supplied = endpoint.signalPort(channelId).orElse(null);
            return supplied != null && supplied.channel() == expected ? (SignalPort<Boolean>) supplied : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void publishExternalSignal(WireSystem wireSystem, boolean powered) {
        for (WireSystem.WireElement element : wireSystem.elements) {
            if (element.type != WireSystem.WireElement.Type.EMITTER_SIDE) continue;
            SignalPort<Boolean> external = getExternalPort(element, wireSystem.color);
            if (external != null) {
                try { external.receive(powered, OperationMode.EXECUTE); }
                catch (RuntimeException ex) {
                    BCLog.logger.warn("External wire signal receiver failed at {}", element.blockPos, ex);
                }
            }
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
        if(!world.isClientSide && (structureChanged || !changedSystems.isEmpty() || !changedPlayers.isEmpty()))
        ((ServerLevel)world).players().forEach(player -> {
            Map<Integer, WireSystem> changedWires = this.wireSystems.keySet().stream()
                    .filter(wireSystem -> (structureChanged || changedPlayers.contains(player)) && wireSystem.isPlayerWatching(player))
                    .collect(Collectors.toMap(WireSystem::getWiresHashCode, Function.identity()));
            if(!changedWires.isEmpty()) {
                MessageManager.sendTo(new MessageWireSystems(changedWires), player);
            }
            Map<Integer, Boolean> hashesPowered = this.wireSystems.entrySet().stream()
                    .filter(systemPower ->
                            (structureChanged || changedSystems.contains(systemPower.getKey()) || changedPlayers.contains(player)) &&
                                    systemPower.getKey().isPlayerWatching(player)
                    )
                    .map(systemPowered -> Pair.of(systemPowered.getKey().getWiresHashCode(), systemPowered.getValue()))
                    .collect(Collectors.toMap(Pair::getLeft, Pair::getRight));
            if(!hashesPowered.isEmpty()) {
                MessageManager.sendTo(new MessageWireSystemsPowered(hashesPowered), player);
            }
        });
        if(structureChanged || !changedSystems.isEmpty()) {
            setDirty();
        }
        structureChanged = false;
        changedSystems.clear();
        changedPlayers.clear();
    }

    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        ListTag entriesList = new ListTag();
        List<CompoundTag> temp = new ArrayList<>();
        wireSystems.forEach((wireSystem, powered) -> {
            CompoundTag entry = new CompoundTag();
            entry.put("wireSystem", wireSystem.writeToNBT());
            entry.putBoolean("powered", powered);
            temp.add(entry);
        });
        int conter = 0;
        for(CompoundTag t : temp)
        	entriesList.add(conter++, t);
        nbt.put("entries", entriesList);
        return nbt;
    }

    public static WorldSavedDataWireSystems load(CompoundTag nbt, HolderLookup.Provider registries) {
    	WorldSavedDataWireSystems wsds = new WorldSavedDataWireSystems();
        wsds.wireSystems.clear();
        wsds.elementsToWireSystemsIndex.clear();

        ListTag entriesList = nbt.getList("entries", Tag.TAG_COMPOUND);
        for(int i = 0; i < entriesList.size(); i++) {
            CompoundTag entry = entriesList.getCompound(i);
            try {
                wsds.addWireSystem(new WireSystem(entry.getCompound("wireSystem")), entry.getBoolean("powered"));
            } catch (RuntimeException invalidNetwork) {
                BCLog.logger.warn("Skipping invalid saved wire network entry {}", i, invalidNetwork);
            }
        }
        return wsds;
    }

    public static WorldSavedDataWireSystems get(Level world) {
        if(world.isClientSide()) {
            throw new UnsupportedOperationException("Attempted to get LevelSavedDataWireSystems on the client!");
        }
        DimensionDataStorage storage = ((ServerLevel)world).getDataStorage();
        SavedData.Factory<WorldSavedDataWireSystems> factory = new SavedData.Factory<>(
            WorldSavedDataWireSystems::new,
            WorldSavedDataWireSystems::load,
            DataFixTypes.LEVEL
        );
        WorldSavedDataWireSystems instance = storage.computeIfAbsent(factory, DATA_NAME);
        instance.world = world;
        return instance;
    }
}
