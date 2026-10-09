//? source if >=1.21.11
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.snapshot;

import buildcraft.lib.platform.storage.PlatformStorage;
import buildcraft.lib.platform.storage.MutableItemStorage;
import buildcraft.lib.platform.storage.ItemStorage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import org.apache.commons.lang3.tuple.Pair;

import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.internal.schematic.legacy.SchematicBlockContext;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.builders.BuildersNbtUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.fluids.FluidStack;
import buildcraft.lib.compat.LevelCompat;
import buildcraft.lib.compat.NbtCompat;

public class SchematicBlockDefault implements ISchematicBlock {
    @SuppressWarnings("WeakerAccess")
    protected final Set<BlockPos> requiredBlockOffsets = new HashSet<>();
    @SuppressWarnings("WeakerAccess")
    protected BlockState blockState;
    @SuppressWarnings("WeakerAccess")
    protected final List<Property<?>> ignoredProperties = new ArrayList<>();
    @SuppressWarnings("WeakerAccess")
    protected CompoundTag tileNbt;
    @SuppressWarnings("WeakerAccess")
    protected final List<DeferredInventoryItem> deferredInventoryItems = new ArrayList<>();
    @SuppressWarnings("WeakerAccess")
    protected boolean deferInventoryContents;
    @SuppressWarnings("WeakerAccess")
    protected boolean deferInventoryRuntimeClear;
    @SuppressWarnings("WeakerAccess")
    protected Rotation tileRotation = Rotation.NONE;
    @SuppressWarnings("WeakerAccess")
    protected Block placeBlock;
    @SuppressWarnings("WeakerAccess")
    protected final Set<BlockPos> updateBlockOffsets = new HashSet<>();
    @SuppressWarnings("WeakerAccess")
    protected final Set<Block> canBeReplacedWithBlocks = new HashSet<>();

    @SuppressWarnings("unused")
    public static boolean predicate(SchematicBlockContext context) {
        if (context.blockState.isAir()) {
            return false;
        }
        Identifier registryName = BuiltInRegistries.BLOCK.getKey(context.block);
        // noinspection ConstantConditions
        return registryName != null &&
            RulesLoader.READ_DOMAINS.contains(registryName.getNamespace()) &&
            RulesLoader.getRules(
                context.blockState,
                context.blockState.hasBlockEntity() && context.world.getBlockEntity(context.pos) != null
                    ? context.world.getBlockEntity(context.pos).saveWithFullMetadata(context.world.registryAccess())
                    : null
            ).stream()
                .noneMatch(rule -> rule.ignore);
    }

    @SuppressWarnings({"unused", "WeakerAccess"})
    protected void setRequiredBlockOffsets(SchematicBlockContext context, Set<JsonRule> rules) {
        requiredBlockOffsets.clear();
        rules.stream()
            .map(rule -> rule.requiredBlockOffsets)
            .filter(Objects::nonNull)
            .flatMap(Collection::stream)
            .forEach(requiredBlockOffsets::add);
        if (context.block instanceof FallingBlock) {
            requiredBlockOffsets.add(new BlockPos(0, -1, 0));
        }
    }

    @SuppressWarnings({"unused", "WeakerAccess"})
    protected void setBlockState(SchematicBlockContext context, Set<JsonRule> rules) {
        blockState = context.blockState;
    }

    @SuppressWarnings({"unused", "WeakerAccess"})
    protected void setIgnoredProperties(SchematicBlockContext context, Set<JsonRule> rules) {
        ignoredProperties.clear();
        rules.stream()
            .map(rule -> rule.ignoredProperties)
            .filter(Objects::nonNull)
            .flatMap(List::stream)
            .flatMap(propertyName ->
                context.blockState.getProperties().stream()
                    .filter(property -> property.getName().equals(propertyName))
            )
            .forEach(ignoredProperties::add);
    }

    @SuppressWarnings({"unused", "WeakerAccess"})
    protected void setTileNbt(SchematicBlockContext context, Set<JsonRule> rules) {
        tileNbt = null;
        deferredInventoryItems.clear();
        deferInventoryContents = false;
        deferInventoryRuntimeClear = false;
        if (context.blockState.hasBlockEntity()) {
            BlockEntity tileEntity = context.world.getBlockEntity(context.pos);
            if (tileEntity != null) {
                CompoundTag originalTileNbt = tileEntity.saveWithFullMetadata(context.world.registryAccess());
                tileNbt = originalTileNbt.copy();
                boolean hasItemHandler = captureDeferredInventory(context.blockState, tileEntity);
                List<ItemStack> configuredItems = getConfiguredInventoryItems(context.blockState, rules, context.world);
                deferInventoryContents = hasItemHandler
                    && containsAllStacks(
                        deferredInventoryItems.stream().map(entry -> entry.stack).collect(Collectors.toList()),
                        configuredItems
                    );
                if (deferInventoryContents) {
                    CompoundTag emptiedNbt = createInventoryClearedNbt(
                        context.blockState,
                        originalTileNbt,
                        deferredInventoryItems,
                        rules
                    );
                    if (emptiedNbt != null) {
                        tileNbt = emptiedNbt;
                        InventoryContentPolicy.stripCopiedBlockContent(context.blockState, tileNbt, rules);
                    } else {
                        // Some capability-backed inventories use a private serialization format. Keep their
                        // non-inventory block-entity state intact and clear the copied contents immediately after
                        // placement, before the builder starts restoring paid-for deferred items.
                        tileNbt = originalTileNbt.copy();
                        deferInventoryRuntimeClear = true;
                    }
                }
                if (!deferInventoryContents) {
                    tileNbt = originalTileNbt.copy();
                    deferredInventoryItems.clear();
                    deferInventoryRuntimeClear = false;
                    InventoryContentPolicy.stripDisallowedBlockContent(context.blockState, tileNbt, rules);
                }
                if (tileNbt != null && tileEntity instanceof TileBC_Neptune) {
                    // Ownership is attribution for the placement actor, not copied machine state.
                    tileNbt.remove("owner");
                }
            }
        }
    }

