/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package buildcraft.builders.tile;

import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

import javax.annotation.Nonnull;

import org.jetbrains.annotations.NotNull;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.enums.EnumSnapshotType;
import buildcraft.lib.internal.inventory.IItemTransactor;
import buildcraft.lib.internal.mj.MjBattery;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.builders.BCBuildersBlocks;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.BlueprintBuilder;
import buildcraft.builders.snapshot.BlueprintBuilder.RobotBuildResult;
import buildcraft.builders.snapshot.BlueprintBuilder.RobotBuildTask;
import buildcraft.builders.snapshot.GlobalSavedDataSnapshots;
import buildcraft.builders.snapshot.ITileForBlueprintBuilder;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.snapshot.SnapshotBuilder;
import buildcraft.lib.fluid.TankManager;
import buildcraft.lib.inventory.NoSpaceTransactor;
import buildcraft.lib.misc.BoundingBoxUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.data.Box;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.tile.item.ItemHandlerManager.EnumAccess;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.lib.misc.ItemStackUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import buildcraft.lib.net.BCPacketContext;
import buildcraft.lib.net.BCNetworkSide;

/**
 * BuildCraft 7 style construction marker.
 * <p>
 * Unlike the modern Builder block this tile does not build by itself or consume MJ. It exposes a blueprint target for
 * Builder robots: the marker owns the blueprint, computes the same blueprint placement box, and lets robots reserve and
 * commit build slots from that blueprint.
 */
public class TileConstructionMarker extends TileBC_Neptune implements IDebuggable, ITileForBlueprintBuilder, IRobotBuilderTarget {
    public static final IdAllocator IDS = TileBC_Neptune.IDS.makeChild("construction_marker");

    private static final Set<TileConstructionMarker> LOADED_MARKERS = Collections.newSetFromMap(new WeakHashMap<>());

    public final ItemHandlerSimple invBlueprint = itemManager.addInvHandler(
        "blueprint",
        1,
        (slot, stack) -> isValidBlueprint(stack),
        EnumAccess.BOTH,
        EnumPipePart.VALUES
    ).setLimitedInsertor(1);

    private final MjBattery battery = new MjBattery(0);
    private final BlueprintBuilder blueprintBuilder = new BlueprintBuilder(this);

    private Snapshot snapshot = null;
    private Blueprint.BuildingInfo blueprintBuildingInfo = null;
    private Box currentBox = new Box();
    @NotNull
    private Direction direction = Direction.NORTH;
    @NotNull
    private Rotation rotation = Rotation.NONE;
    private boolean needMaterial = true;
    private boolean canRotate = true;
    private boolean canExcavate = true;
    private ItemStack clientBlueprint = ItemStack.EMPTY;
    private Player nextBlueprintInserter = null;

    public TileConstructionMarker(BlockPos pos, BlockState state) {
        super(BCBuildersBlocks.CONSTRUCTION_MARKER_TILE_BC8.get(), pos, state);
    }

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    public static boolean isValidBlueprint(ItemStack stack) {
        return stack.getItem() instanceof ItemSnapshot
            && ItemSnapshot.EnumItemSnapshotType.getFromStack(stack).snapshotType == EnumSnapshotType.BLUEPRINT
            && ItemSnapshot.getHeader(stack) != null;
    }

    public static Collection<TileConstructionMarker> getLoadedMarkers() {
        return java.util.List.copyOf(LOADED_MARKERS);
    }

    public Direction getDirection() {
        return direction;
    }

    public ItemStack getBlueprintStack() {
        if (level != null && level.isClientSide) {
            return clientBlueprint;
        }
        return invBlueprint.getStackInSlot(0);
    }

    public boolean setBlueprintFromPlayer(ItemStack source, Player player) {
        if (source.isEmpty() || !isValidBlueprint(source) || !invBlueprint.getStackInSlot(0).isEmpty()) {
            return false;
        }
        ItemStack single = source.copy();
        single.setCount(1);
        nextBlueprintInserter = player;
        invBlueprint.setStackInSlot(0, single);
        nextBlueprintInserter = null;
        return true;
    }

    public boolean ejectBlueprint(Player player) {
        if (level == null || level.isClientSide) {
            return false;
        }
        ItemStack extracted = invBlueprint.extractItem(0, 1, false);
        if (extracted.isEmpty()) {
            return false;
        }
        Containers.dropItemStack(level, worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D, worldPosition.getZ() + 0.5D, extracted);
        return true;
    }

    @Override
    public void onPlacedBy(LivingEntity placer, ItemStack stack) {
        super.onPlacedBy(placer, stack);
        if (placer != null) {
            Direction placedDirection = placer.getDirection();
            if (placedDirection != null && placedDirection.getAxis().isHorizontal()) {
                // BC7 uses the player's 2D look direction for the construction marker's short direction laser.
                direction = placedDirection;
            }
        }
        updateSnapshot(true);
        if (level != null && !level.isClientSide) {
            sendNetworkUpdate(NET_RENDER_DATA);
        }
    }

