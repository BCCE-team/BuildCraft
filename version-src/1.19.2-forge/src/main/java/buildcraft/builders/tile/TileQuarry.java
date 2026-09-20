/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.tile;

import buildcraft.api.v2.energy.MjAmount;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import buildcraft.lib.internal.debug.BCDebugging;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.area.IAreaProvider;
import buildcraft.lib.internal.mj.MjBattery;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.api.v2.content.BuildCraftContentIds;
import buildcraft.lib.internal.api.v2.MachineDefinitionLookup;
import buildcraft.lib.internal.api.v2.MachineRuntimeView;
import buildcraft.lib.internal.mj.MjCapabilityHelper;
import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.lib.internal.tiles.IHasWork;
import buildcraft.lib.internal.tiles.TilesAPI;
import buildcraft.builders.BCBuildersBlocks;
import buildcraft.builders.BCBuildersConfig;
import buildcraft.builders.block.BlockQuarryDrillCollision;
import buildcraft.core.BCCoreConfig;
import buildcraft.core.blockEntity.TileMarkerVolume;
import buildcraft.core.marker.VolumeCache;
import buildcraft.core.marker.VolumeConnection;
import buildcraft.core.marker.VolumeSubCache;
import buildcraft.lib.block.BlockBCBase_Neptune;
import buildcraft.lib.chunkload.ChunkLoaderManager;
import buildcraft.lib.chunkload.IChunkLoadingTile;
import buildcraft.lib.inventory.AutomaticProvidingTransactor;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.AutomationPermissionUtil;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.BoundingBoxUtil;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.InventoryUtil;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.misc.MathUtil;
import buildcraft.lib.misc.MessageUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.VecUtil;
import buildcraft.lib.misc.data.AxisOrder;
import buildcraft.lib.misc.data.Box;
import buildcraft.lib.misc.data.BoxIterator;
import buildcraft.lib.misc.data.EnumAxisOrder;
import buildcraft.lib.internal.mj.MjBatteryReceiver;
import buildcraft.lib.tile.TileBC_Neptune;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEvent.Message;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;



public class TileQuarry extends TileBC_Neptune implements IDebuggable, IChunkLoadingTile, IHasWork, MachineRuntimeView {
    public static final boolean DEBUG_QUARRY = BCDebugging.shouldDebugLog("builders.quarry");
    private static final long DEFAULT_MAX_POWER_PER_TICK = 512 * MjAmount.MICRO_MJ_PER_MJ;
    private static final ResourceLocation ADVANCEMENT_COMPLETE
        = new ResourceLocation("buildcraftbuilders:diggy_diggy_hole");
    private static final ResourceLocation ADVANCEMENT_DESTROYING_THE_WORLD
        = new ResourceLocation("buildcraftbuilders:destroying_the_world");
    private static final Map<ServerLevel, Map<UUID, Map<BlockPos, Long>>> FULL_SPEED_QUARRIES
        = new WeakHashMap<>();

    private final MjBattery battery;
    private final long maxPowerPerTick;
    private final boolean apiChunkLoading;
    private final double apiWorkSpeedMultiplier;
    private final double apiEnergyCostMultiplier;
    public final Box frameBox = new Box();
    private final Box miningBox = new Box();
    private BoxIterator boxIterator;
    public final List<BlockPos> framePoses = new ArrayList<>();
    private int frameBoxPosesCount = 0;
    private BoxIterator initialFrameScan;
    private int initialFrameScannedCount;
    private boolean firstChecked = false;
    private final Set<BlockPos> frameBreakBlockPoses = new TreeSet<>(
        BlockUtil.uniqueBlockPosComparator(Comparator.comparingDouble(p -> getBlockPos().distSqr(p)))
    );
    private final Set<BlockPos> framePlaceFramePoses = new HashSet<>();
    public Task currentTask = null;
    /** Battery energy withdrawn for cancelled quarry tasks that is still owed back to the machine. */
    private long pendingTaskPowerRefund;
    public Vec3 drillPos;
    public Vec3 clientDrillPos;
    public Vec3 prevClientDrillPos;
    private long debugPowerRate = 0;
    private boolean chunkLoadingDirty = true;
    private int chunkLoadingStartupDelay;
    private double blockPercentSoFar;
    private double moveDistanceSoFar;
    /** Rotating index for fast frame-edge rescans, so broken frames are repaired quickly. */
    private int frameEdgeScanIndex;
    /** Rotating X/Z column scan used to recover blocks placed into already-excavated quarry space. */
    private int miningColumnScanIndex;
    private long nextFrameWatchdogTick;
    private static final int MINING_COLUMN_WATCHDOG_SCANS_PER_TICK = 256;
    private final Map<BlockPos, Long> deniedBreakUntil = new HashMap<>();

    private List<AABB> collisionBoxes = ImmutableList.of();
    private Vec3 collisionDrillPos;
    private Vec3 lastCollisionBlocksDrillPos;
    private final Set<BlockPos> collisionBlockPoses = new HashSet<>();

	private final BlockPositionSource blockPosSource = new BlockPositionSource(this.worldPosition);
    public final GameEventListener worldEventListener = new GameEventListener() {
    	@Override
		public boolean handleEventsImmediately() {
			return true;
		}
		@Override
    	public PositionSource getListenerSource() {
    		return blockPosSource;
    	}
    	@Override
    	public int getListenerRadius() {
    		int radius = 64;
    		if (frameBox.isInitialized()) {
    			radius = Math.max(radius, maxDistanceToBox(frameBox) + 4);
    		}
    		if (miningBox.isInitialized()) {
    			radius = Math.max(radius, maxDistanceToBox(miningBox) + 4);
    		}
    		return radius;
    	}
    	@Override
    	public boolean handleGameEvent(ServerLevel w, Message msg) {
    		GameEvent e = msg.gameEvent();
    		if(e == GameEvent.BLOCK_PLACE || e == GameEvent.BLOCK_DESTROY) {
//        	    w.profiler.startSection("bc_quarry_listener");
	    		BlockPos updatePos = new BlockPos(msg.source());
	            if (frameBox.isInitialized() && miningBox.isInitialized()) {
	                if (frameBox.contains(updatePos)) {
	                    check(updatePos);
	                } else if (miningBox.contains(updatePos) && boxIterator != null) {
	                    if (boxIterator.hasVisited(updatePos)) {
	                        if (!canMoveThrough(updatePos) && canMoveDownTo(updatePos)) {
	                            boxIterator.moveTo(updatePos);
	                        }
	                    }
	                }
	                return true;
	            }
    		}
            return false;
//            w.profiler.endSection();
    	}
    };

    private int maxDistanceToBox(Box box) {
        double d1 = Math.sqrt(worldPosition.distSqr(box.min()));
        double d2 = Math.sqrt(worldPosition.distSqr(box.max()));
        return (int) Math.ceil(Math.max(d1, d2));
    }

    public TileQuarry(BlockPos pos, BlockState state) {
    	super(BCBuildersBlocks.QUARRY_TILE_BC8.get(), pos, state);
        maxPowerPerTick = MachineDefinitionLookup.maxInputMicroMj(
            BuildCraftContentIds.Machines.QUARRY, DEFAULT_MAX_POWER_PER_TICK
        );
        battery = new MjBattery(MachineDefinitionLookup.capacityMicroMj(
            BuildCraftContentIds.Machines.QUARRY, 24000 * MjAmount.MICRO_MJ_PER_MJ
        ));
        apiChunkLoading = MachineDefinitionLookup.chunkLoading(BuildCraftContentIds.Machines.QUARRY, true);
        apiWorkSpeedMultiplier = MachineDefinitionLookup.workSpeedMultiplier(BuildCraftContentIds.Machines.QUARRY);
        apiEnergyCostMultiplier = MachineDefinitionLookup.energyCostMultiplier(BuildCraftContentIds.Machines.QUARRY);
        caps.addCapabilityInstance(TilesAPI.CAP_HAS_WORK, this, EnumPipePart.VALUES);
        caps.addProvider(new MjCapabilityHelper(new MjBatteryReceiver(battery) {
            @Override
            public long getPowerRequested() {
                return hasWork() ? super.getPowerRequested() : 0;
            }

            @Override
            public long receivePower(long microJoules, FluidAction action) {
                return hasWork() ? super.receivePower(microJoules, action) : microJoules;
            }

            @Override
            public boolean canReceive() {
                return hasWork();
            }
        }));
        caps.addCapabilityInstance(
            CapUtil.CAP_ITEM_TRANSACTOR, AutomaticProvidingTransactor.INSTANCE, EnumPipePart.VALUES
        );
    }

