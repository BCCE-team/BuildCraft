/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.factory.tile;

import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import buildcraft.lib.compat.GameProfileCompat;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.permission.WorldOperationKind;

import java.io.IOException;
import java.util.ArrayDeque;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.internal.debug.BCDebugging;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.core.SafeTimeTracker;
import buildcraft.lib.internal.mj.IMjReceiver;
import buildcraft.api.v2.content.BuildCraftContentIds;
import buildcraft.core.BCCoreBlocks;
import buildcraft.core.BCCoreConfig;
import buildcraft.energy.BCEnergyFluids;
import buildcraft.energy.tile.ITileOilSpring;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.lib.fluid.FluidCompatRegistry;
import buildcraft.lib.internal.api.v2.MachineDefinitionLookup;
import buildcraft.lib.balance.BalanceConfig;
import buildcraft.lib.internal.api.v2.MachineRuntimeView;
import buildcraft.lib.fluid.Tank;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.AutomationPermissionUtil;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.FluidUtilBC;
import buildcraft.lib.tile.IPumpHost;
import buildcraft.lib.mj.MjRedstoneBatteryReceiver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import buildcraft.lib.fluid.FluidStack;
import buildcraft.lib.compat.neoforge263.fluids.IFluidHandler.FluidAction;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;
import buildcraft.lib.compat.LevelCompat;

public class TilePump extends TileMiner implements MachineRuntimeView {
    public static final boolean DEBUG_PUMP = BCDebugging.shouldDebugComplex("factory.pump");

    private static final Direction[] SEARCH_NORMAL = new Direction[] { //
        Direction.UP, Direction.NORTH, Direction.SOUTH, //
        Direction.WEST, Direction.EAST //
    };

    private static final Direction[] SEARCH_GASEOUS = new Direction[] { //
        Direction.DOWN, Direction.NORTH, Direction.SOUTH, //
        Direction.WEST, Direction.EAST //
    };

