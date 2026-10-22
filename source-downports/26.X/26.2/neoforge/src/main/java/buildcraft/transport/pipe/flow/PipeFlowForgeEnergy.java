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

import javax.annotation.Nullable;

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
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.data.AverageInt;
import buildcraft.transport.pipe.Pipe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import buildcraft.lib.net.BCNetworkSide;
import net.neoforged.neoforge.capabilities.BlockCapability;
import buildcraft.lib.compat.NbtCompat;

/** Forge Energy pipe flow using BuildCraft 8 external-energy semantics with FE terminology. */
public class PipeFlowForgeEnergy extends PipeFlow implements IFlowForgeEnergy, IDebuggable {
    private int apiPowerLimit(buildcraft.api.v2.pipe.PowerMovementPhase phase,
                                 Direction side, int offered, boolean simulate) {
        if (offered <= 0) return 0;
        return (int) buildcraft.transport.api2.PipeFlowEventRuntime.powerLimit(pipe,
            buildcraft.api.v2.pipe.PipeMedium.EXTERNAL_ENERGY, phase, side, offered,
            simulate ? buildcraft.api.v2.OperationMode.SIMULATE : buildcraft.api.v2.OperationMode.EXECUTE);
    }

    private static final int DEFAULT_MAX_POWER = 100;
    public static final int NET_POWER_AMOUNTS = 2;

    public Vec3 clientDisplayFlowCentre = VecUtil.VEC_HALF;
    public Vec3 clientDisplayFlowCentreLast = VecUtil.VEC_HALF;
    public long clientLastDisplayTime;

    private int maxPower = -1;
    private boolean disabled;
    private boolean isReceiver;
    private long currentWorldTime = Long.MIN_VALUE;
    private final buildcraft.lib.compat.transfer.TransferJournal<PowerTransferState> transferJournal =
        new buildcraft.lib.compat.transfer.TransferJournal<>(this::capturePowerTransfer, this::restorePowerTransfer,
            old -> pipe.getHolder().getPipeTile().setChanged());

    private record PowerSectionState(int powerQuery, int nextPowerQuery, int internalPower, int internalNextPower, int debugPowerInput, int debugPowerOutput) {}
    private record PowerTransferState(long time, EnumMap<Direction, PowerSectionState> states,
        long lastSendTick, int sent, long lastReceiveTick, int received) {}

    private PowerTransferState capturePowerTransfer() {
        EnumMap<Direction, PowerSectionState> states = new EnumMap<>(Direction.class);
        sections.forEach((face, s) -> states.put(face, new PowerSectionState(s.powerQuery, s.nextPowerQuery, s.internalPower, s.internalNextPower, s.debugPowerInput, s.debugPowerOutput)));
        return new PowerTransferState(currentWorldTime, states, lastApiSendTick, apiSentThisTick, lastApiReceiveTick, apiReceivedThisTick);
    }

