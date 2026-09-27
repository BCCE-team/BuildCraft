/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.pipe.flow;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidMatcher;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.lib.platform.storage.PlatformFluidPipeTransfer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.logic.distribution.EqualFlowMath;
import buildcraft.lib.internal.core.SafeTimeTracker;
import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.transport.internal.pipe.IFlowFluid;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pipe.PipeEventFluid;
import buildcraft.transport.internal.pipe.PipeEventFluid.OnMoveToCentre;
import buildcraft.transport.internal.pipe.PipeEventFluid.PreMoveToCentre;
import buildcraft.transport.internal.pipe.PipeEventHandler;
import buildcraft.transport.internal.pipe.PipeEventStatement;
import buildcraft.transport.internal.pipe.PipeFlow;
import buildcraft.core.BCCoreConfig;
import buildcraft.core.BCCoreItems;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.misc.MathUtil;
import buildcraft.lib.misc.data.AverageInt;
import buildcraft.lib.misc.VecUtil;
import buildcraft.transport.BCTransportStatements;
import buildcraft.transport.pipe.Pipe;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import buildcraft.lib.net.BCNetworkSide;

public class PipeFlowFluids extends PipeFlow implements IFlowFluid, IDebuggable {

    private static final int DIRECTION_COOLDOWN = 60;
    private static final int COOLDOWN_INPUT = -DIRECTION_COOLDOWN;
    private static final int COOLDOWN_OUTPUT = DIRECTION_COOLDOWN;

    public static final int NET_FLUID_AMOUNTS = 2;

    /** The number of pixels the fluid moves by per millisecond */
    public static final double FLOW_MULTIPLIER = 0.016;

    private final PipeApi.FluidTransferInfo fluidTransferInfo = PipeApi.getFluidTransferInfo(pipe.getDefinition());
    private final int transferPerTick = Math.max(0, fluidTransferInfo.transferPerTick);
    private final int transferDelayTicks = Math.max(1, (int) Math.ceil(fluidTransferInfo.transferDelayMultiplier));

    /** Per-section capacity derived from the pipe type's throughput/delay contract. */
    public final int capacity = fluidTransferInfo.bufferCapacity;

    private final Map<EnumPipePart, Section> sections = new EnumMap<>(EnumPipePart.class);
    private FluidVolume currentFluid = FluidVolume.empty();
    private int currentDelay;
    private final SafeTimeTracker tracker = new SafeTimeTracker(BCCoreConfig.networkUpdateRate, 4);
    private final AverageInt throughputAverage = new AverageInt(10);

    // Client fields for interpolating amounts
    private long lastMessage, lastMessageMinus1;
    private FluidVolume clientFluid = FluidVolume.empty();

    public PipeFlowFluids(IPipe pipe) {
        super(pipe);
        for (EnumPipePart part : EnumPipePart.VALUES) {
            sections.put(part, new Section(part));
        }
    }

    public PipeFlowFluids(IPipe pipe, CompoundTag nbt) {
        super(pipe, nbt);
        for (EnumPipePart part : EnumPipePart.VALUES) {
            sections.put(part, new Section(part));
        }

        FluidVolume saved = FluidVolume.empty();
        if (nbt.contains("fluid")) {
            CompoundTag fluidTag = nbt.getCompound("fluid");
            saved = FluidPipeData.read(fluidTag);
            if (saved.isEmpty()) {
                // Backwards compatibility with the pre-Stage-5.5 native Forge FluidStack save shape.
                saved = PlatformFluidPipeTransfer.readLegacyNbt(fluidTag);
            }
        }
        setFluid(saved);

        for (EnumPipePart part : EnumPipePart.VALUES) {
            int direction = part.getIndex();
            if (!nbt.contains("tank[" + direction + "]")) continue;
            CompoundTag compound = nbt.getCompound("tank[" + direction + "]");

            // Very old saves could embed the fluid identity in each section. Preserve that migration path without
            // leaking the loader-native stack representation back into the flow.
            FluidVolume sectionFluid = FluidPipeData.read(compound);
            if (sectionFluid.isEmpty()) sectionFluid = PlatformFluidPipeTransfer.readLegacyNbt(compound);
            if (!sectionFluid.isEmpty()) {
                if (currentFluid.isEmpty()) setFluid(sectionFluid);
                if (!PlatformFluidPipeTransfer.equivalent(sectionFluid, currentFluid)) continue;
            }
            sections.get(part).readFromNbt(compound);
        }
    }

    @Override
    public boolean requiresPeriodicSave() {
        return sections.values().stream().anyMatch(section -> section.amount > 0 || section.incomingTotalCache > 0);
    }

