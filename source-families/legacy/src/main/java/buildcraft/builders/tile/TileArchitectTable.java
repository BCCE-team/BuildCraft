/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package buildcraft.builders.tile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.area.IAreaProvider;
import buildcraft.lib.internal.enums.EnumSnapshotType;
import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.internal.schematic.legacy.ISchematicEntity;
import buildcraft.builders.internal.schematic.legacy.SchematicBlockContext;
import buildcraft.builders.internal.schematic.legacy.SchematicEntityContext;
import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.builders.BCBuildersBlocks;
import buildcraft.builders.block.BlockArchitectTable;
import buildcraft.builders.client.ClientArchitectTables;
import buildcraft.builders.gui.MenuArchitectTable;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.menu.ContainerArchitectTable;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.SnapshotCreationPersistence;
import buildcraft.builders.snapshot.SchematicBlockManager;
import buildcraft.builders.snapshot.SchematicEntityManager;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.snapshot.Snapshot.Header;
import buildcraft.builders.snapshot.Template;
import buildcraft.core.marker.volume.Lock;
import buildcraft.core.marker.volume.VolumeBox;
import buildcraft.core.marker.volume.WorldSavedDataVolumeBoxes;
import buildcraft.lib.delta.DeltaInt;
import buildcraft.lib.delta.DeltaManager;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.BoundingBoxUtil;
import buildcraft.lib.misc.MessageUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.data.Box;
import buildcraft.lib.misc.data.BoxIterator;
import buildcraft.lib.misc.data.EnumAxisOrder;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.tile.item.ItemHandlerManager.EnumAccess;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class TileArchitectTable extends TileBC_Neptune implements IDebuggable, MenuProvider{

	public static final IdAllocator IDS = TileBC_Neptune.IDS.makeChild("architect");
    public static final int NET_BOX = IDS.allocId("BOX");
    public static final int NET_SCAN = IDS.allocId("SCAN");
    private static final ResourceLocation ADVANCEMENT = new ResourceLocation("buildcraftbuilders:architect");

    public final ItemHandlerSimple invSnapshotIn = itemManager.addInvHandler(
        "in",
        1,
        (slot, stack) -> stack.getItem() instanceof ItemSnapshot,
        EnumAccess.INSERT,
        EnumPipePart.VALUES
    );
    public final ItemHandlerSimple invSnapshotOut = itemManager.addInvHandler(
        "out",
        1,
        EnumAccess.EXTRACT,
        EnumPipePart.VALUES
    );

    private EnumSnapshotType snapshotType = EnumSnapshotType.BLUEPRINT;
    public final Box box = new Box();
    public boolean markerBox = false;
    private BitSet templateScannedBlocks;
    private final List<ISchematicBlock> blueprintScannedPalette = new ArrayList<>();
    private int[] blueprintScannedData;
    private final List<ISchematicEntity> blueprintScannedEntities = new ArrayList<>();
    private BoxIterator boxIterator;
    private boolean isValid = false;
    private boolean scanning = false;
    public String name = "<unnamed>";
    public final DeltaInt deltaProgress = deltaManager.addDelta(
        "progress",
        DeltaManager.EnumNetworkVisibility.GUI_ONLY
    );
    
    private boolean allowCreative = false;
    private boolean canRotate = true;
    private boolean canExcavate = true;
    
    private DataSlot menuSetting = new DataSlot() {
		
		@Override
		public void set(int p) {
            setSnapshotSettings(p, true);
		}
		
		@Override
		public int get() {
            return getSnapshotSettings();
		}
	};

    private static DataSlot createCreativePermissionSlot(Player player) {
        return new DataSlot() {
            @Override
            public int get() {
                return canPlayerUseCreativeBlueprintMode(player) ? 1 : 0;
            }

            @Override
            public void set(int value) {
            }
        };
    }
    
    public TileArchitectTable(BlockPos pos, BlockState state) {
		super(BCBuildersBlocks.ARCHITECT_TILE_BC8.get(), pos, state);
	}

    public static boolean canPlayerUseCreativeBlueprintMode(Player player) {
        return player != null && (player.isCreative() || player.hasPermissions(2));
    }

    public int getSnapshotSettings() {
        return (allowCreative ? 1 : 0) | (canRotate ? 0b10 : 0) | (canExcavate ? 0b100 : 0);
    }

    public void setSnapshotSettingsFromPlayer(int settings, Player player) {
        setSnapshotSettings(settings, canPlayerUseCreativeBlueprintMode(player));
    }

    private void setSnapshotSettings(int settings, boolean canUseCreativeMode) {
        boolean oldAllowCreative = allowCreative;
        boolean oldCanRotate = canRotate;
        boolean oldCanExcavate = canExcavate;

        allowCreative = canUseCreativeMode && (settings & 0b1) == 0b1;
        canRotate = (settings & 0b10) == 0b10;
        canExcavate = (settings & 0b100) == 0b100;

        if (oldAllowCreative != allowCreative || oldCanRotate != canRotate || oldCanExcavate != canExcavate) {
            setChanged();
        }
    }

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    @Override
    public void onPlacedBy(LivingEntity placer, ItemStack stack) {
        super.onPlacedBy(placer, stack);
        //? if <1.20 {
        if (placer.level.isClientSide) {
        //?} else {
        /*?
        if (placer.level().isClientSide) {
        ?*/
        //?}
            return;
        }
        WorldSavedDataVolumeBoxes volumeBoxes = WorldSavedDataVolumeBoxes.get(level);
        BlockState blockState = level.getBlockState(worldPosition);
        BlockPos offsetPos = worldPosition.offset(blockState.getValue(BlockArchitectTable.PROP_FACING).getOpposite().getNormal());
        VolumeBox volumeBox = volumeBoxes.getVolumeBoxAt(offsetPos);
        BlockEntity tile = level.getBlockEntity(offsetPos);
        if (volumeBox != null) {
            box.reset();
            box.setMin(volumeBox.box.min());
            box.setMax(volumeBox.box.max());
            isValid = true;
            volumeBox.locks.add(
                new Lock(
                    new Lock.Cause.CauseBlock(worldPosition, blockState.getBlock()),
                    new Lock.Target.TargetRemove(),
                    new Lock.Target.TargetResize(),
                    new Lock.Target.TargetUsedByMachine(
                        Lock.Target.TargetUsedByMachine.EnumType.STRIPES_READ
                    )
                )
            );
            volumeBoxes.setDirty();
            sendNetworkUpdate(NET_BOX);
        } else if (tile instanceof IAreaProvider) {
            IAreaProvider provider = (IAreaProvider) tile;
            box.reset();
            box.setMin(provider.min());
            box.setMax(provider.max());
            markerBox = true;
            isValid = true;
            provider.removeFromWorld(placer instanceof Player player? player : null);
        } else {
            isValid = false;
            BlockState state = level.getBlockState(worldPosition);
            state = state.setValue(BlockArchitectTable.PROP_VALID, Boolean.FALSE);
            level.setBlockAndUpdate(worldPosition, state);
        }
    }

    @Override
    public void update() {
        deltaManager.tick();

        if (level.isClientSide) {
            if (box.isInitialized()) {
                ClientArchitectTables.BOXES.put(box.getBoundingBox(), ClientArchitectTables.START_BOX_VALUE);
            }
            return;
        }

        if (!invSnapshotIn.getStackInSlot(0).isEmpty() && invSnapshotOut.getStackInSlot(0).isEmpty() && isValid) {
            if (!scanning) {
                snapshotType = ItemSnapshot.EnumItemSnapshotType.getFromStack(
                    invSnapshotIn.getStackInSlot(0)
                ).snapshotType;
                int size = box.size().getX() * box.size().getY() * box.size().getZ();
                size /= snapshotType.maxPerTick;
                deltaProgress.addDelta(0, size, 1);
                deltaProgress.addDelta(size, size + 10, -1);
                scanning = true;
            }
        } else {
            scanning = false;
        }

        if (scanning) {
            scanMultipleBlocks();
            if (!scanning) {
                if (snapshotType == EnumSnapshotType.BLUEPRINT) {
                    scanEntities();
                }
                finishScanning();
            }
        }
    }

    private void scanMultipleBlocks() {
        for (int i = snapshotType.maxPerTick; i > 0; i--) {
            scanSingleBlock();
            if (!scanning) {
                break;
            }
        }
    }

    private void scanSingleBlock() {
        BlockPos size = box.size();
        if (templateScannedBlocks == null || blueprintScannedData == null) {
            boxIterator = new BoxIterator(box, EnumAxisOrder.XZY.getMinToMaxOrder(), true);
            templateScannedBlocks = new BitSet(Snapshot.getDataSize(size));
            blueprintScannedData = new int[Snapshot.getDataSize(size)];
        }

        // Read from level
        BlockPos levelScanPos = boxIterator.getCurrent();
        BlockPos schematicPos = levelScanPos.subtract(box.min());
        if (snapshotType == EnumSnapshotType.TEMPLATE) {
            templateScannedBlocks.set(Snapshot.posToIndex(box.size(), schematicPos), !level.isEmptyBlock(levelScanPos));
        }
        if (snapshotType == EnumSnapshotType.BLUEPRINT) {
            ISchematicBlock schematicBlock = readSchematicBlock(levelScanPos);
            int index = blueprintScannedPalette.indexOf(schematicBlock);
            if (index == -1) {
                index = blueprintScannedPalette.size();
                blueprintScannedPalette.add(schematicBlock);
            }
            blueprintScannedData[Snapshot.posToIndex(box.size(), schematicPos)] = index;
        }

        createAndSendMessage(NET_SCAN, buffer -> MessageUtil.writeBlockPos(buffer, levelScanPos));

        sendNetworkUpdate(NET_RENDER_DATA);

        // Move scanPos along
        boxIterator.advance();

        if (boxIterator.hasFinished()) {
            scanning = false;
            boxIterator = null;
        }
    }

    private ISchematicBlock readSchematicBlock(BlockPos levelScanPos) {
        return SchematicBlockManager.getSchematicBlock(new SchematicBlockContext(
            level,
            box.min(),
            levelScanPos,
            level.getBlockState(levelScanPos),
            level.getBlockState(levelScanPos).getBlock()
        ));
    }

    private void scanEntities() {
        level.getEntitiesOfClass(Entity.class, box.getBoundingBox()).stream()
            .map(entity ->
                SchematicEntityManager.getSchematicEntity(new SchematicEntityContext(
                    level,
                    box.min(),
                    entity
                ))
            )
            .filter(Objects::nonNull)
            .forEach(blueprintScannedEntities::add);
    }

    private void finishScanning() {
        BlockState thisState = getCurrentStateForBlock(BCBuildersBlocks.ARCHITECT.get());
        if (thisState == null) {
            return;
        }

        Direction facing = thisState.getValue(BlockArchitectTable.PROP_FACING);
        Snapshot snapshot = Snapshot.create(snapshotType);
        snapshot.size = box.size();
        snapshot.facing = facing;
        snapshot.offset = box.min().subtract(worldPosition.offset(facing.getOpposite().getNormal()));
        if (snapshot instanceof Template) {
            ((Template) snapshot).data = templateScannedBlocks;
        }
        if (snapshot instanceof Blueprint) {
            ((Blueprint) snapshot).palette.addAll(blueprintScannedPalette);
            ((Blueprint) snapshot).data = blueprintScannedData;
            ((Blueprint) snapshot).entities.addAll(blueprintScannedEntities);
        }
        snapshot.computeKey();
        ItemStack stackIn = invSnapshotIn.getStackInSlot(0);
        stackIn.setCount(stackIn.getCount() - 1);
        if (stackIn.getCount() == 0) {
            stackIn = ItemStack.EMPTY;
        }
        invSnapshotIn.setStackInSlot(0, stackIn);
        var ownerProfile = getOwner();
        Header header = new Header(
            snapshot.key,
            ownerProfile.getId(),
            ownerProfile.getName(),
            new Date(),
            name,
            allowCreative,
            canRotate,
            canExcavate
        );
        SnapshotCreationPersistence.persist(level, snapshot, header);
        invSnapshotOut.setStackInSlot(0, ItemSnapshot.getUsed(snapshotType, header));
        templateScannedBlocks = null;
        blueprintScannedData = null;
        blueprintScannedEntities.clear();
        boxIterator = null;
        sendNetworkUpdate(NET_RENDER_DATA);
        AdvancementUtil.unlockAdvancement(getOwner().getId(), ADVANCEMENT);
    }

    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_RENDER_DATA) {
                writePayload(NET_BOX, buffer, side);
                buffer.writeUtf(name);
            } else if (id == NET_BOX) {
                box.writeData(buffer);
                buffer.writeBoolean(markerBox);
            }
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
    	super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_RENDER_DATA) {
                readPayload(NET_BOX, buffer, side, ctx);
                name = buffer.readUtf();
            } else if (id == NET_BOX) {
                box.readData(buffer);
                markerBox = buffer.readBoolean();
            } else if (id == NET_SCAN) {
                ClientArchitectTables.SCANNED_BLOCKS.put(
                    MessageUtil.readBlockPos(buffer),
                    ClientArchitectTables.START_SCANNED_BLOCK_VALUE
                );
            }
        }
    }

    @Override
	public void saveAdditional(CompoundTag nbt) {
		super.saveAdditional(nbt);
        nbt.put("box", box.writeToNBT());
        nbt.putBoolean("markerBox", markerBox);
        if (boxIterator != null) {
            nbt.put("iter", boxIterator.writeToNbt());
        }
        nbt.putBoolean("scanning", scanning);
        nbt.put("snapshotType", NBTUtilBC.writeEnum(snapshotType));
        nbt.putBoolean("isValid", isValid);
        nbt.putString("name", name);
        nbt.putBoolean("allowCreative", allowCreative);
        nbt.putBoolean("canRotate", canRotate);
        nbt.putBoolean("canExcavate", canExcavate);
	}

    @Override
	public void load(CompoundTag nbt) {
		super.load(nbt);
        box.initialize(nbt.getCompound("box"));
        markerBox = nbt.getBoolean("markerBox");
        if (nbt.contains("iter")) {
            boxIterator = BoxIterator.readFromNbt(nbt.getCompound("iter"));
        }
        scanning = nbt.getBoolean("scanning");
        snapshotType = NBTUtilBC.readEnum(nbt.get("snapshotType"), EnumSnapshotType.class);
        isValid = nbt.getBoolean("isValid");
        name = nbt.getString("name");
        allowCreative = nbt.contains("allowCreative") && nbt.getBoolean("allowCreative");
        canRotate = !nbt.contains("canRotate") || nbt.getBoolean("canRotate");
        canExcavate = !nbt.contains("canExcavate") || nbt.getBoolean("canExcavate");
	}

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        left.add("box:");
        left.add(" - min = " + box.min());
        left.add(" - max = " + box.max());
        left.add("scanning = " + scanning);
        left.add("current = " + (boxIterator == null ? null : boxIterator.getCurrent()));
    }

    // Rendering

    @Nonnull
    @Override
	public AABB getRenderBoundingBox() {
    	return BoundingBoxUtil.makeFrom(worldPosition, box);
	}
/*
	@Override
    public double getMaxRenderDistanceSquared() {
        return Double.MAX_VALUE;
    }*/

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        if (!canPlayerUseCreativeBlueprintMode(player) && allowCreative) {
            allowCreative = false;
            setChanged();
        }
		return new ContainerArchitectTable(id, inventory, invSnapshotIn, invSnapshotOut, menuSetting,
            createCreativePermissionSlot(player), /* deltaProgress.getContainerData(), */ContainerLevelAccess.create(getLevel(), worldPosition));
	}

	@Override
	public Component getDisplayName() {
		return getBlockState().getBlock().getName();
	}
}