    @Nonnull
    private BoxIterator createBoxIterator() {
        // BlockPos provides a stable 64-bit packing of all three coordinates, giving each quarry position a
        // deterministic seed that includes X, Y and Z.
        Random rand = new Random(getBlockPos().asLong());
        EnumAxisOrder axisOrder = rand.nextBoolean() ? EnumAxisOrder.XZY : EnumAxisOrder.ZXY;
        AxisOrder.Inversion inv = AxisOrder.Inversion.getFor(rand.nextBoolean(), rand.nextBoolean(), false);
        return new BoxIterator(miningBox, AxisOrder.getFor(axisOrder, inv), true);
    }

    /** Gets the current positions where frame blocks should be placed, in order.
     * <p>
     * Assumes that {@link #frameBox} is correct for the current position. Does not take into account the current facing
     * of the quarry, as that is assumed to be involved in the {@link #frameBox} itself.
     *
     * @return An ordered list of the positions that the frame should be placed in. The list is in placement order.
     * @throws IllegalStateException if something went wrong during iteration, or the current {@link #frameBox} was
     *             incorrect compared to {@link #getPos()} */
    private List<BlockPos> getFramePositions() {
        // visitedSet and framePositions are considered the same
        // - both should contain the same elements
        // - neither should contain duplicate elements
        // - visitedSet is used as an optimisation, as set.contains is likely to be faster than list.contains
        Set<BlockPos> visitedSet = new HashSet<>();
        List<BlockPos> framePositions = new ArrayList<>();

        List<BlockPos> openSet = new ArrayList<>();
        List<BlockPos> nextOpenSet = new ArrayList<>();

        // Assume that frameBox is right next to the quarries position
        // If it's not then its not that big of a problem, as the iteration will
        // not add any of the frame positions. However we will end up with a list
        // containing no elements, which isn't ideal.
        openSet.add(getBlockPos());

        // Hold on to the array of orders, as we shuffle it on each iteration
        Direction[] order = Direction.values();
        // Also hold on to it as a list, so that we don't have to re-create it all the time
        List<Direction> orderAsList = Arrays.asList(order);

        // This is technically higher than the number of iterations needed as
        // most of the iterations will add more than one edge block.
        int maxIterationCount = frameBox.getBlocksOnEdgeCount();
        int iterationCount = 0;
        do {
            for (BlockPos p : openSet) {
                Collections.shuffle(orderAsList);
                for (Direction face : order) {
                    BlockPos next = p.offset(face.getNormal());
                    // Each iteration we add the *next* positions, rather than the current position
                    // Then we can just add the quarries position once (which isn't part of the frame)
                    if (frameBox.isOnEdge(next) && visitedSet.add(next)) {
                        nextOpenSet.add(next);
                        framePositions.add(next);
                    }
                }
            }
            // Clear openSet and swap it with nextOpenSet
            // Chances are that the arrays will only ever get bigger
            // So its useful to avoid lots of allocation every iteration.
            openSet.clear();
            List<BlockPos> t = openSet;
            openSet = nextOpenSet;
            nextOpenSet = t;

            // Shuffle the open set each time, to avoid the
            // (odd) order that it frames are normally built in.
            Collections.shuffle(openSet);

            // Sanity Check: Ensure that openSet isn't huge
            // the (theoretical) maximum size would be if all
            // 8 corners were visited in the same iteration,
            // and somehow were the first 8 added.
            if (openSet.size() > 8 * 3) {
                String msg = "OpenSet got too big!";
                msg += "\n  Position = " + worldPosition;
                msg += "\n  Frame Box = " + frameBox;
                msg += "\n  Iteration Count = " + iterationCount;
                msg += "\n  OpenSet = " + openSet.stream().map(Object::toString).collect(
                    Collectors.joining("\n  ", "[", "]")
                );
                throw new IllegalStateException(msg);
            }

            // Ensure that we aren't going infinitely
            iterationCount++;
            if (iterationCount >= maxIterationCount) {
                // We definitely failed. As maxIterationCount is an over-estimate
                String msg = "Failed to generate a correct list of frame positions! Was the frame box wrong?";
                msg += "\n  Position = " + worldPosition;
                msg += "\n  Frame Box = " + frameBox;
                msg += "\n  Iteration Count = " + iterationCount;
                msg += "\n  OpenSet = " + openSet.stream().map(Object::toString).collect(
                    Collectors.joining("\n  ", "[", "]")
                );
                throw new IllegalStateException(msg);
            }
        } while (!openSet.isEmpty());

        if (framePositions.isEmpty()) {
            // We failed. Perhaps frameBox wasn't actually right next to the position of the quarry?
            String msg = "Failed to generate a correct list of frame positions! Was the frame box wrong?";
            msg += "\n  Position = " + worldPosition;
            msg += "\n  Frame Box = " + frameBox;
            throw new IllegalStateException(msg);
        }

        return framePositions;
    }

    private boolean shouldBeFrame(BlockPos p) {
        return frameBox.isOnEdge(p);
    }

    @Override
    public void onPlacedBy(LivingEntity placer, ItemStack stack) {
        super.onPlacedBy(placer, stack);
        if (placer.level.isClientSide) {
            return;
        }
        Direction facing = level.getBlockState(worldPosition).getValue(BlockBCBase_Neptune.PROP_FACING);
        BlockPos areaPos = worldPosition.offset(facing.getOpposite().getNormal());
        BlockEntity tile = level.getBlockEntity(areaPos);
        BlockPos min = null, max = null;
        if (tile instanceof IAreaProvider) {
            IAreaProvider provider = (IAreaProvider) tile;
            min = provider.min();
            max = provider.max();
            int dx = max.getX() - min.getX();
            int dz = max.getZ() - min.getZ();
            if (dx < 3 || dz < 3) {
                min = null;
                max = null;
            } else {
                provider.removeFromWorld(placer instanceof Player player ? player : null);
            }
        }
        // noinspection ConstantConditions
        if (min == null || max == null) {
            min = null;
            max = null;
            VolumeSubCache cache = VolumeCache.INSTANCE.getSubCache(getLevel());
            for (BlockPos markerPos : cache.getAllMarkers()) {
                TileMarkerVolume marker = (TileMarkerVolume) cache.getMarker(markerPos);
                if (marker == null) {
                    continue;
                }
                VolumeConnection connection = marker.getCurrentConnection();
                if (connection == null) {
                    continue;
                }
                Box volBox = connection.getBox();
                Box box2 = new Box();
                box2.initialize(volBox);
                if (!box2.isInitialized()) {
                    continue;
                }
                if (worldPosition.getY() != box2.min().getY()) {
                    continue;
                }
                if (box2.contains(worldPosition)) {
                    continue;
                }
                if (!box2.contains(areaPos)) {
                    continue;
                }
                if (box2.size().getX() < 3 || box2.size().getZ() < 3) {
                    continue;
                }
                box2.expand(1);
                box2.setMin(box2.min().above());
                if (box2.isOnEdge(worldPosition)) {
                    min = volBox.min();
                    max = volBox.max();
                    marker.removeFromWorld(placer instanceof Player player ? player : null);
                    break;
                }
            }
        }
        if (min == null || max == null) {
            miningBox.reset();
            frameBox.reset();
            switch (facing.getOpposite()) {
                case DOWN:
                case UP:
                default:
                case EAST: // +X
                    min = worldPosition.offset(1, 0, -5);
                    max = worldPosition.offset(11, 4, 5);
                    break;
                case WEST: // -X
                    min = worldPosition.offset(-11, 0, -5);
                    max = worldPosition.offset(-1, 4, 5);
                    break;
                case SOUTH: // +Z
                    min = worldPosition.offset(-5, 0, 1);
                    max = worldPosition.offset(5, 4, 11);
                    break;
                case NORTH: // -Z
                    min = worldPosition.offset(-5, 0, -11);
                    max = worldPosition.offset(5, 4, -1);
                    break;
            }
        }
        if (max.getY() - min.getY() < BCBuildersConfig.quarryFrameMinHeight) {
            max = new BlockPos(max.getX(), min.getY() + BCBuildersConfig.quarryFrameMinHeight, max.getZ());
        }
        if (level.isOutsideBuildHeight(max)) {
            int dist = max.getY() - min.getY();
            min = min.below(dist);
            max = max.below(dist);
        }
        frameBox.reset();
        frameBox.setMin(min);
        frameBox.setMax(max);
        miningBox.reset();
        int minY = max.getY() - 1 - BCCoreConfig.miningMaxDepth;
        if (level.isOutsideBuildHeight(new BlockPos(min.getX(), minY, min.getZ()))) {
            minY = level.getMinBuildHeight();
        }
        miningBox.setMin(new BlockPos(min.getX() + 1, minY, min.getZ() + 1));
        miningBox.setMax(new BlockPos(max.getX() - 1, max.getY() - 1, max.getZ() - 1));
        updatePoses();
    }