    /** Vanilla infinite-water regeneration only considers horizontal source neighbours. */
    private static final Direction[] INFINITE_WATER_NEIGHBORS = new Direction[] {
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    static final class FluidPath {
        public final BlockPos thisPos;

        @Nullable
        public final FluidPath parent;

        public FluidPath(BlockPos thisPos, FluidPath parent) {
            this.thisPos = thisPos;
            this.parent = parent;
        }

        public FluidPath and(BlockPos pos) {
            return new FluidPath(pos, this);
        }
    }

    private static final Identifier ADVANCEMENT_DRAIN_ANY
        = Identifier.parse("buildcraftfactory:draining_the_world");

    private static final Identifier ADVANCEMENT_DRAIN_OIL
        = Identifier.parse("buildcraftfactory:oil_platform");

    private final Tank tank = new Tank("tank", BalanceConfig.machineInt("buildcraftfactory:pump", "tank_capacity_mb", 16000), this);
    private boolean queueBuilt = false;
    private final Map<BlockPos, FluidPath> paths = new HashMap<>();
    private BlockPos fluidConnection;
    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private boolean isInfiniteWaterSource;
    private final SafeTimeTracker rebuildDelay = new SafeTimeTracker(30);
    private static final int QUEUE_SCAN_BUDGET = 512;
    private final Deque<BlockPos> scanFrontier = new ArrayDeque<>();
    private final Set<BlockPos> scanChecked = new HashSet<>();
    private Fluid scanFluid = Fluids.EMPTY;
    private Direction[] scanDirections = SEARCH_NORMAL;
    private boolean scanForInfiniteWater;
    private int scanMaxLengthSquared;
    private boolean scanInProgress;
    /** Avoid reusing the previous world/host fluid graph when the base block changes. */
    private boolean wasHosted;
    private final List<IntakeCandidate> intakeCandidates = new ArrayList<>();
    private int nextIntakeCandidate;

    /** One accessible fluid reached from the unobstructed vertical pump shaft. */
    private static final class IntakeCandidate {
        final BlockPos shaftPos;
        final BlockPos fluidPos;
        final Fluid fluid;
        final boolean source;

        IntakeCandidate(BlockPos shaftPos, BlockPos fluidPos, Fluid fluid, boolean source) {
            this.shaftPos = shaftPos;
            this.fluidPos = fluidPos;
            this.fluid = fluid;
            this.source = source;
        }
    }

    /** The position just below the bottom of the pump tube. */
    private BlockPos targetPos;

    @Nullable
    private BlockPos oilSpringPos;

//	protected TankManager tankManager = new TankManager();

    public TilePump(BlockPos pos, BlockState state) {
    	super(BCFactoryBlocks.ENTITYBLOCKPUMP.get(), pos, state); 
        tank.setCanFill(false);
        tankManager.addLast(tank);
        caps.addFluidStorage(tankManager, EnumPipePart.VALUES);
    }

    protected IMjReceiver createMjReceiver() {
        return new MjRedstoneBatteryReceiver(battery);
    }

    /**
     * The pump's bore can descend to the world's bottom. Unlike a mining well, it must not stop above oil merely
     * because the configurable maximum mining depth is shorter than the remaining world height. TileMiner uses
     * this same limit to retract the tube when the pump is broken.
     */
    protected int getMaxShaftDepth() {
        return Math.max(0, worldPosition.getY() - LevelCompat.getMinBuildHeight(level));
    }

    /**
     * Record the closest usable seed per fluid type. Prefer a source over a flowing block of the same fluid; a
     * flowing seed is retained when there is no visible source because the normal fluid graph may reach one.
     * Ignore fluid inside the pump's own tubes, including waterlogged tubes.
     */
    private void collectIntakeCandidate(Map<Fluid, IntakeCandidate> candidates, BlockPos shaftPos, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return;
        BlockState block = level.getBlockState(pos);
        if (block.getBlock() == BCFactoryBlocks.TUBE_BLOCK.get()) return;
        Fluid fluid = BlockUtil.getFluidWithFlowing(level, pos);
        // A waterlogged solid is not a reachable liquid block; the tube cannot open this space.
        if (fluid == Fluids.EMPTY || !BlockUtil.isReplaceable(level, pos)) return;
        boolean source = level.getFluidState(pos).isSource();
        Fluid type = buildcraft.lib.fluid.FabricFluidProperties.family(fluid);
        IntakeCandidate previous = candidates.get(type);
        if (previous == null || (source && !previous.source)) {
            candidates.put(type, new IntakeCandidate(shaftPos.immutable(), pos.immutable(), fluid, source));
        }
    }

    /** Any non-water liquid has priority; continuing the fluid in the tank is preferred among non-water types. */
    private int intakePriority(IntakeCandidate candidate, Fluid previousFluid) {
        if (isWater(candidate.fluid)) return 2;
        return previousFluid != Fluids.EMPTY && FluidUtilBC.areFluidsEqual(candidate.fluid, previousFluid) ? 0 : 1;
    }

    /** A Pump sitting directly on a Quarry drains the quarry's obstruction, not its own column. */
    @Nullable
    public IPumpHost getHost() {
        if (level == null || !level.hasChunkAt(worldPosition.below())) return null;
        BlockEntity below = level.getBlockEntity(worldPosition.below());
        return below instanceof IPumpHost ? (IPumpHost) below : null;
    }

    private boolean sameOwner(IPumpHost host) {
        GameProfile pumpOwner = getOwner();
        GameProfile hostOwner = host.getOwner();
        if (pumpOwner == null || hostOwner == null) return pumpOwner == hostOwner;
        var pumpId = GameProfileCompat.id(pumpOwner);
        return pumpId != null && pumpId.equals(GameProfileCompat.id(hostOwner));
    }

    /** Use the Pump's battery first; only borrow idle Quarry power with matching owners. */
    private long drawPower(long amount) {
        long supplied = battery.extractPower(0, amount);
        IPumpHost host = getHost();
        if (supplied < amount && host != null && !host.hasWork() && sameOwner(host)) {
            host.onPumpWantsPower();
            supplied += host.getBattery().extractPower(0, amount - supplied);
        }
        return supplied;
    }

    /**
     * Examine the entire passable column before choosing a liquid. Water in a cleared shaft is traversable: it
     * never prevents oil or any other non-water liquid further down from being considered. Fluid beside each shaft
     * cell is also reachable through the tube opening. Solid obstacles stop the bore; unloaded chunks are not read.
     */
    private void beginQueueBuild() {
        Fluid previousFluid = tank.isEmpty() ? scanFluid : tank.getFluidType();
        queue.clear();
        paths.clear();
        scanFrontier.clear();
        scanChecked.clear();
        intakeCandidates.clear();
        nextIntakeCandidate = 0;
        oilSpringPos = null;
        fluidConnection = null;
        scanFluid = Fluids.EMPTY;
        isInfiniteWaterSource = false;
        scanInProgress = true;
        targetPos = worldPosition.below();

        Map<Fluid, IntakeCandidate> candidates = new LinkedHashMap<>();
        IPumpHost host = getHost();
        if (host != null) {
            // The Quarry itself obstructs the bore. Its cached thick-fluid target
            // replaces the vertical intake probe without loading extra chunks.
            BlockPos blocking = host.getBlockingFluid();
            if (blocking != null && level.hasChunkAt(blocking)) {
                BlockState state = level.getBlockState(blocking);
                Fluid fluid = BlockUtil.getFluidWithFlowing(level, blocking);
                if (fluid != Fluids.EMPTY && buildcraft.lib.fluid.FabricFluidProperties.viscosity(fluid) > 1000
                    && BlockUtil.isReplaceable(level, blocking)) {
                    collectIntakeCandidate(candidates, blocking, blocking);
                }
            }
        } else {
        for (BlockPos shaft = worldPosition.below(); !level.isOutsideBuildHeight(shaft); shaft = shaft.below()) {
            if (worldPosition.getY() - shaft.getY() > getMaxShaftDepth() || !level.hasChunkAt(shaft)) break;
            BlockState block = level.getBlockState(shaft);
            boolean tube = block.getBlock() == BCFactoryBlocks.TUBE_BLOCK.get();
            Fluid fluid = tube ? Fluids.EMPTY : BlockUtil.getFluidWithFlowing(level, shaft);
            // A wall cannot be passed by either the search or the physical tube, even if fluid exists beyond it.
            if (!tube && !block.isAir() && !BlockUtil.isReplaceable(level, shaft)) break;
            if (fluid != Fluids.EMPTY) collectIntakeCandidate(candidates, shaft, shaft);
            for (Direction side : INFINITE_WATER_NEIGHBORS) {
                collectIntakeCandidate(candidates, shaft, shaft.relative(side));
            }
        }
        }

        intakeCandidates.addAll(candidates.values());
        intakeCandidates.sort(Comparator.comparingInt((IntakeCandidate c) -> intakePriority(c, previousFluid))
            .thenComparingInt(c -> c.source ? 0 : 1)
            .thenComparingInt(c -> -c.shaftPos.getY()));
        if (!startNextIntakeScan()) finishQueueBuild();
    }

    /** Start one of the selected fluid graphs without restarting the full vertical probe. */
    private boolean startNextIntakeScan() {
        while (nextIntakeCandidate < intakeCandidates.size()) {
            IntakeCandidate candidate = intakeCandidates.get(nextIntakeCandidate++);
            if (!level.hasChunkAt(candidate.fluidPos)) continue;
            Fluid fluid = BlockUtil.getFluidWithFlowing(level, candidate.fluidPos);
            if (fluid == Fluids.EMPTY || !fluid.isSame(candidate.fluid)) continue;

            queue.clear();
            paths.clear();
            scanFrontier.clear();
            scanChecked.clear();
            isInfiniteWaterSource = false;
            scanFluid = fluid;
            targetPos = candidate.shaftPos;
            fluidConnection = candidate.fluidPos;
            scanFrontier.add(candidate.fluidPos);
            scanChecked.add(candidate.fluidPos);
            paths.put(candidate.fluidPos, new FluidPath(candidate.fluidPos, null));
            if (BlockUtil.getFluid(level, candidate.fluidPos) != Fluids.EMPTY) queue.add(candidate.fluidPos);
            scanDirections = buildcraft.lib.fluid.FabricFluidProperties.isLighterThanAir(fluid) ? SEARCH_GASEOUS : SEARCH_NORMAL;
            scanForInfiniteWater = !BCCoreConfig.pumpsConsumeWater && isWater(scanFluid);
            scanMaxLengthSquared = BCCoreConfig.pumpMaxDistance * BCCoreConfig.pumpMaxDistance;
            return true;
        }
        scanFluid = Fluids.EMPTY;
        return false;
    }

    /** Processes a bounded part of the fluid graph so an ocean cannot monopolise one server tick. */
    private void continueQueueBuild() {
        int budget = QUEUE_SCAN_BUDGET;
        while (budget-- > 0 && !scanFrontier.isEmpty() && !isInfiniteWaterSource) {
            BlockPos posToCheck = scanFrontier.removeFirst();
            if (scanForInfiniteWater && isInfiniteWaterSourceAt(posToCheck)) {
                isInfiniteWaterSource = true;
                break;
            }
            for (Direction side : scanDirections) {
                BlockPos offsetPos = posToCheck.relative(side);
                if (offsetPos.distSqr(targetPos) > scanMaxLengthSquared || !level.hasChunkAt(offsetPos)
                    || !scanChecked.add(offsetPos)) {
                    continue;
                }
                FluidState fluidState = level.getFluidState(offsetPos);
                if (!fluidState.getType().isSame(scanFluid)) {
                    continue;
                }
                paths.put(offsetPos, new FluidPath(offsetPos, paths.get(posToCheck)));
                if (fluidState.isSource()) {
                    queue.add(offsetPos);
                }
                scanFrontier.addLast(offsetPos);
            }
        }
        if (scanFrontier.isEmpty() || isInfiniteWaterSource) {
            // A flowing non-water patch without any reachable source is not worth stalling on. Try the next non-water
            // candidate, then water. One candidate is scanned per update, preserving the 512-node tick budget.
            if (!isInfiniteWaterSource && queue.isEmpty() && startNextIntakeScan()) return;
            finishQueueBuild();
        }
    }

    private boolean isInfiniteWaterSourceAt(BlockPos pos) {
        int adjacentSources = 0;
        for (Direction side : INFINITE_WATER_NEIGHBORS) {
            BlockPos neighbourPos = pos.relative(side);
            if (!level.hasChunkAt(neighbourPos)) {
                continue;
            }
            FluidState neighbour = level.getFluidState(neighbourPos);
            if (isWaterSource(neighbour)) {
                adjacentSources++;
                if (adjacentSources >= 2) {
                    break;
                }
            }
        }
        if (adjacentSources < 2) {
            return false;
        }
        BlockPos belowPos = pos.below();
        if (!level.hasChunkAt(belowPos)) {
            return false;
        }
        BlockState below = level.getBlockState(belowPos);
        return isWater(level.getFluidState(belowPos)) || below.isSolid();
    }

    private static boolean isWater(Fluid fluid) {
        return fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER
            || fluid.defaultFluidState().is(FluidTags.WATER);
    }

    private static boolean isWater(FluidState state) {
        Fluid fluid = state.getType();
        return fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER || state.is(FluidTags.WATER);
    }

    private static boolean isWaterSource(FluidState state) {
        Fluid fluid = state.getType();
        return fluid == Fluids.WATER || (state.isSource() && isWater(state));
    }

    private void finishQueueBuild() {
        intakeCandidates.clear();
        nextIntakeCandidate = 0;
        if (isOil(scanFluid)) {
            List<BlockPos> springPositions = new ArrayList<>();
            int minY = LevelCompat.getMinBuildHeight(level);
            int maxSpringY = Math.min(minY + 16, LevelCompat.getMaxBuildHeight(level) - 1);
            BlockPos center = new BlockPos(getBlockPos().getX(), minY, getBlockPos().getZ());
            for (BlockPos spring : BlockPos.betweenClosed(center.offset(-10, 0, -10),
                    center.offset(10, maxSpringY - minY, 10))) {
                if (!level.hasChunkAt(spring)) {
                    continue;
                }
                if (level.getBlockState(spring).getBlock() == BCCoreBlocks.SPRING.get()
                        && level.getBlockEntity(spring) instanceof ITileOilSpring) {
                    springPositions.add(spring.immutable());
                }
            }
            springPositions.stream().min(Comparator.comparingDouble(worldPosition::distSqr))
                .ifPresent(pos -> oilSpringPos = pos);
        }
        scanInProgress = false;
        queueBuilt = true;
        nextPos();
    }

    private void scheduleQueueRebuild() {
        queueBuilt = false;
        scanInProgress = false;
        intakeCandidates.clear();
        nextIntakeCandidate = 0;
        scanFrontier.clear();
        scanChecked.clear();
    }

    private static boolean isOil(Fluid queueFluid) {
        if (BCModules.ENERGY.isLoaded()) {
            return FluidUtilBC.areFluidsEqual(queueFluid, BCEnergyFluids.crudeOil[0]);//
        }
        return false;
    }

    private boolean canDrain(BlockPos blockPos) {
        if (!level.hasChunkAt(blockPos)) {
            return false;
        }
        Fluid fluid = BlockUtil.getFluid(level, blockPos);
        //USE TO DEBUG
        boolean flag = tank.isEmpty() ? fluid != Fluids.EMPTY : fluid.isSource(fluid.defaultFluidState())&&FluidUtilBC.areFluidsEqual(fluid, tank.getFluidType());
        if (flag) {
            flag = AutomationPermissionUtil.mayBlock(
                level, worldPosition, blockPos, getOwner(), AutomationPermissionUtil.SOURCE_PUMP,
                WorldOperationKind.FLUID_DRAIN, OperationMode.SIMULATE
            );
        }
        BCLog.d(flag, blockPos + " cannot drain, "+ fluid);
        return flag;    }

    private void nextPos() {
        while (!queue.isEmpty()) {
            currentPos = queue.removeLast();
            if (canDrain(currentPos)) {
                updateLength();
                return;
            }
        }
        
        currentPos = null;
        updateLength();
    }

    protected BlockPos getTargetPos() {
        // The Quarry owns its excavation rig; never place a Pump tube through it.
        if (getHost() != null) return null;
        if (queue.isEmpty() && currentPos == null) {
            return null;
        }
        return targetPos;
    }

    /** Drain excess capacity safely before shrinking a tank after an INI change. */
    private void reconcileTankCapacity() {
        int configured = BalanceConfig.machineInt("buildcraftfactory:pump", "tank_capacity_mb", 16000);
        if (tank.getCapacity() > configured && tank.getFluidAmount() <= configured) {
            tank.setCapacity(configured);
        }
    }

    public void update() {
        if (!level.isClientSide()) {
            boolean hosted = getHost() != null;
            if (hosted != wasHosted) {
                wasHosted = hosted;
                currentPos = null;
                progress = 0;
                queue.clear();
                scheduleQueueRebuild();
                updateLength();
            }
            reconcileTankCapacity();
        }
        if (!level.isClientSide() && !queueBuilt) {
            if (!scanInProgress) beginQueueBuild();
            if (scanInProgress) continueQueueBuild();
            if (!queueBuilt) {
                FluidUtilBC.pushFluidAround(level, worldPosition, tank);
                return;
            }
        }
        super.update();
        if (!level.isClientSide()) FluidUtilBC.pushFluidAround(level, worldPosition, tank);
    }

    public void mine() {
        if (shaftBlocked) {
            updateLength();
            if (shaftBlocked) {
                return;
            }
        }
        if (tank.getFluidAmount() > tank.getCapacity() / 2) {
            return;
        }
//        BCLog.logger.debug(""+currentPos);

        long target = Math.max(0L, Math.round(BalanceConfig.machineInt("buildcraftfactory:pump", "energy_per_bucket_mj", 10) * MjAmount.MICRO_MJ_PER_MJ * MachineDefinitionLookup.energyCostMultiplier(BuildCraftContentIds.Machines.PUMP)));
        if (currentPos != null && paths.containsKey(currentPos)) {
            if (!level.hasChunkAt(currentPos)) {
                return;
            }
            progress += drawPower(target - progress);
            if (progress < target) {
                return;
            }

            FluidStack drain = BlockUtil.drainBlock(level, currentPos, false);

            drain_attempt: {

                if (drain == FluidStack.EMPTY) {
                    if (DEBUG_PUMP) {
                        BCLog.logger.info(
                            "Pump @ " + getBlockPos() + " tried to drain " + currentPos
                                + " but couldn't because no fluid was drained!"
                        );
                    }
                    break drain_attempt;
                }

                BlockPos invalid = getFirstInvalidPointOnPath(currentPos);
                if (invalid != null) {
                    if (DEBUG_PUMP) {
                        BCLog.logger.info(
                            "Pump @ " + getBlockPos() + " tried to drain " + currentPos
                                + " but couldn't because the path stopped at " + invalid + "!"
                        );
                    }
                    break drain_attempt;
                } else if (!canDrain(currentPos)) {
                    if (DEBUG_PUMP) {
                        BCLog.logger.info(
                            "Pump @ " + getBlockPos() + " tried to drain " + currentPos
                                + " but couldn't because it couldn't be drained!"
                        );
                    }
                    break drain_attempt;
                }
                int canAccept = tank.fillInternal(drain, FluidAction.SIMULATE);
                if (canAccept != drain.getAmount()) {
                    break drain_attempt;
                }

                boolean keepSource = isInfiniteWaterSource
                    && !BCCoreConfig.pumpsConsumeWater
                    && isWater(drain.getFluid())
                    && isWaterSource(level.getFluidState(currentPos))
                    && isInfiniteWaterSourceAt(currentPos);

                if (!AutomationPermissionUtil.mayBlock(
                    level, worldPosition, currentPos, getOwner(), AutomationPermissionUtil.SOURCE_PUMP,
                    WorldOperationKind.FLUID_DRAIN, OperationMode.EXECUTE
                )) {
                    break drain_attempt;
                }

                FluidStack actualDrain = drain;
                if (!keepSource) {
                    actualDrain = BlockUtil.drainBlock(level, currentPos, true);
                    if (actualDrain.isEmpty()
                        || actualDrain.getAmount() <= 0
                        || !FluidCompatRegistry.areEquivalent(drain, actualDrain)) {
                        if (DEBUG_PUMP) {
                            BCLog.logger.info(
                                "Pump @ " + getBlockPos() + " simulated " + drain + " at " + currentPos
                                    + " but the executed drain returned " + actualDrain
                            );
                        }
                        break drain_attempt;
                    }
                }

                int accepted = tank.fillInternal(actualDrain, FluidAction.EXECUTE);
                if (accepted != actualDrain.getAmount()) {
                    BCLog.logger.error(
                        "Pump @ {} drained {} mB at {} but its internal tank accepted only {} mB",
                        getBlockPos(), actualDrain.getAmount(), currentPos, accepted
                    );
                    break drain_attempt;
                }

                progress = 0;
                // The mine that encountered this fluid needs to rescan the column
                // now that real world fluid has been removed.
                if (!keepSource) {
                    IPumpHost host = getHost();
                    if (host != null) host.onBlockingFluidDrained();
                }
                // The discovered renewable pool and this block's renewable status are independent.
                AdvancementUtil.unlockAdvancement(GameProfileCompat.id(getOwner()), ADVANCEMENT_DRAIN_ANY);
                if (!keepSource) {
                    if (isOil(actualDrain.getFluid())) {
                        AdvancementUtil.unlockAdvancement(GameProfileCompat.id(getOwner()), ADVANCEMENT_DRAIN_OIL);
                        if (oilSpringPos != null) {
                            BlockEntity tile = level.getBlockEntity(oilSpringPos);
                            if (tile instanceof ITileOilSpring) {
                                ((ITileOilSpring) tile).onPumpOil(getOwner(), currentPos);
                            }
                        }
                    }
                    paths.remove(currentPos);
                    nextPos();
                }
                return;
            }
            if (!rebuildDelay.markTimeIfDelay(level)) {
                return;
            }
        } else {
            if (currentPos == null && !rebuildDelay.markTimeIfDelay(level)) {
                return;
            }
            if (DEBUG_PUMP) {
                if (currentPos == null) {
                    BCLog.logger.info("Pump @ " + getBlockPos() + " is rebuilding it's queue...");
                } else {
                    BCLog.logger.info(
                        "Pump @ " + getBlockPos() + " is rebuilding it's queue because we don't have a path for "
                            + currentPos
                    );
                }
            }
        }
        scheduleQueueRebuild();
    }
    
    /** Client render snapshot of the pump buffer. */
    public FluidStack getFluidStackForRender() {
        return tank.getFluid().copy();
    }

    public int getFluidCapacityForRender() {
        return tank.getCapacity();
    }

    public Fluid getFluidInTank() {
    	return tank.getFluidType();
    }

    @Nullable
    private BlockPos getFirstInvalidPointOnPath(BlockPos from) {
        FluidPath path = paths.get(from);
        if (path == null) {
            return from;
        }
        do {
            if (!level.hasChunkAt(path.thisPos)) {
                return path.thisPos;
            }
            Fluid current = BlockUtil.getFluidWithFlowing(level, path.thisPos);
            if (current == Fluids.EMPTY
                || (scanFluid != Fluids.EMPTY && !current.isSame(scanFluid))) {
                return path.thisPos;
            }
        } while ((path = path.parent) != null);
        return null;
    }
    
	public void addDrops(NonNullList<ItemStack> toDrop, int fortune) {
		super.addDrops(toDrop, fortune);
	}

    // NBT

	protected void writeData(BCValueOutput bcData) {
        CompoundTag nbt = bcData.tag();
        super.writeData(bcData);
        if (oilSpringPos != null) {
            bcData.writeLong("oilSpringPos", oilSpringPos.asLong());
        }
		nbt.put("tank", tank.serializeNBT());
        bcData.writeInt("tankCapacity", tank.getCapacity());
        
	}

	protected void readData(BCValueInput bcData) {
		super.readData(bcData);
		oilSpringPos = bcData.has("oilSpringPos") ? BlockPos.of(bcData.readLong("oilSpringPos")) : null;
        // Old worlds had a 16,000 mB tank and no capacity tag. Do not truncate saved fluid.
        int savedCapacity = bcData.has("tankCapacity") ? bcData.readInt("tankCapacity") : 16000;
        if (savedCapacity > tank.getCapacity()) tank.setCapacity(savedCapacity);
        tank.readFromNBT(bcData.readCompound("tank"));
	}

    // Networking

    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_RENDER_DATA) {
                writePayload(NET_LED_STATUS, buffer, side);
            } else if (id == NET_LED_STATUS) {
                tank.writeToBuffer(buffer);
            }
        }
    }

    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_RENDER_DATA) {
                readPayload(NET_LED_STATUS, buffer, side, ctx);
            } else if (id == NET_LED_STATUS) {
                tank.readFromBuffer(buffer);
            }
        }
    }

    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        super.getDebugInfo(left, right, side);
        left.add("fluid = " + tank.getDebugString());
        left.add("queue size = " + queue.size());
        left.add("infinite = " + isInfiniteWaterSource);
    }

    protected long getBatteryCapacity() {
        return MachineDefinitionLookup.capacityMicroMj(BuildCraftContentIds.Machines.PUMP, 50 * MjAmount.MICRO_MJ_PER_MJ);
    }

	public void neighbourBlockChanged(BlockState state, BlockPos neighbor, boolean harvest) {
		if (harvest) {
            scheduleQueueRebuild();
		}
		super.neighbourBlockChanged(state, neighbor, harvest);
	}
    
    
    public net.minecraft.resources.Identifier api2MachineTypeId() {
        return BuildCraftContentIds.Machines.PUMP;
    }

}
