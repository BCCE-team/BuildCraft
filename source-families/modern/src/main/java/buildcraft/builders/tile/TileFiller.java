/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.tile;

import buildcraft.lib.platform.storage.MutableItemStorage;
import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import buildcraft.api.v2.energy.MjAmount;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.area.IAreaProvider;
import buildcraft.lib.internal.area.IBox;
import buildcraft.builders.internal.filler.legacy.IFillerPattern;
import buildcraft.lib.internal.inventory.IItemTransactor;
import buildcraft.lib.internal.mj.MjBattery;
import buildcraft.lib.internal.mj.MjCapabilityHelper;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.statement.containers.IFillerStatementContainer;
import buildcraft.lib.internal.tiles.IControllable;
import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.lib.internal.tiles.TilesAPI;
import buildcraft.builders.BCBuildersBlocks;
import buildcraft.builders.addon.AddonFillerPlanner;
import buildcraft.builders.filler.FillerType;
import buildcraft.builders.filler.FillerUtil;
import buildcraft.builders.menu.ContainerFiller;
import buildcraft.builders.snapshot.FillerTemplateBuilder;
import buildcraft.builders.snapshot.ITileForTemplateBuilder;
import buildcraft.builders.snapshot.SnapshotBuilder;
import buildcraft.builders.snapshot.Template;
import buildcraft.builders.snapshot.Template.BuildingInfo;
import buildcraft.builders.snapshot.TemplateBuilder;
import buildcraft.core.marker.volume.ClientVolumeBoxes;
import buildcraft.core.marker.volume.EnumAddonSlot;
import buildcraft.core.marker.volume.Lock;
import buildcraft.core.marker.volume.VolumeBox;
import buildcraft.core.marker.volume.WorldSavedDataVolumeBoxes;
import buildcraft.lib.block.BlockBCBase_Neptune;
import buildcraft.lib.misc.BoundingBoxUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.data.Box;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.internal.mj.MjBatteryReceiver;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.statement.FullStatement;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.tile.item.ItemHandlerManager.EnumAccess;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class TileFiller extends TileBC_Neptune 
    implements IDebuggable, ITileForTemplateBuilder, IFillerStatementContainer, IControllable, MenuProvider {
    public static final IdAllocator IDS = TileBC_Neptune.IDS.makeChild("filler");
    public static final int NET_CAN_EXCAVATE = IDS.allocId("CAN_EXCAVATE");
    public static final int NET_INVERT = IDS.allocId("INVERT");
    public static final int NET_PATTERN = IDS.allocId("PATTERN");
    public static final int NET_BOX = IDS.allocId("BOX");

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    public final ItemHandlerSimple invResources =
        itemManager.addInvHandler(
            "resources",
            27,
            (slot, stack) -> stack.getItem() instanceof BlockItem,
            EnumAccess.INSERT,
            EnumPipePart.VALUES
        );
    private final MjBattery battery = new MjBattery(16000 * MjAmount.MICRO_MJ_PER_MJ);
    private boolean canExcavate = true;
    public boolean inverted = false;
    private boolean finished = false;
    private byte lockedTicks = 0;
    private Mode mode = Mode.ON;

    public final Box box = new Box();
    public AddonFillerPlanner addon;
    public boolean markerBox = true;

    public final FullStatement<IFillerPattern> patternStatement = new FullStatement<>(
        FillerType.INSTANCE,
        4,
        (statement, paramIndex) -> onStatementChange()
    );
    /** Send task progress every tick so flying blocks are interpolated continuously instead of jumping every 0.5 s. */
    private static final int RENDER_UPDATE_INTERVAL = 1;
    private int renderUpdateCooldown = RENDER_UPDATE_INTERVAL;
    private BuildingInfo buildingInfo;
    public TemplateBuilder builder = new FillerTemplateBuilder(this);

    // BlockEntity NBT is read before the level/volume-box registry is guaranteed to be available.
    // Defer state that depends on a valid BuildingInfo until onLoad().
    private CompoundTag pendingBuilderNbt;
    private UUID pendingAddonVolumeBoxId;
    private EnumAddonSlot pendingAddonSlot;

    public TileFiller(BlockPos pos, BlockState state) {
    	super(BCBuildersBlocks.FILLER_TILE_BC8.get(), pos, state);
        caps.addProvider(new MjCapabilityHelper(new MjBatteryReceiver(battery)));
        caps.addCapabilityInstance(TilesAPI.CAP_CONTROLLABLE, this, EnumPipePart.VALUES);
    }

    @Override
    public void onPlacedBy(LivingEntity placer, ItemStack stack) {
        super.onPlacedBy(placer, stack);
        if (level.isClientSide) {
            return;
        }
        refreshAreaFromMarkers(placer);
    }

    /**
     * Tries to attach this filler to a marker or volume box next to it.
     *
     * The facing-adjacent position has priority, then every other side is checked for a valid marker or volume box.
     * This keeps directional placement deterministic while accepting any directly adjacent area definition.
     */
    public boolean refreshAreaFromMarkers(@Nullable LivingEntity placer) {
        if (level == null || level.isClientSide || hasBox()) {
            return hasBox();
        }

        BlockState blockState = level.getBlockState(worldPosition);
        WorldSavedDataVolumeBoxes volumeBoxes = WorldSavedDataVolumeBoxes.get(level);
        Direction preferred = blockState.hasProperty(BlockBCBase_Neptune.PROP_FACING)
            ? blockState.getValue(BlockBCBase_Neptune.PROP_FACING).getOpposite()
            : Direction.NORTH;

        Direction[] searchOrder = new Direction[] {
            preferred,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST,
            Direction.UP,
            Direction.DOWN
        };

        for (Direction direction : searchOrder) {
            BlockPos offsetPos = worldPosition.relative(direction);
            VolumeBox volumeBox = volumeBoxes.getVolumeBoxAt(offsetPos);
            if (volumeBox != null && attachToVolumeBox(volumeBoxes, volumeBox, blockState)) {
                updateBuildingInfo();
                setChanged();
                sendNetworkUpdate(NET_RENDER_DATA);
                return true;
            }

            BlockEntity tile = level.getBlockEntity(offsetPos);
            if (tile instanceof IAreaProvider provider) {
                box.reset();
                box.setMin(provider.min());
                box.setMax(provider.max());
                provider.removeFromWorld(placer instanceof Player player ? player : null);
                markerBox = true;
                updateBuildingInfo();
                setChanged();
                sendNetworkUpdate(NET_RENDER_DATA);
                return true;
            }
        }

        updateBuildingInfo();
        setChanged();
        sendNetworkUpdate(NET_RENDER_DATA);
        return hasBox();
    }

    private boolean attachToVolumeBox(WorldSavedDataVolumeBoxes volumeBoxes, VolumeBox volumeBox, BlockState blockState) {
        addon = (AddonFillerPlanner) volumeBox.addons
            .values()
            .stream()
            .filter(AddonFillerPlanner.class::isInstance)
            .findFirst()
            .orElse(null);
        if (addon != null) {
            volumeBox.locks.add(
                new Lock(
                    new Lock.Cause.CauseBlock(worldPosition, blockState.getBlock()),
                    new Lock.Target.TargetAddon(addon.getSlot()),
                    new Lock.Target.TargetRemove(),
                    new Lock.Target.TargetResize(),
                    new Lock.Target.TargetUsedByMachine(
                        Lock.Target.TargetUsedByMachine.EnumType.STRIPES_WRITE
                    )
                )
            );
            volumeBoxes.setDirty();
            addon.updateBuildingInfo();
            markerBox = false;
            return true;
        }

        box.reset();
        box.setMin(volumeBox.box.min());
        box.setMax(volumeBox.box.max());
        volumeBox.locks.add(
            new Lock(
                new Lock.Cause.CauseBlock(worldPosition, blockState.getBlock()),
                new Lock.Target.TargetRemove(),
                new Lock.Target.TargetResize(),
                new Lock.Target.TargetUsedByMachine(
                    Lock.Target.TargetUsedByMachine.EnumType.STRIPES_WRITE
                )
            )
        );
        volumeBoxes.setDirty();
        markerBox = false;
        return true;
    }

    @Override
    protected void onSlotChange(MutableItemStorage handler,
                                int slot,
                                @Nonnull ItemStack before,
                                @Nonnull ItemStack after) {
        if (!level.isClientSide) {
            if (handler == invResources) {
                Optional.ofNullable(getBuilder()).ifPresent(SnapshotBuilder::resourcesChanged);
            }
        }
        super.onSlotChange(handler, slot, before, after);
    }

    @Override
    public void update() {
        if (level.isClientSide) {
            if (isValid()) {
                builder.tick();
            }
            patternStatement.canInteract = !isLocked();
            return;
        }
        battery.tick(level, worldPosition);
        if (--renderUpdateCooldown <= 0) {
            renderUpdateCooldown = RENDER_UPDATE_INTERVAL;
            sendNetworkUpdate(NET_RENDER_DATA);
        }
        lockedTicks--;
        if (lockedTicks < 0) {
            lockedTicks = 0;
        }
        if (mode == Mode.OFF || (mode == Mode.ON && finished)) {
            return;
        }
        if (battery.getStored() < MjAmount.MICRO_MJ_PER_MJ) {
            return;
        }
        SnapshotBuilder<?> currentBuilder = getBuilder();
        if (currentBuilder != null) {
            boolean nowFinished = currentBuilder.tick();
            if (finished != nowFinished) {
                finished = nowFinished;
                setChanged();
                sendNetworkGuiUpdate(NET_GUI_TICK);
            }
        }
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        builder.validate();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        builder.invalidate();
    }

    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_RENDER_DATA) {
                builder.writeToByteBuf(buffer);
                writePayload(NET_BOX, buffer, side);
            } else if (id == NET_GUI_DATA || id == NET_GUI_TICK) {
                writePayload(NET_CAN_EXCAVATE, buffer, side);
                writePayload(NET_INVERT, buffer, side);
                writePayload(NET_PATTERN, buffer, side);
                writePayload(NET_BOX, buffer, side);
                builder.writeToByteBuf(buffer);
                buffer.writeBoolean(finished);
                buffer.writeBoolean(lockedTicks > 0);
                buffer.writeEnum(mode);
            } else if (id == NET_BOX) {
                box.writeData(buffer);
                buffer.writeBoolean(markerBox);
                buffer.writeBoolean(addon != null);
                if (addon != null) {
                    buffer.writeUUID(addon.volumeBox.id);
                    buffer.writeEnum(addon.getSlot());
                }
            } else if (id == NET_CAN_EXCAVATE) {
                buffer.writeBoolean(canExcavate);
            } else if (id == NET_INVERT) {
                buffer.writeBoolean(inverted);
            } else if (id == NET_PATTERN) {
                patternStatement.writeToBuffer(buffer);
            }
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_RENDER_DATA) {
                builder.readFromByteBuf(buffer);
                readPayload(NET_BOX, buffer, side, ctx);
            } else if (id == NET_GUI_DATA || id == NET_GUI_TICK) {
                readPayload(NET_CAN_EXCAVATE, buffer, side, ctx);
                readPayload(NET_INVERT, buffer, side, ctx);
                readPayload(NET_PATTERN, buffer, side, ctx);
                readPayload(NET_BOX, buffer, side, ctx);
                builder.readFromByteBuf(buffer);
                finished = buffer.readBoolean();
                lockedTicks = buffer.readBoolean() ? (byte) 1 : (byte) 0;
                mode = buffer.readEnum(Mode.class);
            } else if (id == NET_BOX) {
                box.readData(buffer);
                markerBox = buffer.readBoolean();
                if (buffer.readBoolean()) {
                    UUID volumeBoxId = buffer.readUUID();
                    EnumAddonSlot slot = buffer.readEnum(EnumAddonSlot.class);
                    VolumeBox volumeBox = level.isClientSide
                        ? ClientVolumeBoxes.INSTANCE.volumeBoxes.stream()
                            .filter(localVolumeBox -> localVolumeBox.id.equals(volumeBoxId))
                            .findFirst()
                            .orElse(null)
                        : WorldSavedDataVolumeBoxes.get(level).getVolumeBoxFromId(volumeBoxId);
                    addon = getFillerAddon(volumeBox, slot);
                } else {
                    addon = null;
                }
            } else if (id == NET_CAN_EXCAVATE) {
                canExcavate = buffer.readBoolean();
            } else if (id == NET_INVERT) {
                inverted = buffer.readBoolean();
            } else if (id == NET_PATTERN) {
                patternStatement.readFromBuffer(buffer);
            }
        }
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_CAN_EXCAVATE) {
                canExcavate = buffer.readBoolean();
                sendNetworkGuiUpdate(NET_CAN_EXCAVATE);
            }
        }
    }

    @Nullable
    private AddonFillerPlanner getFillerAddon(@Nullable VolumeBox volumeBox, @Nullable EnumAddonSlot slot) {
        if (volumeBox == null || slot == null) {
            return null;
        }
        Object candidate = volumeBox.addons.get(slot);
        if (candidate instanceof AddonFillerPlanner planner) {
            return planner;
        }
        if (candidate != null) {
            BCLog.logger.warn("Ignoring non-filler addon " + candidate.getClass().getName() + " in filler box "
                + volumeBox.id + " slot " + slot);
        }
        return null;
    }

    private void updateBuildingInfo() {
        Optional.ofNullable(getBuilder()).ifPresent(SnapshotBuilder::cancel);
        buildingInfo = (hasBox() && addon == null) ? FillerUtil.createBuildingInfo(
            this,
            patternStatement,
            IntStream.range(0, patternStatement.maxParams)
                .mapToObj(patternStatement::get)
                .toArray(IStatementParameter[]::new),
            inverted
        ) : null;
        // On a remote client the marker box can already be initialized while the default "none" pattern
        // still produces no BuildingInfo. getBuilder() intentionally remains available client-side for task
        // rendering, so gate snapshot initialization on the actual effective BuildingInfo instead.
        if (getTemplateBuildingInfo() != null) {
            Optional.ofNullable(getBuilder()).ifPresent(SnapshotBuilder::updateSnapshot);
        }
    }

    public void sendCanExcavate(boolean newValue) {
        MessageManager.sendToServer(createMessage(NET_CAN_EXCAVATE, buffer -> buffer.writeBoolean(newValue)));
    }

    public void onStatementChange() {
        if (!level.isClientSide) {
            createAndSendMessage(NET_PATTERN, patternStatement::writeToBuffer);
        }
        finished = false;
        updateBuildingInfo();
        setChanged();
    }

    // Read-write


	public void writeData(BCValueOutput bcData) {
        CompoundTag nbt = bcData.tag();
        HolderLookup.Provider registries = bcData.registries();
		super.writeData(bcData);
        nbt.put("battery", battery.serializeNBT(registries));
        bcData.writeBoolean("canExcavate", canExcavate);
        bcData.writeBoolean("inverted", inverted);
        bcData.writeBoolean("finished", finished);
        bcData.writeByte("lockedTicks", lockedTicks);
        nbt.put("mode", NBTUtilBC.writeEnum(mode));
        nbt.put("box", box.writeToNBT());
        if (addon != null) {
            bcData.writeUUID("addonVolumeBoxId", addon.volumeBox.id);
            nbt.put("addonSlot", NBTUtilBC.writeEnum(addon.getSlot()));
        } else if (pendingAddonVolumeBoxId != null && pendingAddonSlot != null) {
            bcData.writeUUID("addonVolumeBoxId", pendingAddonVolumeBoxId);
            nbt.put("addonSlot", NBTUtilBC.writeEnum(pendingAddonSlot));
        }
        bcData.writeBoolean("markerBox", markerBox);
        nbt.put("patternStatement", patternStatement.writeToNbt());
        if (pendingBuilderNbt != null) {
            nbt.put("builder", pendingBuilderNbt.copy());
        } else {
            Optional.ofNullable(getBuilder()).ifPresent(builder -> nbt.put("builder", builder.serializeNBT()));
        }
	}

	@Override
	protected void readData(BCValueInput bcData) {
        CompoundTag nbt = bcData.tag();
        HolderLookup.Provider registries = bcData.registries();
		super.readData(bcData);
        battery.deserializeNBT(registries, bcData.readCompound("battery"));
        canExcavate = bcData.readBoolean("canExcavate");
        inverted = bcData.readBoolean("inverted");
        finished = bcData.readBoolean("finished");
        lockedTicks = bcData.readByte("lockedTicks");
        mode = Optional.ofNullable(NBTUtilBC.readEnum(nbt.get("mode"), Mode.class)).orElse(Mode.ON);
        box.initialize(bcData.readCompound("box"));
        if (bcData.has("addonSlot") && bcData.has("addonVolumeBoxId")) {
            pendingAddonVolumeBoxId = bcData.readUUID("addonVolumeBoxId");
            pendingAddonSlot = NBTUtilBC.readEnum(nbt.get("addonSlot"), EnumAddonSlot.class);
        } else {
            pendingAddonVolumeBoxId = null;
            pendingAddonSlot = null;
        }
        addon = null;
        markerBox = bcData.readBoolean("markerBox");
        patternStatement.readFromNbt(bcData.readCompound("patternStatement"));
        pendingBuilderNbt = bcData.has("builder") ? bcData.readCompound("builder").copy() : null;
	}
	
    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && pendingAddonVolumeBoxId != null && pendingAddonSlot != null) {
            VolumeBox volumeBox = WorldSavedDataVolumeBoxes.get(level).getVolumeBoxFromId(pendingAddonVolumeBoxId);
            addon = getFillerAddon(volumeBox, pendingAddonSlot);
        }
        pendingAddonVolumeBoxId = null;
        pendingAddonSlot = null;

        // BuildingInfo must exist before SnapshotBuilder can restore/cancel saved tasks and refund
        // their reserved items/MJ. Doing this in load() silently skipped the whole rollback path.
        updateBuildingInfo();
        if (pendingBuilderNbt != null) {
            TemplateBuilder activeBuilder = getBuilder();
            if (activeBuilder != null) {
                activeBuilder.deserializeNBT(pendingBuilderNbt);
            }
            pendingBuilderNbt = null;
        }
    }

    // Rendering

    @Nonnull
    public AABB getRenderBoundingBox() {
        return BoundingBoxUtil.makeFrom(worldPosition, addon != null ? addon.volumeBox.box : box);
    }
