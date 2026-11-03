/*
 * Copyright (c) 2026 the BuildCraft Community Edition contributors
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.builders.snapshot;

import java.util.ArrayList;
import buildcraft.lib.misc.BlockUtil;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import buildcraft.lib.internal.core.InvalidInputDataException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.Locale;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.Items;
import net.minecraft.resources.ResourceLocation;
//? if <1.20 {
import net.minecraft.core.Registry;
//? } else {
/*?
import net.minecraft.core.registries.BuiltInRegistries;
?*/
//? }

/**
 * The contents of a blueprint are portable, user-editable data, not a trusted world save.
 * A valid content hash authenticates neither its author nor the resource values inside it.
 * Never restore owner identities, transient resources, arbitrary machine inventories or
 * privileged vanilla block entities from a blueprint.
 */
public final class BlueprintSecurity {
    private static final Set<String> PRIVILEGED_BLOCKS = Set.of(
        "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
        "minecraft:structure_block", "minecraft:jigsaw", "minecraft:spawner",
        "minecraft:trial_spawner", "minecraft:vault", "minecraft:end_gateway",
        "minecraft:end_portal", "minecraft:end_portal_frame", "minecraft:nether_portal",
        "minecraft:bedrock", "minecraft:barrier", "minecraft:structure_void", "minecraft:light"
    );

    private static final Set<String> RESOURCE_KEYS = Set.of(
        "owner", "owneruuid", "ownerid", "ownername", "owners", "items", "inventory",
        "inventories", "contents", "storeditems", "storeditem", "tanks", "tank", "fluidtanks",
        "fluids", "fluid", "storedfluid", "storedfluids", "gases", "gas", "energy",
        "energystored", "storedenergy", "battery", "mj", "fe", "rf", "charge", "power",
        "fuel", "fueltank", "fuelamount", "burntime", "xp", "experience", "pendingfluidrefunds",
        "reserveditems", "reservedfluids", "reservedresources", "storedresources",
        "apicomponents", "loottable", "loottableseed", "loot_table", "loot_table_seed",
        "loot", "loottables", "item", "book", "recorditem", "bees", "bee_data",
        "storedexperience", "exp", "spawndata", "spawnpotentials", "blockentitytag",
        "blockentitydata", "builder", "blueprintbuilder", "templatebuilder", "snapshotbuilder",
        "pendingbuilderstate", "placetasks", "breaktasks", "tasks", "activejob", "job",
        "progress", "reserved", "pending", "pendingtasks", "creator", "createdby",
        "trusted", "trustedplayers", "permissions", "acl", "security"
    );

    private static final Set<String> ENTITY_UNSAFE_KEYS = Set.of(
        "passengers", "uuid", "owner", "owneruuid", "ownerid", "ownername",
        "invulnerable", "command", "commands", "brain", "attributes", "activeeffects",
        "active_effects", "leash", "leashed", "tags", "motion", "portal_cooldown",
        "loottable", "loottableseed", "loot_table", "loot_table_seed",
        "creator", "createdby", "trusted", "trustedplayers", "permissions", "acl"
    );

    private BlueprintSecurity() {}

    /** Resolve the same registry identifier on both BC8's 1.19.2 Forge and modern targets. */
    private static ResourceLocation blockId(Block block) {
        //? if <1.20 {
        return Registry.BLOCK.getKey(block);
        //? } else {
        /*?
        return BuiltInRegistries.BLOCK.getKey(block);
        ?*/
        //? }
    }

    public static boolean isPrivilegedBlock(BlockState state) {
        if (state == null) return true;
        ResourceLocation id = blockId(state.getBlock());
        return id == null || PRIVILEGED_BLOCKS.contains(id.toString());
    }

