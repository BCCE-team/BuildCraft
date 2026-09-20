/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.factory.tile;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.permission.WorldOperationKind;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
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
import buildcraft.lib.internal.api.v2.MachineRuntimeView;
import buildcraft.lib.fluid.Tank;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.AutomationPermissionUtil;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.FluidUtilBC;
import buildcraft.lib.mj.MjRedstoneBatteryReceiver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

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

    private static final ResourceLocation ADVANCEMENT_DRAIN_ANY
        = new ResourceLocation("buildcraftfactory:draining_the_world");

    private static final ResourceLocation ADVANCEMENT_DRAIN_OIL
        = new ResourceLocation("buildcraftfactory:oil_platform");

    private final Tank tank = new Tank("tank", 16 * FluidType.BUCKET_VOLUME, this);
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

    @Override
    protected IMjReceiver createMjReceiver() {
        return new MjRedstoneBatteryReceiver(battery);
    }

    private void beginQueueBuild() {
        queue.clear();
        paths.clear();
        scanFrontier.clear();
        scanChecked.clear();
        oilSpringPos = null;
        fluidConnection = null;
        scanFluid = Fluids.EMPTY;
        isInfiniteWaterSource = false;
        scanInProgress = true;

        for (targetPos = worldPosition.below(); !level.isOutsideBuildHeight(targetPos); targetPos = targetPos.below()) {
            if (worldPosition.getY() - targetPos.getY() > BCCoreConfig.miningMaxDepth) break;
            Fluid fluid = BlockUtil.getFluidWithFlowing(level, targetPos);
            if (fluid != Fluids.EMPTY) {
                scanFluid = fluid;
                scanFrontier.add(targetPos);
                scanChecked.add(targetPos);
                paths.put(targetPos, new FluidPath(targetPos, null));
                if (BlockUtil.getFluid(level, targetPos) != Fluids.EMPTY) queue.add(targetPos);
                fluidConnection = targetPos;
                break;
            }
            BlockState state = level.getBlockState(targetPos);
            if (!state.isAir() && state.getBlock() != BCFactoryBlocks.TUBE_BLOCK.get()) break;
        }
        if (scanFrontier.isEmpty() || scanFluid == Fluids.EMPTY) {
            finishQueueBuild();
            return;
        }
        scanDirections = scanFluid.getFluidType().isLighterThanAir() ? SEARCH_GASEOUS : SEARCH_NORMAL;
        scanForInfiniteWater = !BCCoreConfig.pumpsConsumeWater && FluidUtilBC.areFluidsEqual(scanFluid, Fluids.WATER);
        scanMaxLengthSquared = BCCoreConfig.pumpMaxDistance * BCCoreConfig.pumpMaxDistance;
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
                if (fluidState.getFluidType() != scanFluid.getFluidType()) {
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
            finishQueueBuild();
        }
    }

    private boolean isInfiniteWaterSourceAt(BlockPos pos) {
        int adjacentSources = 0;
        for (Direction side : INFINITE_WATER_NEIGHBORS) {
            BlockPos neighbourPos = pos.relative(side);
            if (!level.hasChunkAt(neighbourPos)) {
                return false;
            }
            FluidState neighbour = level.getFluidState(neighbourPos);
            if (neighbour.isSource() && FluidUtilBC.areFluidsEqual(neighbour.getType(), Fluids.WATER)) {
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
        Fluid fluidBelow = BlockUtil.getFluidWithoutFlowing(below);
        //? if <1.20 {
        return FluidUtilBC.areFluidsEqual(fluidBelow, Fluids.WATER) || below.getMaterial().isSolid();
        //?} else {
        /*?
        return FluidUtilBC.areFluidsEqual(fluidBelow, Fluids.WATER) || below.isSolid();
        ?*/
        //?}
    }

    private void finishQueueBuild() {
        if (isOil(scanFluid)) {
            List<BlockPos> springPositions = new ArrayList<>();
            int minY = level.getMinBuildHeight();
            int maxSpringY = Math.min(minY + 16, level.getMaxBuildHeight() - 1);
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

    @Override
    protected BlockPos getTargetPos() {
        if (queue.isEmpty() && currentPos == null) {
            return null;
        }
        return targetPos;
    }

    @Override
    public void update() {
        if (!level.isClientSide && !queueBuilt) {
            if (!scanInProgress) beginQueueBuild();
            if (scanInProgress) continueQueueBuild();
            if (!queueBuilt) {
                FluidUtilBC.pushFluidAround(level, worldPosition, tank);
                return;
            }
        }
        super.update();
        if (!level.isClientSide) FluidUtilBC.pushFluidAround(level, worldPosition, tank);
    }

    @Override
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

        long target = Math.max(0L, Math.round(10 * MjAmount.MICRO_MJ_PER_MJ * MachineDefinitionLookup.energyCostMultiplier(BuildCraftContentIds.Machines.PUMP)));
        if (currentPos != null && paths.containsKey(currentPos)) {
            if (!level.hasChunkAt(currentPos)) {
                return;
            }
            progress += battery.extractPower(0, target - progress);
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
                    && FluidUtilBC.areFluidsEqual(drain.getFluid(), Fluids.WATER);

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
                isInfiniteWaterSource = keepSource;
                AdvancementUtil.unlockAdvancement(getOwner().getId(), ADVANCEMENT_DRAIN_ANY);
                if (!keepSource) {
                    if (isOil(actualDrain.getFluid())) {
                        AdvancementUtil.unlockAdvancement(getOwner().getId(), ADVANCEMENT_DRAIN_OIL);
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
            if (BlockUtil.getFluidWithFlowing(level, path.thisPos) == Fluids.EMPTY) {
                return path.thisPos;
            }
        } while ((path = path.parent) != null);
        return null;
    }
    
	@Override
	public void addDrops(NonNullList<ItemStack> toDrop, int fortune) {
		super.addDrops(toDrop, fortune);
	}

    // NBT

    @Override
	public void saveAdditional(CompoundTag nbt) {
        super.saveAdditional(nbt);
        if (oilSpringPos != null) {
        	nbt.putLong("oilSpringPos", oilSpringPos.asLong());
        }
		nbt.put("tank", tank.serializeNBT());
        
	}

	@Override
	public void load(CompoundTag nbt) {
		super.load(nbt);
		oilSpringPos = nbt.contains("oilSpringPos") ? BlockPos.of(nbt.getLong("oilSpringPos")) : null;
        tank.readFromNBT(nbt.getCompound("tank"));
	}

    // Networking

    @Override
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

    @Override
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

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        super.getDebugInfo(left, right, side);
        left.add("fluid = " + tank.getDebugString());
        left.add("queue size = " + queue.size());
        left.add("infinite = " + isInfiniteWaterSource);
    }

    @Override
    protected long getBatteryCapacity() {
        return MachineDefinitionLookup.capacityMicroMj(BuildCraftContentIds.Machines.PUMP, 50 * MjAmount.MICRO_MJ_PER_MJ);
    }

	@Override
	public void neighbourBlockChanged(BlockState state, BlockPos neighbor, boolean harvest) {
		if (harvest) {
            scheduleQueueRebuild();
		}
		super.neighbourBlockChanged(state, neighbor, harvest);
	}
    
    
    @Override
    public net.minecraft.resources.ResourceLocation api2MachineTypeId() {
        return BuildCraftContentIds.Machines.PUMP;
    }

}
