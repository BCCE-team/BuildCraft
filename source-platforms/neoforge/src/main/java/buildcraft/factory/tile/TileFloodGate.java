/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.factory.tile;

import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.WorldOperationKind;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.block.BlockFloodGate;
import buildcraft.lib.fluid.Tank;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.AutomationPermissionUtil;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.FluidUtilBC;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.lib.misc.MessageUtil;
import buildcraft.lib.tile.TileBC_Neptune;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class TileFloodGate extends TileBC_Neptune implements IDebuggable {
    private static final Vec3i[] SEARCH_NORMAL = new Vec3i[] { //
        Direction.DOWN.getNormal(), Direction.NORTH.getNormal(), Direction.SOUTH.getNormal(), //
        Direction.WEST.getNormal(), Direction.EAST.getNormal() //
    };
    private static final Vec3i[] SEARCH_GASEOUS = new Vec3i[] { //
        Direction.UP.getNormal(), Direction.NORTH.getNormal(), Direction.SOUTH.getNormal(), //
        Direction.WEST.getNormal(), Direction.EAST.getNormal() //
    };

    private static final ResourceLocation ADVANCEMENT_FLOOD_SINGLE = ResourceLocation.parse("buildcraftfactory:flooding_the_world");

    private static final int[] REBUILD_DELAYS = { 16, 32, 64, 128, 256 };
    private static final int MAX_FILL_TARGETS = 4096;
    private static final int MAX_SEARCHED_POSITIONS = 65_536;
    private static final int SEARCH_BUDGET_PER_TICK = 1024;
    private static final int MAX_SEARCH_DISTANCE_SQR = 64 * 64;

    private final Tank tank = new Tank("tank", 2 * FluidType.BUCKET_VOLUME, this);
    public final Set<Direction> openSides = EnumSet.copyOf(BlockFloodGate.CONNECTED_MAP.keySet());
    public final Deque<BlockPos> queue = new ArrayDeque<>();
    /** Child -> parent links for reconstructing a route without copying the complete route for every BFS node. */
    private final Map<BlockPos, BlockPos> parents = new HashMap<>();
    private final Deque<BlockPos> searchFrontier = new ArrayDeque<>();
    private final Set<BlockPos> checked = new HashSet<>();
    private Vec3i[] searchDirections = SEARCH_NORMAL;
    private Fluid searchFluid;
    private int searchedPositions;
    private boolean rebuildingQueue;
    private int delayIndex = 0;
    private int tick = 0;

    public TileFloodGate(BlockPos pos, BlockState state) {
    	super(BCFactoryBlocks.ENTITYBLOCKFLOODGATE.get(), pos, state);
    	tankManager.addLast(tank);
        caps.addFluidStorage(tankManager, EnumPipePart.VALUES);
        
    }

    private int getCurrentDelay() {
        return REBUILD_DELAYS[delayIndex];
    }

    public void onOpenSidesChanged() {
        delayIndex = 0;
        tick = 0;
        buildQueue();
        markChunkDirty();
    }

    private void buildQueue() {
        queue.clear();
        parents.clear();
        searchFrontier.clear();
        checked.clear();
        searchedPositions = 0;
        rebuildingQueue = false;
        searchFluid = null;

        FluidStack fluid = tank.getFluid();
        if (fluid == null || fluid.isEmpty()) {
            return;
        }

        searchFluid = fluid.getFluid();
        searchDirections = searchFluid.getFluidType().isLighterThanAir() ? SEARCH_GASEOUS : SEARCH_NORMAL;
        checked.add(worldPosition);
        for (Direction face : openSides) {
            BlockPos offset = worldPosition.relative(face);
            if (level.hasChunkAt(offset) && checked.add(offset)) {
                parents.put(offset, worldPosition);
                searchFrontier.addLast(offset);
            }
        }
        rebuildingQueue = !searchFrontier.isEmpty();
    }

    private void continueQueueBuild() {
        if (!rebuildingQueue) {
            return;
        }

        FluidStack current = tank.getFluid();
        if (current == null || current.isEmpty() || !FluidUtilBC.areFluidsEqual(current.getFluid(), searchFluid)) {
            buildQueue();
            return;
        }

        int budget = SEARCH_BUDGET_PER_TICK;
        while (budget-- > 0 && !searchFrontier.isEmpty()
            && searchedPositions < MAX_SEARCHED_POSITIONS && queue.size() < MAX_FILL_TARGETS) {
            BlockPos toCheck = searchFrontier.removeFirst();
            searchedPositions++;
            if (!level.hasChunkAt(toCheck) || toCheck.distSqr(worldPosition) > MAX_SEARCH_DISTANCE_SQR || !canSearch(toCheck)) {
                continue;
            }

            if (canFill(toCheck)) {
                // Preserve nearest-first consumption order: push at the front, consume from the back.
                queue.addFirst(toCheck);
            }

            for (Vec3i side : searchDirections) {
                BlockPos next = toCheck.offset(side);
                if (!level.hasChunkAt(next) || next.distSqr(worldPosition) > MAX_SEARCH_DISTANCE_SQR || !checked.add(next)) {
                    continue;
                }
                parents.put(next, toCheck);
                searchFrontier.addLast(next);
            }
        }

        if (searchFrontier.isEmpty() || searchedPositions >= MAX_SEARCHED_POSITIONS
            || queue.size() >= MAX_FILL_TARGETS) {
            rebuildingQueue = false;
            searchFrontier.clear();
            checked.clear();
            searchFluid = null;
            if (queue.isEmpty()) {
                // Start the configured backoff only after the incremental scan has actually completed.
                tick = 0;
            }
        }
    }

    private boolean hasOpenPathTo(BlockPos target) {
        BlockPos current = parents.get(target);
        int remaining = MAX_SEARCHED_POSITIONS;
        while (current != null && !current.equals(worldPosition) && remaining-- > 0) {
            if (!canFillThrough(current)) {
                return false;
            }
            current = parents.get(current);
        }
        return current != null && remaining > 0;
    }

    private boolean canFill(BlockPos offsetPos) {
        if (!level.hasChunkAt(offsetPos)) {
            return false;
        }
        if (level.getBlockState(offsetPos).isAir()) {
            return true;
        }
        Fluid fluid = BlockUtil.getFluidWithFlowing(level, offsetPos);
        return fluid != Fluids.EMPTY && FluidUtilBC.areFluidsEqual(fluid, tank.getFluidType())
            && BlockUtil.getFluidWithoutFlowing(getLocalState(offsetPos)) == Fluids.EMPTY;
    }

    private boolean canSearch(BlockPos offsetPos) {
        if (!level.hasChunkAt(offsetPos)) {
            return false;
        }
        if (canFill(offsetPos)) {
            return true;
        }
        Fluid fluid = BlockUtil.getFluidWithFlowing(level, offsetPos);
        return FluidUtilBC.areFluidsEqual(fluid, tank.getFluidType());
    }

    private boolean canFillThrough(BlockPos pos) {
        if (!level.hasChunkAt(pos)) {
            return false;
        }
        if (level.getBlockState(pos).isAir()) {
            return false;
        }
        Fluid fluid = BlockUtil.getFluidWithFlowing(level, pos);
        return FluidUtilBC.areFluidsEqual(fluid, tank.getFluidType());
    }

    // ITickable

    @Override
    public void update() {
        if (level.isClientSide) {
            return;
        }

        if (tank.getFluidAmount() < FluidType.BUCKET_VOLUME || level.hasNeighborSignal(worldPosition)) {
            return;
        }

        tick++;
        continueQueueBuild();
        if (tick % 16 == 0) {
            if (!tank.isEmpty() && !queue.isEmpty()) {
                FluidStack fluid = tank.drain(FluidType.BUCKET_VOLUME, FluidAction.SIMULATE);
                if (fluid != null && fluid.getAmount() >= FluidType.BUCKET_VOLUME) {
                    BlockPos currentPos = queue.removeLast();
                    if (hasOpenPathTo(currentPos) && canFill(currentPos)) {
                        if (!AutomationPermissionUtil.mayBlock(
                            level, worldPosition, currentPos, getOwner(), AutomationPermissionUtil.SOURCE_FLOOD_GATE,
                            WorldOperationKind.FLUID_PLACE, OperationMode.EXECUTE
                        )) {
                            return;
                        }
                        var actor = FakePlayerProvider.INSTANCE.getFakePlayer((ServerLevel) level, getOwner(), currentPos);
                        if (FluidUtil.tryPlaceFluid(actor, level, null, currentPos, tank, fluid)) {
                            AdvancementUtil.unlockAdvancement(getOwner().getId(), ADVANCEMENT_FLOOD_SINGLE);
                            for (Direction side : Direction.values()) {
                                level.neighborChanged(getBlockState(), currentPos.offset(side.getNormal()), BCFactoryBlocks.FLOOD_GATE_BLOCK.get(),
                                    currentPos, false);
                            }
                            delayIndex = 0;
                            tick = 0;
                        }
                    } else {
                        buildQueue();
                    }
                }
            }
        }

        if (queue.isEmpty() && !rebuildingQueue && tick >= getCurrentDelay()) {
            delayIndex = Math.min(delayIndex + 1, REBUILD_DELAYS.length - 1);
            tick = 0;
            buildQueue();
        }
    }

    // NBT

    @Override
    protected void writeData(BCValueOutput bcData) {
        super.writeData(bcData);
        byte b = 0;
        for (Direction face : Direction.values()) {
            if (openSides.contains(face)) {
                b |= 1 << face.get3DDataValue();
            }
        }
        bcData.writeByte("openLogicalSides", b);
    }

    @Override
    protected void readData(BCValueInput bcData) {
        CompoundTag nbt = bcData.tag();
        super.readData(bcData);
        Tag open = nbt.get("openLogicalSides");
        if (open instanceof NumericTag) {
            byte sides = ((NumericTag) open).getAsByte();
            for (Direction face : Direction.values()) {
                if (((sides >> face.get3DDataValue()) & 1) == 1) {
                    openSides.add(face);
                } else {
                    openSides.remove(face);
                }
            }
        } else if (open instanceof ByteArrayTag) {
            // Legacy: 7.99.7 and before
            byte[] bytes = ((ByteArrayTag) open).getAsByteArray();
            BitSet bitSet = BitSet.valueOf(bytes);
            for (Direction face : Direction.values()) {
                if (bitSet.get(face.get3DDataValue())) {
                    openSides.add(face);
                } else {
                    openSides.remove(face);
                }
            }
        }
    }

    // Networking

    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_RENDER_DATA) {
                // tank.writeToBuffer(buffer);
                MessageUtil.writeEnumSet(buffer, openSides, Direction.class);
            }
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_RENDER_DATA) {
                // tank.readFromBuffer(buffer);
                EnumSet<Direction> _new = MessageUtil.readEnumSet(buffer, Direction.class);
                if (!_new.equals(openSides)) {
                    openSides.clear();
                    openSides.addAll(_new);
                    redrawBlock();
                }
            }
        }
    }
    
	@Override
	public void addDrops(NonNullList<ItemStack> toDrop, int fortune) {
		super.addDrops(toDrop, fortune);
	}

    // IDebuggable

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        left.add("fluid = " + tank.getDebugString());
        left.add("open sides = " + openSides.stream().map(Enum::name).collect(Collectors.joining(", ")));
        left.add("delay = " + getCurrentDelay());
        left.add("tick = " + tick);
        left.add("queue size = " + queue.size());
    }
}