    /**
     * Non-BuildCraft addon machines have no declared safe NBT schema. They may still
     * be copied as blocks and filled with charged items via the deferred inventory
     * mechanism, but their opaque server state must start from defaults. An addon
     * that needs to copy configuration can implement a typed API v2 schematic codec.
     */
    public static void validateDefaultBlock(BlockState source, Block placed, CompoundTag tile,
        Collection<BlockPos> requiredOffsets, Collection<BlockPos> updateOffsets,
        Collection<Block> replaceable, Collection<Property<?>> ignored, Set<JsonRule> rules)
        throws InvalidInputDataException {
        if (source == null || source.isAir() || placed == null || isPrivilegedBlock(source)
            || BlockUtil.getFluid(source.getBlock()) != Fluids.EMPTY
            || !RulesLoader.READ_DOMAINS.contains(blockId(source.getBlock()).getNamespace())
            || isPrivilegedBlock(placed.defaultBlockState()) || rules.stream().anyMatch(rule -> rule.ignore)) {
            throw new InvalidInputDataException("Blueprint contains a forbidden or ignored block");
        }
        String actual = blockId(placed).toString();
        boolean allowedPlacement = placed == source.getBlock()
            || rules.stream().map(rule -> rule.placeBlock).filter(Objects::nonNull)
                .anyMatch(actual::equals);
        if (!allowedPlacement) {
            throw new InvalidInputDataException("Blueprint placement block is not authorized by server rules: " + actual);
        }
        if (tile != null && (!source.hasBlockEntity() || !placed.defaultBlockState().hasBlockEntity())) {
            throw new InvalidInputDataException("Blueprint contains NBT for a non-block-entity placement");
        }
        Set<String> allowedIgnored = new HashSet<>();
        rules.stream().map(rule -> rule.ignoredProperties).filter(Objects::nonNull)
            .flatMap(Collection::stream).forEach(allowedIgnored::add);
        for (Property<?> property : ignored) {
            if (property == null || !allowedIgnored.contains(property.getName())) {
                throw new InvalidInputDataException("Blueprint contains unauthorized ignored block properties");
            }
        }
        Set<BlockPos> permittedRequired = new HashSet<>();
        rules.stream().map(rule -> rule.requiredBlockOffsets).filter(Objects::nonNull)
            .flatMap(Collection::stream).forEach(permittedRequired::add);
        if (source.getBlock() instanceof FallingBlock) permittedRequired.add(new BlockPos(0, -1, 0));
        // An uncraftable state with no explicit material rule must not be a free
        // blueprint entry. This includes fire and a number of server-only blocks.
        if (source.getBlock().asItem() == Items.AIR && rules.stream().noneMatch(rule ->
            rule.requiredExtractors != null && !rule.requiredExtractors.isEmpty())) {
            throw new InvalidInputDataException("Blueprint block has no obtainable item or cost rule");
        }
        if (!permittedRequired.containsAll(requiredOffsets) || !requiredOffsets.containsAll(permittedRequired)) {
            throw new InvalidInputDataException("Blueprint altered the required placement dependencies");
        }
        Set<BlockPos> permittedUpdates = new HashSet<>();
        if (rules.stream().anyMatch(rule -> rule.updateBlockOffsets != null)) {
            rules.stream().map(rule -> rule.updateBlockOffsets).filter(Objects::nonNull)
                .flatMap(Collection::stream).forEach(permittedUpdates::add);
        } else {
            for (Direction dir : Direction.values()) permittedUpdates.add(
                new BlockPos(dir.getStepX(), dir.getStepY(), dir.getStepZ()));
            permittedUpdates.add(BlockPos.ZERO);
        }
        if (!permittedUpdates.containsAll(updateOffsets) || !updateOffsets.containsAll(permittedUpdates)) {
            throw new InvalidInputDataException("Blueprint altered block update positions");
        }
        Set<String> allowedReplacementIds = new HashSet<>();
        rules.stream().map(rule -> rule.canBeReplacedWithBlocks).filter(Objects::nonNull)
            .flatMap(Collection::stream).forEach(allowedReplacementIds::add);
        allowedReplacementIds.add(blockId(source.getBlock()).toString());
        allowedReplacementIds.add(actual);
        for (Block replacement : replaceable) {
            if (replacement == null || !allowedReplacementIds.contains(
                blockId(replacement).toString())) {
                throw new InvalidInputDataException("Blueprint forged an accepted replacement block");
            }
        }
        if (replaceable.size() > allowedReplacementIds.size()) {
            throw new InvalidInputDataException("Blueprint replacement whitelist is excessive");
        }
    }

    public static CompoundTag forBlockEntity(BlockState state, CompoundTag original) {
        CompoundTag result = original.copy();
        ResourceLocation blockKey = blockId(state.getBlock());
        String blockId = blockKey == null ? "unknown:unregistered" : blockKey.toString();
        if (!blockId.startsWith("minecraft:") && !blockId.startsWith("buildcraft")) {
            CompoundTag minimal = new CompoundTag();
            for (String tagKey : Set.of("id", "x", "y", "z", "CustomName")) {
                Tag value = result.get(tagKey);
                if (value != null) minimal.put(tagKey, value.copy());
            }
            return minimal;
        }
        stripResourceState(result, 0);
        return result;
    }

    /**
     * Pipes are special: their definition, colour, pluggables and wires are paid
     * for by their item drops. Live flow queues, stored fluids, API component
     * state and progress are NOT blueprint configuration and must not be copied.
     */
    public static CompoundTag forPipe(CompoundTag original) {
        CompoundTag result = original.copy();
        stripResourceState(result, 0);
        Tag pipeValue = result.get("pipe");
        if (pipeValue instanceof CompoundTag pipe) {
            pipe.put("flow", new CompoundTag());
            pipe.remove("apiComponents");
            pipe.remove("con");
            Tag behaviorValue = pipe.get("beh");
            if (behaviorValue instanceof CompoundTag behavior) {
                stripResourceState(behavior, 0);
                behavior.remove("progress");
                behavior.remove("progressTarget");
                behavior.remove("progressState");
            }
        }
        return result;
    }

    public static void stripUnsafeEntityState(CompoundTag entity) {
        for (String key : new ArrayList<>(entity.getAllKeys())) {
            if (ENTITY_UNSAFE_KEYS.contains(key.toLowerCase(Locale.ROOT))) entity.remove(key);
        }
    }

    private static void stripResourceState(CompoundTag tag, int depth) {
        if (depth > 32) {
            for (String key : new ArrayList<>(tag.getAllKeys())) tag.remove(key);
            return;
        }
        for (String key : new ArrayList<>(tag.getAllKeys())) {
            if (RESOURCE_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                tag.remove(key);
                continue;
            }
            Tag child = tag.get(key);
            if (child instanceof CompoundTag compound) {
                stripResourceState(compound, depth + 1);
            } else if (child instanceof ListTag list) {
                stripResourceList(list, depth + 1);
            }
        }
    }

    private static void stripResourceList(ListTag list, int depth) {
        for (Tag element : list) {
            if (element instanceof CompoundTag compound) stripResourceState(compound, depth);
            else if (element instanceof ListTag nested && depth <= 32) stripResourceList(nested, depth + 1);
        }
    }
}