    private boolean canMine(BlockPos blockPos) {
        BlockState state = level.getBlockState(blockPos);
        if (isQuarryCollisionBlock(state)) {
            return false;
        }
        if (state.getDestroySpeed(level, blockPos) < 0) {
            return false;
        }
        // BC8 only treated fluids up to water viscosity as traversable/minable quarry space. More viscous fluids
        // (lava, crude oil, heavy oil, etc.) are barriers: the iterator skips the fluid itself and canMoveDownTo()
        // prevents mining blocks underneath it. Treating those fluids as normal break tasks makes their empty block
        // shape feed the drill animation and, more importantly, charges block-breaking work for a fluid BC8 never mined.
        Fluid fluid = getStandaloneFluid(state);
        return fluid == Fluids.EMPTY || fluid.getFluidType().getViscosity() <= 1000;
    }

    private static Fluid getStandaloneFluid(BlockState state) {
        FluidState fluidState = state.getFluidState();
        if (fluidState.isEmpty()) {
            return Fluids.EMPTY;
        }

        // FluidState is present for both a real fluid block and a waterlogged solid. Comparing the block with the
        // fluid's legacy block cleanly separates those cases without depending on the concrete LiquidBlock class.
        return fluidState.createLegacyBlock().getBlock() == state.getBlock() ? fluidState.getType() : Fluids.EMPTY;
    }

    private boolean canMoveThrough(BlockPos blockPos) {
        BlockState state = level.getBlockState(blockPos);
        if (isQuarryCollisionBlock(state)) {
            return true;
        }
        if (level.isEmptyBlock(blockPos)) {
            return true;
        }
        // Only standalone fluid blocks are passable. Waterlogged blocks still contain a real block that must be mined.
        Fluid fluid = getStandaloneFluid(state);
        return fluid != Fluids.EMPTY && fluid.getFluidType().getViscosity() <= 1000;
    }

    private boolean canMoveDownTo(BlockPos blockPos) {
        for (int y = miningBox.max().getY(); y > blockPos.getY(); y--) {
            if (!canMoveThrough(VecUtil.replaceValue(blockPos, Axis.Y, y))) {
                return false;
            }
        }
        return true;
    }

    /**
     * BC8 listened to every world block-state update, so placing a block into an already-cleared quarry column rewound
     * the iterator and the drill came back for it. Modern GameEvent listeners are spatially dispatched around the event
     * and cannot reproduce that global world-listener behaviour for a large quarry. Keep the cheap event fast-path, but
     * also sweep the loaded quarry columns through the heightmap so missed placements (notably sponge in a deep hole)
     * are discovered without rescanning the complete excavated volume.
     */
    private void rescanVisitedMiningColumns() {
        if (boxIterator == null || !miningBox.isInitialized()) {
            return;
        }

        int sizeX = miningBox.max().getX() - miningBox.min().getX() + 1;
        int sizeZ = miningBox.max().getZ() - miningBox.min().getZ() + 1;
        if (sizeX <= 0 || sizeZ <= 0) {
            return;
        }
        int columnCount = sizeX * sizeZ;
        int scans = Math.min(MINING_COLUMN_WATCHDOG_SCANS_PER_TICK, columnCount);
        for (int i = 0; i < scans; i++) {
            if (miningColumnScanIndex >= columnCount) {
                miningColumnScanIndex = 0;
            }
            int index = miningColumnScanIndex++;
            int x = miningBox.min().getX() + index % sizeX;
            int z = miningBox.min().getZ() + index / sizeX;
            int y = Math.min(
                miningBox.max().getY(),
                level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1
            );

            BlockPos candidate = new BlockPos(x, y, z);
            while (candidate.getY() >= miningBox.min().getY() && canMoveThrough(candidate)) {
                candidate = candidate.below();
            }
            if (candidate.getY() < miningBox.min().getY()) {
                continue;
            }
            boolean visited = boxIterator.hasFinished() || boxIterator.hasVisited(candidate);
            if (!visited || !canMine(candidate) || !canMoveDownTo(candidate)) {
                continue;
            }

            boxIterator.moveTo(candidate);
            return;
        }
    }

    private boolean canIgnoreInFrameBox(BlockPos blockPos) {
        BlockState state = level.getBlockState(blockPos);
        return !isQuarryCollisionBlock(state) && !level.isEmptyBlock(blockPos) && BlockUtil.getFluidWithFlowing(level, blockPos) == Fluids.EMPTY;
    }

    public boolean hasWork() {
        if (!frameBox.isInitialized() || !miningBox.isInitialized()) return false;
        if (!firstChecked || currentTask != null) return true;
        if (getRetryableFramePlace() != null || getRetryableFrameBreak() != null) return true;
        if (boxIterator == null || boxIterator.hasNext()) return true;
        return getRetryableDeniedMiningPosition() != null;
    }

    private void check(BlockPos blockPos) {
        frameBreakBlockPoses.remove(blockPos);
        framePlaceFramePoses.remove(blockPos);
        if (shouldBeFrame(blockPos)) {
            if (level.getBlockState(blockPos).getBlock() != BCBuildersBlocks.FRAME.get()) {
                if (canIgnoreInFrameBox(blockPos)) {
                    frameBreakBlockPoses.add(blockPos);
                } else {
                    framePlaceFramePoses.add(blockPos);
                }
            }
        } else {
            if (canIgnoreInFrameBox(blockPos)) {
                frameBreakBlockPoses.add(blockPos);
            }
        }
    }

    @Override
    public void onLoad() {
        if (!level.isClientSide) {
            updatePoses();
            chunkLoadingDirty = true;
            // Do not call ForgeChunkManager while the owner chunk is still completing its load future.
            chunkLoadingStartupDelay = 40;
        }
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
    }

    @Override
    public void onRemove(boolean dropSelf) {
        if (level instanceof ServerLevel) {
            ChunkLoaderManager.releaseChunksFor(this);
        }
        clearQuarryCollisionBlocks();
        super.onRemove(dropSelf);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
    }

    @Nullable
    @Override
    public LoadType getLoadType() {
        return apiChunkLoading ? LoadType.HARD : null;
    }

    @Nullable
    @Override
    public Set<ChunkPos> getChunksToLoad() {
        if (!apiChunkLoading || !frameBox.isInitialized() || !miningBox.isInitialized()) {
            return null;
        }
        Set<ChunkPos> chunkPoses = new HashSet<>();
        ChunkPos minChunkPos = new ChunkPos(frameBox.min());
        ChunkPos maxChunkPos = new ChunkPos(frameBox.max());
        for (int x = minChunkPos.x; x <= maxChunkPos.x; x++) {
            for (int z = minChunkPos.z; z <= maxChunkPos.z; z++) {
                chunkPoses.add(new ChunkPos(x, z));
            }
        }
        return chunkPoses;
    }

    private void updatePoses() {
        framePoses.clear();
        frameBoxPosesCount = 0;
        initialFrameScan = null;
        initialFrameScannedCount = 0;
        firstChecked = false;
        frameBreakBlockPoses.clear();
        framePlaceFramePoses.clear();
        frameEdgeScanIndex = 0;
        miningColumnScanIndex = 0;
        nextFrameWatchdogTick = 0;
        lastCollisionBlocksDrillPos = null;
        deniedBreakUntil.clear();
        BlockState state = level.getBlockState(worldPosition);
        if (state.getBlock() == BCBuildersBlocks.QUARRY.get() && frameBox.isInitialized()) {
            BlockPos size = frameBox.size();
            long volume = (long) size.getX() * size.getY() * size.getZ();
            frameBoxPosesCount = (int) Math.min(Integer.MAX_VALUE, Math.max(0, volume));
            initialFrameScan = new BoxIterator(frameBox, EnumAxisOrder.XZY.getMinToMaxOrder(), false);
            try {
                framePoses.addAll(getFramePositions());
            } catch (IllegalStateException e) {
                BCLog.logger.warn("Resetting invalid quarry frame/mining box at " + worldPosition, e);
                frameBox.reset();
                miningBox.reset();
                drillPos = null;
                cancelCurrentTaskWithRefund();
                boxIterator = null;
                initialFrameScan = null;
                framePoses.clear();
                frameBoxPosesCount = 0;
                sendNetworkUpdate(NET_RENDER_DATA);
            }
        }
        chunkLoadingDirty = true;
    }