    @Override
    public CompoundTag writeToNbt() {
        CompoundTag nbt = super.writeToNbt();
        if (!currentFluid.isEmpty()) {
            nbt.put("fluid", FluidPipeData.write(currentFluid));
            for (EnumPipePart part : EnumPipePart.VALUES) {
                int direction = part.getIndex();
                CompoundTag subTag = new CompoundTag();
                sections.get(part).writeToNbt(subTag);
                nbt.put("tank[" + direction + "]", subTag);
            }
        }
        return nbt;
    }

    @Override
    public boolean canConnect(Direction face, PipeFlow other) {
        return other instanceof IFlowFluid;
    }

    @Override
    public boolean canConnect(Direction face, BlockEntity oTile) {
        if (oTile == null || oTile.getLevel() == null) return false;
        return canConnect(face, oTile.getLevel(), oTile.getBlockPos(), oTile);
    }

    /** Position-aware lookup so Fabric storages that do not own a block entity remain connectable. */
    public boolean canConnect(Direction face, Level level, BlockPos pos, @Nullable BlockEntity oTile) {
        return level != null && pos != null && level.hasChunkAt(pos)
            && PlatformFluidPipeTransfer.canConnect(level, pos, oTile, face.getOpposite());
    }

    @Override
    public void addDrops(NonNullList<ItemStack> toDrop, int fortune) {
        super.addDrops(toDrop, fortune);
        if (!currentFluid.isEmpty() && BCCoreItems.FRAGILE_FLUID_SHARD.isPresent()) {
            int totalAmount = 0;
            for (EnumPipePart part : EnumPipePart.VALUES) totalAmount += sections.get(part).amount;
            if (totalAmount > 0) {
                PlatformFluidPipeTransfer.addFluidDrops(
                    toDrop,
                    currentFluid.withAmount(FluidAmount.of(totalAmount))
                );
            }
        }
    }

    public boolean doesContainFluid() {
        for (EnumPipePart part : EnumPipePart.VALUES) {
            if (sections.get(part).amount > 0) return true;
        }
        return false;
    }

    public boolean doesContainFluid(@Nullable FluidVolume fluid) {
        return (fluid == null || fluid.isEmpty() || PlatformFluidPipeTransfer.equivalent(fluid, currentFluid))
            && doesContainFluid();
    }

    @PipeEventHandler
    public static void addTriggers(PipeEventStatement.AddTriggerInternal event) {
        event.triggers.add(BCTransportStatements.TRIGGER_FLUIDS_TRAVERSING);
    }

    // IFlowFluid

    @Override
    public FluidVolume tryExtractFluid(
        int millibuckets, Direction from, @Nullable FluidVolume filter, OperationMode mode
    ) {
        FluidVolume preferred = filter == null || filter.isEmpty() ? currentFluid : filter;
        return tryExtractFluidInternal(millibuckets, from, null, preferred, mode);
    }

    @Override
    public FluidVolume tryExtractFluidMatching(
        int millibuckets, Direction from, FluidMatcher matcher, OperationMode mode
    ) {
        if (matcher == null) return FluidVolume.empty();
        return tryExtractFluidInternal(millibuckets, from, matcher, currentFluid, mode);
    }

    private FluidVolume tryExtractFluidInternal(
        int millibuckets,
        Direction from,
        @Nullable FluidMatcher matcher,
        @Nullable FluidVolume preferred,
        OperationMode mode
    ) {
        if (from == null || millibuckets <= 0) return FluidVolume.empty();
        Level level = pipe.getHolder().getPipeWorld();
        if (level == null || level.isClientSide()) return FluidVolume.empty();

        Section section = sections.get(EnumPipePart.fromFacing(from));
        Section middle = sections.get(EnumPipePart.CENTER);
        millibuckets = Math.min(millibuckets, capacity * 2 - section.amount - middle.amount);
        if (millibuckets <= 0) return FluidVolume.empty();

        BlockPos targetPos = pipe.getHolder().getPipePos().relative(from);
        if (!level.hasChunkAt(targetPos)) return FluidVolume.empty();
        BlockEntity tile = level.getBlockEntity(targetPos);
        Direction targetSide = from.getOpposite();

        FluidVolume toAdd = PlatformFluidPipeTransfer.extract(
            level, targetPos, tile, targetSide, matcher, preferred, 1, millibuckets, mode
        );
        if (toAdd.isEmpty()) return FluidVolume.empty();

        int extracted = (int) Math.min(Integer.MAX_VALUE, toAdd.amount().milliBuckets());
        if (currentFluid.isEmpty() && mode == OperationMode.EXECUTE) setFluid(toAdd);
        if (!currentFluid.isEmpty() && !PlatformFluidPipeTransfer.equivalent(currentFluid, toAdd)) {
            return FluidVolume.empty();
        }

        int reallyFilled = section.fillInternal(extracted, mode == OperationMode.EXECUTE);
        int leftOver = extracted - reallyFilled;
        reallyFilled += middle.fillInternal(leftOver, mode == OperationMode.EXECUTE);
        if (mode == OperationMode.EXECUTE && reallyFilled > 0) section.ticksInDirection = COOLDOWN_INPUT;

        if (reallyFilled != extracted) {
            // This should be impossible because the external extraction is capped by the space calculated above.
            // Failing loudly is preferable to silently deleting fluid.
            throw new IllegalStateException(
                "Fluid pipe accepted " + reallyFilled + " mB after extracting " + extracted
                    + " mB at " + pipe.getHolder().getPipePos()
            );
        }
        return toAdd.withAmount(FluidAmount.of(reallyFilled));
    }