/*
    @Override
    public double getMaxRenderDistanceSquared() {
        return Double.MAX_VALUE;
    }*/

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        left.add("battery = " + battery.getDebugString());
        left.add("box = " + box);
        left.add("pattern = " + patternStatement.get());
        left.add("mode = " + mode);
        left.add("is_finished = " + finished);
        left.add("lockedTicks = " + lockedTicks);
        left.add("addon = " + addon);
        left.add("markerBox = " + markerBox);
    }

    @Override
    public Level getWorldBC() {
        return level;
    }

    public int getCountToPlace() {
        return builder == null ? 0 : builder.leftToPlace;
    }

    public int getCountToBreak() {
        return builder == null ? 0 : builder.leftToBreak;
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

    public boolean isFinished() {
        return mode != Mode.LOOP && this.finished;
    }

    public boolean isLocked() {
        return lockedTicks > 0;
    }

    @Override
    public TemplateBuilder getBuilder() {
        return isValid() ? builder : null;
    }

    @Override
    public Template.BuildingInfo getTemplateBuildingInfo() {
        return isValid()
            ? addon != null ? addon.buildingInfo : buildingInfo
            : null;
    }

    @Override
    public IItemTransactor getInvResources() {
        return invResources;
    }

    // IFillerStatementContainer

    @Override
    public BlockEntity getTile() {
        return this;
    }

    @Override
    public Level getFillerWorld() {
        return level;
    }

    @Override
    public boolean hasBox() {
        return addon != null || box.isInitialized();
    }

    public boolean isValid() {
        return hasBox() && ((level != null &&level.isClientSide) || (addon != null ? addon.buildingInfo : buildingInfo) != null);
    }

    @Override
    public IBox getBox() {
        if (!hasBox()) {
            throw new IllegalStateException("Called getBox() when hasBox() returned false!");
        }
        return addon != null ? addon.volumeBox.box : box;
    }

    @Override
    public void setPattern(IFillerPattern pattern, IStatementParameter[] params) {
        patternStatement.set(pattern, params);
        finished = false;
        lockedTicks = 3;
    }

    // IControllable

    @Override
    public Mode getControlMode() {
        return mode;
    }

    @Override
    public void setControlMode(Mode mode) {
        if (this.mode == Mode.OFF && mode != Mode.OFF) {
            finished = false;
        }
        this.mode = mode;
        setChanged();
    }

	@Override
	public ContainerFiller createMenu(int id, Inventory inv, Player player) {
		return new ContainerFiller(id, inv, invResources, ContainerLevelAccess.create(level, worldPosition));
	}

	@Override
	public Component getDisplayName() {
		return this.getBlockState().getBlock().getName();
	}

	@Override
	public boolean needMeterial() {
		return true;
	}
}
