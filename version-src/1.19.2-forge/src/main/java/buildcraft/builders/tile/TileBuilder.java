/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package buildcraft.builders.tile;

import buildcraft.api.v2.energy.MjAmount;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import org.jetbrains.annotations.NotNull;

import com.google.common.collect.ImmutableList;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.area.IPathProvider;
import buildcraft.lib.internal.core.SafeTimeTracker;
import buildcraft.lib.internal.enums.EnumOptionalSnapshotType;
import buildcraft.lib.internal.enums.EnumSnapshotType;
import buildcraft.lib.internal.inventory.IItemTransactor;
import buildcraft.lib.internal.mj.MjBattery;
import buildcraft.lib.internal.mj.MjCapabilityHelper;
import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.lib.internal.tiles.TilesAPI;
import buildcraft.builders.BCBuildersBlocks;
import buildcraft.core.BCCoreConfig;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.menu.ContainerBuilder;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.BlueprintBuilder;
import buildcraft.builders.snapshot.BlueprintBuilder.RobotBuildResult;
import buildcraft.builders.snapshot.BlueprintBuilder.RobotBuildTask;
import buildcraft.builders.snapshot.GlobalSavedDataSnapshots;
import buildcraft.builders.snapshot.ITileForBlueprintBuilder;
import buildcraft.builders.snapshot.ITileForTemplateBuilder;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.snapshot.SnapshotBuilder;
import buildcraft.builders.snapshot.Template;
import buildcraft.builders.snapshot.TemplateBuilder;
import buildcraft.lib.block.BlockBCBase_Neptune;
import buildcraft.lib.fluid.Tank;
import buildcraft.lib.fluid.TankManager;
import buildcraft.lib.gui.ItemProvider;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.BoundingBoxUtil;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.MessageUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.PositionUtil;
import buildcraft.lib.misc.data.Box;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.internal.mj.MjBatteryReceiver;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.tile.item.ItemHandlerManager.EnumAccess;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent.Message;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fluids.FluidType;
import buildcraft.lib.net.BCNetworkSide;
import net.minecraftforge.items.IItemHandlerModifiable;
import buildcraft.lib.net.BCPacketContext;

public class TileBuilder extends TileBC_Neptune implements IDebuggable, ITileForTemplateBuilder, ITileForBlueprintBuilder, IRobotBuilderTarget, MenuProvider {
    public static final IdAllocator IDS = TileBC_Neptune.IDS.makeChild("builder");
    public static final int NET_CAN_EXCAVATE = IDS.allocId("CAN_EXCAVATE");
    public static final int NET_SNAPSHOT_TYPE = IDS.allocId("SNAPSHOT_TYPE");
    private static final ResourceLocation ADVANCEMENT = new ResourceLocation("buildcraftbuilders:paving_the_way");
    private static final Set<TileBuilder> LOADED_BUILDERS = Collections.newSetFromMap(new WeakHashMap<>());

    public final ItemHandlerSimple invSnapshot =
        itemManager
            .addInvHandler("snapshot", 1,
                (slot, stack) -> stack.getItem() instanceof ItemSnapshot
                    && ItemSnapshot.EnumItemSnapshotType.getFromStack(stack).used,
                EnumAccess.BOTH, EnumPipePart.VALUES);
    public final ItemHandlerSimple invResources =
        itemManager.addInvHandler("resources", 27, EnumAccess.BOTH, EnumPipePart.VALUES);
    public final ItemProvider invRequire = new ItemProvider(this::getDisplay, 24);

    private final MjBattery battery = new MjBattery(16000 * MjAmount.MICRO_MJ_PER_MJ);

    /** Stores the real path - just a few block positions. */
    public List<BlockPos> path = null;
    /** Stores the real path plus all possible block positions inbetween. */
    private List<BlockPos> basePoses = new ArrayList<>();
    private int currentBasePosIndex = 0;
    private Snapshot snapshot = null;
    public EnumSnapshotType snapshotType = null;
    private Template.BuildingInfo templateBuildingInfo = null;
    private Blueprint.BuildingInfo blueprintBuildingInfo = null;
    public TemplateBuilder templateBuilder = new TemplateBuilder(this);
    public BlueprintBuilder blueprintBuilder = new BlueprintBuilder(this);
    private boolean needsRestartAfterLoad = false;
    private CompoundTag pendingBuilderState;
    private Box currentBox = new Box();
    @NotNull
    private Rotation rotation = Rotation.NONE;
    