    @Override
    public int insertFluidsForce(FluidVolume fluid, @Nullable Direction from, OperationMode mode) {
        Section center = sections.get(EnumPipePart.CENTER);
        if (fluid == null || fluid.isEmpty()) return 0;
        long rawAmount = fluid.amount().milliBuckets();
        if (rawAmount <= 0) return 0;
        int offered = (int) Math.min(Integer.MAX_VALUE, rawAmount);
        FluidVolume bounded = fluid.withAmount(FluidAmount.of(offered));

        if (pipe instanceof Pipe runtimePipe) {
            java.util.Optional<FluidAmount> handled = runtimePipe.applyFluidIngress(from, bounded, mode);
            if (handled.isPresent()) {
                return (int) Math.min(offered, handled.get().milliBuckets());
            }
        }
        if (!currentFluid.isEmpty() && !PlatformFluidPipeTransfer.equivalent(currentFluid, bounded)) return 0;
        if (currentFluid.isEmpty() && mode == OperationMode.EXECUTE) setFluid(bounded);

        int filled = center.fill(offered, mode);
        if (filled <= 0 || mode == OperationMode.SIMULATE) return filled;
        if (from != null) sections.get(EnumPipePart.fromFacing(from)).ticksInDirection = COOLDOWN_INPUT;
        return filled;
    }

    @Override
    public FluidVolume extractFluidsForce(int min, int max, @Nullable Direction section, OperationMode mode) {
        if (min > max) throw new IllegalArgumentException("Minimum (" + min + ") > maximum (" + max + ")");
        if (max < 0 || currentFluid.isEmpty()) return FluidVolume.empty();
        Section selected = sections.get(EnumPipePart.fromFacing(section));
        if (selected.amount < min) return FluidVolume.empty();
        int amount = MathUtil.clamp(selected.amount, min, max);
        FluidVolume fluid = currentFluid.withAmount(FluidAmount.of(amount));
        if (mode == OperationMode.EXECUTE) {
            selected.forceDrain(amount);
            if (allSectionsEmpty()) setFluid(FluidVolume.empty());
        }
        return fluid;
    }

    @Override
    public int insertFluidsExternal(FluidVolume fluid, Direction from, OperationMode mode) {
        if (from == null || fluid == null || fluid.isEmpty()) return 0;
        Section section = sections.get(EnumPipePart.fromFacing(from));
        if (!section.getCurrentDirection().canInput() || !pipe.isConnected(from)) return 0;

        int offered = (int) Math.min(Integer.MAX_VALUE, fluid.amount().milliBuckets());
        if (offered <= 0) return 0;
        FluidVolume bounded = fluid.withAmount(FluidAmount.of(offered));
        PipeEventFluid.TryInsert tryInsert = new PipeEventFluid.TryInsert(pipe.getHolder(), this, from, bounded);
        pipe.getHolder().fireEvent(tryInsert);
        if (tryInsert.isCanceled()) return 0;
        if (!currentFluid.isEmpty() && !PlatformFluidPipeTransfer.equivalent(currentFluid, bounded)) return 0;

        if (currentFluid.isEmpty() && mode == OperationMode.EXECUTE) setFluid(bounded);
        int filled = section.fill(offered, mode);
        if (filled > 0 && mode == OperationMode.EXECUTE) section.ticksInDirection = COOLDOWN_INPUT;
        return filled;
    }

    @Override
    public FluidVolume getFluidInSection(Direction side) {
        Section section = sections.get(EnumPipePart.fromFacing(side));
        if (section == null || section.amount <= 0 || currentFluid.isEmpty()) return FluidVolume.empty();
        return currentFluid.withAmount(FluidAmount.of(section.amount));
    }

    @Override
    public int getFluidSectionCapacity() {
        return capacity;
    }

    @Override
    public boolean isFluidValidForSection(Direction side, FluidVolume fluid) {
        if (side == null || fluid == null || fluid.isEmpty()) return false;
        Section section = sections.get(EnumPipePart.fromFacing(side));
        if (section == null || !section.getCurrentDirection().canInput() || !pipe.isConnected(side)) return false;
        return currentFluid.isEmpty() || PlatformFluidPipeTransfer.equivalent(currentFluid, fluid);
    }

