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
import net.minecraftforge.items.IItemHandlerModifiable;

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

    public TileReplacer(BlockPos pos, BlockState state) {
        super(BCBuildersBlocks.REPLACER_TILE_BC8.get(), pos, state);
    }

    private static boolean isUsedBlueprint(int slot, @Nonnull ItemStack stack) {
        return stack.getItem() instanceof ItemSnapshot &&
            ItemSnapshot.EnumItemSnapshotType.getFromStack(stack) == ItemSnapshot.EnumItemSnapshotType.BLUEPRINT_USED;
    }

    private static boolean isValidSingleSchematic(int slot, @Nonnull ItemStack stack) {
        return ItemSchematicSingle.isValidUsed(stack);
    }

    /** A Replacer never edits by itself: only an authenticated menu click may
     * request the server to replace (or remove) the selected block schematic.
     * The input single-block schematics remain reusable. */
    public void doReplace(String requestedName) {
        if (level == null || level.isClientSide) return;
        ItemStack blueprintStack = invSnapshot.getStackInSlot(0);
        ItemStack fromStack = invSchematicFrom.getStackInSlot(0);
        ItemStack toStack = invSchematicTo.getStackInSlot(0);
        if (blueprintStack.isEmpty() || fromStack.isEmpty()) return;
        Header header = ItemSnapshot.getHeader(blueprintStack);
        if (header == null) return;
        Snapshot snapshot = GlobalSavedDataSnapshots.getSnapshotForConstruction(level, header.key);
        if (!(snapshot instanceof Blueprint original)) return;
        try {
            ISchematicBlock from = ItemSchematicSingle.getSchematic(fromStack);
            ISchematicBlock to = toStack.isEmpty()
                ? new buildcraft.builders.snapshot.SchematicBlockAir()
                : ItemSchematicSingle.getSchematic(toStack);
            if (from == null || from.isAir() || to == null) return;
            Blueprint modified = original.copy();
            int replaced = modified.replace(from, to);
            if (replaced <= 0) return;
            modified.computeKey();
            String newName = requestedName == null || requestedName.isBlank()
                ? header.name : requestedName.strip();
            if (newName.length() > MenuReplacer.MAX_BLUEPRINT_NAME_LENGTH) {
                newName = newName.substring(0, MenuReplacer.MAX_BLUEPRINT_NAME_LENGTH);
            }
            Header newHeader = new Header(modified.key, getOwnerId(header), getOwnerName(header),
                new Date(), newName, header.allowCreative, header.canRotate, header.canExcavate);
            SnapshotCreationPersistence.persist(level, modified, newHeader);
            invSnapshot.setStackInSlot(0, ItemSnapshot.getUsed(EnumSnapshotType.BLUEPRINT, newHeader));
            // No invSchematicFrom/To extraction: a replacement does not consume patterns.
            setChanged();
        } catch (InvalidInputDataException e) {
            BCLog.logger.warn("Invalid schematic in Replacer at " + worldPosition + ": " + e.getMessage());
        } catch (RuntimeException e) {
            BCLog.logger.warn("Unable to apply Replacer blueprint at " + worldPosition, e);
        }
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