    @Override
    public void rotate(Rotation axis) {
        super.rotate(axis);
        Direction rotated = axis.rotate(direction);
        if (rotated != null && rotated.getAxis().isHorizontal()) {
            direction = rotated;
            updateSnapshot(true);
            if (level != null && !level.isClientSide) {
                sendNetworkUpdate(NET_RENDER_DATA);
            }
        }
    }

    @Override
    protected void onSlotChange(IItemHandlerModifiable handler, int slot, @Nonnull ItemStack before, @Nonnull ItemStack after) {
        if (level != null && !level.isClientSide && handler == invBlueprint) {
            reloadSnapshotFromItem(after, nextBlueprintInserter, true);
            sendNetworkUpdate(NET_RENDER_DATA);
        }
        super.onSlotChange(handler, slot, before, after);
    }

    private void reloadSnapshotFromItem(ItemStack stack, Player player, boolean canGetFacing) {
        if (level == null) {
            return;
        }
        snapshot = null;
        Snapshot.Header header = isValidBlueprint(stack) ? ItemSnapshot.getHeader(stack) : null;
        if (header != null) {
            Snapshot newSnapshot = GlobalSavedDataSnapshots.get(level).getSnapshot(header.key);
            if (newSnapshot instanceof Blueprint) {
                snapshot = newSnapshot;
                applySnapshotHeaderSettings(header, player);
            }
        }
        updateSnapshot(canGetFacing);
    }

    private void applySnapshotHeaderSettings(Snapshot.Header header, Player player) {
        boolean oldNeedMaterial = needMaterial;
        boolean oldCanRotate = canRotate;
        boolean oldCanExcavate = canExcavate;

        // The blueprint only permits creative building if the architect-table author allowed it. The actual
        // no-material mode still depends on the gamemode of the player who inserted the blueprint into this marker.
        needMaterial = !(header.allowCreative && player != null && player.isCreative());
        canRotate = header.canRotate;
        canExcavate = header.canExcavate;

        if (oldNeedMaterial != needMaterial) {
            blueprintBuilder.resourcesChanged();
        }
        if (oldCanExcavate != canExcavate || oldCanRotate != canRotate) {
            blueprintBuilder.forceRecheckCurrentTask();
        }
    }

    private void updateSnapshot(boolean canGetFacing) {
        blueprintBuilder.cancel();
        if (snapshot instanceof Blueprint blueprint && level != null) {
            if (canGetFacing && canRotate) {
                rotation = Arrays.stream(Rotation.values())
                    .filter(r -> r.rotate(snapshot.facing) == direction)
                    .findFirst()
                    .orElse(Rotation.NONE);
            } else {
                rotation = Rotation.NONE;
            }
            BlockPos basePos = worldPosition.offset(direction.getNormal());
            basePos = adjustBasePosIfOverlappingMarker(blueprint, basePos, rotation);
            blueprintBuildingInfo = blueprint.new BuildingInfo(basePos, rotation, level);
            currentBox = Optional.ofNullable(blueprintBuildingInfo.box).orElseGet(Box::new);
            blueprintBuilder.updateSnapshot();
        } else {
            rotation = Rotation.NONE;
            blueprintBuildingInfo = null;
            currentBox = new Box();
        }
        setChanged();
    }

    private BlockPos adjustBasePosIfOverlappingMarker(Blueprint blueprint, BlockPos basePos, Rotation appliedRotation) {
        Blueprint.BuildingInfo buildingInfo = blueprint.new BuildingInfo(basePos, appliedRotation, level);
        if (!buildingInfo.box.contains(worldPosition)) {
            return basePos;
        }

        int maxShift = Math.max(1, Math.max(blueprint.size.getX(), blueprint.size.getZ())) + 1;
        BlockPos shiftedBasePos = basePos;
        for (int i = 0; i < maxShift; i++) {
            shiftedBasePos = shiftedBasePos.offset(direction.getNormal());
            buildingInfo = blueprint.new BuildingInfo(shiftedBasePos, appliedRotation, level);
            if (!buildingInfo.box.contains(worldPosition)) {
                return shiftedBasePos;
            }
        }
        return shiftedBasePos;
    }