    private boolean isDone = false;

    private final SafeTimeTracker renderSyncTracker = new SafeTimeTracker(BCCoreConfig.networkUpdateRate);
    private int lastRenderStructureFingerprint = Integer.MIN_VALUE;
    private int lastRenderDataFingerprint = Integer.MIN_VALUE;

    private boolean shouldInit = false;
    
    private boolean needMaterial = true;
    private boolean canRotate = true;
    private boolean canExcavate = true;
    
    private DataSlot menuSetting = new DataSlot() {
		
		@Override
		public void set(int p) {
            boolean oldNeedMaterial = needMaterial;
            boolean oldCanRotate = canRotate;
            boolean oldCanExcavate = canExcavate;

			needMaterial = (p&0b1) == 1;
			canRotate = (p&0b10) == 0b10;
			canExcavate = (p&0b100) == 0b100;

            if (level != null && !level.isClientSide) {
                if (oldNeedMaterial != needMaterial) {
                    Optional.ofNullable(getBuilder()).ifPresent(SnapshotBuilder::resourcesChanged);
                }
                if (oldCanRotate != canRotate) {
                    reloadSnapshotFromItem(invSnapshot.getStackInSlot(0), false, true);
                }
                if (oldCanExcavate != canExcavate) {
                    Optional.ofNullable(getBuilder()).ifPresent(SnapshotBuilder::forceRecheckCurrentTask);
                    sendNetworkUpdate(NET_CAN_EXCAVATE);
                }
            }
		}
		
		@Override
		public int get() {
			return (needMaterial ? 1 : 0) | (canRotate ? 0b10 : 0) | (canExcavate ? 0b100 : 0);
		}
	};
    public final GameEventListener worldEventListener = new GameEventListener() {
    	
    	GameEventListener blueprint = blueprintBuilder.getListener();
    	GameEventListener template = templateBuilder.getListener();
    	@Override
    	public boolean handleEventsImmediately() {
    		return true;
    	}
    	@Override
    	public PositionSource getListenerSource() {
    		return blueprint.getListenerSource();
    	}
    	@Override
    	public int getListenerRadius() {
    		return 64;
    	}
    	@Override
    	public boolean handleGameEvent(ServerLevel level, Message msg) {
    		return blueprint.handleGameEvent(level, msg) || template.handleGameEvent(level, msg);
    	}
    };

    public TileBuilder(BlockPos pos, BlockState state) {
    	super(BCBuildersBlocks.BUILDER_TILE_BC8.get(), pos, state);
    	Tank[] tanks = new Tank[4];
        for (int i = 0; i < 4; i++) {
            tanks[i] = new Tank(("tank" + (i+1)), FluidType.BUCKET_VOLUME * 8, this) {
                @Override
                protected void onContentsChanged() {
                    super.onContentsChanged();
                    Optional.ofNullable(getBuilder()).ifPresent(SnapshotBuilder::resourcesChanged);
                }
            };
            tankManager.add(tanks[i]);
        }
        caps.addProvider(new MjCapabilityHelper(new MjBatteryReceiver(battery)));
        caps.addFluidStorage(tankManager, EnumPipePart.VALUES);
        caps.addCapabilityInstance(TilesAPI.CAP_HAS_WORK, () -> !invSnapshot.isEmpty(), EnumPipePart.VALUES);
    }

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    @Override
    protected void onSlotChange(IItemHandlerModifiable handler, int slot, @Nonnull ItemStack before,
        @Nonnull ItemStack after) {
        if (!level.isClientSide) {
            if (handler == invSnapshot) {
                needsRestartAfterLoad = false;
                reloadSnapshotFromItem(after, true, true);
                applySnapshotSettingsFromInsertedItem(after, null);
                sendNetworkUpdate(NET_SNAPSHOT_TYPE);
            }
            if (handler == invResources) {
                Optional.ofNullable(getBuilder()).ifPresent(SnapshotBuilder::resourcesChanged);
            }
        }
        super.onSlotChange(handler, slot, before, after);
    }

