/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.pipe.flow;

import buildcraft.api.v2.OperationMode;
import buildcraft.lib.internal.mj.MjCapabilities;
import buildcraft.lib.logic.distribution.WeightedAllocation;
import buildcraft.lib.logic.energy.EnergyMath;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.List;
import java.util.function.ToLongFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;
import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.core.SafeTimeTracker;
import buildcraft.lib.internal.mj.IMjConnector;
import buildcraft.lib.internal.mj.IMjPassiveProvider;
import buildcraft.lib.internal.mj.IMjReceiver;
import buildcraft.lib.internal.mj.IMjRedstoneReceiver;
import buildcraft.lib.internal.mj.MjToFeAutoConverter;
import buildcraft.lib.platform.storage.EnergyStorage;
import buildcraft.lib.platform.storage.PlatformStorage;
import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.transport.internal.pipe.IFlowPower;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.IPipe.ConnectedType;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pipe.PipeEventPower;
import buildcraft.transport.internal.pipe.PipeFlow;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.core.BCCoreConfig;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.misc.MathUtil;
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
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import buildcraft.lib.net.BCNetworkSide;

public class PipeFlowPower extends PipeFlow implements IFlowPower, IDebuggable {
    private static final long DEFAULT_MAX_POWER = MjAmount.MICRO_MJ_PER_MJ * 10;
    public static final int NET_POWER_AMOUNTS = 2;

    public Vec3 clientDisplayFlowCentre = Vec3.ZERO;
    public Vec3 clientDisplayFlowCentreLast = Vec3.ZERO;
    public long clientLastDisplayTime = 0;

    private long maxPower = -1;
    private long powerLoss = -1;
    private long powerResistance = -1;
    private boolean disabled = false;

    private long currentWorldTime = Long.MIN_VALUE;

    private boolean isReceiver = false;
    private final EnumMap<Direction, Section> sections;

    private final SafeTimeTracker networkTracker = new SafeTimeTracker(BCCoreConfig.networkUpdateRate, 2);
    private final EnumFlow[] lastObservedFlows = new EnumFlow[Direction.values().length];
    private final int[] lastObservedDisplayPower = new int[Direction.values().length];
    private boolean networkUpdatePending;

    public PipeFlowPower(IPipe pipe) {
        super(pipe);
        sections = new EnumMap<>(Direction.class);
        for (Direction face : Direction.values()) {
            sections.put(face, new Section(face));
        }
    }

    public PipeFlowPower(IPipe pipe, CompoundTag nbt) {
        super(pipe, nbt);
        isReceiver = nbt.getBoolean("isReceiver");
        sections = new EnumMap<>(Direction.class);
        for (Direction face : Direction.values()) {
            sections.put(face, new Section(face));
        }
        CompoundTag energyBuffers = nbt.getCompound("energyBuffers");
        for (Direction face : Direction.values()) {
            CompoundTag sectionNbt = energyBuffers.getCompound(Integer.toString(face.ordinal()));
            Section section = sections.get(face);
            section.internalPower = Math.max(0L, sectionNbt.getLong("power"));
            section.internalNextPower = Math.max(0L, sectionNbt.getLong("nextPower"));
        }
    }