    private void updateInitialFrameScan() {
        int budget = 500;
        while (budget-- > 0 && initialFrameScan != null && initialFrameScan.getCurrent() != null) {
            check(initialFrameScan.getCurrent());
            initialFrameScannedCount++;
            initialFrameScan.advance();
        }
        if (initialFrameScan == null || initialFrameScan.getCurrent() == null) {
            initialFrameScan = null;
            firstChecked = true;
        }
    }

    private void updateCollisionBlocksIfNeeded() {
        // The quarry itself performs one O(N) reconciliation per tick. Collision BlockEntities must
        // never rebuild this map themselves, otherwise N children each doing O(N) work becomes O(N^2).
        updateQuarryCollisionBlocks();
        lastCollisionBlocksDrillPos = drillPos;
    }

    /**
     * Rebuilds the collision mask only when the drill position changes. Collision block entities call
     * shouldKeepCollisionBlock every tick, so rebuilding the complete X/Z/drill map from each child
     * turns a large quarry into O(N^2) work.
     */
    private void ensureCollisionBlocksCurrent() {
        if (!Objects.equals(lastCollisionBlocksDrillPos, drillPos)) {
            updateQuarryCollisionBlocks();
            lastCollisionBlocksDrillPos = drillPos;
        }
    }

    private boolean mayStartBreakTask(BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        long now = serverLevel.getGameTime();
        long retryAt = deniedBreakUntil.getOrDefault(pos, 0L);
        if (retryAt > now) {
            return false;
        }
        if (!AutomationPermissionUtil.mayBlock(
            serverLevel, worldPosition, pos, getOwner(), AutomationPermissionUtil.SOURCE_QUARRY,
            WorldOperationKind.BLOCK_BREAK, OperationMode.SIMULATE
        )) {
            deniedBreakUntil.put(pos.immutable(), now + 200);
            return false;
        }
        Player actor = buildcraft.lib.misc.FakePlayerProvider.INSTANCE.getFakePlayer(serverLevel, getOwner(), pos);
        if (!BlockUtil.canBreakBlock(serverLevel, pos, actor)) {
            deniedBreakUntil.put(pos.immutable(), now + 200);
            return false;
        }
        deniedBreakUntil.remove(pos);
        return true;
    }

    private boolean retryIsDue(BlockPos pos) {
        return level != null && deniedBreakUntil.getOrDefault(pos, 0L) <= level.getGameTime();
    }

    private BlockPos getRetryableFrameBreak() {
        return frameBreakBlockPoses.stream().filter(this::retryIsDue).findFirst().orElse(null);
    }

    private BlockPos getRetryableFramePlace() {
        return framePoses.stream()
            .filter(framePlaceFramePoses::contains)
            .filter(this::retryIsDue)
            .findFirst()
            .orElse(null);
    }

    private boolean isMiningDeniedPosition(BlockPos pos) {
        return miningBox.isInitialized() && miningBox.contains(pos) && !framePoses.contains(pos);
    }

    private void cleanStaleDeniedMiningPositions() {
        deniedBreakUntil.keySet().removeIf(pos -> isMiningDeniedPosition(pos)
            && (canMoveThrough(pos) || !canMine(pos) || !canMoveDownTo(pos)));
    }

    private boolean hasPendingDeniedMiningPosition() {
        return deniedBreakUntil.keySet().stream().anyMatch(this::isMiningDeniedPosition);
    }

    private BlockPos getRetryableDeniedMiningPosition() {
        cleanStaleDeniedMiningPositions();
        return deniedBreakUntil.keySet().stream()
            .filter(this::isMiningDeniedPosition)
            .filter(this::retryIsDue)
            .findFirst()
            .orElse(null);
    }

