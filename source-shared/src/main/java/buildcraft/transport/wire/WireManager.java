/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.wire;

import java.io.IOException;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.signal.BuildCraftSignalChannels;
import buildcraft.api.v2.signal.SignalChannelType;
import buildcraft.api.v2.signal.SignalPort;
import buildcraft.api.v2.signal.SignalPortProvider;

import buildcraft.transport.internal.EnumWirePart;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.pipe.Pipe;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class WireManager {
    private final IPipeHolder holder;
    public final Map<EnumWirePart, DyeColor> parts = new EnumMap<>(EnumWirePart.class);
    public final Set<EnumWirePart> poweredClient = EnumSet.noneOf(EnumWirePart.class);
    public final Map<EnumWireBetween, DyeColor> betweens = new EnumMap<>(EnumWireBetween.class);
    private final Map<Direction, EnumSet<DyeColor>> signalOutputs = new EnumMap<>(Direction.class);
    public boolean initialised = false;

    public WireManager(IPipeHolder holder) {
        this.holder = holder;
    }

    public WorldSavedDataWireSystems getWireSystems() {
        return WorldSavedDataWireSystems.get(holder.getPipeWorld());
    }

    public IPipeHolder getHolder() {
        return holder;
    }

    public void invalidate() {
        if (!holder.getPipeWorld().isClientSide()&&initialised) {
            removePartsFromSystem(parts.keySet());
            initialised = false;
        }
    }

    public void validate() {
        if (!holder.getPipeWorld().isClientSide()) {
            initialised = false;
        }
    }

    public void tick() {
        if (!initialised) {
            initialised = true;
            if (!holder.getPipeWorld().isClientSide()) {
                for (EnumWirePart part : parts.keySet()) {
                    getWireSystems().buildAndAddWireSystem(new WireSystem.WireElement(holder.getPipePos(), part));
                }
            }
            updateBetweens(false);
        }
    }

    public boolean addPart(EnumWirePart part, DyeColor colour) {
        if (getColorOfPart(part) == null) {
            parts.put(part, colour);
            if (!holder.getPipeWorld().isClientSide()) {
                getWireSystems().buildAndAddWireSystem(new WireSystem.WireElement(holder.getPipePos(), part));
                holder.getPipeTile().setChanged();
            }
            updateBetweens(false);
            return true;
        } else {
            return false;
        }
    }

    public DyeColor removePart(EnumWirePart part) {
        DyeColor color = getColorOfPart(part);
        if (color == null) {
            return null;
        } else {
            parts.remove(part);
            if (!holder.getPipeWorld().isClientSide()) {
                WireSystem.WireElement element = new WireSystem.WireElement(holder.getPipePos(), part);
                WireSystem.getConnectedElementsOfElement(holder, element)
                    .forEach(getWireSystems()::buildAndAddWireSystem);
                getWireSystems().getWireSystemsWithElement(element).forEach(getWireSystems()::removeWireSystem);
                holder.getPipeTile().setChanged();
            }
            updateBetweens(false);
            return color;
        }
    }

    public void removeParts(Collection<EnumWirePart> toRemove) {
        toRemove.forEach(this.parts::remove);
        if (!holder.getPipeWorld().isClientSide()) {
            removePartsFromSystem(toRemove);
        }
        updateBetweens(false);
    }

    private void removePartsFromSystem(Collection<EnumWirePart> toRemove) {
        toRemove.stream().map(part -> new WireSystem.WireElement(holder.getPipePos(), part))
            .flatMap(element -> WireSystem.getConnectedElementsOfElement(holder, element).stream()).distinct()
            .forEach(getWireSystems()::buildAndAddWireSystem);
        toRemove.stream().map(part -> new WireSystem.WireElement(holder.getPipePos(), part))
            .flatMap(element -> getWireSystems().getWireSystemsWithElement(element).stream())
            .forEach(getWireSystems()::removeWireSystem);
//        holder.getPipeTile().setChanged();
    }
    
    public void rotate(Rotation rotation) {
        if (rotation == Rotation.NONE) {
            return;
        }
        
        Map<EnumWirePart, DyeColor> rotatedParts = Map.copyOf(parts);
        parts.clear();
        for (Map.Entry<EnumWirePart, DyeColor> entry : rotatedParts.entrySet()) 
            parts.put(entry.getKey().rotate(rotation), entry.getValue());
        
        Set<EnumWirePart> rotatedPowered = EnumSet.copyOf(poweredClient);
        poweredClient.clear();
        for (EnumWirePart part : rotatedPowered) 
        	poweredClient.add(part.rotate(rotation));
        
        Map<EnumWireBetween, DyeColor> rotatedBetweens = Map.copyOf(betweens);
        betweens.clear();
        for (Map.Entry<EnumWireBetween, DyeColor> entry : rotatedBetweens.entrySet()) 
        	betweens.put(entry.getKey().rotate(rotation), entry.getValue());

        Map<Direction, EnumSet<DyeColor>> rotatedOutputs = new EnumMap<>(Direction.class);
        signalOutputs.forEach((side, colors) ->
            rotatedOutputs.put(rotation.rotate(side), colors.isEmpty() ? EnumSet.noneOf(DyeColor.class) : EnumSet.copyOf(colors))
        );
        signalOutputs.clear();
        signalOutputs.putAll(rotatedOutputs);
    }

    public void updateBetweens(boolean recursive) {
        betweens.clear();
        parts.forEach((part, color) -> {
            for (EnumWireBetween between : EnumWireBetween.VALUES) {
                EnumWirePart[] betweenParts = between.parts;
                if (between.to == null) {
                    if ((betweenParts[0] == part && getColorOfPart(betweenParts[1]) == color)
                        || (betweenParts[1] == part && getColorOfPart(betweenParts[0]) == color)) {
                        betweens.put(between, color);
                    }
                } else {
                    IPipe pipe = holder.getNeighbourPipe(between.to);
                    if (pipe != Pipe.EMPTY && WireSystem.canWireConnect(holder, between.to)) {
                        WireManager wireManager = pipe.getHolder().getWireManager();
                        if (betweenParts[0] == part && wireManager.getColorOfPart(betweenParts[1]) == color) {
                            betweens.put(between, color);
                        }
                    } else if (pipe == Pipe.EMPTY && betweenParts[0] == part && hasExternalSignalPort(between.to, color)) {
                        // Extend the wire to the block face even though there is no neighbouring pipe model.
                        betweens.put(between, color);
                    }
                }
            }
        });

        if (!recursive) {
            for (Direction side : Direction.values()) {
                BlockEntity tile = holder.getPipeWorld().getBlockEntity(holder.getPipePos().offset(side.getNormal()));
                if (tile instanceof IPipeHolder) {
                    ((IPipeHolder) tile).getWireManager().updateBetweens(true);
                }
            }
        }
    }

    private boolean hasExternalSignalPort(Direction side, DyeColor color) {
        BlockEntity blockEntity = holder.getPipeWorld().getBlockEntity(holder.getPipePos().relative(side));
        if (!(blockEntity instanceof SignalPortProvider provider)) return false;
        var channelId = BuildCraftSignalChannels.id(color);
        SignalChannelType<?> expected = BuildCraftApi.registry(BuildCraftRegistries.SIGNAL_CHANNEL_TYPES).get(channelId);
        if (expected == null) return false;
        return provider.signalPort(side.getOpposite(), channelId)
            .map(SignalPort::channel)
            .map(channel -> channel == expected)
            .orElse(false);
    }

    public DyeColor getColorOfPart(EnumWirePart part) {
        return parts.get(part);
    }

    public boolean hasPartOfColor(DyeColor color) {
        return parts.values().contains(color);
    }

    public boolean isPowered(EnumWirePart part) {
        if (holder.getPipeWorld().isClientSide()) {
            return poweredClient.contains(part);
        } else {
            WorldSavedDataWireSystems wireSystems = this.getWireSystems();
            List<WireSystem> wireSystemsWithElement = wireSystems.getWireSystemsWithElementAsReadOnlyList(new WireSystem.WireElement(holder.getPipePos(), part));
            if (!wireSystemsWithElement.isEmpty()) {
                for (WireSystem wireSystem : wireSystemsWithElement) {
                    Boolean powered = wireSystems.wireSystems.get(wireSystem);
                    if (powered != null && powered) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    public boolean isAnyPowered(DyeColor color) {
        if (!this.parts.isEmpty()) {
            for (Map.Entry<EnumWirePart, DyeColor> partColor : this.parts.entrySet()) {
                if (partColor.getValue() == color && this.isPowered(partColor.getKey())) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isSignalOutputActive(Direction side, DyeColor color) {
        EnumSet<DyeColor> outputs = signalOutputs.get(side);
        return outputs != null && outputs.contains(color);
    }

    /** Updates one API2 signal source attached to this pipe side. */
    public boolean setSignalOutput(Direction side, DyeColor color, boolean active) {
        EnumSet<DyeColor> outputs = signalOutputs.computeIfAbsent(side, ignored -> EnumSet.noneOf(DyeColor.class));
        boolean changed = active ? outputs.add(color) : outputs.remove(color);
        if (outputs.isEmpty()) signalOutputs.remove(side);
        if (changed && !holder.getPipeWorld().isClientSide()) {
            WorldSavedDataWireSystems systems = getWireSystems();
            systems.gatesChanged = true;
            // Legacy wire-system save topology may omit sides that did not host emitter pluggables.
            // Rebuild once when an active source is missing from the saved topology; complete
            // systems already contain every side endpoint, so normal signal toggles are O(1).
            if (active && hasPartOfColor(color)) {
                WireSystem.WireElement endpoint = new WireSystem.WireElement(holder.getPipePos(), side);
                boolean endpointKnown = systems.getWireSystemsWithElementAsReadOnlyList(endpoint).stream()
                    .anyMatch(system -> system.color == color);
                if (!endpointKnown) systems.rebuildWireSystemsAround(holder);
            }
            holder.getPipeTile().setChanged();
        }
        return changed;
    }

    public CompoundTag writeToNbt() {
        CompoundTag nbt = new CompoundTag();
        int[] wiresArray = new int[parts.size() * 2];
        int[] i = { 0 };
        parts.forEach((part, color) -> {
            wiresArray[i[0]] = part.ordinal();
            wiresArray[i[0] + 1] = color.getId();
            i[0] += 2;
        });
        nbt.putIntArray("parts", wiresArray);

        int outputCount = signalOutputs.values().stream().mapToInt(Set::size).sum();
        int[] outputsArray = new int[outputCount * 2];
        int outputIndex = 0;
        for (Map.Entry<Direction, EnumSet<DyeColor>> entry : signalOutputs.entrySet()) {
            for (DyeColor color : entry.getValue()) {
                outputsArray[outputIndex++] = entry.getKey().get3DDataValue();
                outputsArray[outputIndex++] = color.getId();
            }
        }
        nbt.putIntArray("signalOutputs", outputsArray);
        return nbt;
    }

    public void readFromNbt(CompoundTag nbt) {
        parts.clear();
        int[] wiresArray = nbt.getIntArray("parts");
        for (int i = 0; i + 1 < wiresArray.length; i += 2) {
            parts.put(EnumWirePart.VALUES[wiresArray[i]], DyeColor.byId(wiresArray[i + 1]));
        }

        signalOutputs.clear();
        int[] outputsArray = nbt.getIntArray("signalOutputs");
        for (int i = 0; i + 1 < outputsArray.length; i += 2) {
            Direction side = Direction.from3DDataValue(outputsArray[i]);
            DyeColor color = DyeColor.byId(outputsArray[i + 1]);
            signalOutputs.computeIfAbsent(side, ignored -> EnumSet.noneOf(DyeColor.class)).add(color);
        }
    }

    public void writePayload(FriendlyByteBuf buffer, BCNetworkSide side) {
        if (side == BCNetworkSide.SERVER) {
            buffer.writeInt(parts.size());
            for (Entry<EnumWirePart, DyeColor> entry : parts.entrySet()) {
                buffer.writeEnum(entry.getKey());
                buffer.writeEnum(entry.getValue());
            }
        }
    }
    public void readPayload(FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        if (side == BCNetworkSide.CLIENT) {
            parts.clear();
            int count = buffer.readInt();
            for (int i = 0; i < count; i++) {
                EnumWirePart part = buffer.readEnum(EnumWirePart.class);
                DyeColor colour = buffer.readEnum(DyeColor.class);
                parts.put(part, colour);
            }
            updateBetweens(false);
        }
    }
}