    @Override
    public CompoundTag writeToNbt() {
        CompoundTag nbt = super.writeToNbt();
        nbt.putBoolean("isReceiver", isReceiver);
        CompoundTag energyBuffers = new CompoundTag();
        for (Direction face : Direction.values()) {
            Section section = sections.get(face);
            CompoundTag sectionNbt = new CompoundTag();
            sectionNbt.putLong("power", Math.max(0L, section.internalPower));
            sectionNbt.putLong("nextPower", Math.max(0L, section.internalNextPower));
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
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_POWER_AMOUNTS || id == NET_ID_FULL_STATE) {
                for (Direction face : Direction.values()) {
                    Section s = sections.get(face);
                    buffer.writeInt(s.displayPower);
                    buffer.writeEnum(s.displayFlow);
                }
            }
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) throws IOException {
        super.readPayload(id, buffer, side);
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_POWER_AMOUNTS || id == NET_ID_FULL_STATE) {
                for (Direction face : Direction.values()) {
                    Section s = sections.get(face);
                    s.displayPower = buffer.readInt();
                    s.displayFlow = buffer.readEnum(EnumFlow.class);
                }
            }
        }
    }

    @Override
    public boolean canConnect(Direction face, PipeFlow other) {
        return other instanceof PipeFlowPower;
    }

    @Override
    public boolean canConnect(Direction face, BlockEntity oTile) {
        if (isReceiver) {
            LazyOptional<IMjPassiveProvider> provider = oTile.getCapability(MjCapabilities.CAP_PASSIVE_PROVIDER, face.getOpposite());
            if (provider.isPresent()) {
                return true;
            }
        }
        IMjConnector receiver = oTile.getCapability(MjCapabilities.CAP_CONNECTOR, face.getOpposite()).orElse(null);
        if (receiver != null && receiver.canConnect(sections.get(face))) {
            return true;
        }
        EnergyStorage fe = PlatformStorage.energy(oTile, face.getOpposite());
        IMjReceiver converted = MjToFeAutoConverter.createReceiver(fe);
        return converted != null && converted.canConnect(sections.get(face));
    }

    private void ensureConfigured() {
        if (maxPower < 0) {
            reconfigure();
        }
    }

    @Override
    public void reconfigure() {
        PipeEventPower.Configure configure = new PipeEventPower.Configure(pipe.getHolder(), this);
        PipeApi.PowerTransferInfo transferInfo = PipeApi.getPowerTransferInfo(pipe.getDefinition());
        configure.setReceiver(transferInfo.isReceiver);
        configure.setMaxPower(transferInfo.transferPerTick);
        configure.setPowerLoss(transferInfo.lossPerTick);
        configure.setPowerResistance(transferInfo.resistancePerTick);
        pipe.getHolder().fireEvent(configure);
        isReceiver = configure.isReceiver();
        maxPower = configure.getMaxPower();
        disabled = configure.isTransferDisabled();
        if (maxPower <= 0) {
            maxPower = DEFAULT_MAX_POWER;
        }
        powerLoss = MathUtil.clamp(configure.getPowerLoss(), -1, maxPower);
        powerResistance = MathUtil.clamp(configure.getPowerResistance(), -1, MjAmount.MICRO_MJ_PER_MJ);

        if (powerLoss < 0) {
            if (powerResistance < 0) {
                // 1% resistance
                powerResistance = MjAmount.MICRO_MJ_PER_MJ / 100;
            }
            powerLoss = maxPower * powerResistance / MjAmount.MICRO_MJ_PER_MJ;
        } else if (powerResistance < 0) {
            powerResistance = powerLoss * MjAmount.MICRO_MJ_PER_MJ / maxPower;
        }
    }

    @Override
    public long tryExtractPower(long maxExtracted, Direction from) {
        if (maxPower < 0) {
            reconfigure();
        }
        if (!isReceiver || disabled || from == null || maxExtracted <= 0) {
            return 0;
        }

        LazyOptional<IMjPassiveProvider> providerCapability =
            pipe.getHolder().getCapabilityFromPipe(from, MjCapabilities.CAP_PASSIVE_PROVIDER);
        IMjPassiveProvider provider = providerCapability == null ? null : providerCapability.orElse(null);
        if (provider == null) return 0;

        step();
        Section section = sections.get(from);
        long freeCapacity = Math.max(0, maxPower - section.internalNextPower);
        long requested = Math.min(Math.min(Math.min(maxExtracted, maxPower), getPowerRequested(from)), freeCapacity);
        if (requested <= 0) {
            return 0;
        }

        // Existing BuildCraft providers use false for a dry run and true for the actual extraction.
        long simulated = Math.max(0, Math.min(requested, provider.extractPower(0, requested, false)));
        if (simulated <= 0) {
            return 0;
        }
        long extracted = Math.max(0, Math.min(simulated, provider.extractPower(0, simulated, true)));
        if (extracted <= 0) {
            return 0;
        }

        long leftover = section.receivePowerInternal(extracted);
        long accepted = extracted - leftover;
        if (accepted > 0) {
            section.debugPowerInput += accepted;
            section.displayFlow = EnumFlow.IN;
            section.powerAverage.push((int) Math.min(Integer.MAX_VALUE, accepted));
        }
        return accepted;
    }

    @Override
    public boolean onFlowActivate(Player player, BlockHitResult trace, Level level,
        EnumPipePart part) {
        return super.onFlowActivate(player, trace, level, part);
    }

    public Section getSection(Direction side) {
        return sections.get(side);
    }

    /** API2 bridge: accepts MJ into this pipe without exposing legacy MJ capability interfaces. */
    public long receivePowerFromApi(Direction side, long offered, boolean simulate) {
        if (side == null || offered <= 0) return 0;
        ensureConfigured();
        Section section = sections.get(side);
        if (section == null) return 0;
        long remainder = section.receivePower(offered, simulate ? FluidAction.SIMULATE : FluidAction.EXECUTE);
        return Math.max(0, offered - remainder);
    }

    public long getStoredPowerForApi(Direction side) {
        if (side == null) return 0;
        ensureConfigured();
        Section section = sections.get(side);
        return section == null ? 0 : Math.max(0, section.getEffectivePendingPower());
    }

    public long getMaxPowerForApi() {
        ensureConfigured();
        return Math.max(0, maxPower);
    }

    /** Rolling server-side MJ throughput through the pipe, in micro-MJ per tick. */
    public long getAverageThroughput() {
        ensureConfigured();
        if (disabled || maxPower <= 0) return 0L;
        double maxAverage = 0.0D;
        for (Section section : sections.values()) {
            maxAverage = Math.max(maxAverage, section.powerAverage.getAverage());
        }
        return Math.min(maxPower, Math.max(0L, Math.round(maxAverage)));
    }

    /** Effective MJ throughput ceiling after pipe behaviours, in micro-MJ/t. */
    public long getTransferCapacityPerTick() {
        ensureConfigured();
        return disabled ? 0L : Math.max(0L, maxPower);
    }

    public boolean canReceivePowerFromApi() {
        ensureConfigured();
        return isReceiver && !disabled;
    }

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@Nonnull Capability<T> capability, Direction facing) {
        if (facing == null) {
            return LazyOptional.empty();
        } else if (capability == MjCapabilities.CAP_RECEIVER) {
            return isReceiver ? LazyOptional.of(() -> sections.get(facing)).cast() : LazyOptional.empty();
        } else if (capability == MjCapabilities.CAP_CONNECTOR) {
            return LazyOptional.of(() -> sections.get(facing)).cast();
        } else {
            return LazyOptional.empty();
        }
    }

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        left.add("maxPower = " + LocaleUtil.localizeMj(maxPower));
        left.add("isReceiver = " + isReceiver);
        left.add("disabled = " + disabled);
        left.add("powerLoss = " + LocaleUtil.localizeMj(powerLoss));
        left.add("powerResistance = " + (powerResistance * 100.0 / MjAmount.MICRO_MJ_PER_MJ) + "%");
        left.add(
            "internalPower = " + arrayToString(s -> s.internalPower) + " <- " + arrayToString(s -> s.internalNextPower)
        );
        left.add("- powerQuery: " + arrayToString(s -> s.powerQuery) + " <- " + arrayToString(s -> s.nextPowerQuery));
        left.add(
            "- power: IN " + arrayToString(s -> s.debugPowerInput) + ", OUT " + arrayToString(s -> s.debugPowerOutput)
        );
        left.add("- power: OFFERED " + arrayToString(s -> s.debugPowerOffered));
    }

    private String arrayToString(ToLongFunction<Section> getter) {
        long[] arr = new long[6];
        for (Direction face : Direction.values()) {
            arr[face.ordinal()] = getter.applyAsLong(sections.get(face)) / MjAmount.MICRO_MJ_PER_MJ;
        }
        return Arrays.toString(arr);
    }

    @Override
    public void onTick() {
        if (maxPower == -1) {
            reconfigure();
        }
        if (pipe.getHolder().getPipeWorld().isClientSide()) {
            clientDisplayFlowCentreLast = clientDisplayFlowCentre;
            for (Direction face : Direction.values()) {
                Section s = sections.get(face);
                s.clientDisplayFlowLast = s.clientDisplayFlow;
                double diff = s.displayFlow.value * 2.4 * face.getAxisDirection().getStep();
                s.clientDisplayFlow += 16 + diff;
                s.clientDisplayFlow %= 16;

                double cVal = VecUtil.getValue(clientDisplayFlowCentre, face.getAxis());
                cVal += 16 + diff / 2;
                cVal %= 16;
                clientDisplayFlowCentre = VecUtil.replaceValue(clientDisplayFlowCentre, face.getAxis(), cVal);
            }
            return;
        }

        step();


        for (Direction face : Direction.values()) {
            Section s = sections.get(face);
            if (s.internalPower > 0) {
                EnumSet<Direction> routeCandidates = EnumSet.noneOf(Direction.class);
                for (Direction face2 : Direction.values()) {
                    if (face != face2 && sections.get(face2).powerQuery > 0) routeCandidates.add(face2);
                }
                Map<Direction, Long> routeWeights = new EnumMap<>(Direction.class);
                if (pipe instanceof Pipe runtimePipe) {
                    routeWeights.putAll(runtimePipe.applyMjRouting(face, MjAmount.ofMicro(s.internalPower), routeCandidates));
                } else {
                    for (Direction candidate : routeCandidates) routeWeights.put(candidate, 1L);
                }

                WeightedAllocation allocation = new WeightedAllocation();
                for (Direction face2 : routeCandidates) {
                    allocation.add(sections.get(face2).powerQuery, routeWeights.getOrDefault(face2, 0L));
                }

                if (allocation.hasDemand()) {
                    for (Direction face2 : Direction.values()) {
                        if (face == face2) {
                            continue;
                        }
                        Section s2 = sections.get(face2);
                        long routeWeight = routeWeights.getOrDefault(face2, 0L);
                        if (s2.powerQuery > 0 && routeWeight > 0) {
                            long offeredInput = allocation.offer(s.internalPower, s2.powerQuery, routeWeight);

                            long offeredAfterLoss = applyResistance(offeredInput);
                            if (offeredAfterLoss <= 0) {
                                continue;
                            }

                            IPipe neighbour = pipe.getConnectedPipe(face2);
                            long leftover = offeredAfterLoss;
                            if (
                                neighbour != null && neighbour.getFlow() instanceof PipeFlowPower && neighbour
                                    .isConnected(face2.getOpposite())
                            ) {
                                PipeFlowPower oFlow = (PipeFlowPower) neighbour.getFlow();
                                leftover = oFlow.sections.get(face2.getOpposite()).receivePowerInternal(offeredAfterLoss);
                            } else {
                                IMjReceiver receiver = getPowerSink(face2);
                                if (receiver != null && receiver.canReceive()) {
                                    leftover = receiver.receivePower(offeredAfterLoss, FluidAction.EXECUTE);
                                }
                            }

                            leftover = Math.max(0, Math.min(offeredAfterLoss, leftover));
                            long delivered = offeredAfterLoss - leftover;
                            // A fully accepted transfer consumes the entire offered input. Reconstructing it from the
                            // resistance-rounded output can be one microjoule short and leave permanent power dust.
                            long consumed = delivered == offeredAfterLoss
                                ? offeredInput
                                : getInputForDelivered(delivered, offeredInput);
                            if (consumed <= 0) {
                                continue;
                            }
                            s.internalPower -= consumed;
                            s2.debugPowerOutput += delivered;

                            s.powerAverage.push((int) Math.min(Integer.MAX_VALUE, consumed));
                            s2.powerAverage.push((int) Math.min(Integer.MAX_VALUE, delivered));

                            s.displayFlow = EnumFlow.OUT;
                            s2.displayFlow = EnumFlow.IN;
                        }
                    }
                }
            }
        }
        // Render compute goes here
        for (Section s : sections.values()) {
            s.powerAverage.tick();
            double value = s.powerAverage.getAverage() / maxPower;
            value = Math.sqrt(value);
            s.displayPower = (int) (value * MjAmount.MICRO_MJ_PER_MJ);
        }

        // Compute local consumers requesting power. This includes both external tiles and internal pluggables such as
        // robot stations. A robot station blocks the pipe side, so it never appears as ConnectedType.TILE, but it still
        // must contribute a request to the power network to preserve pluggable energy-receiver semantics.
        for (Direction face : Direction.values()) {
            IMjReceiver recv = getPowerSink(face);
            if (recv != null && recv.canReceive()) {
                long requested = recv.getPowerRequested();
                if (requested > 0) {
                    requestPower(face, requested);
                }
            }
        }

        // Sum the amount of power requested on each side
        long[] transferQueryTemp = new long[6];
        for (Direction face : Direction.values()) {
            if (!pipe.isConnected(face)) {
                continue;
            }
            long query = 0;
            for (Direction face2 : Direction.values()) {
                if (face != face2) {
                    query = Math.min(maxPower, saturatingAdd(query, sections.get(face2).powerQuery));
                }
            }
            transferQueryTemp[face.ordinal()] = query;
        }

        // Transfer requested power to neighbouring pipes
        for (Direction face : Direction.values()) {
            if (disabled) {
                continue;
            }
            if (transferQueryTemp[face.ordinal()] <= 0 || !pipe.isConnected(face)) {
                continue;
            }
            IPipe oPipe = pipe.getHolder().getNeighbourPipe(face);
            if (oPipe == Pipe.EMPTY || !(oPipe.getFlow() instanceof PipeFlowPower)) {
                continue;
            }
            PipeFlowPower oFlow = (PipeFlowPower) oPipe.getFlow();
            oFlow.requestPower(face.getOpposite(), transferQueryTemp[face.ordinal()]);
        }
        // Powered wooden/diamond-wood pipes actively pull from passive providers. The extracted power is queued in
        // this side's section and becomes available to the network on the next power step.
        if (isReceiver && !disabled) {
            for (Direction face : Direction.values()) {
                long requested = transferQueryTemp[face.ordinal()];
                if (requested > 0 && pipe.getConnectedType(face) == ConnectedType.TILE) {
                    tryExtractPower(requested, face);
                }
            }
        }

        // Networking
        boolean didChange = false;
        for (Direction face : Direction.values()) {
            Section s = sections.get(face);
            int i = face.ordinal();
            if (lastObservedFlows[i] != s.displayFlow || lastObservedDisplayPower[i] != s.displayPower) {
                didChange = true;
            }
            lastObservedFlows[i] = s.displayFlow;
            lastObservedDisplayPower[i] = s.displayPower;
        }

        if (didChange) {
            networkUpdatePending = true;
        }
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


    private void requestPower(Direction from, long amount) {
        if (disabled || amount <= 0) {
            return;
        }
        step();

        Section s = sections.get(from);
        long requested = pipe.getBehaviour() instanceof IPipeTransportPowerHook
            ? ((IPipeTransportPowerHook) pipe.getBehaviour()).requestPower(from, amount)
            : amount;
        s.nextPowerQuery = Math.min(maxPower, saturatingAdd(s.nextPowerQuery, Math.max(0, requested)));
    }

    @Nullable
    private IMjReceiver getPowerSink(Direction face) {
        PipePluggable plug = pipe.getHolder().getPluggable(face);
        if (plug != null && plug != PipePluggable.EMPTY) {
            LazyOptional<IMjReceiver> pluggableReceiver = plug.getInternalCapability(MjCapabilities.CAP_RECEIVER);
            if (pluggableReceiver.isPresent()) {
                return pluggableReceiver.orElse(null);
            }
            if (plug.isBlocking()) {
                return null;
            }
        }

        if (pipe.getConnectedType(face) != ConnectedType.TILE) {
            return null;
        }

        LazyOptional<IMjReceiver> tileReceiver = pipe.getHolder().getCapabilityFromPipe(face, MjCapabilities.CAP_RECEIVER);
        IMjReceiver receiver = tileReceiver == null ? null : tileReceiver.orElse(null);
        if (receiver != null) {
            return receiver;
        }

        EnergyStorage fe = PlatformStorage.pipeEnergy(pipe.getHolder(), face);
        return MjToFeAutoConverter.createReceiver(fe);
    }

    public long getPowerRequested(@Nullable Direction side) {
        ensureConfigured();
        if (disabled) {
            return 0;
        }
        long local = getLocalPowerRequested(side);
        long api = pipe instanceof Pipe runtimePipe ? runtimePipe.apiMjDemand(side, maxPower) : 0L;
        return Math.min(maxPower, saturatingAdd(local, api));
    }

    private long getLocalPowerRequested(@Nullable Direction side) {
        long req = 0;
        for (Direction face : Direction.values()) {
            if (side == null || face != side) {
                req = saturatingAdd(req, sections.get(face).getEffectivePowerQuery());
            }
        }
        return Math.min(req, maxPower);
    }

    private long applyResistance(long input) {
        return EnergyMath.applyResistance(input, powerResistance, MjAmount.MICRO_MJ_PER_MJ);
    }

    private long getInputForDelivered(long delivered, long maxInput) {
        return EnergyMath.inputForDelivered(delivered, maxInput, powerResistance, MjAmount.MICRO_MJ_PER_MJ);
    }

    private static long saturatingAdd(long a, long b) {
        return EnergyMath.saturatingAdd(a, b);
    }

    public double getMaxTransferForRender(float partialTicks) {
//        if (true) 
            return maxPower / (double) MjAmount.MICRO_MJ_PER_MJ;
/*        double max = 0;
        for (Section s : sections.values()) {
            double value = s.displayPower / (double) MjAmount.MICRO_MJ_PER_MJ;
            // value = MathUtil.interp(partialTicks, value, value);
            max = Math.max(max, value);
        }
        return max;*/
    }

    public class Section implements IMjReceiver, IMjRedstoneReceiver {
        public final Direction side;

        public final AverageInt clientDisplayAverage = new AverageInt(10);
        public double clientDisplayFlow, clientDisplayFlowLast;

        /** Range: 0 to {@link MjAmount#MICRO_MJ_PER_MJ} */
        public int displayPower;
        public EnumFlow displayFlow = EnumFlow.STATIONARY;
        public long nextPowerQuery;
        public long internalNextPower;
        public final AverageInt powerAverage = new AverageInt(10);

        long powerQuery;
        long internalPower;

        /** Debugging fields */
        long debugPowerInput, debugPowerOutput, debugPowerOffered;

        public Section(Direction side) {
            this.side = side;
        }

        void step() {
            powerQuery = Math.min(maxPower, Math.max(0, nextPowerQuery));
            nextPowerQuery = 0;

            long next = Math.min(maxPower, Math.max(0, internalPower));
            internalPower = Math.min(maxPower, Math.max(0, internalNextPower));
            internalNextPower = next;
        }

        long getEffectivePowerQuery() {
            long now = pipe.getHolder().getPipeWorld().getGameTime();
            return currentWorldTime == now ? powerQuery : nextPowerQuery;
        }

        long getEffectivePendingPower() {
            long now = pipe.getHolder().getPipeWorld().getGameTime();
            return currentWorldTime == now ? internalNextPower : internalPower;
        }

        @Override
        public boolean canConnect(@Nonnull IMjConnector other) {
            return true;
        }

        @Override
        public long getPowerRequested() {
            return PipeFlowPower.this.getPowerRequested(side);
        }

        long receivePowerInternal(long sent) {
            ensureConfigured();
            if (disabled || sent <= 0) {
                return sent;
            }
            debugPowerOffered = saturatingAdd(debugPowerOffered, sent);
            long remaining = sent;
            if (pipe instanceof Pipe runtimePipe) {
                remaining -= runtimePipe.apiReceiveMj(side, remaining, OperationMode.EXECUTE);
            }
            long free = Math.max(0, maxPower - internalNextPower);
            long accepted = Math.min(remaining, free);
            internalNextPower += accepted;
            return remaining - accepted;
        }

        @Override
        public long receivePower(long microJoules, FluidAction action) {
            if (!isReceiver || disabled || microJoules <= 0) {
                return microJoules;
            }

            if (action == FluidAction.SIMULATE) {
                long apiAccepted = pipe instanceof Pipe runtimePipe
                    ? runtimePipe.apiReceiveMj(side, microJoules, OperationMode.SIMULATE) : 0L;
                long remaining = microJoules - apiAccepted;
                long requested = Math.min(maxPower, getLocalPowerRequested(side));
                long free = Math.max(0, maxPower - getEffectivePendingPower());
                long localAccepted = Math.min(remaining, Math.min(requested, free));
                return remaining - localAccepted;
            }

            PipeFlowPower.this.step();
            long requested = Math.min(maxPower, getPowerRequested());
            long accepted = Math.min(microJoules, requested);
            if (accepted <= 0) return microJoules;
            return microJoules - accepted + receivePowerInternal(accepted);
        }

        @Override
        public boolean canReceive() {
            return isReceiver && !disabled;
        }
    }

    public enum EnumFlow {
        IN(-1),
        OUT(1),
        STATIONARY(0);

        public final int value;

        private EnumFlow(int value) {
            this.value = value;
        }
    }
}