    @Override
    public void update() {
        if (drillPos == null) {
            collisionBoxes = ImmutableList.of();
            collisionDrillPos = null;
            if (level != null && !level.isClientSide) {
                clearQuarryCollisionBlocks();
            }
        }
        
        if (level.isClientSide) {
            prevClientDrillPos = clientDrillPos;
            clientDrillPos = drillPos;
            if (currentTask != null) {
                currentTask.clientTick();
            }
            return;
        }

        // Refunds are part of the transaction ledger, not best-effort battery writes. If the battery was full when
        // a task was cancelled, keep retrying instead of silently losing the already-withdrawn MJ.
        flushPendingTaskPowerRefund();

        if (chunkLoadingDirty) {
            if (chunkLoadingStartupDelay > 0) {
                chunkLoadingStartupDelay--;
            } else {
                chunkLoadingDirty = false;
                if (frameBox.isInitialized() && miningBox.isInitialized()) {
                    ChunkLoaderManager.loadChunksForTile(this);
                } else {
                    ChunkLoaderManager.releaseChunksFor(this);
                }
            }
        }

        if (!frameBox.isInitialized() || !miningBox.isInitialized()) {
            clearQuarryCollisionBlocks();
            return;
        }

        if (!firstChecked) {
            updateInitialFrameScan();
            updateCollisionBlocksIfNeeded();
            return;
        }

        long gameTime = level.getGameTime();
        if (!framePoses.isEmpty() && gameTime >= nextFrameWatchdogTick) {
            nextFrameWatchdogTick = gameTime + 20;
            int scans = Math.min(8, framePoses.size());
            for (int i = 0; i < scans; i++) {
                if (frameEdgeScanIndex >= framePoses.size()) {
                    frameEdgeScanIndex = 0;
                }
                check(framePoses.get(frameEdgeScanIndex++));
            }
        }

        if (currentTask == null) {
            rescanVisitedMiningColumns();
        }

        if (!hasWork()) {
            updateCollisionBlocksIfNeeded();
            return;
        }

        long max;
        if (battery.getStored() > battery.getCapacity() / 2) {
            max = maxPowerPerTick;
        } else {
            long roundedUp = battery.getStored() + MjAmount.MICRO_MJ_PER_MJ / 2;
            if (roundedUp > Long.MAX_VALUE / maxPowerPerTick) {
                // The multiplication would overflow, so we'll have to use BigInteger for this bit
                max = BigInteger.valueOf(roundedUp).multiply(BigInteger.valueOf(maxPowerPerTick))
                    .divide(BigInteger.valueOf(battery.getCapacity() / 2)).longValue();
            } else {
                max = maxPowerPerTick * roundedUp / (battery.getCapacity() / 2);
            }
            max = MathUtil.clamp(max, 0, maxPowerPerTick);
        }
        long initialPowerBudget = max;
        debugPowerRate = max;
        blockPercentSoFar = 0;
        moveDistanceSoFar = 0;

        int maxTasks = Math.max(1, (int) (max * BCBuildersConfig.quarryMaxTasksPerTick * apiWorkSpeedMultiplier / Math.max(1L, maxPowerPerTick)));
        boolean sendUpdate = false;
        power_loop: for (int i = 0; i < maxTasks; i++) {

            if (currentTask != null) {

                long needed = currentTask.getRequiredPowerThisTick();
                long added;
                long withdrawn;
                final int mult = BCBuildersConfig.quarryTaskPowerDivisor;
                if (mult > 0) {
                    long nNeeded = needed * (mult + i) / mult;
                    long leftover = (needed * (mult + i)) % mult;
                    withdrawn = battery.extractPower(0, Math.min(max, nNeeded));
                    max -= withdrawn;
                    added = withdrawn * mult / (mult + i);
                    if (leftover > 0 && withdrawn > 0) {
                        added++;
                    }
                } else {
                    withdrawn = battery.extractPower(0, Math.min(max, needed));
                    max -= withdrawn;
                    added = withdrawn;
                }
                if (currentTask.addPower(added, withdrawn)) {
                    currentTask = null;
                } else {
                    sendUpdate = true;
                    break;
                }
            }

            if (!frameBreakBlockPoses.isEmpty()) {
                BlockPos blockPos = getRetryableFrameBreak();
                if (blockPos == null) {
                    break power_loop;
                }
                // BC8 reconciled the queued frame position before moving on to the next task. Modern permission
                // probes reject air, so probing first can turn a block that vanished naturally (or was removed by a
                // neighbouring quarry) into a permanent denied entry: the stale frame-break position is never
                // checked again and the quarry appears to lose its red-laser task. Refresh the queue first.
                check(blockPos);
                if (!frameBreakBlockPoses.contains(blockPos)) {
                    deniedBreakUntil.remove(blockPos);
                    continue power_loop;
                }
                if (canMine(blockPos)) {
                    if (!mayStartBreakTask(blockPos)) {
                        continue power_loop;
                    }
                    drillPos = null;
                    currentTask = new TaskBreakBlock(blockPos);
                    sendUpdate = true;
                }
                continue power_loop;
            }

            if (!framePlaceFramePoses.isEmpty()) {
                BlockPos blockPos = getRetryableFramePlace();
                if (blockPos == null) {
                    break power_loop;
                }
                check(blockPos);
                if (!framePlaceFramePoses.contains(blockPos)) {
                    continue power_loop;
                }
                drillPos = null;
                currentTask = new TaskAddFrame(blockPos);
                sendUpdate = true;
                continue power_loop;
            }

            if (boxIterator == null || drillPos == null) {
                boxIterator = createBoxIterator();
                while (
                    canMoveThrough(boxIterator.getCurrent()) || !canMine(boxIterator.getCurrent())
                    || !canMoveDownTo(boxIterator.getCurrent())
                ) {
                    if (boxIterator.advance() == null) {
                        break;
                    }
                }
                drillPos = Vec3.atLowerCornerOf(miningBox.closestInsideTo(worldPosition));
            }

            if (boxIterator != null && boxIterator.hasNext()) {
                while (
                    canMoveThrough(boxIterator.getCurrent()) || !canMine(boxIterator.getCurrent())
                    || !canMoveDownTo(boxIterator.getCurrent())
                ) {
                    if (boxIterator.advance() == null) {
                        break;
                    }
                }

                if (boxIterator.hasNext()) {
                    boolean found = false;

                    if (drillPos.distanceTo(Vec3.atLowerCornerOf(boxIterator.getCurrent())) >= 1) {
                        currentTask = new TaskMoveDrill(drillPos, Vec3.atLowerCornerOf(boxIterator.getCurrent()));
                        found = true;
                    } else if (canMine(boxIterator.getCurrent())) {
                        BlockPos breakPos = boxIterator.getCurrent();
                        if (!mayStartBreakTask(breakPos)) {
                            boxIterator.advance();
                            continue power_loop;
                        }
                        currentTask = new TaskBreakBlock(breakPos);
                        found = true;
                    }

                    if (found) {
                        sendUpdate = true;
                    }
                }
            }

            if (boxIterator != null && !boxIterator.hasNext()) {
                BlockPos retryPos = getRetryableDeniedMiningPosition();
                if (retryPos != null) {
                    if (drillPos == null || drillPos.distanceTo(Vec3.atLowerCornerOf(retryPos)) >= 1) {
                        Vec3 from = drillPos == null
                            ? Vec3.atLowerCornerOf(miningBox.closestInsideTo(worldPosition))
                            : drillPos;
                        currentTask = new TaskMoveDrill(from, Vec3.atLowerCornerOf(retryPos));
                        sendUpdate = true;
                        continue power_loop;
                    }
                    if (mayStartBreakTask(retryPos)) {
                        currentTask = new TaskBreakBlock(retryPos);
                        sendUpdate = true;
                        continue power_loop;
                    }
                    break power_loop;
                }
                if (hasPendingDeniedMiningPosition()) {
                    break power_loop;
                }
                unlockCompletionAdvancement();
            }
        }
        debugPowerRate -= max;
        if (initialPowerBudget == maxPowerPerTick && debugPowerRate > 0) {
            trackFullSpeedQuarry();
        }
        if (sendUpdate) {
            sendNetworkUpdate(NET_RENDER_DATA);
        }
        updateCollisionBlocksIfNeeded();
    }

    private void trackFullSpeedQuarry() {
        if (!(level instanceof ServerLevel serverLevel) || getOwner() == null || !frameBox.isInitialized()) {
            return;
        }
        if (!is64By64Quarry()) {
            return;
        }

        long gameTime = serverLevel.getGameTime();
        UUID ownerId = getOwner().getId();
        Map<UUID, Map<BlockPos, Long>> byOwner = FULL_SPEED_QUARRIES.computeIfAbsent(
            serverLevel, ignored -> new HashMap<>()
        );
        Map<BlockPos, Long> quarries = byOwner.computeIfAbsent(ownerId, ignored -> new HashMap<>());
        quarries.entrySet().removeIf(entry -> entry.getValue() != gameTime);
        quarries.put(worldPosition.immutable(), gameTime);

        if (quarries.size() >= 2) {
            AdvancementUtil.unlockAdvancement(ownerId, ADVANCEMENT_DESTROYING_THE_WORLD);
        }
    }

    private void unlockCompletionAdvancement() {
        if (getOwner() != null && is64By64Quarry()) {
            AdvancementUtil.unlockAdvancement(getOwner().getId(), ADVANCEMENT_COMPLETE);
        }
    }

    private boolean is64By64Quarry() {
        if (!frameBox.isInitialized()) {
            return false;
        }
        BlockPos size = frameBox.size();
        return size.getX() == 64 && size.getZ() == 64;
    }

    public List<AABB> getCollisionBoxes() {
        if (drillPos != null && !drillPos.equals(collisionDrillPos) && frameBox.isInitialized()) {
            Vec3 max = VecUtil.convertCenter(frameBox.max());
            Vec3 min = VecUtil.replaceValue(VecUtil.convertCenter(frameBox.min()), Axis.Y, max.y);
            collisionBoxes = ImmutableList.of(
                BoundingBoxUtil.makeFrom(
                    VecUtil.replaceValue(min, Axis.X, drillPos.x + 0.5), VecUtil.replaceValue(
                        max, Axis.X, drillPos.x + 0.5
                    ), 0.25
                ), BoundingBoxUtil.makeFrom(
                    VecUtil.replaceValue(min, Axis.Z, drillPos.z + 0.5), VecUtil.replaceValue(
                        max, Axis.Z, drillPos.z + 0.5
                    ), 0.25
                ), BoundingBoxUtil.makeFrom(
                    drillPos.add(0.5, 0, 0.5), VecUtil.replaceValue(drillPos, Axis.Y, max.y).add(
                        0.5, 0, 0.5
                    ), 0.25
                )
            );
            collisionDrillPos = drillPos;
        }
        return collisionBoxes;
    }

    public boolean shouldKeepCollisionBlock(BlockPos pos) {
        ensureCollisionBlocksCurrent();
        return collisionBlockPoses.contains(pos);
    }

    private void updateQuarryCollisionBlocks() {
        if (level == null || level.isClientSide) {
            return;
        }
        Map<BlockPos, Integer> wantedBlocks = buildCollisionBlockMasks();
        for (BlockPos oldPos : new HashSet<>(collisionBlockPoses)) {
            if (!wantedBlocks.containsKey(oldPos)) {
                removeCollisionBlock(oldPos);
                collisionBlockPoses.remove(oldPos);
            }
        }
        for (Map.Entry<BlockPos, Integer> entry : wantedBlocks.entrySet()) {
            if (placeOrUpdateCollisionBlock(entry.getKey(), entry.getValue())) {
                collisionBlockPoses.add(entry.getKey());
            }
        }
    }

