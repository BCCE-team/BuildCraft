/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.transport.pipe.flow;

import buildcraft.lib.platform.storage.StorageAdapters;
import buildcraft.lib.platform.storage.EnergyStorage;
import buildcraft.lib.platform.storage.PlatformStorage;
import buildcraft.api.v2.energy.MjAmount;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.List;
import java.util.function.ToIntFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.logic.distribution.WeightedAllocation;
import buildcraft.lib.logic.energy.EnergyMath;
import buildcraft.lib.internal.core.SafeTimeTracker;
import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.transport.internal.pipe.IFlowForgeEnergy;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.IPipe.ConnectedType;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pipe.PipeEventForgeEnergy;
import buildcraft.transport.internal.pipe.PipeFlow;
import buildcraft.core.BCCoreConfig;
import buildcraft.lib.misc.VecUtil;
import buildcraft.lib.misc.data.AverageInt;
import buildcraft.transport.pipe.Pipe;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import buildcraft.lib.net.BCNetworkSide;

/** Forge Energy pipe flow using BuildCraft 8 external-energy semantics with FE terminology. */
public class PipeFlowForgeEnergy extends PipeFlow implements IFlowForgeEnergy, IDebuggable {
    private static final int DEFAULT_MAX_POWER = 100;
    public static final int NET_POWER_AMOUNTS = 2;

    public Vec3 clientDisplayFlowCentre = VecUtil.VEC_HALF;
    public Vec3 clientDisplayFlowCentreLast = VecUtil.VEC_HALF;
    public long clientLastDisplayTime;

    private int maxPower = -1;
    private boolean disabled;
    private boolean isReceiver;
    private long currentWorldTime = Long.MIN_VALUE;
    private final EnumMap<Direction, Section> sections = new EnumMap<>(Direction.class);

    private final SafeTimeTracker networkTracker = new SafeTimeTracker(BCCoreConfig.networkUpdateRate, 2);
    private final EnumFlow[] lastObservedFlows = new EnumFlow[Direction.values().length];
    private final int[] lastObservedDisplayPower = new int[Direction.values().length];
    private boolean networkUpdatePending;

    public PipeFlowForgeEnergy(IPipe pipe) {
        super(pipe);
        initSections();
    }

    public PipeFlowForgeEnergy(IPipe pipe, CompoundTag nbt) {
        super(pipe, nbt);
        isReceiver = nbt.getBoolean("isReceiver");
        initSections();
        CompoundTag energyBuffers = nbt.getCompound("energyBuffers");
        for (Direction face : Direction.values()) {
            CompoundTag sectionNbt = energyBuffers.getCompound(Integer.toString(face.ordinal()));
            Section section = sections.get(face);
            section.internalPower = Math.max(0, sectionNbt.getInt("power"));
            section.internalNextPower = Math.max(0, sectionNbt.getInt("nextPower"));
        }
    }

    private void initSections() {
        for (Direction face : Direction.values()) sections.put(face, new Section(face));
    }

    @Override
    public CompoundTag writeToNbt() {
        CompoundTag nbt = super.writeToNbt();
        nbt.putBoolean("isReceiver", isReceiver);
        CompoundTag energyBuffers = new CompoundTag();
        for (Direction face : Direction.values()) {
            Section section = sections.get(face);
            CompoundTag sectionNbt = new CompoundTag();
            sectionNbt.putInt("power", Math.max(0, section.internalPower));
            sectionNbt.putInt("nextPower", Math.max(0, section.internalNextPower));
            energyBuffers.put(Integer.toString(face.ordinal()), sectionNbt);
        }
        nbt.put("energyBuffers", energyBuffers);
        return nbt;
    }

