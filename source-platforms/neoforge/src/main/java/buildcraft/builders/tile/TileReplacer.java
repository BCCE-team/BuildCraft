/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.tile;

import java.util.Date;
import java.util.UUID;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.lib.internal.enums.EnumSnapshotType;
import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.BCBuildersBlocks;
import buildcraft.builders.gui.MenuReplacer;
import buildcraft.builders.item.ItemSchematicSingle;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.GlobalSavedDataSnapshots;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.snapshot.SnapshotCreationPersistence;
import buildcraft.builders.snapshot.Snapshot.Header;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.tile.item.ItemHandlerManager;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.lib.tile.item.StackInsertionFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

public class TileReplacer extends TileBC_Neptune implements MenuProvider {

    public static final IdAllocator IDS = TileBC_Neptune.IDS.makeChild("replacer");

    private static final StackInsertionFunction SINGLE_STACK_INSERTER = StackInsertionFunction.getInsertionFunction(1);

    public final ItemHandlerSimple invSnapshot = itemManager.addInvHandler(
        "snapshot",
        1,
        TileReplacer::isUsedBlueprint,
        SINGLE_STACK_INSERTER,
        ItemHandlerManager.EnumAccess.NONE
    );
    public final ItemHandlerSimple invSchematicFrom = itemManager.addInvHandler(
        "schematicFrom",
        1,
        TileReplacer::isValidSingleSchematic,
        SINGLE_STACK_INSERTER,
        ItemHandlerManager.EnumAccess.NONE
    );
    public final ItemHandlerSimple invSchematicTo = itemManager.addInvHandler(
        "schematicTo",
        1,
        TileReplacer::isValidSingleSchematic,
        SINGLE_STACK_INSERTER,
        ItemHandlerManager.EnumAccess.NONE
    );

    private int lastSkippedInputFingerprint;

    public TileReplacer(BlockPos pos, BlockState state) {
        super(BCBuildersBlocks.REPLACER_TILE_BC8.get(), pos, state);
    }

    @Override
    public void update() {
        if (level == null || level.isClientSide) {
            return;
        }

        ItemStack snapshotStack = invSnapshot.getStackInSlot(0);
        ItemStack fromStack = invSchematicFrom.getStackInSlot(0);
        ItemStack toStack = invSchematicTo.getStackInSlot(0);
        if (snapshotStack.isEmpty() || fromStack.isEmpty() || toStack.isEmpty()) {
            return;
        }

        int inputFingerprint = getInputFingerprint(snapshotStack, fromStack, toStack);
        if (inputFingerprint == lastSkippedInputFingerprint) {
            return;
        }

        Header header = ItemSnapshot.getHeader(snapshotStack);
        if (header == null) {
            lastSkippedInputFingerprint = inputFingerprint;
            return;
        }

        Snapshot snapshot = GlobalSavedDataSnapshots.getSnapshotForConstruction(level, header.key);
        if (!(snapshot instanceof Blueprint blueprint)) {
            lastSkippedInputFingerprint = inputFingerprint;
            return;
        }

        try {
            ISchematicBlock from = ItemSchematicSingle.getSchematic(fromStack);
            ISchematicBlock to = ItemSchematicSingle.getSchematic(toStack);
            if (from == null || to == null) {
                lastSkippedInputFingerprint = inputFingerprint;
                return;
            }
            if (Blueprint.schematicMatchesForReplacement(from, to)) {
                lastSkippedInputFingerprint = inputFingerprint;
                return;
            }

            Blueprint newBlueprint = blueprint.copy();
            int replacedBlocks = newBlueprint.replace(from, to);
            if (replacedBlocks <= 0) {
                lastSkippedInputFingerprint = inputFingerprint;
                return;
            }

            newBlueprint.computeKey();
            Header newHeader = new Header(
                newBlueprint.key,
                getOwnerId(header),
                getOwnerName(header),
                new Date(),
                header.name,
                header.allowCreative,
                header.canRotate,
                header.canExcavate
            );
            SnapshotCreationPersistence.persist(level, newBlueprint, newHeader);
            invSnapshot.setStackInSlot(0, ItemSnapshot.getUsed(EnumSnapshotType.BLUEPRINT, newHeader));
            invSchematicFrom.extractItem(0, 1, false);
            invSchematicTo.extractItem(0, 1, false);
            lastSkippedInputFingerprint = 0;
            setChanged();
        } catch (InvalidInputDataException e) {
            lastSkippedInputFingerprint = inputFingerprint;
            BCLog.logger.warn("Invalid schematic in replacer at " + worldPosition + ": " + e.getMessage());
        } catch (RuntimeException e) {
            lastSkippedInputFingerprint = inputFingerprint;
            BCLog.logger.warn("Failed to replace blueprint blocks at " + worldPosition, e);
        }
    }

    @Override
    protected void onSlotChange(IItemHandlerModifiable handler, int slot, @Nonnull ItemStack before, @Nonnull ItemStack after) {
        super.onSlotChange(handler, slot, before, after);
        lastSkippedInputFingerprint = 0;
    }

    private static boolean isUsedBlueprint(int slot, @Nonnull ItemStack stack) {
        return stack.getItem() instanceof ItemSnapshot &&
            ItemSnapshot.EnumItemSnapshotType.getFromStack(stack) == ItemSnapshot.EnumItemSnapshotType.BLUEPRINT_USED;
    }

    private static boolean isValidSingleSchematic(int slot, @Nonnull ItemStack stack) {
        return ItemSchematicSingle.isValidUsed(stack);
    }

    private static int getInputFingerprint(ItemStack snapshotStack, ItemStack fromStack, ItemStack toStack) {
        int result = stackFingerprint(snapshotStack);
        result = 31 * result + stackFingerprint(fromStack);
        result = 31 * result + stackFingerprint(toStack);
        return result;
    }

    private static int stackFingerprint(ItemStack stack) {
        return 31 * ItemStack.hashItemAndComponents(stack) + stack.getCount();
    }

    private UUID getOwnerId(Header fallbackHeader) {
        return getOwner() != null && getOwner().getId() != null ? getOwner().getId() : fallbackHeader.owner;
    }

    private String getOwnerName(Header fallbackHeader) {
        return getOwner() != null && getOwner().getName() != null && !getOwner().getName().isBlank()
            ? getOwner().getName()
            : fallbackHeader.ownerName;
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new MenuReplacer(id, inv, invSnapshot, invSchematicFrom, invSchematicTo, ContainerLevelAccess.create(level, worldPosition));
    }

    @Override
    public Component getDisplayName() {
        return getBlockState().getBlock().getName();
    }
}