    private Map<BlockPos, Integer> buildCollisionBlockMasks() {
        Map<BlockPos, Integer> masks = new HashMap<>();
        if (drillPos == null || !frameBox.isInitialized()) {
            return masks;
        }
        BlockPos min = frameBox.min();
        BlockPos max = frameBox.max();
        int drillX = clamp(floorToBlock(drillPos.x), min.getX(), max.getX());
        int drillY = clamp(floorToBlock(drillPos.y), level.getMinBuildHeight(), max.getY());
        int drillZ = clamp(floorToBlock(drillPos.z), min.getZ(), max.getZ());
        int topY = max.getY();

        for (int x = min.getX(); x <= max.getX(); x++) {
            addCollisionMask(masks, new BlockPos(x, topY, drillZ), BlockQuarryDrillCollision.MASK_X_BEAM);
        }
        for (int z = min.getZ(); z <= max.getZ(); z++) {
            addCollisionMask(masks, new BlockPos(drillX, topY, z), BlockQuarryDrillCollision.MASK_Z_BEAM);
        }
        for (int y = drillY; y <= topY; y++) {
            addCollisionMask(masks, new BlockPos(drillX, y, drillZ), BlockQuarryDrillCollision.MASK_DRILL);
        }
        return masks;
    }

    private static void addCollisionMask(Map<BlockPos, Integer> masks, BlockPos pos, int mask) {
        masks.merge(pos.immutable(), mask, (left, right) -> left | right);
    }

    private boolean placeOrUpdateCollisionBlock(BlockPos pos, int mask) {
        BlockState current = level.getBlockState(pos);
        BlockState wanted = BCBuildersBlocks.QUARRY_DRILL_COLLISION.get().defaultBlockState()
            .setValue(BlockQuarryDrillCollision.MASK, mask);
        if (isQuarryCollisionBlock(current)) {
            if (!canClaimCollisionBlock(pos)) {
                return false;
            }
            if (!current.equals(wanted)) {
                level.setBlock(pos, wanted, 3);
            }
            setCollisionBlockOwner(pos);
            return true;
        }
        if (!current.isAir()) {
            return false;
        }
        level.setBlock(pos, wanted, 3);
        setCollisionBlockOwner(pos);
        return true;
    }

    private boolean canClaimCollisionBlock(BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return !(blockEntity instanceof TileQuarryDrillCollision collision)
            || collision.getOwner() == null
            || worldPosition.equals(collision.getOwner());
    }

    private void setCollisionBlockOwner(BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof TileQuarryDrillCollision collision) {
            collision.setOwner(worldPosition);
        }
    }

    private void clearQuarryCollisionBlocks() {
        if (level == null || level.isClientSide || collisionBlockPoses.isEmpty()) {
            return;
        }
        for (BlockPos pos : new HashSet<>(collisionBlockPoses)) {
            removeCollisionBlock(pos);
        }
        collisionBlockPoses.clear();
        lastCollisionBlocksDrillPos = null;
    }

    private void removeCollisionBlock(BlockPos pos) {
        if (isQuarryCollisionBlock(level.getBlockState(pos)) && canClaimCollisionBlock(pos)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private boolean isQuarryCollisionBlock(BlockState state) {
        return state.getBlock() == BCBuildersBlocks.QUARRY_DRILL_COLLISION.get();
    }

    private static int floorToBlock(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void saveAdditional(CompoundTag nbt) {
        super.saveAdditional(nbt);
        nbt.put("box", miningBox.writeToNBT());
        nbt.put("frame", frameBox.writeToNBT());
        if (boxIterator != null) {
            nbt.put("boxIterator", boxIterator.writeToNbt());
        }
        nbt.put("battery", battery.serializeNBT());
        if (pendingTaskPowerRefund > 0) {
            nbt.putLong("pendingTaskPowerRefund", pendingTaskPowerRefund);
        }
        if (currentTask != null) {
            nbt.putByte(
                "currentTaskId", (byte) Arrays.stream(EnumTaskType.values()).filter(
                    type -> type.clazz == currentTask.getClass()
                ).findFirst().orElseThrow(IllegalStateException::new).ordinal()
            );
            nbt.put("currentTaskData", currentTask.serializeNBT());
        }
        if (drillPos != null) {
            nbt.put("drillPos", NBTUtilBC.writeVec3(drillPos));
        }
        nbt.putBoolean("firstChecked", firstChecked);
    }

    @Override
    public void load(CompoundTag nbt) {
        super.load(nbt);
        miningBox.initialize(nbt.getCompound("box"));
        frameBox.initialize(nbt.getCompound("frame"));
        boxIterator = BoxIterator.readFromNbt(nbt.getCompound("boxIterator"));
        battery.deserializeNBT(nbt.getCompound("battery"));
        pendingTaskPowerRefund = Math.max(0, nbt.getLong("pendingTaskPowerRefund"));
        if (nbt.contains("currentTaskId") && nbt.contains("currentTaskData")) {
            int currentTaskId = Byte.toUnsignedInt(nbt.getByte("currentTaskId"));
            if (currentTaskId >= 0 && currentTaskId < EnumTaskType.values().length) {
                currentTask = EnumTaskType.values()[currentTaskId].supplier.apply(this);
                currentTask.readFromNBT(nbt.getCompound("currentTaskData"));
                if (!currentTask.valid) {
                    cancelCurrentTaskWithRefund();
                }
            } else {
                queueTaskPowerRefund(readSerializedTaskReservedPower(nbt.getCompound("currentTaskData")));
                currentTask = null;
            }
        } else {
            currentTask = null;
        }
        drillPos = NBTUtilBC.readVec3(nbt.get("drillPos"));
        firstChecked = nbt.getBoolean("firstChecked");
        if (drillPos != null && drillPos.distanceToSqr(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ()) > 1024 * 1024) {
            drillPos = null;
        }

        // Validation
        boolean isValid = false;
        if (frameBox.isInitialized() && miningBox.isInitialized()) {
            isValid = true;
            Direction validFace = null;
            for (Direction face : Direction.values()) {
                if (face.getAxis() == Axis.Y) continue;
                // We can't read the blockstate yet so instead we'll have to try all possible faces
                if (frameBox.isOnEdge(getBlockPos().offset(face.getNormal()))) {
                    validFace = face;
                    break;
                }
            }
            if (validFace == null) {
                isValid = false;
            } else {

                int fx0 = frameBox.min().getX();
                int fy0 = frameBox.min().getY();
                int fz0 = frameBox.min().getZ();

                int fx1 = frameBox.max().getX();
                int fy1 = frameBox.max().getY();
                int fz1 = frameBox.max().getZ();

                int mx0 = miningBox.min().getX();
                int my0 = miningBox.min().getY();
                int mz0 = miningBox.min().getZ();

                int mx1 = miningBox.max().getX();
                int my1 = miningBox.max().getY();
                int mz1 = miningBox.max().getZ();

                isValid = true //
                    && fx0 + 1 == mx0//
                    && fx1 - 1 == mx1//
                    && fz0 + 1 == mz0//
                    && fz1 - 1 == mz1//
                    && fy0 >= my0//
                    && fy1 - 1 == my1//
                ;
            }
        }
        if (!isValid) {
            frameBox.reset();
            miningBox.reset();
            drillPos = null;
            cancelCurrentTaskWithRefund();
        }
        flushPendingTaskPowerRefund();
    }

    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_RENDER_DATA) {
                frameBox.writeData(buffer);
                miningBox.writeData(buffer);
                buffer.writeBoolean(drillPos != null);
                if (drillPos != null) {
                    MessageUtil.writeVec3(buffer, drillPos);
                }
                buffer.writeBoolean(currentTask != null);
                if (currentTask != null) {
                    buffer.writeByte(
                        (byte) Arrays.stream(EnumTaskType.values()).filter(type -> type.clazz == currentTask.getClass())
                            .findFirst().orElseThrow(IllegalStateException::new).ordinal()
                    );
                    for (int i = 0; i < 2; i++) {
                        currentTask.toBytes(buffer);
                    }
                }
            }
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_RENDER_DATA) {
                frameBox.readData(buffer);
                miningBox.readData(buffer);
                if (buffer.readBoolean()) {
                    drillPos = MessageUtil.readVec3(buffer);
                } else {
                    drillPos = null;
                }
                if (buffer.readBoolean()) {
                    int taskId = Byte.toUnsignedInt(buffer.readByte());
                    EnumTaskType[] values = EnumTaskType.values();
                    if (taskId >= values.length) {
                        currentTask = null;
                        return;
                    }
                    Task task = values[taskId].supplier.apply(this);
                    task.fromBytes(buffer);
                    if (currentTask == null || !currentTask.equals(task)) {
                        currentTask = task;
                        Task tempTask = values[taskId].supplier.apply(this);
                        tempTask.fromBytes(buffer);
                    } else {
                        currentTask.fromBytes(buffer);
                    }
                } else {
                    currentTask = null;
                }
            }
        }
    }

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        left.add("battery = " + battery.getDebugString());
        left.add("rate = " + LocaleUtil.localizeMjFlow(debugPowerRate));
        left.add("frameBox");
        left.add(" - min = " + frameBox.min());
        left.add(" - max = " + frameBox.max());
        left.add("miningBox:");
        left.add(" - min = " + miningBox.min());
        left.add(" - max = " + miningBox.max());

        left.add("initialFrameScanned = " + initialFrameScannedCount);
        left.add("frameBoxPosesCount = " + frameBoxPosesCount);
        left.add("firstChecked = " + firstChecked);

        BoxIterator iter = boxIterator;
        left.add("current = " + (iter == null ? "null" : iter.getCurrent()));

        Task task = currentTask;
        if (task != null) {
            left.add("task:");
            left.add(" - class = " + task.getClass().getName());
            left.add(" - power = " + LocaleUtil.localizeMj(task.power));
            left.add(" - target = " + LocaleUtil.localizeMj(task.getTarget()));
        } else {
            left.add("task = null");
        }
        left.add("drill = " + drillPos);
    }

    @Nonnull
    @Override
    @OnlyIn(Dist.CLIENT)
    public AABB getRenderBoundingBox() {
        return BoundingBoxUtil.makeFrom(worldPosition, miningBox);
    }

    @Override
	public boolean hasCustomOutlineRendering(Player player) {
		return true;
	}