    @Override
    public void update() {
        if (level == null) {
            return;
        }
        if (level.isClientSide) {
            if (getBuilder() != null) {
                blueprintBuilder.tick();
            }
        }
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        if (level != null && !level.isClientSide) {
            LOADED_MARKERS.add(this);
        }
        blueprintBuilder.validate();
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            LOADED_MARKERS.remove(this);
        }
        super.setRemoved();
        blueprintBuilder.invalidate();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            LOADED_MARKERS.add(this);
            reloadSnapshotFromItem(invBlueprint.getStackInSlot(0), null, true);
            sendNetworkUpdate(NET_RENDER_DATA);
        }
    }

    @Override
    public void onChunkUnloaded() {
        if (level != null && !level.isClientSide) {
            LOADED_MARKERS.remove(this);
        }
        super.onChunkUnloaded();
    }

    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER && id == NET_RENDER_DATA) {
            buffer.writeEnum(direction);
            ItemStackUtil.writeOptional(buffer, invBlueprint.getStackInSlot(0));
            buffer.writeBoolean(blueprintBuildingInfo != null);
            if (blueprintBuildingInfo != null) {
                blueprintBuilder.writeToByteBuf(buffer);
            }
            currentBox.writeData(buffer);
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT && id == NET_RENDER_DATA) {
            direction = buffer.readEnum(Direction.class);
            clientBlueprint = ItemStackUtil.readOptional(buffer);
            if (buffer.readBoolean()) {
                blueprintBuilder.readFromByteBuf(buffer);
            } else {
                blueprintBuilder.cancel();
            }
            currentBox.readData(buffer);
            redrawBlock();
        }
    }

    @Override
    public void writeData(BCValueOutput bcData) {
        CompoundTag nbt = bcData.tag();
        super.writeData(bcData);
        nbt.put("direction", NBTUtilBC.writeEnum(direction));
        nbt.put("rotation", NBTUtilBC.writeEnum(rotation));
        bcData.writeBoolean("needMaterial", needMaterial);
        bcData.writeBoolean("canRotate", canRotate);
        bcData.writeBoolean("canExcavate", canExcavate);
        nbt.put("box", currentBox.writeToNBT());
    }

    @Override
    protected void readData(BCValueInput bcData) {
        CompoundTag nbt = bcData.tag();
        super.readData(bcData);
        direction = NBTUtilBC.readEnum(nbt.get("direction"), Direction.class);
        if (direction == null || direction.getAxis().isVertical()) {
            direction = Direction.NORTH;
        }
        rotation = NBTUtilBC.readEnum(nbt.get("rotation"), Rotation.class);
        if (rotation == null) {
            rotation = Rotation.NONE;
        }
        needMaterial = !bcData.has("needMaterial") || bcData.readBoolean("needMaterial");
        canRotate = !bcData.has("canRotate") || bcData.readBoolean("canRotate");
        canExcavate = !bcData.has("canExcavate") || bcData.readBoolean("canExcavate");
        if (bcData.has("box")) {
            currentBox.initialize(bcData.readCompound("box"));
        }
    }

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        left.add("direction = " + direction);
        left.add("rotation = " + rotation);
        left.add("needMaterial = " + needMaterial);
        left.add("canRotate = " + canRotate);
        left.add("canExcavate = " + canExcavate);
        left.add("hasBlueprint = " + !invBlueprint.getStackInSlot(0).isEmpty());
    }

    public boolean canRobotsBuild() {
        return level != null
            && !level.isClientSide
            && !isRemoved()
            && blueprintBuildingInfo != null
            && !invBlueprint.getStackInSlot(0).isEmpty();
    }

    public List<RobotBuildTask> reserveRobotBuildTasks(EntityRobotBase robot, int maxItems) {
        if (!canRobotsBuild()) {
            return Collections.emptyList();
        }
        return blueprintBuilder.reserveNextRobotTasks(robot, needMaterial, maxItems);
    }

    public boolean buildRobotTask(EntityRobotBase robot, RobotBuildTask task) {
        if (!canRobotsBuild()) {
            releaseRobotBuildTask(robot, task);
            return false;
        }
        return blueprintBuilder.buildRobotTask(robot, task);
    }

    @Override
    public RobotBuildResult buildRobotTaskResult(EntityRobotBase robot, RobotBuildTask task) {
        if (!canRobotsBuild()) {
            releaseRobotBuildTask(robot, task);
            return RobotBuildResult.FAILED;
        }
        return blueprintBuilder.buildRobotTaskResult(robot, task);
    }

    public void releaseRobotBuildTask(EntityRobotBase robot, RobotBuildTask task) {
        blueprintBuilder.releaseRobotTask(robot, task);
    }
    public Box getBox() {
        return currentBox;
    }

    @Nonnull
    public AABB getRenderBoundingBox() {
        return BoundingBoxUtil.makeFrom(getBlockPos(), getBox());
    }

    @Override
    public Level getWorldBC() {
        return level;
    }

    @Override
    public MjBattery getBattery() {
        return battery;
    }

    @Override
    public BlockPos getBuilderPos() {
        return worldPosition;
    }

    @Override
    public boolean canExcavate() {
        return canExcavate;
    }

    @Override
    public SnapshotBuilder<?> getBuilder() {
        if (blueprintBuildingInfo != null || (level != null && level.isClientSide && !clientBlueprint.isEmpty())) {
            return blueprintBuilder;
        }
        return null;
    }

    @Override
    public Blueprint.BuildingInfo getBlueprintBuildingInfo() {
        return blueprintBuildingInfo;
    }

    @Override
    public IItemTransactor getInvResources() {
        return NoSpaceTransactor.INSTANCE;
    }

    @Override
    public TankManager getTankManager() {
        return tankManager;
    }

    @Override
    public boolean needMeterial() {
        return needMaterial;
    }
}