    private void reloadSnapshotFromItem(ItemStack stack, boolean resetBasePosIndex, boolean canGetFacing) {
        if (resetBasePosIndex) {
            currentBasePosIndex = 0;
        }
        if (level == null) {
            return;
        }
        if (basePoses.isEmpty()) {
            updateBasePoses();
        }
        if (!basePoses.isEmpty()) {
            currentBasePosIndex = Math.max(0, Math.min(currentBasePosIndex, basePoses.size() - 1));
        }

        snapshot = null;
        if (stack.getItem() instanceof ItemSnapshot) {
            Snapshot.Header header = ItemSnapshot.getHeader(stack);
            if (header != null) {
                Snapshot newSnapshot = GlobalSavedDataSnapshots.get(level).getSnapshot(header.key);
                if (newSnapshot != null) {
                    snapshot = newSnapshot;
                }
            }
        }
        updateSnapshot(canGetFacing);
    }

    public void applySnapshotSettingsFromInsertedItem(ItemStack stack, Player player) {
        if (level == null || level.isClientSide) {
            return;
        }
        Snapshot.Header header = ItemSnapshot.getHeader(stack);
        if (header == null) {
            return;
        }

        boolean oldNeedMaterial = needMaterial;
        boolean oldCanRotate = canRotate;
        boolean oldCanExcavate = canExcavate;

        // The blueprint only permits creative building if the architect-table author allowed it.
        // The actual no-material mode still depends on the gamemode of the player who inserted the blueprint.
        needMaterial = !(header.allowCreative && player != null && player.isCreative());
        canRotate = header.canRotate;
        canExcavate = header.canExcavate;

        if (oldCanRotate != canRotate) {
            reloadSnapshotFromItem(stack, false, true);
        }
        if (oldNeedMaterial != needMaterial) {
            Optional.ofNullable(getBuilder()).ifPresent(SnapshotBuilder::resourcesChanged);
        }
        if (oldCanExcavate != canExcavate) {
            Optional.ofNullable(getBuilder()).ifPresent(SnapshotBuilder::forceRecheckCurrentTask);
            sendNetworkUpdate(NET_CAN_EXCAVATE);
        }
        if (oldNeedMaterial != needMaterial || oldCanRotate != canRotate || oldCanExcavate != canExcavate) {
            setChanged();
        }
    }