/*	@OnlyIn(Dist.CLIENT)
    @Override
    public DetachedRenderer.IDetachedRenderer getDebugRenderer() {
        return new AdvDebuggerQuarry(this);
    }*/

    private void cancelCurrentTaskWithRefund() {
        if (currentTask == null) return;
        queueTaskPowerRefund(currentTask.reservedPower);
        currentTask = null;
    }

    private void queueTaskPowerRefund(long power) {
        if (power <= 0) return;
        pendingTaskPowerRefund = saturatingAdd(pendingTaskPowerRefund, power);
        setChanged();
        flushPendingTaskPowerRefund();
    }

    private void flushPendingTaskPowerRefund() {
        if (pendingTaskPowerRefund <= 0) return;
        long free = Math.max(0, battery.getCapacity() - battery.getStored());
        long refunded = Math.min(free, pendingTaskPowerRefund);
        if (refunded > 0) {
            battery.addPower(refunded, FluidAction.EXECUTE);
            pendingTaskPowerRefund -= refunded;
            setChanged();
        }
    }

    private static long readSerializedTaskReservedPower(CompoundTag nbt) {
        if (nbt == null) return 0;
        return Math.max(0, nbt.contains("reservedPower") ? nbt.getLong("reservedPower") : nbt.getLong("power"));
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0) return Math.max(0, left);
        if (left >= Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return Math.max(0, left) + right;
    }

    private enum EnumTaskType {
        BREAK_BLOCK(TaskBreakBlock.class, quarry -> quarry.new TaskBreakBlock()),
        ADD_FRAME(TaskAddFrame.class, quarry -> quarry.new TaskAddFrame()),
        MOVE_DRILL(TaskMoveDrill.class, quarry -> quarry.new TaskMoveDrill());

        public final Class<? extends Task> clazz;
        public final Function<TileQuarry, Task> supplier;

        EnumTaskType(Class<? extends Task> clazz, Function<TileQuarry, Task> supplier) {
            this.clazz = clazz;
            this.supplier = supplier;
        }
    }

    private long scaleTaskEnergy(long baseMicroMj) {
        if (baseMicroMj <= 0 || apiEnergyCostMultiplier <= 0) {
            return 0;
        }
        double scaled = baseMicroMj * apiEnergyCostMultiplier;
        return scaled >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(1L, Math.round(scaled));
    }

    private double movementDistanceForPower(long addedMicroMj, long targetMicroMj, double totalDistance) {
        if (addedMicroMj <= 0 || totalDistance <= 0) return 0;
        if (targetMicroMj <= 0) return totalDistance;
        return Math.min(totalDistance, addedMicroMj / (double) targetMicroMj * totalDistance);
    }

    private abstract class Task {
        /** Effective work progress. */
        public long power;
        /** Exact amount physically withdrawn from the battery and refundable until this task commits. */
        public long reservedPower;
        public long clientPower;
        public long prevClientPower;
        private boolean valid = true;

        CompoundTag serializeNBT() {
            CompoundTag nbt = new CompoundTag();
            nbt.putLong("power", power);
            nbt.putLong("reservedPower", reservedPower);
            return nbt;
        }

        void readFromNBT(CompoundTag nbt) {
            power = Math.max(0, nbt.getLong("power"));
            // Save data without reservedPower tracks only effective progress. Use it as the refundable floor rather than
            // inventing energy; reservedPower records the exact physical withdrawal when present.
            reservedPower = Math.max(0, nbt.contains("reservedPower") ? nbt.getLong("reservedPower") : power);
        }

        void invalidateLoadedTask() {
            valid = false;
        }

        void toBytes(FriendlyByteBuf buffer) {
            buffer.writeLong(power);
        }

        void fromBytes(FriendlyByteBuf buffer) {
            power = buffer.readLong();
        }

        void clientTick() {
            prevClientPower = clientPower;
            clientPower = power;
        }

        public abstract long getTarget();

        public long getRequiredPowerThisTick() {
            return Math.max(0, getTarget() - power);
        }

        /** @param target the total power target for this task
         * @return {@code true} if this task has been completed, or cancelled. */
        protected abstract boolean onReceivePower(long added, long target);

        protected abstract boolean finish(long added, long target);

        /** @return {@code true} if this task has been completed, or cancelled. */
        final boolean addPower(long progressMicroJoules, long withdrawnMicroJoules) {
            power = saturatingAdd(power, progressMicroJoules);
            reservedPower = saturatingAdd(reservedPower, withdrawnMicroJoules);
            long target = getTarget();
            if (target <= 0) {
                boolean committed = finish(0, 0);
                if (!committed) queueTaskPowerRefund(reservedPower);
                return true;
            }
            if (power >= target) {
                boolean committed = finish(progressMicroJoules, target);
                if (!committed) queueTaskPowerRefund(reservedPower);
                return true;
            }
            boolean cancelled = onReceivePower(progressMicroJoules, target);
            if (cancelled) {
                queueTaskPowerRefund(reservedPower);
            }
            return cancelled;
        }
    }

    public class TaskBreakBlock extends Task {
        public BlockPos breakPos = BlockPos.ZERO;

        TaskBreakBlock() {}

        TaskBreakBlock(BlockPos pos) {
            this.breakPos = pos;
        }

        @Override
        CompoundTag serializeNBT() {
            CompoundTag nbt = super.serializeNBT();
            nbt.putLong("breakPos", breakPos.asLong());
            return nbt;
        }

        @Override
        void readFromNBT(CompoundTag nbt) {
            super.readFromNBT(nbt);
            if (!nbt.contains("breakPos", Tag.TAG_LONG)) {
                // Missing task data must not silently turn into the world origin.
                invalidateLoadedTask();
                return;
            }
            breakPos = BlockPos.of(nbt.getLong("breakPos"));
        }

        @Override
        void toBytes(FriendlyByteBuf buffer) {
            super.toBytes(buffer);
            buffer.writeBlockPos(breakPos);
        }

        @Override
        void fromBytes(FriendlyByteBuf buffer) {
            super.fromBytes(buffer);
            breakPos = buffer.readBlockPos();
        }

        @Override
        public long getTarget() {
            return scaleTaskEnergy(BlockUtil.computeBlockBreakPower(level, breakPos));
        }

        @Override
        public long getRequiredPowerThisTick() {
            long target = getTarget();
            long req = Math.max(0, target - power);
            double rate = BCBuildersConfig.quarryMaxBlockMineRate * apiWorkSpeedMultiplier;
            if (rate < 0.1) {
                return req;
            }
            rate /= 20; // seconds -> ticks
            rate -= blockPercentSoFar;
            if (rate <= 0) {
                return 0;
            }
            return Math.min(req, (long) (target * rate));
        }

        @Override
        protected boolean onReceivePower(long added, long target) {
            blockPercentSoFar += target <= 0 ? 1.0 : added / (double) target;
            if (!level.isEmptyBlock(breakPos)) {
                level.destroyBlockProgress(breakPos.hashCode(), breakPos, (int) (power * 9 / getTarget()));
                return false;
            } else {
                // The target can disappear between frame scans (falling blocks, leaf/plant updates, another quarry,
                // or a player). Reconcile it immediately instead of leaving a stale frame-break queue entry behind.
                level.destroyBlockProgress(breakPos.hashCode(), breakPos, -1);
                check(breakPos);
                deniedBreakUntil.remove(breakPos);
                return true;
            }
        }

        @Override
        protected boolean finish(long added, long target) {
            blockPercentSoFar += target <= 0 ? 1.0 : added / (double) target;
            if (!canMine(breakPos)) {
                // The target disappeared or became irrelevant while energy was reserved: cancel, don't charge, and
                // reconcile frame bookkeeping so a stale task cannot keep hasWork() false forever.
                level.destroyBlockProgress(breakPos.hashCode(), breakPos, -1);
                check(breakPos);
                deniedBreakUntil.remove(breakPos);
                return false;
            }
            if (!AutomationPermissionUtil.mayBlock(
                level, worldPosition, breakPos, getOwner(), AutomationPermissionUtil.SOURCE_QUARRY,
                WorldOperationKind.BLOCK_BREAK, OperationMode.EXECUTE
            )) {
                if (level instanceof ServerLevel serverLevel) {
                    deniedBreakUntil.put(breakPos.immutable(), serverLevel.getGameTime() + 200);
                }
                return false;
            }
            level.destroyBlockProgress(breakPos.hashCode(), breakPos, -1);
            Optional<List<ItemStack>> stacks = BlockUtil.breakBlockAndGetDrops(
                (ServerLevel) level, breakPos, new ItemStack(Items.DIAMOND_PICKAXE), getOwner(), false
            );
            if (stacks.isPresent()) {
                // The drill pos will be null if we are making the frame: this is when we want to destroy the block, not
                // drop its contents
                if (drillPos != null) {
                    stacks.get().forEach(stack -> InventoryUtil.addToBestAcceptor(level, worldPosition, null, stack));
                }
            }
            if (stacks.isEmpty() && level instanceof ServerLevel serverLevel) {
                deniedBreakUntil.put(breakPos.immutable(), serverLevel.getGameTime() + 200);
            } else {
                deniedBreakUntil.remove(breakPos);
            }
            check(breakPos);
            return stacks.isPresent();
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) return true;
            if (o == null) return false;
            if (getClass() != o.getClass()) {
                return false;
            }
            return breakPos.equals(((TaskBreakBlock) o).breakPos);
        }
    }

    public class TaskAddFrame extends Task {
        public BlockPos framePos = BlockPos.ZERO;

        TaskAddFrame() {}

        TaskAddFrame(BlockPos framePos) {
            this.framePos = framePos;
        }

        @Override
        CompoundTag serializeNBT() {
            CompoundTag nbt = super.serializeNBT();
            nbt.putLong("framePos", framePos.asLong());
            return nbt;
        }

        @Override
        void readFromNBT(CompoundTag nbt) {
            super.readFromNBT(nbt);
            if (!nbt.contains("framePos", Tag.TAG_LONG)) {
                invalidateLoadedTask();
                return;
            }
            // BlockPos.ZERO.asLong() is a valid position, so field presence is the validity check.
            framePos = BlockPos.of(nbt.getLong("framePos"));
        }

        @Override
        void toBytes(FriendlyByteBuf buffer) {
            super.toBytes(buffer);
            buffer.writeBlockPos(framePos);
        }

        @Override
        void fromBytes(FriendlyByteBuf buffer) {
            super.fromBytes(buffer);
            framePos = buffer.readBlockPos();
        }

        @Override
        public long getTarget() {
            return scaleTaskEnergy(24 * MjAmount.MICRO_MJ_PER_MJ);
        }

        @Override
        protected boolean onReceivePower(long added, long target) {
            return canIgnoreInFrameBox(framePos);
        }

        @Override
        protected boolean finish(long added, long target) {
            if (canIgnoreInFrameBox(framePos)) {
                return false;
            }
            if (!(level instanceof ServerLevel serverLevel)) {
                return false;
            }
            if (!AutomationPermissionUtil.mayBlock(
                serverLevel, worldPosition, framePos, getOwner(), AutomationPermissionUtil.SOURCE_QUARRY,
                WorldOperationKind.BLOCK_PLACE, OperationMode.EXECUTE
            )) {
                deniedBreakUntil.put(framePos.immutable(), serverLevel.getGameTime() + 200);
                return false;
            }
            Player actor = buildcraft.lib.misc.FakePlayerProvider.INSTANCE.getFakePlayer(serverLevel, getOwner(), framePos);
            boolean placed = BlockUtil.placeBlock(
                serverLevel, framePos, BCBuildersBlocks.FRAME.get().defaultBlockState(), actor, Direction.UP, 3
            );
            if (placed) {
                deniedBreakUntil.remove(framePos);
            } else {
                deniedBreakUntil.put(framePos.immutable(), serverLevel.getGameTime() + 200);
            }
            return placed;
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) return true;
            if (o == null) return false;
            if (getClass() != o.getClass()) {
                return false;
            }
            return framePos.equals(((TaskAddFrame) o).framePos);
        }
    }

    private class TaskMoveDrill extends Task {
        public Vec3 from = Vec3.ZERO;
        public Vec3 to = Vec3.ZERO;

        TaskMoveDrill() {}

        TaskMoveDrill(Vec3 from, Vec3 to) {
            this.from = from;
            this.to = to;
        }

        @Override
        CompoundTag serializeNBT() {
            CompoundTag nbt = super.serializeNBT();
            nbt.put("from", NBTUtilBC.writeVec3(from));
            nbt.put("to", NBTUtilBC.writeVec3(to));
            return nbt;
        }

        @Override
        void readFromNBT(CompoundTag nbt) {
            super.readFromNBT(nbt);
            from = NBTUtilBC.readVec3(nbt.get("from"));
            to = NBTUtilBC.readVec3(nbt.get("to"));
            if (from == null || to == null) {
                // We failed to read. Abort and refund the persisted reservation.
                invalidateLoadedTask();
            }
        }

        @Override
        void toBytes(FriendlyByteBuf buffer) {
            super.toBytes(buffer);
            MessageUtil.writeVec3(buffer, from);
            MessageUtil.writeVec3(buffer, to);
        }

        @Override
        void fromBytes(FriendlyByteBuf buffer) {
            super.fromBytes(buffer);
            from = MessageUtil.readVec3(buffer);
            to = MessageUtil.readVec3(buffer);
        }

        @Override
        public long getTarget() {
            return scaleTaskEnergy((long) (from.distanceTo(to) * 20 * MjAmount.MICRO_MJ_PER_MJ));
        }

        @Override
        public long getRequiredPowerThisTick() {
            long req = Math.max(0, getTarget() - power);

            double max = BCBuildersConfig.quarryMaxFrameMoveSpeed * apiWorkSpeedMultiplier;
            if (max < 0.1) {
                return req;
            }
            max /= 20;
            max -= moveDistanceSoFar;
            if (max <= 0) {
                return 0;
            }
            return Math.min(req, (long) (max * 20 * MjAmount.MICRO_MJ_PER_MJ));
        }

        @Override
        protected boolean onReceivePower(long added, long target) {
            moveDistanceSoFar += movementDistanceForPower(added, target, from.distanceTo(to));
            // Vec3 oldDrillPos = drillPos;
            drillPos = from.scale(1 - power / (double) target).add(to.scale(power / (double) target));
            // moveEntities(oldDrillPos);
            return false;
        }

        @Override
        protected boolean finish(long added, long target) {
            moveDistanceSoFar += movementDistanceForPower(added, target, from.distanceTo(to));
            // Vec3 oldDrillPos = drillPos;
            drillPos = to;
            // moveEntities(oldDrillPos);
            return true;
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) return true;
            if (o == null) return false;
            if (getClass() != o.getClass()) {
                return false;
            }
            TaskMoveDrill other = (TaskMoveDrill) o;
            return from.equals(other.from) && to.equals(other.to);
        }
    }


    @Override
    public net.minecraft.resources.ResourceLocation api2MachineTypeId() {
        return BuildCraftContentIds.Machines.QUARRY;
    }

}