    private void restorePowerTransfer(PowerTransferState state) {
        currentWorldTime = state.time();
        lastApiSendTick = state.lastSendTick();
        apiSentThisTick = state.sent();
        lastApiReceiveTick = state.lastReceiveTick();
        apiReceivedThisTick = state.received();
        state.states().forEach((face, value) -> {
            Section s = sections.get(face);
            s.powerQuery = value.powerQuery(); s.nextPowerQuery = value.nextPowerQuery(); s.internalPower = value.internalPower(); s.internalNextPower = value.internalNextPower(); s.debugPowerInput = value.debugPowerInput(); s.debugPowerOutput = value.debugPowerOutput();
        });
    }

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
        isReceiver = NbtCompat.getBoolean(nbt, "isReceiver");
        initSections();
        CompoundTag energyBuffers = NbtCompat.getCompound(nbt, "energyBuffers");
        for (Direction face : Direction.values()) {
            CompoundTag sectionNbt = NbtCompat.getCompound(energyBuffers, Integer.toString(face.ordinal()));
            Section section = sections.get(face);
            section.internalPower = Math.max(0, NbtCompat.getInt(sectionNbt, "power"));
            section.internalNextPower = Math.max(0, NbtCompat.getInt(sectionNbt, "nextPower"));
        }
    }

    private void initSections() {
        for (Direction face : Direction.values()) sections.put(face, new Section(face));
    }

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

    public boolean requiresPeriodicSave() {
        return sections.values().stream().anyMatch(section -> section.internalPower > 0 || section.internalNextPower > 0);
    }

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

    public boolean canConnect(Direction face, PipeFlow other) {
        return other instanceof PipeFlowForgeEnergy;
    }

    public boolean canConnect(Direction face, BlockEntity tile) {
        if (tile == null || tile.getLevel() == null) return false;
        return canConnect(face, tile.getLevel(), tile.getBlockPos(), tile);
    }

    public boolean canConnect(Direction face, Level level, BlockPos pos, @Nullable BlockEntity tile) {
        return PlatformStorage.energy(level, pos, face.getOpposite()) != null;
    }

    private void ensureConfigured() {
        if (maxPower < 0) reconfigure();
    }

    public void reconfigure() {
        PipeEventForgeEnergy.Configure configure = new PipeEventForgeEnergy.Configure(pipe.getHolder(), this);
        PipeApi.ForgeEnergyTransferInfo transferInfo = PipeApi.getForgeEnergyTransferInfo(pipe.getDefinition());
        configure.setReceiver(transferInfo.isReceiver);
        configure.setMaxPower(transferInfo.transferPerTick);
        pipe.getHolder().fireEvent(configure);
        buildcraft.api.v2.pipe.PowerFlowSettings settings = buildcraft.transport.api2.PipeFlowEventRuntime.configure(
            pipe, new buildcraft.api.v2.pipe.PowerFlowSettings(buildcraft.api.v2.pipe.PipeMedium.EXTERNAL_ENERGY,
                configure.getMaxPower() > 0 ? configure.getMaxPower() : DEFAULT_MAX_POWER,
                -1L, -1L, configure.isReceiver(), configure.isTransferDisabled()));
        configure.setMaxPower((int) settings.maxPerTick());
        configure.setReceiver(settings.receiver());
        if (settings.disabled()) configure.disableTransfer();
        isReceiver = configure.isReceiver();
        maxPower = configure.getMaxPower();
        disabled = configure.isTransferDisabled();
        if (maxPower <= 0) maxPower = DEFAULT_MAX_POWER;
    }

    public int tryExtractPower(int maxExtracted, Direction from) {
        ensureConfigured();
        if (!isReceiver || disabled || from == null || maxExtracted <= 0) return 0;
        EnergyStorage storage = StorageAdapters.fromNativeEnergy(pipe.getHolder().getCapabilityFromPipe(from, CapUtil.CAP_FE));
        if (storage == null || !storage.canExtract()) return 0;

        step();
        Section section = sections.get(from);
        int buffered = saturatingAdd(Math.max(0, section.internalPower), Math.max(0, section.internalNextPower));
        int free = Math.max(0, maxPower - buffered);
        int requested = Math.min(Math.min(maxExtracted, maxPower), Math.min(getPowerRequested(from), free));
        requested = apiPowerLimit(buildcraft.api.v2.pipe.PowerMovementPhase.EXTRACT,
            from, requested, false);
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

    public boolean isExternalEnergyReceiver(Direction side) {
        if (side == null || pipe.getConnectedType(side) != ConnectedType.TILE) return false;
        EnergyStorage storage = StorageAdapters.fromNativeEnergy(pipe.getHolder().getCapabilityFromPipe(side, CapUtil.CAP_FE));
        return storage != null && storage.canReceive();
    }

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

    private long lastApiSendTick = Long.MIN_VALUE;
    private int apiSentThisTick;
    private long lastApiReceiveTick = Long.MIN_VALUE;
    private int apiReceivedThisTick;

    /** Extract FE already present in the pipe, without drawing from adjacent machines. */
    public int extractEnergyForApi(Direction side, int requested, boolean simulate) {
        if (side == null || requested <= 0) return 0;
        ensureConfigured();
        if (disabled) return 0;
        Section section = sections.get(side);
        long now = pipe.getHolder().getPipeWorld().getGameTime();
        int available = currentWorldTime == now ? section.internalPower
            : Math.min(maxPower, saturatingAdd(section.internalPower, section.internalNextPower));
        int budget = Math.max(0, maxPower - (lastApiSendTick == now ? apiSentThisTick : 0));
        int taken = Math.max(0, Math.min(requested, Math.min(available, budget)));
        taken = apiPowerLimit(buildcraft.api.v2.pipe.PowerMovementPhase.EXTRACT, side, taken, simulate);
        if (!simulate && taken > 0) {
            // Journal the pre-transfer buffers AND quotas before any tick advancement.
            transferJournal.record();
            step();
            section.internalPower -= taken;
            if (lastApiSendTick != now) apiSentThisTick = 0;
            lastApiSendTick = now;
            apiSentThisTick += taken;
        }
        return taken;
    }

    /** Atomic in-memory FE transfer between loaded pipe buffers. */
    public int transferEnergyForApi(PipeFlowForgeEnergy target, Direction sourceSide, Direction targetSide,
                                    int maximum, boolean simulate) {
        if (target == null || target == this || sourceSide == null || targetSide == null || maximum <= 0) return 0;
        ensureConfigured();
        target.ensureConfigured();
        if (disabled || target.disabled) return 0;
        Section from = sections.get(sourceSide);
        Section to = target.sections.get(targetSide);
        long sourceTick = pipe.getHolder().getPipeWorld().getGameTime();
        long targetTick = target.pipe.getHolder().getPipeWorld().getGameTime();
        int available = currentWorldTime == sourceTick ? from.internalPower
            : Math.min(maxPower, saturatingAdd(from.internalPower, from.internalNextPower));
        int buffered = saturatingAdd(to.internalPower, to.internalNextPower);
        int sourceBudget = Math.max(0, maxPower - (lastApiSendTick == sourceTick ? apiSentThisTick : 0));
        int targetBudget = Math.max(0, target.maxPower - (target.lastApiReceiveTick == targetTick ? target.apiReceivedThisTick : 0));
        int accepted = Math.max(0, Math.min(maximum, Math.min(available,
            Math.min(Math.max(0, target.maxPower - buffered), Math.min(sourceBudget, targetBudget)))));
        accepted = apiPowerLimit(buildcraft.api.v2.pipe.PowerMovementPhase.PIPE_TRANSFER,
            sourceSide, accepted, simulate);
        accepted = target.apiPowerLimit(buildcraft.api.v2.pipe.PowerMovementPhase.PIPE_TRANSFER,
            targetSide, accepted, simulate);
        if (accepted == 0 || simulate) return accepted;
        transferJournal.record();
        target.transferJournal.record();
        step();
        target.step();
        from.internalPower -= accepted;
        to.internalNextPower += accepted;
        if (lastApiSendTick != sourceTick) apiSentThisTick = 0;
        lastApiSendTick = sourceTick;
        apiSentThisTick += accepted;
        if (target.lastApiReceiveTick != targetTick) target.apiReceivedThisTick = 0;
        target.lastApiReceiveTick = targetTick;
        target.apiReceivedThisTick += accepted;
        return accepted;
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

    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T getCapability(BlockCapability<T, Direction> capability, @Nullable Direction facing) {
        if (facing != null && capability == CapUtil.CAP_FE && isReceiver && !disabled) {
            return (T) StorageAdapters.toNativeEnergy(sections.get(facing));
        }
        return null;
    }

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
                offered = apiPowerLimit(buildcraft.api.v2.pipe.PowerMovementPhase.SEND,
                    outputFace, offered, false);
                if (offered <= 0) continue;

                int leftover = offered;
                IPipe neighbour = pipe.getHolder().getNeighbourPipe(outputFace);
                if (neighbour != null && neighbour != Pipe.EMPTY && neighbour.getFlow() instanceof PipeFlowForgeEnergy other
                    && neighbour.isConnected(outputFace.getOpposite())) {
                    int remoteOffer = other.apiPowerLimit(buildcraft.api.v2.pipe.PowerMovementPhase.RECEIVE,
                        outputFace.getOpposite(), offered, false);
                    leftover = offered - remoteOffer
                        + other.sections.get(outputFace.getOpposite()).receivePowerInternal(remoteOffer);
                } else {
                    EnergyStorage receiver = StorageAdapters.fromNativeEnergy(pipe.getHolder().getCapabilityFromPipe(outputFace, CapUtil.CAP_FE));
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
            EnergyStorage receiver = StorageAdapters.fromNativeEnergy(pipe.getHolder().getCapabilityFromPipe(face, CapUtil.CAP_FE));
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
        transferJournal.record();
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
        requested = apiPowerLimit(buildcraft.api.v2.pipe.PowerMovementPhase.REQUEST,
            from, Math.max(0, requested), false);
        section.nextPowerQuery = Math.min(maxPower, saturatingAdd(section.nextPowerQuery, requested));
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
            transferJournal.record();
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
            transferJournal.record();
            ensureConfigured();
            if (disabled || sent <= 0) return sent;
            step();
            int buffered = saturatingAdd(Math.max(0, internalPower), Math.max(0, internalNextPower));
            int free = Math.max(0, maxPower - buffered);
            int accepted = Math.min(sent, free);
            internalNextPower += accepted;
            return sent - accepted;
        }

        public int receiveEnergy(int maxReceive, boolean simulate) {
            if (!isReceiver || disabled || maxReceive <= 0) return 0;
            ensureConfigured();
            int requested = Math.max(0, getPowerRequested(side));
            if (requested <= 0) return 0;
            int buffered = saturatingAdd(Math.max(0, internalPower), Math.max(0, internalNextPower));
            int free = Math.max(0, maxPower - buffered);
            int accepted = Math.min(maxReceive, Math.min(free, requested));
            accepted = apiPowerLimit(buildcraft.api.v2.pipe.PowerMovementPhase.RECEIVE,
                side, accepted, simulate);
            if (!simulate && accepted > 0) accepted -= receivePowerInternal(accepted);
            return accepted;
        }

        public int extractEnergy(int maxExtract, boolean simulate) { return 0; }
        public int getEnergyStored() {
            ensureConfigured();
            return Math.min(maxPower, saturatingAdd(Math.max(0, internalPower), Math.max(0, internalNextPower)));
        }
        public int getMaxEnergyStored() { ensureConfigured(); return maxPower; }
        public boolean canExtract() { return false; }
        public boolean canReceive() { return isReceiver && !disabled; }
    }

    public enum EnumFlow {
        IN(-1), OUT(1), STATIONARY(0);
        public final int value;
        EnumFlow(int value) { this.value = value; }
    }
}