    private boolean captureDeferredInventory(BlockState state, BlockEntity blockEntity) {
        if (!InventoryContentPolicy.canCopyGenericBlockItems(state)) {
            return false;
        }
        // Prefer the block entity's own inventory. A chest's unsided Forge capability may wrap the
        // combined double chest and would incorrectly include the neighbouring block's slots.
        if (blockEntity instanceof Container container) {
            return captureDeferredInventory(PlatformStorage.localInventory(container), null);
        }

        Level level = blockEntity.getLevel();
        DeferredInventoryAccess access = findDeferredInventory(level, blockEntity.getBlockPos());
        return access != null && captureDeferredInventory(access.storage(), access.side());
    }

    private boolean captureDeferredInventory(ItemStorage handler, Direction accessSide) {
        int start = deferredInventoryItems.size();
        try {
            int slots = handler.getSlots();
            if (slots < 0) {
                return false;
            }
            for (int slot = 0; slot < slots; slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack != null && !stack.isEmpty()) {
                    deferredInventoryItems.add(new DeferredInventoryItem(slot, accessSide, stack));
                }
            }
            return true;
        } catch (RuntimeException ignored) {
            deferredInventoryItems.subList(start, deferredInventoryItems.size()).clear();
            return false;
        }
    }

    /** Prefer an unsided capability. If a mod exposes only sided views, use the richest stable view. */
    private static DeferredInventoryAccess findDeferredInventory(Level level, BlockPos blockPos) {
        try {
            ItemStorage unsided = PlatformStorage.items(level, blockPos, null);
            if (unsided != null) {
                return new DeferredInventoryAccess(null, unsided);
            }
        } catch (RuntimeException ignored) {
        }

        DeferredInventoryAccess best = null;
        int bestSlots = -1;
        int bestNonEmpty = -1;
        for (Direction side : Direction.values()) {
            try {
                ItemStorage candidate = PlatformStorage.items(level, blockPos, side);
                if (candidate == null) {
                    continue;
                }
                int slots = candidate.getSlots();
                int nonEmpty = 0;
                for (int slot = 0; slot < slots; slot++) {
                    ItemStack stack = candidate.getStackInSlot(slot);
                    if (stack != null && !stack.isEmpty()) {
                        nonEmpty++;
                    }
                }
                if (slots > bestSlots || slots == bestSlots && nonEmpty > bestNonEmpty) {
                    best = new DeferredInventoryAccess(side, candidate);
                    bestSlots = slots;
                    bestNonEmpty = nonEmpty;
                }
            } catch (RuntimeException ignored) {
                // A broken side must not make blueprint capture crash. Try the remaining faces.
            }
        }
        return best;
    }

    private static CompoundTag createInventoryClearedNbt(
        BlockState state, CompoundTag originalNbt, List<DeferredInventoryItem> deferredItems, Set<JsonRule> rules
    ) {
        // Never attach a copied block entity to the real world here. In particular, Forge's chest
        // capability resolves a combined container through Level + BlockPos, so mutating that
        // "copy" also mutates the actual chest in the world. Work only on copied NBT instead.
        CompoundTag emptiedNbt = originalNbt.copy();
        InventoryContentPolicy.stripCopiedBlockContent(state, emptiedNbt, rules);

        // Do not recursively delete arbitrary ItemStack-shaped compounds: in modern Minecraft they may be data
        // components or configuration values rather than inventory slots. Unknown serialization is handled by the
        // post-placement clear path instead.
        if (!deferredItems.isEmpty() && emptiedNbt.equals(originalNbt)) {
            return null;
        }
        return emptiedNbt;
    }

    private static ItemStack insertIntoExactSlot(ItemStorage handler, int slot, ItemStack stack, boolean simulate) {
        if (handler == null || slot < 0 || stack.isEmpty()) {
            return stack;
        }
        ItemStack beforeSlot;
        try {
            if (slot >= handler.getSlots()) {
                return stack;
            }
            ItemStack observed = handler.getStackInSlot(slot);
            beforeSlot = observed == null ? ItemStack.EMPTY : observed.copy();
        } catch (RuntimeException ignored) {
            return stack;
        }

        ItemStack remaining = stack.copy();
        if (handler instanceof MutableItemStorage modifiable) {
            remaining = insertIntoModifiableSlot(modifiable, slot, remaining, simulate);
        }
        if (remaining.isEmpty() || remaining.getCount() != stack.getCount()) {
            return remaining;
        }

        try {
            ItemStack nativeRemainder = handler.insertItem(slot, remaining, simulate);
            if (nativeRemainder != null && (nativeRemainder.isEmpty()
                || ItemStack.isSameItemSameComponents(nativeRemainder, remaining)
                    && nativeRemainder.getCount() <= remaining.getCount())) {
                return nativeRemainder;
            }
        } catch (RuntimeException ignored) {
            if (simulate) {
                return stack;
            }
        }
        return observedInsertRemainder(handler, slot, stack, beforeSlot, simulate);
    }

    private static ItemStack insertIntoModifiableSlot(
        MutableItemStorage handler, int slot, ItemStack stack, boolean simulate
    ) {
        ItemStack current;
        int limit;
        try {
            ItemStack observed = handler.getStackInSlot(slot);
            current = observed == null ? ItemStack.EMPTY : observed.copy();
            if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, stack)) {
                return stack;
            }
            limit = Math.min(handler.getSlotLimit(slot), stack.getMaxStackSize());
        } catch (RuntimeException ignored) {
            return stack;
        }

        int currentCount = current.isEmpty() ? 0 : current.getCount();
        int accepted = Math.min(stack.getCount(), Math.max(0, limit - currentCount));
        if (accepted <= 0) {
            return stack;
        }
        if (simulate) {
            ItemStack remaining = stack.copy();
            remaining.shrink(accepted);
            return remaining;
        }

        ItemStack updated = current.isEmpty() ? stack.copy() : current.copy();
        updated.setCount(currentCount + accepted);
        try {
            handler.setStackInSlot(slot, updated);
        } catch (RuntimeException ignored) {
            // Some nonstandard handlers throw after mutating. First try to put the exact previous slot back; if that
            // cannot be proven, observe the slot and account for what was actually inserted instead of refunding
            // the full stack and creating a duplicate.
            try {
                handler.setStackInSlot(slot, current.copy());
                ItemStack restored = handler.getStackInSlot(slot);
                if (sameStackAndCount(current, restored)) {
                    return stack;
                }
            } catch (RuntimeException rollbackFailure) {
                // Fall through to observation.
            }
            return observedInsertRemainder(handler, slot, stack, current, false);
        }

        try {
            ItemStack observed = handler.getStackInSlot(slot);
            ItemStack after = observed == null ? ItemStack.EMPTY : observed;
            int afterCount = !after.isEmpty() && ItemStack.isSameItemSameComponents(after, stack)
                ? after.getCount() : 0;
            int inserted = Math.min(stack.getCount(), Math.max(0, afterCount - currentCount));
            ItemStack remaining = stack.copy();
            remaining.shrink(inserted);
            return remaining;
        } catch (RuntimeException ignored) {
            // Direct mutation returned normally. If the handler refuses a verification read, honour the mutable
            // contract and account for the amount that was set rather than refunding an item that may be present.
            ItemStack expectedRemainder = stack.copy();
            expectedRemainder.shrink(accepted);
            return expectedRemainder;
        }
    }

    private static ItemStack observedInsertRemainder(
        ItemStorage handler, int slot, ItemStack insertedStack, ItemStack beforeSlot, boolean simulate
    ) {
        try {
            ItemStack observed = handler.getStackInSlot(slot);
            ItemStack after = observed == null ? ItemStack.EMPTY : observed;
            int beforeCount = !beforeSlot.isEmpty()
                && ItemStack.isSameItemSameComponents(beforeSlot, insertedStack) ? beforeSlot.getCount() : 0;
            int afterCount = !after.isEmpty()
                && ItemStack.isSameItemSameComponents(after, insertedStack) ? after.getCount() : 0;
            int inserted = Math.min(insertedStack.getCount(), Math.max(0, afterCount - beforeCount));
            ItemStack remaining = insertedStack.copy();
            remaining.shrink(inserted);
            return remaining;
        } catch (RuntimeException ignored) {
            // Simulation has not reserved anything yet, so reject it. During execution an unobservable mutation
            // cannot safely be refunded: consuming the reservation is preferable to duplicating the item.
            return simulate ? insertedStack : ItemStack.EMPTY;
        }
    }

    private static boolean sameStackAndCount(ItemStack expected, ItemStack actual) {
        if (actual == null || expected.isEmpty() != actual.isEmpty()) {
            return false;
        }
        return expected.isEmpty() || expected.getCount() == actual.getCount()
            && ItemStack.isSameItemSameComponents(expected, actual);
    }

    /**
     * Clears the inventory that arrived through an opaque/custom block-entity serialization.
     * This only runs for schematics that could not be made inventory-empty in NBT. The block is
     * rolled back if even one captured slot cannot be cleared, so a lying/nonstandard handler can
     * never turn copied contents into free items.
     */
    private boolean clearRuntimeDeferredInventory(Level level, BlockPos blockPos, BlockEntity blockEntity) {
        for (DeferredInventoryItem entry : deferredInventoryItems) {
            ItemStorage handler = getDeferredInventoryHandler(level, blockPos, blockEntity, entry.accessSide);
            if (handler == null || entry.slot < 0) {
                return false;
            }
            try {
                if (entry.slot >= handler.getSlots()) {
                    return false;
                }
                ItemStack current = handler.getStackInSlot(entry.slot);
                if (current == null || current.isEmpty()) {
                    continue;
                }
                if (!ItemStack.isSameItemSameComponents(entry.stack, current)) {
                    return false;
                }
                int remove = Math.min(entry.stack.getCount(), current.getCount());
                if (remove <= 0) {
                    continue;
                }

                if (handler instanceof MutableItemStorage mutable) {
                    ItemStack replacement = current.copy();
                    replacement.shrink(remove);
                    mutable.setStackInSlot(entry.slot, replacement.isEmpty() ? ItemStack.EMPTY : replacement);
                    ItemStack after = handler.getStackInSlot(entry.slot);
                    int afterCount = after == null || after.isEmpty() ? 0 : after.getCount();
                    if (afterCount != current.getCount() - remove) {
                        return false;
                    }
                } else {
                    ItemStack extracted = handler.extractItem(entry.slot, remove, false);
                    if (extracted == null
                        || extracted.getCount() != remove
                        || !ItemStack.isSameItemSameComponents(entry.stack, extracted)) {
                        return false;
                    }
                }
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        blockEntity.setChanged();
        return true;
    }

    private static void rollbackOpaqueInventoryPlacement(Level level, BlockPos blockPos) {
        // Detach the copied block entity before replacing its block. Container blocks normally drop their contents
        // from onRemove; leaving the failed, pre-filled block entity attached here would turn a restore failure into
        // free dropped items. SnapshotBuilder can then restore the original world state without duplicated contents.
        if (level.getBlockEntity(blockPos) != null) {
            level.removeBlockEntity(blockPos);
        }
        level.setBlock(blockPos, Blocks.AIR.defaultBlockState(), 3);
    }

    private List<ItemStack> getConfiguredInventoryItems(BlockState state, Set<JsonRule> rules, Level level) {
        Set<NbtPath> paths = new java.util.LinkedHashSet<>();
        rules.stream()
            .map(rule -> rule.requiredExtractors)
            .filter(Objects::nonNull)
            .flatMap(Collection::stream)
            .filter(RequiredExtractorItemsList.class::isInstance)
            .map(RequiredExtractorItemsList.class::cast)
            .map(RequiredExtractorItemsList::getPath)
            .filter(Objects::nonNull)
            .filter(path -> InventoryContentPolicy.canCopyBlockItems(state, path))
            .forEach(paths::add);
        paths.addAll(InventoryContentPolicy.getAllowedBlockItemPaths(state));
        return paths.stream()
            .map(RequiredExtractorItemsList::new)
            .flatMap(extractor -> extractor.extractItemsFromBlock(state, tileNbt, level).stream())
            .filter(stack -> !stack.isEmpty())
            .map(ItemStack::copy)
            .collect(Collectors.toCollection(ArrayList::new));
    }

    private static boolean containsAllStacks(List<ItemStack> available, List<ItemStack> required) {
        List<ItemStack> remainingAvailable = available.stream()
            .filter(stack -> stack != null && !stack.isEmpty())
            .map(ItemStack::copy)
            .collect(Collectors.toCollection(ArrayList::new));
        for (ItemStack wanted : required) {
            int remaining = wanted.getCount();
            for (ItemStack candidate : remainingAvailable) {
                if (remaining <= 0) {
                    break;
                }
                if (ItemStack.isSameItemSameComponents(wanted, candidate)) {
                    int used = Math.min(remaining, candidate.getCount());
                    remaining -= used;
                    candidate.shrink(used);
                }
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings({"unused", "WeakerAccess"})
    protected void setPlaceBlock(SchematicBlockContext context, Set<JsonRule> rules) {
        placeBlock = rules.stream()
            .map(rule -> rule.placeBlock)
            .filter(Objects::nonNull)
            .findFirst()
            .flatMap(id -> BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)))
            .orElse(context.block);
    }

    @SuppressWarnings({"unused", "WeakerAccess"})
    protected void setUpdateBlockOffsets(SchematicBlockContext context, Set<JsonRule> rules) {
        updateBlockOffsets.clear();
        if (rules.stream().map(rule -> rule.updateBlockOffsets).anyMatch(Objects::nonNull)) {
            rules.stream()
                .map(rule -> rule.updateBlockOffsets)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .forEach(updateBlockOffsets::add);
        } else {
            Stream.of(Direction.values())
                .map(Direction::getUnitVec3i)
                .map(BlockPos::new)
                .forEach(updateBlockOffsets::add);
            updateBlockOffsets.add(BlockPos.ZERO);
        }
    }

    @SuppressWarnings({"unused", "WeakerAccess"})
    protected void setCanBeReplacedWithBlocks(SchematicBlockContext context, Set<JsonRule> rules) {
        canBeReplacedWithBlocks.clear();
        rules.stream()
            .map(rule -> rule.canBeReplacedWithBlocks)
            .filter(Objects::nonNull)
            .flatMap(Collection::stream)
            .flatMap(id -> BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).stream())
            .forEach(canBeReplacedWithBlocks::add);
        canBeReplacedWithBlocks.add(context.block);
        canBeReplacedWithBlocks.add(placeBlock);
    }

    public void init(SchematicBlockContext context) {
        // noinspection ConstantConditions
        Set<JsonRule> rules = RulesLoader.getRules(
            context.blockState,
            context.blockState.hasBlockEntity() && context.world.getBlockEntity(context.pos) != null
                ? context.world.getBlockEntity(context.pos).saveWithFullMetadata(context.world.registryAccess())
                : null
        );
        setRequiredBlockOffsets /*   */(context, rules);
        setBlockState /*             */(context, rules);
        setIgnoredProperties /*      */(context, rules);
        setTileNbt /*                */(context, rules);
        setPlaceBlock /*             */(context, rules);
        setUpdateBlockOffsets /*     */(context, rules);
        setCanBeReplacedWithBlocks /**/(context, rules);
    }

    @Nonnull
    public Set<BlockPos> getRequiredBlockOffsets() {
        return requiredBlockOffsets;
    }

    @Nonnull
    public List<ItemStack> computeRequiredItems(Level level) {
        if (!deferInventoryContents) {
            return computeLegacyRequiredItems(level);
        }
        List<ItemStack> items = new ArrayList<>(computeRequiredItemsForPlacement(level));
        items.addAll(computeDeferredRequiredItems(level));
        return items;
    }

    @Nonnull
    public List<ItemStack> computeRequiredItemsForPlacement(Level level) {
        if (!deferInventoryContents) {
            // Keep specialised schematics (for example banners) compatible with their computeRequiredItems override.
            return computeRequiredItems(level);
        }

        Set<JsonRule> rules = RulesLoader.getRules(blockState, tileNbt);
        List<List<RequiredExtractor>> collect = rules.stream()
            .map(rule -> rule.requiredExtractors)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        return (
            collect.isEmpty()
                ? Stream.of(new RequiredExtractorItemFromBlock())
                : collect.stream().flatMap(Collection::stream)
        )
            .filter(requiredExtractor -> !(requiredExtractor instanceof RequiredExtractorItemsList))
            .flatMap(requiredExtractor -> requiredExtractor.extractItemsFromBlock(blockState, tileNbt, level).stream())
            .filter(((Predicate<ItemStack>) ItemStack::isEmpty).negate())
            .collect(Collectors.toList());
    }

    private List<ItemStack> computeLegacyRequiredItems(Level level) {
        Set<JsonRule> rules = RulesLoader.getRules(blockState, tileNbt);
        List<List<RequiredExtractor>> collect = rules.stream()
            .map(rule -> rule.requiredExtractors)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        Set<NbtPath> extractedItemListPaths = new HashSet<>();
        List<ItemStack> items = (
            collect.isEmpty()
                ? Stream.of(new RequiredExtractorItemFromBlock())
                : collect.stream().flatMap(Collection::stream)
        )
            .filter(requiredExtractor -> {
                if (requiredExtractor instanceof RequiredExtractorItemsList itemsList) {
                    NbtPath path = itemsList.getPath();
                    if (!InventoryContentPolicy.canCopyBlockItems(blockState, path)) {
                        return false;
                    }
                    extractedItemListPaths.add(path);
                }
                return true;
            })
            .flatMap(requiredExtractor -> requiredExtractor.extractItemsFromBlock(blockState, tileNbt, level).stream())
            .filter(((Predicate<ItemStack>) ItemStack::isEmpty).negate())
            .collect(Collectors.toList());
        InventoryContentPolicy.getAllowedBlockItemPaths(blockState).stream()
            .filter(path -> !extractedItemListPaths.contains(path))
            .map(RequiredExtractorItemsList::new)
            .flatMap(requiredExtractor -> requiredExtractor.extractItemsFromBlock(blockState, tileNbt, level).stream())
            .filter(((Predicate<ItemStack>) ItemStack::isEmpty).negate())
            .forEach(items::add);
        return items;
    }

    @Nonnull
    public List<ItemStack> computeDeferredRequiredItems(Level level) {
        return deferredInventoryItems.stream()
            .map(entry -> entry.stack.copy())
            .collect(Collectors.toList());
    }

    /**
     * Returns the inventory belonging to this exact block entity. Vanilla double chests expose two
     * separate Containers, but their block capability may resolve to one combined 54-slot handler.
     * Deferred schematic entries store local slot numbers for each half, so use the local Container
     * whenever one is available.
     */
    private static ItemStorage getDeferredInventoryHandler(
        Level level, BlockPos blockPos, BlockEntity blockEntity, Direction accessSide
    ) {
        if (blockEntity instanceof Container container) {
            return PlatformStorage.localInventory(container);
        }
        if (accessSide != null) {
            try {
                // Sided handlers may remap their exposed slots, so a saved side must keep using the same view.
                // Falling back to another face could restore an item into a different physical slot.
                return PlatformStorage.items(level, blockPos, accessSide);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        try {
            ItemStorage unsided = PlatformStorage.items(level, blockPos, null);
            if (unsided != null) {
                return unsided;
            }
        } catch (RuntimeException ignored) {
            // Blueprints without side metadata fall through to a conservative sided lookup.
        }
        DeferredInventoryAccess fallback = findDeferredInventory(level, blockPos);
        return fallback == null ? null : fallback.storage();
    }

    private static int getMissingDeferredCount(
        Level level, BlockPos blockPos, BlockEntity blockEntity, DeferredInventoryItem entry
    ) {
        ItemStorage handler = getDeferredInventoryHandler(level, blockPos, blockEntity, entry.accessSide);
        if (handler == null || entry.slot < 0) {
            return entry.stack.getCount();
        }
        try {
            if (entry.slot >= handler.getSlots()) {
                return entry.stack.getCount();
            }
            ItemStack present = handler.getStackInSlot(entry.slot);
            if (present == null || present.isEmpty() || !ItemStack.isSameItemSameComponents(entry.stack, present)) {
                return entry.stack.getCount();
            }
            return Math.max(0, entry.stack.getCount() - present.getCount());
        } catch (RuntimeException ignored) {
            return entry.stack.getCount();
        }
    }

    @Nonnull
    public List<ItemStack> computeMissingDeferredRequiredItems(Level level, BlockPos blockPos) {
        if (deferredInventoryItems.isEmpty()) {
            return List.of();
        }
        BlockEntity blockEntity = level.getBlockEntity(blockPos);
        if (blockEntity == null) {
            return computeDeferredRequiredItems(level);
        }
        List<ItemStack> missing = new ArrayList<>();
        for (DeferredInventoryItem entry : deferredInventoryItems) {
            int remaining = getMissingDeferredCount(level, blockPos, blockEntity, entry);
            if (remaining > 0) {
                ItemStack stack = entry.stack.copy();
                stack.setCount(remaining);
                missing.add(stack);
            }
        }
        return missing;
    }

    @Nonnull
    public ItemStack insertDeferredItem(Level level, BlockPos blockPos, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        BlockEntity blockEntity = level.getBlockEntity(blockPos);
        if (blockEntity == null) {
            return stack;
        }
        ItemStack remaining = stack.copy();
        for (DeferredInventoryItem entry : deferredInventoryItems) {
            if (!ItemStack.isSameItemSameComponents(entry.stack, stack)) {
                continue;
            }
            int missing = getMissingDeferredCount(level, blockPos, blockEntity, entry);
            if (missing <= 0) {
                continue;
            }
            ItemStorage handler = getDeferredInventoryHandler(level, blockPos, blockEntity, entry.accessSide);
            if (handler == null) {
                continue;
            }
            int attemptCount = Math.min(missing, remaining.getCount());
            ItemStack attempt = remaining.copy();
            attempt.setCount(attemptCount);
            ItemStack rejected = insertIntoExactSlot(handler, entry.slot, attempt, simulate);
            int inserted = attemptCount - (rejected == null ? attemptCount : rejected.getCount());
            if (inserted > 0) {
                remaining.shrink(Math.min(inserted, remaining.getCount()));
            }
            if (remaining.isEmpty()) {
                break;
            }
        }
        if (!simulate && remaining.getCount() != stack.getCount()) {
            blockEntity.setChanged();
            BlockState state = level.getBlockState(blockPos);
            level.sendBlockUpdated(blockPos, state, state, 3);
        }
        return remaining;
    }

    @Nonnull
    public List<FluidStack> computeRequiredFluids(Level level) {
        Set<JsonRule> rules = RulesLoader.getRules(blockState, tileNbt);
        return rules.stream()
            .map(rule -> rule.requiredExtractors)
            .filter(Objects::nonNull)
            .flatMap(Collection::stream)
            .flatMap(requiredExtractor -> requiredExtractor.extractFluidsFromBlock(blockState, tileNbt, level).stream())
            .filter(((Predicate<FluidStack>) FluidStack::isEmpty).negate())
            .collect(Collectors.toList());
    }

    public SchematicBlockDefault getRotated(Rotation rotation) {
        SchematicBlockDefault schematicBlock = SchematicBlockManager.createCleanCopy(this);
        requiredBlockOffsets.stream()
            .map(blockPos -> blockPos.rotate(rotation))
            .forEach(schematicBlock.requiredBlockOffsets::add);
        schematicBlock.blockState = blockState.rotate(rotation);
        schematicBlock.ignoredProperties.addAll(ignoredProperties);
        schematicBlock.tileNbt = tileNbt;
        schematicBlock.deferInventoryContents = deferInventoryContents;
        schematicBlock.deferInventoryRuntimeClear = deferInventoryRuntimeClear;
        deferredInventoryItems.stream()
            .map(DeferredInventoryItem::copy)
            .forEach(schematicBlock.deferredInventoryItems::add);
        schematicBlock.tileRotation = tileRotation.getRotated(rotation);
        schematicBlock.placeBlock = placeBlock;
        updateBlockOffsets.stream()
            .map(blockPos -> blockPos.rotate(rotation))
            .forEach(schematicBlock.updateBlockOffsets::add);
        schematicBlock.canBeReplacedWithBlocks.addAll(canBeReplacedWithBlocks);
        return schematicBlock;
    }

    public boolean canBuild(Level level, BlockPos blockPos) {
        if (!level.isEmptyBlock(blockPos)) {
            return false;
        }
        if (placeBlock == Blocks.AIR) {
            return true;
        }
        return getPlacementState(level, blockPos).canSurvive(level, blockPos);
    }

    private BlockState getPlacementState(Level level, BlockPos blockPos) {
        BlockState newBlockState = blockState;
        if (placeBlock != blockState.getBlock()) {
            newBlockState = placeBlock.defaultBlockState();
            for (Property<?> property : blockState.getProperties()) {
                if (newBlockState.getProperties().contains(property)) {
                    newBlockState = BlockUtil.copyProperty(
                        property,
                        newBlockState,
                        blockState
                    );
                }
            }
        }
        for (Property<?> property : ignoredProperties) {
            newBlockState = BlockUtil.copyProperty(
                property,
                newBlockState,
                placeBlock.defaultBlockState()
            );
        }
        // BuildingInfo already rotates the palette via getRotated(rotation). Applying tileRotation
        // to the BlockState again here would rotate directional blocks twice and make the builder
        // immediately classify its own placement as incorrect. tileRotation is retained for
        // block-entity-specific rotation metadata only.
        // Blueprint-built leaves must not immediately enter vanilla distance-decay.
        if (newBlockState.hasProperty(BlockStateProperties.PERSISTENT)) {
            newBlockState = newBlockState.setValue(BlockStateProperties.PERSISTENT, true);
        }
        return newBlockState;
    }

    @SuppressWarnings("Duplicates")
    public boolean build(Level level, BlockPos blockPos) {
        return buildInternal(level, blockPos, null, false);
    }

    @SuppressWarnings("Duplicates")
    public boolean build(Level level, BlockPos blockPos, Player actor) {
        return buildInternal(level, blockPos, actor, true);
    }

    private boolean buildInternal(Level level, BlockPos blockPos, Player actor, boolean firePlaceEvent) {
        if (placeBlock == Blocks.AIR) {
            return true;
        }
        LevelCompat.profilerPush(level, "prepare block");
        BlockState newBlockState = getPlacementState(level, blockPos);
        if (!newBlockState.canSurvive(level, blockPos)) {
            LevelCompat.profilerPop(level);
            return false;
        }
        LevelCompat.profilerPop(level);
        LevelCompat.profilerPush(level, "place block");
        boolean b = firePlaceEvent
            ? BlockUtil.placeBlock(level, blockPos, newBlockState, actor, Direction.UP, 11)
            : level.setBlock(blockPos, newBlockState, 11);
        LevelCompat.profilerPop(level);
        if (b) {
            BlockState placedBlockState = level.getBlockState(blockPos);
            LevelCompat.profilerPush(level, "notify");
            updateBlockOffsets.stream()
                .map(blockPos::offset)
                .forEach(updatePos -> level.updateNeighborsAt(updatePos, placeBlock));
            LevelCompat.profilerPop(level);
            if (tileNbt != null && placedBlockState.hasBlockEntity()) {
                LevelCompat.profilerPush(level, "prepare tile");
                Set<JsonRule> rules = RulesLoader.getRules(blockState, tileNbt);
                CompoundTag replaceNbt = rules.stream()
                    .map(rule -> rule.replaceNbt)
                    .filter(Objects::nonNull)
                    .map(Tag.class::cast)
                    .reduce(NBTUtilBC::merge)
                    .map(CompoundTag.class::cast)
                    .orElse(null);
                CompoundTag newTileNbt = new CompoundTag();
                NbtCompat.getAllKeys(tileNbt).stream()
                    .map(key -> Pair.of(key, tileNbt.get(key)))
                    .forEach(kv -> newTileNbt.put(kv.getKey(), kv.getValue()));
                newTileNbt.putInt("x", blockPos.getX());
                newTileNbt.putInt("y", blockPos.getY());
                newTileNbt.putInt("z", blockPos.getZ());
                LevelCompat.profilerPop(level);
                LevelCompat.profilerPush(level, "place tile");
                CompoundTag finalTileNbt = replaceNbt != null
                    ? (CompoundTag) NBTUtilBC.merge(newTileNbt, replaceNbt)
                    : newTileNbt;
                if (deferInventoryContents) {
                    InventoryContentPolicy.stripCopiedBlockContent(blockState, finalTileNbt, rules);
                } else {
                    InventoryContentPolicy.stripDisallowedBlockContent(blockState, finalTileNbt, rules);
                }
                BlockEntity tileEntity = BlockEntity.loadStatic(
                    blockPos,
                    placedBlockState,
                    finalTileNbt,
                    level.registryAccess()
                );
                if (tileEntity != null) {
                    tileEntity.setLevel(level);
                    level.setBlockEntity(tileEntity);
                    if (actor != null && tileEntity instanceof TileBC_Neptune bcTile) {
                        bcTile.setOwnerProfile(actor.getGameProfile());
                    }
                    if (deferInventoryRuntimeClear && !clearRuntimeDeferredInventory(level, blockPos, tileEntity)) {
                        LevelCompat.profilerPop(level);
                        rollbackOpaqueInventoryPlacement(level, blockPos);
                        return false;
                    }
                } else if (deferInventoryRuntimeClear) {
                    LevelCompat.profilerPop(level);
                    rollbackOpaqueInventoryPlacement(level, blockPos);
                    return false;
                }
                LevelCompat.profilerPop(level);
            }
            return true;
        }
        return false;
    }

    @SuppressWarnings("Duplicates")
    public boolean buildWithoutChecks(Level Level, BlockPos blockPos) {
        if (Level.setBlock(blockPos, blockState, 0)) {
            if (tileNbt != null && blockState.hasBlockEntity()) {
                CompoundTag newTileNbt = new CompoundTag();
                NbtCompat.getAllKeys(tileNbt).stream()
                    .map(key -> Pair.of(key, tileNbt.get(key)))
                    .forEach(kv -> newTileNbt.put(kv.getKey(), kv.getValue()));
                newTileNbt.putInt("x", blockPos.getX());
                newTileNbt.putInt("y", blockPos.getY());
                newTileNbt.putInt("z", blockPos.getZ());
                Set<JsonRule> rules = RulesLoader.getRules(blockState, tileNbt);
                if (deferInventoryContents) {
                    InventoryContentPolicy.stripCopiedBlockContent(blockState, newTileNbt, rules);
                } else {
                    InventoryContentPolicy.stripDisallowedBlockContent(blockState, newTileNbt, rules);
                }
                BlockEntity tileEntity = BlockEntity.loadStatic(blockPos, blockState, newTileNbt, Level.registryAccess());
                if (tileEntity != null) {
                    tileEntity.setLevel(Level);
                    Level.setBlockEntity(tileEntity);
                    if (tileRotation != Rotation.NONE && tileEntity instanceof StructureBlockEntity sbe) {
                        sbe.setRotation(tileRotation);
                    }
                }
                return true;
            }
        }
        return false;
    }

    public boolean isBuilt(Level world, BlockPos blockPos) {
        BlockState blockState2 = world.getBlockState(blockPos);
        if (blockState == null) {
            return false;
        }
        BlockState expectedState = getPlacementState(world, blockPos);
        if (blockState2 == expectedState) {
            return true;
        }
        if (!canBeReplacedWithBlocks.contains(blockState2.getBlock())) {
            return false;
        }
        if (BlockUtil.blockStatesWithoutBlockEqual(expectedState, blockState2, ignoredProperties)) {
            return true;
        }

        // A half of a double chest can temporarily become SINGLE while its neighbour is still
        // being built. Treat that runtime-managed pairing property as structurally equivalent,
        // but keep it in the placement state so the completed pair can still form correctly.
        if (blockState2.getBlock() == expectedState.getBlock()
                && expectedState.hasProperty(BlockStateProperties.CHEST_TYPE)
                && blockState2.hasProperty(BlockStateProperties.CHEST_TYPE)) {
            List<Property<?>> comparisonIgnored = new ArrayList<>(ignoredProperties);
            comparisonIgnored.add(BlockStateProperties.CHEST_TYPE);
            return BlockUtil.blockStatesWithoutBlockEqual(expectedState, blockState2, comparisonIgnored);
        }
        return false;
    }

    public CompoundTag serializeNBT() {
        CompoundTag nbt = new CompoundTag();
        nbt.put(
            "requiredBlockOffsets",
            BuildersNbtUtil.writeBlockPosList(requiredBlockOffsets.stream())
        );
        nbt.put("blockState", NbtUtils.writeBlockState(blockState));
        nbt.put(
            "ignoredProperties",
            NBTUtilBC.writeStringList(
                ignoredProperties.stream()
                    .map(Property::getName)
            )
        );
        if (tileNbt != null) {
            nbt.put("tileNbt", tileNbt);
        }
        if (deferInventoryContents) {
            nbt.putBoolean("deferInventoryContents", true);
        }
        if (deferInventoryRuntimeClear) {
            nbt.putBoolean("deferInventoryRuntimeClear", true);
        }
        if (!deferredInventoryItems.isEmpty()) {
            nbt.put(
                "deferredInventoryItems",
                NBTUtilBC.writeObjectList(deferredInventoryItems.stream().map(DeferredInventoryItem::serializeNBT))
            );
        }
        nbt.put("tileRotation", NBTUtilBC.writeEnum(tileRotation));
        nbt.putString("placeBlock", BuiltInRegistries.BLOCK.getKey(placeBlock).toString());
        nbt.put(
            "updateBlockOffsets",
            BuildersNbtUtil.writeBlockPosList(updateBlockOffsets.stream())
        );
        nbt.put(
            "canBeReplacedWithBlocks",
            NBTUtilBC.writeStringList(
                canBeReplacedWithBlocks.stream()
                    .map(BuiltInRegistries.BLOCK::getKey)
                    .map(Object::toString)
            )
        );
        return nbt;
    }

    public void deserializeNBT(CompoundTag nbt) throws InvalidInputDataException {
        BuildersNbtUtil.readBlockPosList(nbt.get("requiredBlockOffsets"))
            .forEach(requiredBlockOffsets::add);
        blockState = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, NbtCompat.getCompound(nbt, "blockState"));
        NBTUtilBC.readStringList(nbt.get("ignoredProperties"))
            .map(propertyName ->
                blockState.getProperties().stream()
                    .filter(property -> property.getName().equals(propertyName))
                    .findFirst()
                    .orElse(null)
            )
            .forEach(ignoredProperties::add);
        if (nbt.contains("tileNbt")) {
            tileNbt = NbtCompat.getCompound(nbt, "tileNbt");
        }
        deferInventoryContents = NbtCompat.getBoolean(nbt, "deferInventoryContents");
        deferInventoryRuntimeClear = NbtCompat.getBoolean(nbt, "deferInventoryRuntimeClear");
        if (deferInventoryRuntimeClear && !deferInventoryContents) {
            throw new InvalidInputDataException("Runtime inventory clear requires deferred inventory contents");
        }
        deferredInventoryItems.clear();
        for (CompoundTag entryNbt : NBTUtilBC.readCompoundList(nbt.get("deferredInventoryItems")).toList()) {
            DeferredInventoryItem entry = new DeferredInventoryItem(entryNbt);
            if (entry.slot < 0 || entry.stack.isEmpty()) {
                throw new InvalidInputDataException(
                    "Deferred inventory entry contains an invalid slot or an unavailable/missing item"
                );
            }
            deferredInventoryItems.add(entry);
        }
        if (deferInventoryContents && deferredInventoryItems.isEmpty()) {
            deferInventoryRuntimeClear = false;
        }
        tileRotation = NBTUtilBC.readEnum(nbt.get("tileRotation"), Rotation.class);
        Identifier placeBlockId = Identifier.parse(NbtCompat.getString(nbt, "placeBlock"));
        placeBlock = BuiltInRegistries.BLOCK.getOptional(placeBlockId)
            .orElseThrow(() -> new InvalidInputDataException("Unknown placement block " + placeBlockId));
        BuildersNbtUtil.readBlockPosList(nbt.get("updateBlockOffsets"))
            .forEach(updateBlockOffsets::add);
        NBTUtilBC.readStringList(nbt.get("canBeReplacedWithBlocks"))
            .map(Identifier::parse)
            .flatMap(id -> BuiltInRegistries.BLOCK.getOptional(id).stream())
            .forEach(canBeReplacedWithBlocks::add);
    }

    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        SchematicBlockDefault that = (SchematicBlockDefault) o;

        return requiredBlockOffsets.equals(that.requiredBlockOffsets) &&
            blockState.equals(that.blockState) &&
            ignoredProperties.equals(that.ignoredProperties) &&
            (tileNbt != null ? tileNbt.equals(that.tileNbt) : that.tileNbt == null) &&
            deferInventoryContents == that.deferInventoryContents &&
            deferInventoryRuntimeClear == that.deferInventoryRuntimeClear &&
            deferredInventoryItems.equals(that.deferredInventoryItems) &&
            tileRotation == that.tileRotation &&
            placeBlock.equals(that.placeBlock) &&
            updateBlockOffsets.equals(that.updateBlockOffsets) &&
            canBeReplacedWithBlocks.equals(that.canBeReplacedWithBlocks);
    }

    public int hashCode() {
        int result = requiredBlockOffsets.hashCode();
        result = 31 * result + blockState.hashCode();
        result = 31 * result + ignoredProperties.hashCode();
        result = 31 * result + (tileNbt != null ? tileNbt.hashCode() : 0);
        result = 31 * result + Boolean.hashCode(deferInventoryContents);
        result = 31 * result + Boolean.hashCode(deferInventoryRuntimeClear);
        result = 31 * result + deferredInventoryItems.hashCode();
        result = 31 * result + tileRotation.hashCode();
        result = 31 * result + placeBlock.hashCode();
        result = 31 * result + updateBlockOffsets.hashCode();
        result = 31 * result + canBeReplacedWithBlocks.hashCode();
        return result;
    }

    protected static final class DeferredInventoryItem {
        private final int slot;
        private final Direction accessSide;
        private final ItemStack stack;

        private DeferredInventoryItem(int slot, Direction accessSide, ItemStack stack) {
            this.slot = slot;
            this.accessSide = accessSide;
            this.stack = stack.copy();
        }

        private DeferredInventoryItem(CompoundTag nbt) {
            this.slot = NbtCompat.getInt(nbt, "slot");
            String sideName = NbtCompat.getString(nbt, "side");
            this.accessSide = Stream.of(Direction.values())
                .filter(side -> side.getName().equals(sideName))
                .findFirst()
                .orElse(null);
            this.stack = ItemStackUtil.parseOptional(ItemStackUtil.requireActiveRegistryProvider(), NbtCompat.getCompound(nbt, "stack"));
        }

        private DeferredInventoryItem copy() {
            return new DeferredInventoryItem(slot, accessSide, stack);
        }

        private CompoundTag serializeNBT() {
            CompoundTag nbt = new CompoundTag();
            nbt.putInt("slot", slot);
            if (accessSide != null) {
                nbt.putString("side", accessSide.getName());
            }
            nbt.put("stack", ItemStackUtil.saveOptional(stack, ItemStackUtil.requireActiveRegistryProvider()));
            return nbt;
        }

        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof DeferredInventoryItem other)) {
                return false;
            }
            return slot == other.slot
                && accessSide == other.accessSide
                && stack.getCount() == other.stack.getCount()
                && ItemStack.isSameItemSameComponents(stack, other.stack);
        }

        public int hashCode() {
            int result = 31 * slot + (accessSide == null ? 0 : accessSide.hashCode());
            result = 31 * result + stack.getCount();
            return 31 * result + ItemStack.hashItemAndComponents(stack);
        }
    }

    private record DeferredInventoryAccess(Direction side, ItemStorage storage) {
    }
}