    @Override
    public boolean requiresPeriodicSave() {
        return sections.values().stream().anyMatch(section -> section.internalPower > 0 || section.internalNextPower > 0);
    }

    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER && (id == NET_POWER_AMOUNTS || id == NET_ID_FULL_STATE)) {
            for (Direction face : Direction.values()) {
                Section section = sections.get(face);
                buffer.writeInt(section.displayPower);
                buffer.writeEnum(section.displayFlow);
            }
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) throws IOException {
        super.readPayload(id, buffer, side);
        if (side == BCNetworkSide.CLIENT && (id == NET_POWER_AMOUNTS || id == NET_ID_FULL_STATE)) {
            for (Direction face : Direction.values()) {
                Section section = sections.get(face);
                section.displayPower = buffer.readInt();
                section.displayFlow = buffer.readEnum(EnumFlow.class);
            }
        }
    }

    @Override
    public boolean canConnect(Direction face, PipeFlow other) {
        return other instanceof PipeFlowForgeEnergy;
    }

    @Override
    public boolean canConnect(Direction face, BlockEntity tile) {
        if (tile == null) return false;
        return PlatformStorage.energy(tile, face.getOpposite()) != null;
    }

    private void ensureConfigured() {
        if (maxPower < 0) reconfigure();
    }

    @Override
    public void reconfigure() {
        PipeEventForgeEnergy.Configure configure = new PipeEventForgeEnergy.Configure(pipe.getHolder(), this);
        PipeApi.ForgeEnergyTransferInfo transferInfo = PipeApi.getForgeEnergyTransferInfo(pipe.getDefinition());
        configure.setReceiver(transferInfo.isReceiver);
        configure.setMaxPower(transferInfo.transferPerTick);
        pipe.getHolder().fireEvent(configure);
        isReceiver = configure.isReceiver();
        maxPower = configure.getMaxPower();
        disabled = configure.isTransferDisabled();
        if (maxPower <= 0) maxPower = DEFAULT_MAX_POWER;
    }

    @Override
    public int tryExtractPower(int maxExtracted, Direction from) {
        ensureConfigured();
        if (!isReceiver || disabled || from == null || maxExtracted <= 0) return 0;
        BlockEntity tile = pipe.getConnectedTile(from);
        if (tile == null) return 0;
        EnergyStorage storage = PlatformStorage.energy(tile, from.getOpposite());
        if (storage == null || !storage.canExtract()) return 0;

        step();
        Section section = sections.get(from);
        int buffered = saturatingAdd(Math.max(0, section.internalPower), Math.max(0, section.internalNextPower));
        int free = Math.max(0, maxPower - buffered);
        int requested = Math.min(Math.min(maxExtracted, maxPower), Math.min(getPowerRequested(from), free));
        if (requested <= 0) return 0;
        int simulated = Math.max(0, Math.min(requested, storage.extractEnergy(requested, true)));
        if (simulated <= 0) return 0;
        int extracted = Math.max(0, Math.min(simulated, storage.extractEnergy(simulated, false)));
        if (extracted <= 0) return 0;
        int leftover = section.receivePowerInternal(extracted);
        int accepted = extracted - leftover;
        if (accepted > 0) {
            section.debugPowerInput = saturatingAdd(section.debugPowerInput, accepted);
            section.displayFlow = EnumFlow.IN;
            section.powerAverage.push(accepted);
        }
        return accepted;
    }

    @Override
    public boolean isExternalEnergyReceiver(Direction side) {
        if (side == null || pipe.getConnectedType(side) != ConnectedType.TILE) return false;
        LazyOptional<net.minecraftforge.energy.IEnergyStorage> cap =
            pipe.getHolder().getCapabilityFromPipe(side, ForgeCapabilities.ENERGY);
        EnergyStorage storage = StorageAdapters.fromNativeEnergy(cap == null ? null : cap.orElse(null));
        return storage != null && storage.canReceive();
    }

    @Override
    public boolean onFlowActivate(Player player, BlockHitResult trace, Level level, EnumPipePart part) {
        return super.onFlowActivate(player, trace, level, part);
    }

    public Section getSection(Direction side) {
        return sections.get(side);
    }

    /** API2 bridge: receives external integer energy without exposing loader energy-storage types. */
    public int receiveEnergyFromApi(Direction side, int offered, boolean simulate) {
        if (side == null || offered <= 0) return 0;
        ensureConfigured();
        Section section = sections.get(side);
        return section == null ? 0 : section.receiveEnergy(offered, simulate);
    }

    public int getStoredEnergyForApi(Direction side) {
        if (side == null) return 0;
        ensureConfigured();
        Section section = sections.get(side);
        return section == null ? 0 : section.getEnergyStored();
    }

    public int getMaxEnergyForApi() {
        ensureConfigured();
        return Math.max(0, maxPower);
    }

    /** Rolling server-side FE throughput through the pipe, in FE per tick. */
    public int getAverageThroughput() {
        ensureConfigured();
        if (disabled || maxPower <= 0) return 0;
        double maxAverage = 0.0D;
        for (Section section : sections.values()) {
            maxAverage = Math.max(maxAverage, section.powerAverage.getAverage());
        }
        return Math.min(maxPower, Math.max(0, (int) Math.round(maxAverage)));
    }

    /** Effective FE throughput ceiling after pipe behaviours, in FE/t. */
    public int getTransferCapacityPerTick() {
        ensureConfigured();
        return disabled ? 0 : Math.max(0, maxPower);
    }

    public boolean canReceiveEnergyFromApi() {
        ensureConfigured();
        return isReceiver && !disabled;
    }

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@Nonnull Capability<T> capability, Direction facing) {
        if (facing != null && capability == ForgeCapabilities.ENERGY && isReceiver && !disabled) {
            return LazyOptional.of(() -> StorageAdapters.toNativeEnergy(sections.get(facing))).cast();
        }
        return LazyOptional.empty();
    }

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        left.add("maxFE = " + maxPower + " FE/t");
        left.add("isReceiver = " + isReceiver);
        left.add("disabled = " + disabled);
        left.add("internalFE = " + arrayToString(s -> s.internalPower) + " <- " + arrayToString(s -> s.internalNextPower));
        left.add("- request: " + arrayToString(s -> s.powerQuery) + " <- " + arrayToString(s -> s.nextPowerQuery));
        left.add("- FE: IN " + arrayToString(s -> s.debugPowerInput) + ", OUT " + arrayToString(s -> s.debugPowerOutput));
    }

    private String arrayToString(ToIntFunction<Section> getter) {
        int[] arr = new int[Direction.values().length];
        for (Direction face : Direction.values()) arr[face.ordinal()] = getter.applyAsInt(sections.get(face));
        return Arrays.toString(arr);
    }

    @Override
    public void onTick() {
        ensureConfigured();
        if (pipe.getHolder().getPipeWorld().isClientSide()) {
            clientDisplayFlowCentreLast = clientDisplayFlowCentre;
            for (Direction face : Direction.values()) {
                Section section = sections.get(face);
                section.clientDisplayFlowLast = section.clientDisplayFlow;
                double diff = section.displayFlow.value * 2.4 * face.getAxisDirection().getStep();
                section.clientDisplayFlow = (section.clientDisplayFlow + 16 + diff) % 16;
                double centre = VecUtil.getValue(clientDisplayFlowCentre, face.getAxis());
                clientDisplayFlowCentre = VecUtil.replaceValue(clientDisplayFlowCentre, face.getAxis(), (centre + 16 + diff / 2) % 16);
            }
            return;
        }

        step();

        // Distribute energy already present in the pipe towards demand. API2 route components participate
        // by weighting or blocking output faces without exposing the internal FE flow graph.
        for (Direction inputFace : Direction.values()) {
            Section input = sections.get(inputFace);
            if (input.internalPower <= 0) continue;

            EnumSet<Direction> routeCandidates = EnumSet.noneOf(Direction.class);
            for (Direction outputFace : Direction.values()) {
                if (outputFace != inputFace && sections.get(outputFace).powerQuery > 0) routeCandidates.add(outputFace);
            }
            boolean returnPower = false;
            if (routeCandidates.isEmpty() && input.powerQuery > 0) {
                routeCandidates.add(inputFace);
                returnPower = true;
            }
            if (routeCandidates.isEmpty()) continue;

            Map<Direction, Long> routeWeights = new EnumMap<>(Direction.class);
            if (pipe instanceof Pipe runtimePipe) {
                routeWeights.putAll(runtimePipe.applyExternalEnergyRouting(inputFace, input.internalPower, routeCandidates));
            } else {
                for (Direction candidate : routeCandidates) routeWeights.put(candidate, 1L);
            }

            WeightedAllocation allocation = new WeightedAllocation();
            for (Direction candidate : routeCandidates) {
                allocation.add(sections.get(candidate).powerQuery, routeWeights.getOrDefault(candidate, 0L));
            }
            if (!allocation.hasDemand()) continue;

            for (Direction outputFace : Direction.values()) {
                if (outputFace == inputFace && !returnPower) continue;
                Section output = sections.get(outputFace);
                long routeWeight = routeWeights.getOrDefault(outputFace, 0L);
                if (output.powerQuery <= 0 || routeWeight <= 0 || input.internalPower <= 0) continue;

                int offered = allocation.offerInt(input.internalPower, output.powerQuery, routeWeight);
                if (offered <= 0) continue;

                int leftover = offered;
                IPipe neighbour = pipe.getHolder().getNeighbourPipe(outputFace);
                if (neighbour != null && neighbour != Pipe.EMPTY && neighbour.getFlow() instanceof PipeFlowForgeEnergy other
                    && neighbour.isConnected(outputFace.getOpposite())) {
                    leftover = other.sections.get(outputFace.getOpposite()).receivePowerInternal(offered);
                } else {
                    LazyOptional<IEnergyStorage> cap = pipe.getHolder().getCapabilityFromPipe(outputFace, ForgeCapabilities.ENERGY);
                    EnergyStorage receiver = StorageAdapters.fromNativeEnergy(cap == null ? null : cap.orElse(null));
                    if (receiver != null && receiver.canReceive()) {
                        int accepted = Math.max(0, Math.min(offered, receiver.receiveEnergy(offered, false)));
                        leftover = offered - accepted;
                    }
                }

                int used = offered - leftover;
                if (used > 0) {
                    input.internalPower -= used;
                    output.debugPowerOutput = saturatingAdd(output.debugPowerOutput, used);
                    input.powerAverage.push(used);
                    output.powerAverage.push(used);
                    input.displayFlow = EnumFlow.OUT;
                    output.displayFlow = EnumFlow.IN;
                }
            }
        }

        for (Section section : sections.values()) {
            section.powerAverage.tick();
            double normalized = maxPower <= 0 ? 0 : section.powerAverage.getAverage() / (double) maxPower;
            section.displayPower = (int) (Math.sqrt(Math.max(0, normalized)) * MjAmount.MICRO_MJ_PER_MJ);
        }

        // Ask neighbouring FE consumers how much they can really receive. Simulation is important for
        // bufferless machines whose getMaxEnergyStored()/getEnergyStored() do not describe demand.
        for (Direction face : Direction.values()) {
            if (pipe.getConnectedType(face) != ConnectedType.TILE) continue;
            LazyOptional<IEnergyStorage> cap = pipe.getHolder().getCapabilityFromPipe(face, ForgeCapabilities.ENERGY);
            EnergyStorage receiver = StorageAdapters.fromNativeEnergy(cap == null ? null : cap.orElse(null));
            if (receiver != null && receiver.canReceive()) {
                int requested = Math.max(0, receiver.receiveEnergy(maxPower, true));
                if (requested > 0) requestPower(face, requested);
            }
        }

        int[] transferQuery = new int[Direction.values().length];
        for (Direction face : Direction.values()) {
            if (!pipe.isConnected(face)) continue;
            int query = 0;
            for (Direction other : Direction.values()) {
                if (other != face) query = saturatingAdd(query, sections.get(other).powerQuery);
            }
            transferQuery[face.ordinal()] = Math.min(maxPower, query);
        }

        for (Direction face : Direction.values()) {
            if (disabled || transferQuery[face.ordinal()] <= 0 || !pipe.isConnected(face)) continue;
            IPipe neighbour = pipe.getHolder().getNeighbourPipe(face);
            if (neighbour == null || neighbour == Pipe.EMPTY || !(neighbour.getFlow() instanceof PipeFlowForgeEnergy other)) continue;
            other.requestPower(face.getOpposite(), transferQuery[face.ordinal()]);
        }

        if (isReceiver && !disabled) {
            for (Direction face : Direction.values()) {
                int requested = transferQuery[face.ordinal()];
                if (requested > 0 && pipe.getConnectedType(face) == ConnectedType.TILE) tryExtractPower(requested, face);
            }
        }

        boolean changed = false;
        for (Direction face : Direction.values()) {
            Section section = sections.get(face);
            int i = face.ordinal();
            if (lastObservedFlows[i] != section.displayFlow || lastObservedDisplayPower[i] != section.displayPower) changed = true;
            lastObservedFlows[i] = section.displayFlow;
            lastObservedDisplayPower[i] = section.displayPower;
        }
        if (changed) networkUpdatePending = true;
        if (networkUpdatePending && networkTracker.markTimeIfDelay(pipe.getHolder().getPipeWorld())) {
            sendPayload(NET_POWER_AMOUNTS);
            networkUpdatePending = false;
        }
    }

    private void step() {
        ensureConfigured();
        long now = pipe.getHolder().getPipeWorld().getGameTime();
        if (currentWorldTime != now) {
            currentWorldTime = now;
            sections.values().forEach(Section::step);
        }
    }

    private void requestPower(Direction from, int amount) {
        if (disabled || amount <= 0) return;
        step();
        int requested = pipe.getBehaviour() instanceof IPipeTransportForgeEnergyHook hook
            ? hook.requestPower(from, amount) : amount;
        Section section = sections.get(from);
        section.nextPowerQuery = Math.min(maxPower, saturatingAdd(section.nextPowerQuery, Math.max(0, requested)));
    }

    public int getPowerRequested(@Nullable Direction side) {
        ensureConfigured();
        if (disabled) return 0;
        int requested = 0;
        for (Direction face : Direction.values()) {
            if (side == null || face != side) requested = saturatingAdd(requested, sections.get(face).getEffectivePowerQuery());
        }
        return Math.min(maxPower, requested);
    }

    public double getMaxTransferForRender(float partialTicks) {
        return maxPower / (double) MjAmount.MICRO_MJ_PER_MJ;
    }

    private static int saturatingAdd(int a, int b) {
        return EnergyMath.saturatingAdd(a, b);
    }

    public class Section implements EnergyStorage {
        public final Direction side;
        public final AverageInt clientDisplayAverage = new AverageInt(10);
        public double clientDisplayFlow;
        public double clientDisplayFlowLast;
        public int displayPower;
        public EnumFlow displayFlow = EnumFlow.STATIONARY;
        public int nextPowerQuery;
        public int internalNextPower;
        public final AverageInt powerAverage = new AverageInt(10);
        int powerQuery;
        int internalPower;
        int debugPowerInput;
        int debugPowerOutput;

        Section(Direction side) {
            this.side = side;
            clientDisplayFlow = (side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 7 : 1) / 8.0;
        }

        void step() {
            powerQuery = Math.min(maxPower, Math.max(0, nextPowerQuery));
            nextPowerQuery = 0;
            internalPower = Math.min(
                maxPower,
                saturatingAdd(Math.max(0, internalPower), Math.max(0, internalNextPower))
            );
            internalNextPower = 0;
        }

        int getEffectivePowerQuery() {
            return currentWorldTime == pipe.getHolder().getPipeWorld().getGameTime() ? powerQuery : nextPowerQuery;
        }

        int receivePowerInternal(int sent) {
            ensureConfigured();
            if (disabled || sent <= 0) return sent;
            step();
            int buffered = saturatingAdd(Math.max(0, internalPower), Math.max(0, internalNextPower));
            int free = Math.max(0, maxPower - buffered);
            int accepted = Math.min(sent, free);
            internalNextPower += accepted;
            return sent - accepted;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            if (!isReceiver || disabled || maxReceive <= 0) return 0;
            ensureConfigured();
            int requested = Math.max(0, getPowerRequested(side));
            if (requested <= 0) return 0;
            int buffered = saturatingAdd(Math.max(0, internalPower), Math.max(0, internalNextPower));
            int free = Math.max(0, maxPower - buffered);
            int accepted = Math.min(maxReceive, Math.min(free, requested));
            if (!simulate && accepted > 0) accepted -= receivePowerInternal(accepted);
            return accepted;
        }

        @Override public int extractEnergy(int maxExtract, boolean simulate) { return 0; }
        @Override public int getEnergyStored() {
            ensureConfigured();
            return Math.min(maxPower, saturatingAdd(Math.max(0, internalPower), Math.max(0, internalNextPower)));
        }
        @Override public int getMaxEnergyStored() { ensureConfigured(); return maxPower; }
        @Override public boolean canExtract() { return false; }
        @Override public boolean canReceive() { return isReceiver && !disabled; }
    }

    public enum EnumFlow {
        IN(-1), OUT(1), STATIONARY(0);
        public final int value;
        EnumFlow(int value) { this.value = value; }
    }
}
