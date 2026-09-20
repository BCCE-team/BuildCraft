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
import buildcraft.lib.misc.NBTUtilBC;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
//? if >=1.20 {
/*?
import net.minecraft.core.registries.BuiltInRegistries;
?*/
//?}
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

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
        ResourceLocation registryName = ForgeRegistries.BLOCKS.getKey(context.block);
        // noinspection ConstantConditions
        return registryName != null &&
            RulesLoader.READ_DOMAINS.contains(registryName.getNamespace()) &&
            RulesLoader.getRules(
                context.blockState,
                context.blockState.hasBlockEntity() && context.world.getBlockEntity(context.pos) != null
                    ? context.world.getBlockEntity(context.pos).serializeNBT()
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
        if (context.blockState.hasBlockEntity()) {
            BlockEntity tileEntity = context.world.getBlockEntity(context.pos);
            if (tileEntity != null) {
                CompoundTag originalTileNbt = tileEntity.serializeNBT();
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
                        deferInventoryContents = false;
                    }
                }
                if (!deferInventoryContents) {
                    tileNbt = originalTileNbt.copy();
                    deferredInventoryItems.clear();
                    InventoryContentPolicy.stripDisallowedBlockContent(context.blockState, tileNbt, rules);
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
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) {
                    deferredInventoryItems.add(new DeferredInventoryItem(slot, stack.copy()));
                }
            }
            return true;
        }

        ItemStorage handler = PlatformStorage.items(blockEntity, null);
        if (handler == null) {
            return false;
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                deferredInventoryItems.add(new DeferredInventoryItem(slot, stack.copy()));
            }
        }
        return true;
    }

    private static CompoundTag createInventoryClearedNbt(
        BlockState state, CompoundTag originalNbt, List<DeferredInventoryItem> deferredItems, Set<JsonRule> rules
    ) {
        // Never attach a copied block entity to the real world here. In particular, Forge's chest
        // capability resolves a combined container through Level + BlockPos, so mutating that
        // "copy" also mutates the actual chest in the world. Work only on copied NBT instead.
        CompoundTag emptiedNbt = originalNbt.copy();
        InventoryContentPolicy.stripCopiedBlockContent(state, emptiedNbt, rules);
        removeSerializedDeferredItems(emptiedNbt, deferredItems);

        // Unknown mod inventories may serialize their slots in a custom format that cannot be
        // recognised safely. Fall back to pre-filled-NBT compatibility behavior rather than duplicate
        // items or touch the live block entity.
        if (!deferredItems.isEmpty() && emptiedNbt.equals(originalNbt)) {
            return null;
        }
        return emptiedNbt;
    }

    private static void removeSerializedDeferredItems(Tag tag, List<DeferredInventoryItem> deferredItems) {
        if (tag instanceof CompoundTag compoundTag) {
            for (String key : new ArrayList<>(compoundTag.getAllKeys())) {
                Tag child = compoundTag.get(key);
                if (child instanceof CompoundTag childCompound && isDeferredItemStack(childCompound, deferredItems)) {
                    compoundTag.remove(key);
                } else if (child != null) {
                    removeSerializedDeferredItems(child, deferredItems);
                }
            }
        } else if (tag instanceof ListTag listTag) {
            for (int index = listTag.size() - 1; index >= 0; index--) {
                Tag child = listTag.get(index);
                if (child instanceof CompoundTag childCompound && isDeferredItemStack(childCompound, deferredItems)) {
                    listTag.remove(index);
                } else {
                    removeSerializedDeferredItems(child, deferredItems);
                }
            }
        }
    }

    private static boolean isDeferredItemStack(
        CompoundTag tag, List<DeferredInventoryItem> deferredItems
    ) {
        if (!tag.contains("id", Tag.TAG_STRING)) {
            return false;
        }
        try {
            ItemStack serialized = ItemStack.of(tag);
            return !serialized.isEmpty() && deferredItems.stream()
                .anyMatch(entry -> ItemStack.isSameItemSameTags(entry.stack, serialized));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static ItemStack insertIntoExactSlot(ItemStorage handler, int slot, ItemStack stack, boolean simulate) {
        ItemStack remaining = stack.copy();
        int before = remaining.getCount();
        if (handler instanceof MutableItemStorage modifiable) {
            remaining = insertIntoModifiableSlot(modifiable, slot, remaining, simulate);
        }
        if (!remaining.isEmpty() && remaining.getCount() == before) {
            remaining = handler.insertItem(slot, remaining, simulate);
        }
        return remaining;
    }

    private static ItemStack insertIntoModifiableSlot(
        MutableItemStorage handler, int slot, ItemStack stack, boolean simulate
    ) {
        ItemStack current = handler.getStackInSlot(slot);
        if (!current.isEmpty() && !ItemStack.isSameItemSameTags(current, stack)) {
            return stack;
        }
        int limit = Math.min(handler.getSlotLimit(slot), stack.getMaxStackSize());
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
            return stack;
        }
        ItemStack after = handler.getStackInSlot(slot);
        int inserted = !after.isEmpty() && ItemStack.isSameItemSameTags(after, stack)
            ? Math.max(0, after.getCount() - currentCount)
            : 0;
        ItemStack remaining = stack.copy();
        remaining.shrink(Math.min(inserted, stack.getCount()));
        return remaining;
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
                if (ItemStack.isSameItemSameTags(wanted, candidate)) {
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
            .map((id) -> ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id)))
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
                .map(Direction::getNormal)
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
            .map((id) -> ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id)))
            .forEach(canBeReplacedWithBlocks::add);
        canBeReplacedWithBlocks.add(context.block);
        canBeReplacedWithBlocks.add(placeBlock);
    }

    @Override
    public void init(SchematicBlockContext context) {
        // noinspection ConstantConditions
        Set<JsonRule> rules = RulesLoader.getRules(
            context.blockState,
            context.blockState.hasBlockEntity() && context.world.getBlockEntity(context.pos) != null
                ? context.world.getBlockEntity(context.pos).serializeNBT()
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
    @Override
    public Set<BlockPos> getRequiredBlockOffsets() {
        return requiredBlockOffsets;
    }

    @Nonnull
    @Override
    public List<ItemStack> computeRequiredItems(Level level) {
        if (!deferInventoryContents) {
            return computeLegacyRequiredItems(level);
        }
        List<ItemStack> items = new ArrayList<>(computeRequiredItemsForPlacement(level));
        items.addAll(computeDeferredRequiredItems(level));
        return items;
    }

    @Nonnull
    @Override
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
    @Override
    public List<ItemStack> computeDeferredRequiredItems(Level level) {
        return deferredInventoryItems.stream()
            .map(entry -> entry.stack.copy())
            .collect(Collectors.toList());
    }

    /**
     * Returns the inventory belonging to this exact block entity. Vanilla double chests expose two
     //? if <1.20 {
     * separate {@link Container}s, but their Forge capability may resolve to one combined 54-slot
     * handler. Deferred schematic entries store local slot numbers for each half, so using the
     * combined handler would make both halves target slots 0-26 and can falsely mark the second
     * half as complete.
     //?} else {
     /*?
     * separate Containers, but their block capability may resolve to one combined 54-slot handler.
     * Deferred schematic entries store local slot numbers for each half, so use the local Container
     * whenever one is available.
     ?*/
     //?}
     */
    private static ItemStorage getDeferredInventoryHandler(BlockEntity blockEntity) {
        if (blockEntity instanceof Container container) {
            return PlatformStorage.localInventory(container);
        }
        return PlatformStorage.items(blockEntity, null);
    }

    @Nonnull
    @Override
    public List<ItemStack> computeMissingDeferredRequiredItems(Level level, BlockPos blockPos) {
        if (deferredInventoryItems.isEmpty()) {
            return List.of();
        }
        BlockEntity blockEntity = level.getBlockEntity(blockPos);
        if (blockEntity == null) {
            return computeDeferredRequiredItems(level);
        }
        ItemStorage handler = getDeferredInventoryHandler(blockEntity);
        if (handler == null) {
            return computeDeferredRequiredItems(level);
        }

        List<ItemStack> available = new ArrayList<>();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                available.add(stack.copy());
            }
        }

        List<ItemStack> missing = new ArrayList<>();
        for (DeferredInventoryItem entry : deferredInventoryItems) {
            int remaining = entry.stack.getCount();
            for (ItemStack present : available) {
                if (remaining <= 0) {
                    break;
                }
                if (ItemStack.isSameItemSameTags(entry.stack, present)) {
                    int used = Math.min(remaining, present.getCount());
                    remaining -= used;
                    present.shrink(used);
                }
            }
            if (remaining > 0) {
                ItemStack stack = entry.stack.copy();
                stack.setCount(remaining);
                missing.add(stack);
            }
        }
        return missing;
    }

    @Nonnull
    @Override
    public ItemStack insertDeferredItem(Level level, BlockPos blockPos, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        BlockEntity blockEntity = level.getBlockEntity(blockPos);
        if (blockEntity == null) {
            return stack;
        }
        ItemStorage handler = getDeferredInventoryHandler(blockEntity);
        if (handler == null) {
            return stack;
        }

        ItemStack remaining = stack.copy();
        Set<Integer> preferredSlots = deferredInventoryItems.stream()
            .filter(entry -> ItemStack.isSameItemSameTags(entry.stack, stack))
            .map(entry -> entry.slot)
            .filter(slot -> slot >= 0 && slot < handler.getSlots())
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        for (int slot : preferredSlots) {
            remaining = insertIntoExactSlot(handler, slot, remaining, simulate);
            if (remaining.isEmpty()) {
                break;
            }
        }
        for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++) {
            if (!preferredSlots.contains(slot)) {
                remaining = handler.insertItem(slot, remaining, simulate);
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
    @Override
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

    @Override
    public SchematicBlockDefault getRotated(Rotation rotation) {
        SchematicBlockDefault schematicBlock = SchematicBlockManager.createCleanCopy(this);
        requiredBlockOffsets.stream()
            .map(blockPos -> blockPos.rotate(rotation))
            .forEach(schematicBlock.requiredBlockOffsets::add);
        schematicBlock.blockState = blockState.rotate(rotation);
        schematicBlock.ignoredProperties.addAll(ignoredProperties);
        schematicBlock.tileNbt = tileNbt;
        schematicBlock.deferInventoryContents = deferInventoryContents;
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

    @Override
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

    @Override
    @SuppressWarnings("Duplicates")
    public boolean build(Level level, BlockPos blockPos) {
        return buildInternal(level, blockPos, null, false);
    }

    @Override
    @SuppressWarnings("Duplicates")
    public boolean build(Level level, BlockPos blockPos, Player actor) {
        return buildInternal(level, blockPos, actor, true);
    }

    private boolean buildInternal(Level level, BlockPos blockPos, Player actor, boolean firePlaceEvent) {
        if (placeBlock == Blocks.AIR) {
            return true;
        }
        level.getProfiler().push("prepare block");
        BlockState newBlockState = getPlacementState(level, blockPos);
        if (!newBlockState.canSurvive(level, blockPos)) {
            level.getProfiler().pop();
            return false;
        }
        level.getProfiler().pop();
        level.getProfiler().push("place block");
        boolean b = firePlaceEvent
            ? BlockUtil.placeBlock(level, blockPos, newBlockState, actor, Direction.UP, 11)
            : level.setBlock(blockPos, newBlockState, 11);
        level.getProfiler().pop();
        if (b) {
            BlockState placedBlockState = level.getBlockState(blockPos);
            level.getProfiler().push("notify");
            updateBlockOffsets.stream()
                .map(blockPos::offset)
                .forEach(updatePos -> level.updateNeighborsAt(updatePos, placeBlock));
            level.getProfiler().pop();
            if (tileNbt != null && placedBlockState.hasBlockEntity()) {
                level.getProfiler().push("prepare tile");
                Set<JsonRule> rules = RulesLoader.getRules(blockState, tileNbt);
                CompoundTag replaceNbt = rules.stream()
                    .map(rule -> rule.replaceNbt)
                    .filter(Objects::nonNull)
                    .map(Tag.class::cast)
                    .reduce(NBTUtilBC::merge)
                    .map(CompoundTag.class::cast)
                    .orElse(null);
                CompoundTag newTileNbt = new CompoundTag();
                tileNbt.getAllKeys().stream()
                    .map(key -> Pair.of(key, tileNbt.get(key)))
                    .forEach(kv -> newTileNbt.put(kv.getKey(), kv.getValue()));
                newTileNbt.putInt("x", blockPos.getX());
                newTileNbt.putInt("y", blockPos.getY());
                newTileNbt.putInt("z", blockPos.getZ());
                level.getProfiler().pop();
                level.getProfiler().push("place tile");
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
                    finalTileNbt
                );
                if (tileEntity != null) {
                    tileEntity.setLevel(level);
                    level.setBlockEntity(tileEntity);

                }
                level.getProfiler().pop();
            }
            return true;
        }
        return false;
    }

    @Override
    @SuppressWarnings("Duplicates")
    public boolean buildWithoutChecks(Level Level, BlockPos blockPos) {
        if (Level.setBlock(blockPos, blockState, 0)) {
            if (tileNbt != null && blockState.hasBlockEntity()) {
                CompoundTag newTileNbt = new CompoundTag();
                tileNbt.getAllKeys().stream()
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
                BlockEntity tileEntity = BlockEntity.loadStatic(blockPos, blockState, newTileNbt);
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

    @Override
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

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag nbt = new CompoundTag();
        nbt.put(
            "requiredBlockOffsets",
            NBTUtilBC.writeCompoundList(
                requiredBlockOffsets.stream()
                    .map(NbtUtils::writeBlockPos)
            )
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
        if (!deferredInventoryItems.isEmpty()) {
            nbt.put(
                "deferredInventoryItems",
                NBTUtilBC.writeObjectList(deferredInventoryItems.stream().map(DeferredInventoryItem::serializeNBT))
            );
        }
        nbt.put("tileRotation", NBTUtilBC.writeEnum(tileRotation));
        nbt.putString("placeBlock", ForgeRegistries.BLOCKS.getKey(placeBlock).toString());
        nbt.put(
            "updateBlockOffsets",
            NBTUtilBC.writeCompoundList(
                updateBlockOffsets.stream()
                    .map(NbtUtils::writeBlockPos)
            )
        );
        nbt.put(
            "canBeReplacedWithBlocks",
            NBTUtilBC.writeStringList(
                canBeReplacedWithBlocks.stream()
                    .map(ForgeRegistries.BLOCKS::getKey)
                    .map(Object::toString)
            )
        );
        return nbt;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) throws InvalidInputDataException {
        NBTUtilBC.readCompoundList(nbt.get("requiredBlockOffsets"))
            .map(NbtUtils::readBlockPos)
            .forEach(requiredBlockOffsets::add);
        //? if <1.20 {
        blockState = NbtUtils.readBlockState(nbt.getCompound("blockState"));
        //?} else {
        /*?
        blockState = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), nbt.getCompound("blockState"));
        ?*/
        //?}
        NBTUtilBC.readStringList(nbt.get("ignoredProperties"))
            .map(propertyName ->
                blockState.getProperties().stream()
                    .filter(property -> property.getName().equals(propertyName))
                    .findFirst()
                    .orElse(null)
            )
            .forEach(ignoredProperties::add);
        if (nbt.contains("tileNbt")) {
            tileNbt = nbt.getCompound("tileNbt");
        }
        deferInventoryContents = nbt.getBoolean("deferInventoryContents");
        deferredInventoryItems.clear();
        NBTUtilBC.readCompoundList(nbt.get("deferredInventoryItems"))
            .map(DeferredInventoryItem::new)
            .filter(entry -> !entry.stack.isEmpty())
            .forEach(deferredInventoryItems::add);
        tileRotation = NBTUtilBC.readEnum(nbt.get("tileRotation"), Rotation.class);
        placeBlock = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(nbt.getString("placeBlock")));
        NBTUtilBC.readCompoundList(nbt.get("updateBlockOffsets"))
            .map(NbtUtils::readBlockPos)
            .forEach(updateBlockOffsets::add);
        NBTUtilBC.readStringList(nbt.get("canBeReplacedWithBlocks"))
            .map(ResourceLocation::new)
            .map((id) -> ForgeRegistries.BLOCKS.getValue(id))
            .forEach(canBeReplacedWithBlocks::add);
    }

    @Override
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
            deferredInventoryItems.equals(that.deferredInventoryItems) &&
            tileRotation == that.tileRotation &&
            placeBlock.equals(that.placeBlock) &&
            updateBlockOffsets.equals(that.updateBlockOffsets) &&
            canBeReplacedWithBlocks.equals(that.canBeReplacedWithBlocks);
    }

    @Override
    public int hashCode() {
        int result = requiredBlockOffsets.hashCode();
        result = 31 * result + blockState.hashCode();
        result = 31 * result + ignoredProperties.hashCode();
        result = 31 * result + (tileNbt != null ? tileNbt.hashCode() : 0);
        result = 31 * result + Boolean.hashCode(deferInventoryContents);
        result = 31 * result + deferredInventoryItems.hashCode();
        result = 31 * result + tileRotation.hashCode();
        result = 31 * result + placeBlock.hashCode();
        result = 31 * result + updateBlockOffsets.hashCode();
        result = 31 * result + canBeReplacedWithBlocks.hashCode();
        return result;
    }

    protected static final class DeferredInventoryItem {
        private final int slot;
        private final ItemStack stack;

        private DeferredInventoryItem(int slot, ItemStack stack) {
            this.slot = slot;
            this.stack = stack.copy();
        }

        private DeferredInventoryItem(CompoundTag nbt) {
            this.slot = nbt.getInt("slot");
            this.stack = ItemStack.of(nbt.getCompound("stack"));
        }

        private DeferredInventoryItem copy() {
            return new DeferredInventoryItem(slot, stack);
        }

        private CompoundTag serializeNBT() {
            CompoundTag nbt = new CompoundTag();
            nbt.putInt("slot", slot);
            nbt.put("stack", stack.serializeNBT());
            return nbt;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof DeferredInventoryItem other)) {
                return false;
            }
            return slot == other.slot
                && stack.getCount() == other.stack.getCount()
                && ItemStack.isSameItemSameTags(stack, other.stack);
        }

        @Override
        public int hashCode() {
            return 31 * slot + stack.serializeNBT().hashCode();
        }
    }
}