    private boolean allSectionsEmpty() {
        for (Section section : sections.values()) {
            if (section.amount != 0) return false;
        }
        return true;
    }

    // IDebuggable

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        boolean isClientSide = pipe.getHolder().getPipeWorld().isClientSide();

        FluidVolume fluid = isClientSide ? getFluidForRender() : currentFluid;
        left.add(" - FluidType = " + (fluid.isEmpty() ? "empty" : PlatformFluidPipeTransfer.displayName(fluid)));

        for (EnumPipePart part : EnumPipePart.VALUES) {
            Section section = sections.get(part);
            if (section == null) {
                continue;
            }
            StringBuilder line = new StringBuilder(" - " + LocaleUtil.localizeFacing(part.face) + " = ");
            int amount = isClientSide ? section.target : section.amount;
            line.append(amount > 0 ? ChatFormatting.GREEN : "");
            line.append(amount).append("").append(ChatFormatting.RESET).append("mB");
            line.append(" ").append(section.getCurrentDirection()).append(" (").append(section.ticksInDirection).append(
                ")"
            );

            line.append(" [");
            int last = -1;
            int skipped = 0;

            for (int i : section.incoming) {
                if (i != last) {
                    if (skipped > 0) {
                        line.append("...").append(skipped).append("... ");
                        skipped = 0;
                    }
                    last = i;
                    line.append(i).append(", ");
                } else {
                    skipped++;
                }
            }
            if (skipped > 0) {
                line.append("...").append(skipped).append("... ");
                skipped = 0;
            }
            line.append("0]");

            left.add(line.toString());
        }
    }

    // Rendering
    public FluidVolume getFluidForRender() {
        return clientFluid == null ? FluidVolume.empty() : clientFluid;
    }
    public double[] getAmountsForRender(float partialTicks) {
        double[] arr = new double[7];
        for (EnumPipePart part : EnumPipePart.VALUES) {
            Section s = sections.get(part);
            arr[part.getIndex()] = s.clientAmountLast * (1 - partialTicks) + s.clientAmountThis * (partialTicks);
        }
        return arr;
    }
    public Vec3[] getOffsetsForRender(float partialTicks) {
        Vec3[] arr = new Vec3[7];
        for (EnumPipePart part : EnumPipePart.VALUES) {
            Section s = sections.get(part);
            if (s.offsetLast != null & s.offsetThis != null) {
                arr[part.getIndex()] = s.offsetLast.scale(1 - partialTicks).add(s.offsetThis.scale(partialTicks));
            }
        }
        return arr;
    }

    // Internal logic

    private void setFluid(FluidVolume fluid) {
        currentFluid = PlatformFluidPipeTransfer.canonicalize(fluid == null ? FluidVolume.empty() : fluid);
        currentDelay = transferDelayTicks;
        for (Section section : sections.values()) {
            section.incoming = new int[currentDelay];
            section.incomingTotalCache = 0;
            section.currentTime = 0;
            section.ticksInDirection = 0;
        }
    }

    @Override
    public void onTick() {
        Level world = pipe.getHolder().getPipeWorld();
        if (world.isClientSide()) {
            for (EnumPipePart part : EnumPipePart.VALUES) {
                sections.get(part).tickClient();
            }
            return;
        }

        int movedFromCentre = 0;
        int movedToCentre = 0;
        if (!currentFluid.isEmpty()) {
            // int timeSlot = (int) (world.getTotalWorldTime() % currentDelay);
            int totalFluid = 0;
            boolean canOutput = false;

            for (EnumPipePart part : EnumPipePart.VALUES) {
                Section section = sections.get(part);
                section.currentTime = (section.currentTime + 1) % currentDelay;
                section.advanceForMovement();
                totalFluid += section.amount;
                if (section.getCurrentDirection().canOutput()) {
                    canOutput = true;
                }
            }
            if (totalFluid == 0) {
                setFluid(FluidVolume.empty());
            } else {
                // Fluid movement is split into 3 parts
                // - move from pipe (to other tiles)
                // - move from center (to sides)
                // - move into center (from sides)

                if (canOutput) {
                    moveFromPipe();
                }
                movedFromCentre = moveFromCenter();
                movedToCentre = moveToCenter();
            }

            // tick cooldowns
            for (EnumPipePart part : EnumPipePart.VALUES) {
                Section section = sections.get(part);
                if (section.ticksInDirection > 0) {
                    section.ticksInDirection--;
                } else if (section.ticksInDirection < 0) {
                    section.ticksInDirection++;
                }
            }
        }

        int throughputThisTick = Math.min(transferPerTick, Math.max(movedFromCentre, movedToCentre));
        throughputAverage.tick(Math.max(0, throughputThisTick));

        boolean send = false;

        for (EnumPipePart part : EnumPipePart.VALUES) {
            Section section = sections.get(part);
            if (section.amount != section.lastSentAmount) {
                send = true;
                break;
            } else {
                Dir should = Dir.get(section.ticksInDirection);
                if (section.lastSentDirection != should) {
                    send = true;
                    break;
                }
            }
        }

        if (send && tracker.markTimeIfDelay(world)) {
            // send a net update
            sendPayload(NET_FLUID_AMOUNTS);
        }
    }

    private void moveFromPipe() {
        for (EnumPipePart part : EnumPipePart.FACES) {
            Section section = sections.get(part);
            if (section.getCurrentDirection().canOutput()) {
                int maxDrain = section.drainInternal(transferPerTick, false);
                if (maxDrain <= 0) {
                    continue;
                }
                PipeEventFluid.SideCheck sideCheck = new PipeEventFluid.SideCheck(pipe.getHolder(), this, currentFluid);
                sideCheck.disallowAllExcept(part.face);
                pipe.getHolder().fireEvent(sideCheck);
                if (sideCheck.getOrder().size() == 1) {
                    Level level = pipe.getHolder().getPipeWorld();
                    BlockPos targetPos = pipe.getHolder().getPipePos().relative(part.face);
                    if (!level.hasChunkAt(targetPos)) continue;
                    BlockEntity tile = level.getBlockEntity(targetPos);
                    Direction targetSide = part.face.getOpposite();
                    FluidVolume fluidToPush = currentFluid.withAmount(FluidAmount.of(maxDrain));
                    FluidAmount accepted = PlatformFluidPipeTransfer.insert(
                        level, targetPos, tile, targetSide, fluidToPush, OperationMode.EXECUTE
                    );
                    int filled = (int) Math.min(maxDrain, accepted.milliBuckets());
                    if (filled > 0) {
                        int drained = section.drainInternal(filled, true);
                        if (drained != filled) {
                            throw new IllegalStateException(
                                "Fluid endpoint accepted " + filled + " mB but pipe section only drained " + drained
                            );
                        }
                        section.ticksInDirection = COOLDOWN_OUTPUT;
                    }
                }
            }
        }
    }

    private int moveFromCenter() {
        int moved = 0;
        Section center = sections.get(EnumPipePart.CENTER);
        // Split liquids moving to output equally based on flowrate, how much each side can accept and available liquid
        int totalAvailable = center.getMaxDrained();
        if (totalAvailable < 1) {
            return 0;
        }

        int flowRate = transferPerTick;
        Set<Direction> realDirections = EnumSet.noneOf(Direction.class);

        // Move liquid from the center to the output sides
        for (Direction direction : Direction.values()) {
            Section section = sections.get(EnumPipePart.fromFacing(direction));
            if (!section.getCurrentDirection().canOutput()) {
                continue;
            }
            if (section.getMaxFilled() > 0) {
                Level level = pipe.getHolder().getPipeWorld();
                BlockPos targetPos = pipe.getHolder().getPipePos().relative(direction);
                if (!level.hasChunkAt(targetPos)) continue;
                BlockEntity tile = level.getBlockEntity(targetPos);
                if (PlatformFluidPipeTransfer.canConnect(level, targetPos, tile, direction.getOpposite())) {
                    realDirections.add(direction);
                }
            }
        }

        if (realDirections.size() > 0) {
            PipeEventFluid.SideCheck sideCheck = new PipeEventFluid.SideCheck(pipe.getHolder(), this, currentFluid);
            sideCheck.disallowAllExcept(realDirections);
            pipe.getHolder().fireEvent(sideCheck);

            EnumSet<Direction> set = sideCheck.getOrder();

            List<Direction> random;
            if (pipe instanceof Pipe runtimePipe) {
                EnumSet<Direction> inputs = EnumSet.noneOf(Direction.class);
                for (Direction input : Direction.values()) {
                    Section inputSection = sections.get(EnumPipePart.fromFacing(input));
                    if (inputSection.getCurrentDirection().canInput()) inputs.add(input);
                }
                random = runtimePipe.applyFluidRouting(
                    inputs,
                    currentFluid.withAmount(FluidAmount.of(totalAvailable)),
                    set
                );
            } else {
                random = new ArrayList<>(set);
                Collections.shuffle(random);
            }

            if (random.isEmpty()) return 0;
            for (Direction direction : random) {
                Section section = sections.get(EnumPipePart.fromFacing(direction));
                int available = section.fill(flowRate, OperationMode.SIMULATE);
                int amountToPush = EqualFlowMath.share(available, flowRate, random.size(), totalAvailable);

                amountToPush = center.drainInternal(amountToPush, false);
                if (amountToPush > 0) {
                    int filled = section.fill(amountToPush, OperationMode.EXECUTE);
                    if (filled > 0) {
                        center.drainInternal(filled, true);
                        moved += filled;
                        section.ticksInDirection = COOLDOWN_OUTPUT;
                    }
                    // flow[direction.ordinal()] = 1;
                }
            }
        }
        return Math.min(transferPerTick, moved);
    }

    private int moveToCenter() {
        int moved = 0;
        int transferInCount = 0;
        Section center = sections.get(EnumPipePart.CENTER);
        int spaceAvailable = capacity - center.amount;
        if (spaceAvailable <= 0 || center.getMaxFilled() <= 0) {
            return 0;
        }
        int flowRate = transferPerTick;

        List<EnumPipePart> faces = new ArrayList<>();
        Collections.addAll(faces, EnumPipePart.FACES);
        Collections.shuffle(faces);

        int[] inputPerTick = new int[6];
        for (EnumPipePart part : faces) {
            Section section = sections.get(part);
            inputPerTick[part.getIndex()] = 0;
            if (section.getCurrentDirection().canInput()) {
                inputPerTick[part.getIndex()] = section.drainInternal(flowRate, false);
                if (inputPerTick[part.getIndex()] > 0) {
                    transferInCount++;
                }
            }
        }

        int[] totalOffered = Arrays.copyOf(inputPerTick, 6);
        PreMoveToCentre preMove = new PreMoveToCentre(
            pipe.getHolder(), this, currentFluid, Math.min(flowRate, spaceAvailable), totalOffered, inputPerTick
        );
        // Event handlers edit the array in-place
        pipe.getHolder().fireEvent(preMove);

        int[] fluidLeavingSide = new int[6];

        // Work out how much fluid should leave
        int left = Math.min(flowRate, spaceAvailable);
        for (EnumPipePart part : EnumPipePart.FACES) {
            Section section = sections.get(part);
            // Move liquid from input sides to the centre
            int i = part.getIndex();
            if (inputPerTick[i] > 0) {
                int amountToDrain = EqualFlowMath.share(inputPerTick[i], flowRate, transferInCount, spaceAvailable);
                if (amountToDrain > left) {
                    amountToDrain = left;
                }
                int amountToPush = section.drainInternal(amountToDrain, false);
                if (amountToPush > 0) {
                    fluidLeavingSide[i] = amountToPush;
                    left -= amountToPush;
                }
            }
        }

        int[] fluidEnteringCentre = Arrays.copyOf(fluidLeavingSide, 6);
        OnMoveToCentre move = new OnMoveToCentre(
            pipe.getHolder(), this, currentFluid, fluidLeavingSide, fluidEnteringCentre
        );
        pipe.getHolder().fireEvent(move);

        for (EnumPipePart part : EnumPipePart.FACES) {
            Section section = sections.get(part);
            int i = part.getIndex();
            int leaving = fluidLeavingSide[i];
            if (leaving > 0) {
                int actuallyDrained = section.drainInternal(leaving, true);
                if (actuallyDrained != leaving) {
                    throw new IllegalStateException(
                        "Couldn't drain " + leaving + " from " + part + ", only drained " + actuallyDrained
                    );
                }
                if (actuallyDrained > 0) {
                    section.ticksInDirection = COOLDOWN_INPUT;
                }
                int entering = fluidEnteringCentre[i];
                if (entering > 0) {
                    int actuallyFilled = center.fill(entering, OperationMode.EXECUTE);
                    if (actuallyFilled != entering) {
                        throw new IllegalStateException(
                            "Couldn't fill " + entering + " from " + part + ", only filled " + actuallyFilled
                        );
                    }
                    moved += actuallyFilled;
                }
            }
        }
        return Math.min(transferPerTick, moved);
    }

    /** Rolling server-side fluid throughput through the pipe centre, in mB/t. */
    public int getAverageThroughput() {
        return Math.min(transferPerTick, Math.max(0, (int) Math.round(throughputAverage.getAverage())));
    }

    /** Declared fluid throughput ceiling for this pipe, in mB/t. */
    public int getTransferCapacityPerTick() {
        return Math.max(0, transferPerTick);
    }

    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_FLUID_AMOUNTS || id == NET_ID_FULL_STATE) {
                boolean full = id == NET_ID_FULL_STATE;
                if (currentFluid.isEmpty()) {
                    buffer.writeBoolean(false);
                } else {
                    buffer.writeBoolean(true);
                    buffer.writeNbt(FluidPipeData.write(currentFluid));
                }
                for (EnumPipePart part : EnumPipePart.VALUES) {
                    Section section = sections.get(part);
                    if (full) {
                        buffer.writeVarInt(section.amount);
                    } else if (section.amount == section.lastSentAmount) {
                        buffer.writeBoolean(false);
                    } else {
                        buffer.writeBoolean(true);
                        buffer.writeVarInt(section.amount);
                        section.lastSentAmount = section.amount;
                    }
                    Dir should = Dir.get(section.ticksInDirection);
                    buffer.writeEnum(should); // This writes out 2 bits so don't bother with a boolean flag
                    section.lastSentDirection = should;
                }
            }
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) throws IOException {
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_FLUID_AMOUNTS || id == NET_ID_FULL_STATE) {
                boolean full = id == NET_ID_FULL_STATE;
                if (buffer.readBoolean()) {
                    CompoundTag fluidTag = buffer.readNbt();
                    clientFluid = fluidTag == null ? FluidVolume.empty() : FluidPipeData.read(fluidTag);
                } else {
                    clientFluid = FluidVolume.empty();
                }
                for (EnumPipePart part : EnumPipePart.VALUES) {
                    Section section = sections.get(part);
                    if (full || buffer.readBoolean()) {
                        section.target = buffer.readVarInt();
                        if (full) {
                            section.clientAmountLast = section.clientAmountThis = section.target;
                        }
                    }

                    Dir dir = buffer.readEnum(Dir.class);
                    section.ticksInDirection = dir == Dir.NONE ? 0 : dir == Dir.IN ? COOLDOWN_INPUT : COOLDOWN_OUTPUT;
                }
                lastMessageMinus1 = lastMessage;
                lastMessage = pipe.getHolder().getPipeWorld().getGameTime();
            }
        }
    }

    /** Holds data about a single section of this pipe. */
    class Section {
        final EnumPipePart part;

        int amount = 0;

        int lastSentAmount = 0;

        Dir lastSentDirection = Dir.NONE;

        int currentTime = 0;

        /** Map of [time] -> [amount inserted]. Used to implement the delayed fluid travelling. */
        int[] incoming = new int[1];

        int incomingTotalCache = 0;

        /** If 0 then fluids can move from this in either direction. If less than 0 then fluids can only move into this
         * section from other tiles, and outputs to other sections. If greater than 0 then fluids can only move out of
         * this section into other tiles. */
        int ticksInDirection = 0;

        // Client side fields

        /** Used to interpolate between {@link #clientAmountThis} and {@link #clientAmountLast} for rendering. */
        int clientAmountThis, clientAmountLast;

        /** Holds the amount of fluid was last sent to us from the sever */
        int target = 0;

        Vec3 offsetLast, offsetThis;

        Section(EnumPipePart part) {
            this.part = part;
        }

        void writeToNbt(CompoundTag nbt) {
            nbt.putInt("capacity", amount);
            nbt.putInt("lastSentAmount", lastSentAmount);
            nbt.putInt("ticksInDirection", ticksInDirection);
            nbt.putInt("currentTime", currentTime);

            for (int i = 0; i < incoming.length; ++i) {
                nbt.putInt("in[" + i + "]", incoming[i]);
            }
        }

        void readFromNbt(CompoundTag nbt) {
            this.amount = Math.max(0, nbt.getInt("capacity"));
            this.lastSentAmount = Math.max(0, nbt.getInt("lastSentAmount"));
            this.ticksInDirection = nbt.getInt("ticksInDirection");
            this.currentTime = incoming.length == 0 ? 0 : Math.floorMod(nbt.getInt("currentTime"), incoming.length);

            incomingTotalCache = 0;
            for (int i = 0; i < incoming.length; ++i) {
                incomingTotalCache += incoming[i] = Math.max(0, nbt.getInt("in[" + i + "]"));
            }
            trimDelayedFluidToAmount();
        }

        /** @return The maximum amount of fluid that can be inserted into this pipe on this tick. */
        int getMaxFilled() {
            int availableTotal = capacity - amount;
            int availableThisTick = transferPerTick - incoming[currentTime];
            return Math.min(availableTotal, availableThisTick);
        }

        /** @return The maximum amount of fluid that can be extracted out of this pipe this tick. */
        int getMaxDrained() {
            return Math.min(Math.max(0, amount - incomingTotalCache), transferPerTick);
        }

        /** @return The fluid filled */
        int fill(int maxFill, OperationMode mode) {
            int amountToFill = Math.min(getMaxFilled(), maxFill);
            if (amountToFill <= 0) {
                return 0;
            }
            if (mode == OperationMode.EXECUTE) {
                incoming[currentTime] += amountToFill;
                incomingTotalCache += amountToFill;
                amount += amountToFill;
            }
            return amountToFill;
        }

        public int fillInternal(int maxFill, boolean doFill) {
            int amountToFill = Math.min(capacity - amount, maxFill);
            if (amountToFill <= 0) {
                return 0;
            }
            if (doFill) {
                incoming[currentTime] += amountToFill;
                incomingTotalCache += amountToFill;
                amount += amountToFill;
            }
            return amountToFill;
        }

        /** @param maxDrain
         * @param doDrain
         * @return The amount drained */
        int drainInternal(int maxDrain, boolean doDrain) {
            maxDrain = Math.min(maxDrain, getMaxDrained());
            if (maxDrain <= 0) {
                return 0;
            } else {
                if (doDrain) {
                    amount -= maxDrain;
                }
                return maxDrain;
            }
        }

        void forceDrain(int maxDrain) {
            int drained = Math.min(Math.max(0, maxDrain), amount);
            if (drained <= 0) {
                return;
            }
            int matured = Math.max(0, amount - incomingTotalCache);
            int delayedToRemove = Math.max(0, drained - matured);
            amount -= drained;
            removeDelayedFluid(delayedToRemove);
            trimDelayedFluidToAmount();
        }

        private void trimDelayedFluidToAmount() {
            if (incomingTotalCache > amount) {
                removeDelayedFluid(incomingTotalCache - amount);
            }
        }

        private void removeDelayedFluid(int toRemove) {
            int remaining = Math.min(Math.max(0, toRemove), incomingTotalCache);
            // Drain the newest delayed buckets first so already-aged fluid keeps its original latency.
            for (int age = 0; age < incoming.length && remaining > 0; age++) {
                int index = Math.floorMod(currentTime - age, incoming.length);
                int removed = Math.min(incoming[index], remaining);
                incoming[index] -= removed;
                incomingTotalCache -= removed;
                remaining -= removed;
            }
        }

        void advanceForMovement() {
            incomingTotalCache -= incoming[currentTime];
            incoming[currentTime] = 0;
        }

        void setTime(int current) {
            currentTime = current;
        }

        Dir getCurrentDirection() {
            Dir dir = ticksInDirection == 0 ? Dir.NONE : ticksInDirection < 0 ? Dir.IN : Dir.OUT;
            return dir;
        }

        /** @return True if this still contains fluid, false if not. */
        boolean tickClient() {
            clientAmountLast = clientAmountThis;

            if (target != clientAmountThis) {
                int delta = target - clientAmountThis;
                long msgDelta = lastMessage - lastMessageMinus1;
                msgDelta = MathUtil.clamp((int) msgDelta, 1, 60);
                if (Math.abs(delta) < msgDelta) {
                    clientAmountThis += delta;
                } else {
                    clientAmountThis += delta / (int) msgDelta;
                }
            }

            if (offsetThis == null || (clientAmountThis == 0 && clientAmountLast == 0)) {
                offsetThis = Vec3.ZERO;
            }
            offsetLast = offsetThis;

            if (part.face == null) {
                Vec3 dir = Vec3.ZERO;
                // Firstly find all the outgoing faces
                for (EnumPipePart p : EnumPipePart.FACES) {
                    Section s = sections.get(p);
                    if (s.ticksInDirection > 0) {
                        dir = dir.add(p.face.getStepX(), p.face.getStepY(), p.face.getStepZ());
                    }
                }
                // If that failed then find all of the incoming faces
                for (EnumPipePart p : EnumPipePart.FACES) {
                    Section s = sections.get(p);
                    if (s.ticksInDirection < 0) {
                        dir = dir.add(-p.face.getStepX(), -p.face.getStepY(), -p.face.getStepZ());
                    }
                }
                dir = new Vec3(Math.signum(dir.x), Math.signum(dir.y), Math.signum(dir.z));
                offsetThis = offsetThis.add(dir.scale(-FLOW_MULTIPLIER));
            } else {
                double mult = Math.signum(ticksInDirection);
                offsetThis = VecUtil.offset(offsetLast, part.face, -FLOW_MULTIPLIER * (mult));
            }

            double dx = offsetThis.x >= 0.5 ? -1 : offsetThis.x <= -0.5 ? 1 : 0;
            double dy = offsetThis.y >= 0.5 ? -1 : offsetThis.y <= -0.5 ? 1 : 0;
            double dz = offsetThis.z >= 0.5 ? -1 : offsetThis.z <= -0.5 ? 1 : 0;
            if (dx != 0 || dy != 0 || dz != 0) {
                offsetThis = offsetThis.add(dx, dy, dz);
                offsetLast = offsetLast.add(dx, dy, dz);
            }
            return clientAmountThis > 0 | clientAmountLast > 0;
        }

    }

    /** Enum used for the current direction that a fluid is flowing. */
    enum Dir {
        IN(-1),
        NONE(0),
        OUT(1);

        final byte nbtValue;

        private Dir(int nbtValue) {
            this.nbtValue = (byte) nbtValue;
        }

        public boolean isInput() {
            return this == IN;
        }

        public boolean canInput() {
            return this != OUT;
        }

        public boolean isOutput() {
            return this == OUT;
        }

        public boolean canOutput() {
            return this != IN;
        }

        public static Dir get(int dir) {
            if (dir == 0) {
                return Dir.NONE;
            } else if (dir < 0) {
                return IN;
            } else {
                return OUT;
            }
        }
    }
}