    private void restartSnapshotAfterLoad() {
        if (level == null || level.isClientSide || !needsRestartAfterLoad) {
            return;
        }

        ItemStack stack = invSnapshot.getStackInSlot(0);
        if (stack.isEmpty()) {
            needsRestartAfterLoad = false;
            snapshot = null;
            snapshotType = null;
            templateBuildingInfo = null;
            blueprintBuildingInfo = null;
            currentBox = new Box();
            isDone = false;
            return;
        }

        // Recreate the runtime builder from the blueprint item, but retain the saved path position. Saved
        // active tasks are not trusted as world state; deserializeNBT returns their reserved resources and then
        // performs a fresh scan of the current area.
        needsRestartAfterLoad = false;
        isDone = false;
        updateBasePoses();
        reloadSnapshotFromItem(stack, false, true);
        SnapshotBuilder<?> restoredBuilder = getBuilder();
        if (restoredBuilder != null) {
            if (pendingBuilderState != null) {
                restoredBuilder.deserializeNBT(pendingBuilderState);
            } else {
                restoredBuilder.forceRecheckCurrentTask();
            }
        }
        pendingBuilderState = null;
        sendNetworkUpdate(NET_SNAPSHOT_TYPE);
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        if (level != null && !level.isClientSide) {
            LOADED_BUILDERS.add(this);
        }
        templateBuilder.validate();
        blueprintBuilder.validate();
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            LOADED_BUILDERS.remove(this);
        }
        super.setRemoved();
        templateBuilder.invalidate();
        blueprintBuilder.invalidate();
    }

    private void updateSnapshot(boolean canGetFacing) {
        Optional.ofNullable(getBuilder()).ifPresent(SnapshotBuilder::cancel);
        if (snapshot != null && getCurrentBasePos() != null) {
            snapshotType = snapshot.getType();
            if (canGetFacing && canRotate) {
                rotation = Arrays.stream(Rotation.values()).filter(r -> r.rotate(snapshot.facing) == level
                    .getBlockState(worldPosition).getValue(BlockBCBase_Neptune.PROP_FACING)).findFirst().orElse(Rotation.NONE);
            } else {
                rotation = Rotation.NONE;
            }
            BlockPos buildBasePos = getCurrentBasePos();
            if (!canRotate) {
                buildBasePos = adjustBasePosIfOverlappingBuilder(buildBasePos, rotation);
            }
            if (snapshot.getType() == EnumSnapshotType.TEMPLATE) {
                templateBuildingInfo = ((Template) snapshot).new BuildingInfo(buildBasePos, rotation);
            }
            if (snapshot.getType() == EnumSnapshotType.BLUEPRINT) {
                blueprintBuildingInfo = ((Blueprint) snapshot).new BuildingInfo(buildBasePos, rotation, level);
            }
            currentBox = Optional.ofNullable(getBuildingInfo()).map(buildingInfo -> buildingInfo.box).orElse(null);
            Optional.ofNullable(getBuilder()).ifPresent(SnapshotBuilder::updateSnapshot);
        } else {
            snapshotType = null;
            rotation = Rotation.NONE;
            templateBuildingInfo = null;
            blueprintBuildingInfo = null;
            currentBox = null;
        }
        if (currentBox == null) {
            currentBox = new Box();
        }
    }

    private BlockPos adjustBasePosIfOverlappingBuilder(BlockPos basePos, Rotation appliedRotation) {
        Snapshot.BuildingInfo buildingInfo = createBuildingInfo(basePos, appliedRotation);
        if (buildingInfo == null || !buildingInfo.box.contains(worldPosition)) {
            return basePos;
        }

        Direction buildDirection = level.getBlockState(worldPosition).getValue(BlockBCBase_Neptune.PROP_FACING).getOpposite();
        int maxShift = Math.max(1, Math.max(snapshot.size.getX(), snapshot.size.getZ())) + 1;
        BlockPos shiftedBasePos = basePos;
        for (int i = 0; i < maxShift; i++) {
            shiftedBasePos = shiftedBasePos.offset(buildDirection.getNormal());
            buildingInfo = createBuildingInfo(shiftedBasePos, appliedRotation);
            if (buildingInfo == null || !buildingInfo.box.contains(worldPosition)) {
                return shiftedBasePos;
            }
        }
        return shiftedBasePos;
    }

    private Snapshot.BuildingInfo createBuildingInfo(BlockPos basePos, Rotation appliedRotation) {
        if (snapshot == null || basePos == null) {
            return null;
        }
        if (snapshot.getType() == EnumSnapshotType.TEMPLATE) {
            return ((Template) snapshot).new BuildingInfo(basePos, appliedRotation);
        }
        if (snapshot.getType() == EnumSnapshotType.BLUEPRINT) {
            return ((Blueprint) snapshot).new BuildingInfo(basePos, appliedRotation, level);
        }
        return null;
    }

    private void updateBasePoses() {
        basePoses.clear();
        if (path != null) {
            int max = path.size() - 1;
            // Create a list of all the possible block positions on the path that could be used
            basePoses.add(path.get(0));
            for (int i = 1; i <= max; i++) {
                basePoses.addAll(PositionUtil.getAllOnPath(path.get(i - 1), path.get(i)));
            }
        } else {
            basePoses.add(worldPosition.offset(level.getBlockState(worldPosition).getValue(BlockBCBase_Neptune.PROP_FACING).getOpposite().getNormal()));
        }
    }

    private BlockPos getCurrentBasePos() {
        return currentBasePosIndex < basePoses.size() ? basePoses.get(currentBasePosIndex) : null;
    }
    
    @Override
    public void onPlacedBy(LivingEntity placer, ItemStack stack) {
        super.onPlacedBy(placer, stack);
        Direction facing = level.getBlockState(worldPosition).getValue(BlockBCBase_Neptune.PROP_FACING);
        BlockEntity inFront = level.getBlockEntity(worldPosition.offset(facing.getOpposite().getNormal()));
        if (inFront instanceof IPathProvider) {
            IPathProvider provider = (IPathProvider) inFront;
            ImmutableList<BlockPos> copiedPath = ImmutableList.copyOf(provider.getPath());
            if (copiedPath.size() >= 2) {
                path = copiedPath;
                provider.removeFromWorld(placer instanceof Player player ? player : null);
            }
        }
        updateBasePoses();
    }

    @Override
    public void update() {
        if (shouldInit) {
            updateBasePoses();
            shouldInit = false;
        }

        level.getProfiler().push("main");
        if (!level.isClientSide) {
            restartSnapshotAfterLoad();
        }

        SnapshotBuilder<?> builder = getBuilder();
        if (builder == null) {
            // Idle builders need no per-tick construction work. Only service an overfilled battery and render-state
            // changes; slot/NBT callbacks wake the real builder path immediately when a snapshot appears.
            if (!level.isClientSide && battery.getStored() > battery.getCapacity() * 2L) {
                battery.tick(getLevel(), getBlockPos());
            }
            if (!level.isClientSide) {
                syncRenderDataIfNeeded();
            }
            level.getProfiler().pop();
            return;
        }

        level.getProfiler().push("power");
        battery.tick(getLevel(), getBlockPos());
        level.getProfiler().popPush("builder");
        if (level.isClientSide) {
            // Client-side ticking only interpolates data that the server already sent.
            builder.tick();
        } else {
            builder.resetWorkRendering();
            if (battery.getStored() <= 0) {
                builder.stopRenderingForNoPower();
                isDone = false;
            } else {
                isDone = builder.tick();
            }
            if (isDone) {
                int nextBasePosIndex = findNextBasePosIndex();
                if (nextBasePosIndex >= 0) {
                    currentBasePosIndex = nextBasePosIndex;
                    updateSnapshot(true);
                } else {
                    finishCurrentSnapshot();
                }
            }
            syncRenderDataIfNeeded();
        }
        level.getProfiler().pop();
        level.getProfiler().pop();
    }

    private void syncRenderDataIfNeeded() {
        int structureFingerprint = getRenderStructureFingerprint();
        int dataFingerprint = getRenderDataFingerprint(structureFingerprint);
        if (dataFingerprint == lastRenderDataFingerprint) {
            return;
        }

        boolean structureChanged = structureFingerprint != lastRenderStructureFingerprint;
        if (structureChanged || renderSyncTracker.markTimeIfDelay(level)) {
            sendNetworkUpdate(NET_RENDER_DATA);
            lastRenderStructureFingerprint = structureFingerprint;
            lastRenderDataFingerprint = dataFingerprint;
            renderSyncTracker.markTime(level);
        }
    }

    private int getRenderStructureFingerprint() {
        int result = path == null ? 0 : path.hashCode();
        result = 31 * result + (snapshotType == null ? 0 : snapshotType.hashCode());
        result = 31 * result + currentBox.hashCode();
        result = 31 * result + Boolean.hashCode(canExcavate);
        SnapshotBuilder<?> builder = getBuilder();
        result = 31 * result + (builder == null ? 0 : builder.getRenderStructureFingerprint());
        return result;
    }

    private int getRenderDataFingerprint(int structureFingerprint) {
        SnapshotBuilder<?> builder = getBuilder();
        return 31 * structureFingerprint + (builder == null ? 0 : builder.getRenderDataFingerprint());
    }

    private int findNextBasePosIndex() {
        if (currentBasePosIndex >= basePoses.size() - 1 || currentBox == null || !currentBox.isInitialized()) {
            return -1;
        }
        BlockPos currentBasePos = getCurrentBasePos();
        if (currentBasePos == null) {
            return -1;
        }
        for (int i = currentBasePosIndex + 1; i < basePoses.size(); i++) {
            BlockPos newBasePos = basePoses.get(i);
            BlockPos dPos = newBasePos.subtract(currentBasePos);
            Box newBox = new Box(currentBox.min().offset(dPos), currentBox.max().offset(dPos));
            if (!currentBox.doesTouchWith(newBox)) {
                return i;
            }
        }
        return -1;
    }

    private void finishCurrentSnapshot() {
        if (basePoses.size() > 1 && getOwner() != null) {
            AdvancementUtil.unlockAdvancement(getOwner().getId(), ADVANCEMENT);
        }
        ItemStack blueprint = invSnapshot.extractItem(0, 1, false);
        if (!blueprint.isEmpty() && !invResources.insert(blueprint, true ,false).isEmpty()) {
            Containers.dropItemStack(level, getBlockPos().getX(), getBlockPos().getY()+1, getBlockPos().getZ(), blueprint);
        }
    }

    // Networking

    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_RENDER_DATA) {
                buffer.writeInt(path == null ? 0 : path.size());
                if (path != null) {
                    path.forEach((p) -> MessageUtil.writeBlockPos(buffer, p));
                }
                buffer.writeBoolean(snapshotType != null);
                if (snapshotType != null) {
                    buffer.writeEnum(snapshotType);
                    // noinspection ConstantConditions
                    getBuilder().writeToByteBuf(buffer);
                }
                currentBox.writeData(buffer);
                writePayload(NET_CAN_EXCAVATE, buffer, side);
                writePayload(NET_SNAPSHOT_TYPE, buffer, side);
            }
            if (id == NET_GUI_DATA || id == NET_GUI_TICK) {
                tankManager.writeData(buffer);
            }
            if (id == NET_CAN_EXCAVATE) {
                buffer.writeBoolean(canExcavate);
            }
            if (id == NET_SNAPSHOT_TYPE) {
                buffer.writeEnum(EnumOptionalSnapshotType.fromNullable(snapshotType));
            }
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
    	super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_RENDER_DATA) {
                path = new ArrayList<>();
                int pathSize = buffer.readInt();
                if (pathSize != 0) {
                    for (int i = 0; i < pathSize; i++) {
                        path.add(MessageUtil.readBlockPos(buffer));
                    }
                } else {
                    path = null;
                }
                //updateBasePoses();
                shouldInit = true;
                if (buffer.readBoolean()) {
                    snapshotType = buffer.readEnum(EnumSnapshotType.class);
                    getBuilder().readFromByteBuf(buffer);
                } else {
                    snapshotType = null;
                }
                currentBox.readData(buffer);
                readPayload(NET_CAN_EXCAVATE, buffer, side, ctx);
                readPayload(NET_SNAPSHOT_TYPE, buffer, side, ctx);
            }
            if (id == NET_GUI_DATA || id == NET_GUI_TICK) {
                tankManager.readData(buffer);
            }
            if (id == NET_CAN_EXCAVATE) {
                canExcavate = buffer.readBoolean();
            }
            if (id == NET_SNAPSHOT_TYPE) {
                EnumSnapshotType old = snapshotType;
                snapshotType = buffer.readEnum(EnumOptionalSnapshotType.class).type;
                if (old != snapshotType) {
                    redrawBlock();
                }
            }
        }
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_CAN_EXCAVATE) {
                canExcavate = buffer.readBoolean();
                sendNetworkUpdate(NET_CAN_EXCAVATE);
            }
        }
    }

    public void sendCanExcavate(boolean newValue) {
        MessageManager.sendToServer(createMessage(NET_CAN_EXCAVATE, buffer -> buffer.writeBoolean(newValue)));
    }

    // Read-write
    
    

    @Override
	public void saveAdditional(CompoundTag nbt) {
        super.saveAdditional(nbt);
        if (path != null) {
            nbt.put("path", NBTUtilBC.writeObjectList(path.stream().map(NbtUtils::writeBlockPos)));
        }
        nbt.put("basePoses", NBTUtilBC.writeObjectList(basePoses.stream().map(NbtUtils::writeBlockPos)));
        nbt.putInt("currentBasePosIndex", currentBasePosIndex);
        nbt.putBoolean("needMaterial", needMaterial);
        nbt.putBoolean("canRotate", canRotate);
        nbt.putBoolean("canExcavate", canExcavate);
        nbt.put("rotation", NBTUtilBC.writeEnum(rotation));
        SnapshotBuilder<?> activeBuilder = getBuilder();
        if (activeBuilder != null) {
            nbt.put("builderState", activeBuilder.serializeNBT());
        }
	}

	@Override
	public void load(CompoundTag nbt) {
		super.load(nbt);
        if (nbt.contains("path")) {
            path =
                NBTUtilBC.readCompoundList(nbt.get("path")).map(NbtUtils::readBlockPos).collect(Collectors.toList());
        }
        basePoses = NBTUtilBC.readCompoundList(nbt.get("basePoses")).map(NbtUtils::readBlockPos)
            .collect(Collectors.toList());
        if (basePoses.isEmpty() && level != null) {
            updateBasePoses();
        }
        currentBasePosIndex = nbt.getInt("currentBasePosIndex");
        if (!basePoses.isEmpty()) {
            currentBasePosIndex = Math.max(0, Math.min(currentBasePosIndex, basePoses.size() - 1));
        }
        // Persist the builder mode switches too. Without this, a builder that had
        // "need materials" disabled in creative would reload with it enabled, scan the
        // target area, cache all missing blocks as unavailable, and then sit idle until
        // any resource-inventory change invalidated that cache.
        needMaterial = !nbt.contains("needMaterial") || nbt.getBoolean("needMaterial");
        canRotate = !nbt.contains("canRotate") || nbt.getBoolean("canRotate");
        canExcavate = !nbt.contains("canExcavate") || nbt.getBoolean("canExcavate");
        rotation = NBTUtilBC.readEnum(nbt.get("rotation"), Rotation.class);
        pendingBuilderState = nbt.contains("builderState") ? nbt.getCompound("builderState") : null;
        needsRestartAfterLoad = true;
	}
	
    @Override
	public void onLoad() {
		super.onLoad();
        if (level != null && !level.isClientSide) {
            LOADED_BUILDERS.add(this);
            restartSnapshotAfterLoad();
        }
	}

    @Override
    public void onChunkUnloaded() {
        if (level != null && !level.isClientSide) {
            LOADED_BUILDERS.remove(this);
        }
        super.onChunkUnloaded();
    }

    public static Collection<TileBuilder> getLoadedBuilders() {
        return java.util.List.copyOf(LOADED_BUILDERS);
    }

    public boolean canRobotsBuild() {
        return level != null
            && !level.isClientSide
            && !isRemoved()
            && snapshotType == EnumSnapshotType.BLUEPRINT
            && blueprintBuildingInfo != null
            && blueprintBuilder != null
            && !invSnapshot.getStackInSlot(0).isEmpty();
    }

    public RobotBuildTask reserveRobotBuildTask(EntityRobotBase robot) {
        if (!canRobotsBuild()) {
            return null;
        }
        return blueprintBuilder.reserveNextRobotTask(robot, needMaterial);
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
        if (blueprintBuilder != null) {
            blueprintBuilder.releaseRobotTask(robot, task);
        }
    }

    // Rendering

	@OnlyIn(Dist.CLIENT)
    public Box getBox() {
        return currentBox;
    }
    @Nonnull
    @Override
    @OnlyIn(Dist.CLIENT)
	public AABB getRenderBoundingBox() {
    	 return BoundingBoxUtil.makeFrom(getBlockPos(), getBox(), path);
	}
    

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        left.add("battery = " + battery.getDebugString());
        left.add("basePoses = " + (basePoses == null ? "null" : basePoses.size()));
        left.add("currentBasePosIndex = " + currentBasePosIndex);
        left.add("isDone = " + isDone);
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
        if (snapshotType == EnumSnapshotType.TEMPLATE) {
            return templateBuilder;
        }
        if (snapshotType == EnumSnapshotType.BLUEPRINT) {
            return blueprintBuilder;
        }
        return null;
    }

    private Snapshot.BuildingInfo getBuildingInfo() {
        if (snapshotType == EnumSnapshotType.TEMPLATE) {
            return templateBuildingInfo;
        }
        if (snapshotType == EnumSnapshotType.BLUEPRINT) {
            return blueprintBuildingInfo;
        }
        return null;
    }

    @Override
    public Template.BuildingInfo getTemplateBuildingInfo() {
        return templateBuildingInfo;
    }

    @Override
    public Blueprint.BuildingInfo getBlueprintBuildingInfo() {
        return blueprintBuildingInfo;
    }

    @Override
    public IItemTransactor getInvResources() {
        return invResources;
    }

    @Override
    public TankManager getTankManager() {
        return tankManager;
    }
    
	
    private ItemStack getDisplay(int index) {
        return snapshotType == EnumSnapshotType.BLUEPRINT &&
                index < blueprintBuilder.remainingDisplayRequired.size()
                ? blueprintBuilder.remainingDisplayRequired.get(index)
                : ItemStack.EMPTY;
    }

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
		return new ContainerBuilder(id, inv, invSnapshot, invResources, invRequire, menuSetting, ContainerLevelAccess.create(level, worldPosition));
	}

	@Override
	public Component getDisplayName() {
		return this.getBlockState().getBlock().getName();
	}

	@Override
	public boolean needMeterial() {
		return needMaterial;
	}

}
