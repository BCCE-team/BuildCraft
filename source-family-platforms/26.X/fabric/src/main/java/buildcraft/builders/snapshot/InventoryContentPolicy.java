/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.snapshot;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.schematic.DataPath;
import buildcraft.api.v2.schematic.InventoryCopyPolicy;
import buildcraft.lib.misc.BlockUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.core.registries.BuiltInRegistries;

public final class InventoryContentPolicy {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson CONFIG_GSON = new GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(NbtPath.class, NbtPath.DESERIALIZER)
        .create();
    private static final String CONFIG_DIR = "buildcraft";
    private static final String CUSTOM_BLOCKS_FILE = "custom_blocks_simple_inventories.json";
    private static final String BLACKLIST_BLOCKS_FILE = "blacklist_blocks_simple_inventories.json";
    private static final List<NbtPath> COMMON_BLOCK_ITEM_PATHS = Collections.singletonList(NbtPath.of("Items"));
    private static final List<BlockContentConfigRule> CUSTOM_BLOCK_RULES = new ArrayList<>();
    private static final List<BlockContentConfigRule> BLACKLIST_BLOCK_RULES = new ArrayList<>();

    private InventoryContentPolicy() {
    }

    public static void loadConfig() {
        CUSTOM_BLOCK_RULES.clear();
        BLACKLIST_BLOCK_RULES.clear();
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_DIR);
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            LOGGER.warn("Failed to create BuildCraft config directory {}", configDir, e);
            return;
        }
        CUSTOM_BLOCK_RULES.addAll(readBlockRules(configDir.resolve(CUSTOM_BLOCKS_FILE)));
        BLACKLIST_BLOCK_RULES.addAll(readBlockRules(configDir.resolve(BLACKLIST_BLOCKS_FILE)));
    }

    public static Set<String> getAllowedBlockDomains() {
        Set<String> domains = new HashSet<>();
        CUSTOM_BLOCK_RULES.stream()
            .flatMap(rule -> rule.getSelectors().stream())
            .map(InventoryContentPolicy::getDomainFromSelector)
            .filter(Objects::nonNull)
            .forEach(domains::add);
        apiInventoryCopyPolicies().stream()
            .flatMap(policy -> policy.blockIds().stream())
            .map(Identifier::getNamespace)
            .forEach(domains::add);
        return domains;
    }

    /** Generic capability inventories are copied unless the whole block is explicitly blacklisted. */
    public static boolean canCopyGenericBlockItems(BlockState blockState) {
        return BLACKLIST_BLOCK_RULES.stream().noneMatch(rule -> rule.matches(blockState));
    }

    public static boolean canCopyBlockItems(BlockState blockState, NbtPath path) {
        if (path == null) {
            return false;
        }
        if (matchesAny(BLACKLIST_BLOCK_RULES, blockState, path)) {
            return false;
        }
        if (matchesAny(CUSTOM_BLOCK_RULES, blockState, path)) {
            return true;
        }
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(blockState.getBlock());
        if (blockId == null) {
            return false;
        }
        DataPath apiPath = new DataPath(path.getElements());
        List<InventoryCopyPolicy> policies = apiInventoryCopyPolicies(blockId);
        if (policies.stream().anyMatch(policy -> policy.deniedPaths().contains(apiPath))) {
            return false;
        }
        return policies.stream().anyMatch(policy -> policy.allowedPaths().contains(apiPath));
    }

    public static Set<NbtPath> getAllowedBlockItemPaths(BlockState blockState) {
        Set<NbtPath> paths = new LinkedHashSet<>();
        CUSTOM_BLOCK_RULES.stream()
            .filter(rule -> rule.matches(blockState))
            .flatMap(rule -> rule.getPaths().stream())
            .forEach(paths::add);
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(blockState.getBlock());
        if (blockId != null) {
            apiInventoryCopyPolicies(blockId).stream()
                .flatMap(policy -> policy.allowedPaths().stream())
                .map(path -> NbtPath.of(path.segments()))
                .forEach(paths::add);
            apiInventoryCopyPolicies(blockId).stream()
                .flatMap(policy -> policy.deniedPaths().stream())
                .map(path -> NbtPath.of(path.segments()))
                .forEach(paths::remove);
        }
        paths.removeIf(path -> matchesAny(BLACKLIST_BLOCK_RULES, blockState, path));
        return paths;
    }

    public static void stripDisallowedBlockContent(BlockState blockState, CompoundTag tileNbt, Set<JsonRule> rules) {
        if (tileNbt == null) {
            return;
        }
        getBlockItemListPathsToCheck(blockState, rules).stream()
            .filter(path -> !canCopyBlockItems(blockState, path))
            .forEach(path -> path.remove(tileNbt));
    }

    /**
     * Removes every known item-list path from block-entity NBT. Builder inventory contents are restored later through
     * the item-handler capability, so leaving them in NBT would place a pre-filled inventory and duplicate items.
     */
    public static void stripCopiedBlockContent(BlockState blockState, CompoundTag tileNbt, Set<JsonRule> rules) {
        if (tileNbt == null) {
            return;
        }
        getBlockItemListPathsToCheck(blockState, rules).forEach(path -> path.remove(tileNbt));
    }

    public static boolean canCopyEntityItems(Identifier entityId, NbtPath path) {
        if (entityId == null || path == null) {
            return false;
        }
        if (entityId.equals(Identifier.fromNamespaceAndPath("minecraft", "armor_stand"))) {
            return path.equals(NbtPath.of("HandItems")) || path.equals(NbtPath.of("ArmorItems"));
        }
        return false;
    }

    public static void stripDisallowedEntityContent(Identifier entityId, CompoundTag entityNbt, Set<JsonRule> rules) {
        if (entityNbt == null) {
            return;
        }
        getItemListPaths(rules).stream()
            .filter(path -> !canCopyEntityItems(entityId, path))
            .forEach(path -> path.remove(entityNbt));
    }

    public static boolean isItemListExtractorAllowedForBlock(BlockState blockState, RequiredExtractor extractor) {
        if (extractor instanceof RequiredExtractorItemsList itemsList) {
            return canCopyBlockItems(blockState, itemsList.getPath());
        }
        return true;
    }

    public static boolean isItemListExtractorAllowedForEntity(Identifier entityId, RequiredExtractor extractor) {
        if (extractor instanceof RequiredExtractorItemsList itemsList) {
            return canCopyEntityItems(entityId, itemsList.getPath());
        }
        return true;
    }

    private static Set<NbtPath> getBlockItemListPathsToCheck(BlockState blockState, Set<JsonRule> rules) {
        Set<NbtPath> paths = new LinkedHashSet<>();
        paths.addAll(COMMON_BLOCK_ITEM_PATHS);
        paths.addAll(getItemListPaths(rules));
        CUSTOM_BLOCK_RULES.stream()
            .filter(rule -> rule.matches(blockState))
            .flatMap(rule -> rule.getPaths().stream())
            .forEach(paths::add);
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(blockState.getBlock());
        if (blockId != null) {
            apiInventoryCopyPolicies(blockId).stream()
                .flatMap(policy -> policy.allowedPaths().stream())
                .map(path -> NbtPath.of(path.segments()))
                .forEach(paths::add);
            apiInventoryCopyPolicies(blockId).stream()
                .flatMap(policy -> policy.deniedPaths().stream())
                .map(path -> NbtPath.of(path.segments()))
                .forEach(paths::add);
        }
        BLACKLIST_BLOCK_RULES.stream()
            .filter(rule -> rule.matches(blockState))
            .flatMap(rule -> rule.getPaths().stream())
            .forEach(paths::add);
        return paths;
    }

    private static Set<NbtPath> getItemListPaths(Set<JsonRule> rules) {
        if (rules == null) {
            return Collections.emptySet();
        }
        return rules.stream()
            .map(rule -> rule.requiredExtractors)
            .filter(Objects::nonNull)
            .flatMap(Collection::stream)
            .filter(RequiredExtractorItemsList.class::isInstance)
            .map(RequiredExtractorItemsList.class::cast)
            .map(RequiredExtractorItemsList::getPath)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<InventoryCopyPolicy> apiInventoryCopyPolicies() {
        return new ArrayList<>(BuildCraftApi.registry(BuildCraftRegistries.INVENTORY_COPY_POLICIES).values());
    }

    private static List<InventoryCopyPolicy> apiInventoryCopyPolicies(Identifier blockId) {
        return apiInventoryCopyPolicies().stream().filter(policy -> policy.appliesTo(blockId)).collect(Collectors.toList());
    }

    private static boolean matchesAny(List<BlockContentConfigRule> rules, BlockState blockState, NbtPath path) {
        return rules.stream().anyMatch(rule -> rule.matches(blockState, path));
    }

    private static List<BlockContentConfigRule> readBlockRules(Path file) {
        if (!Files.exists(file)) {
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write("[]\n");
            } catch (IOException e) {
                LOGGER.warn("Failed to create BuildCraft inventory-copy config {}", file, e);
            }
            return Collections.emptyList();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<BlockContentConfigRule> rules = CONFIG_GSON.fromJson(
                reader,
                new TypeToken<List<BlockContentConfigRule>>() {
                }.getType()
            );
            return rules == null ? Collections.emptyList() : rules;
        } catch (Exception e) {
            LOGGER.warn("Failed to load BuildCraft inventory-copy config {}", file, e);
            return Collections.emptyList();
        }
    }

    private static String getDomainFromSelector(String selector) {
        if (selector == null) {
            return null;
        }
        String base = selector.contains("[") ? selector.substring(0, selector.indexOf('[')) : selector;
        int index = base.indexOf(':');
        return index > 0 ? base.substring(0, index) : null;
    }

    private static boolean selectorMatches(String selector, BlockState blockState) {
        if (selector == null) {
            return false;
        }
        boolean complex = selector.contains("[");
        if (complex && selector.indexOf(']') < selector.indexOf('[')) {
            return false;
        }
        String blockName = complex ? selector.substring(0, selector.indexOf('[')) : selector;
        Block block;
        try {
            block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(blockName)).orElse(null);
        } catch (RuntimeException e) {
            return false;
        }
        if (block != blockState.getBlock()) {
            return false;
        }
        if (!complex) {
            return true;
        }
        String properties = selector.substring(selector.indexOf('[') + 1, selector.indexOf(']'));
        if (properties.trim().isEmpty()) {
            return true;
        }
        return Stream.of(properties.split(","))
            .map(String::trim)
            .map(nameValue -> nameValue.split("=", 2))
            .allMatch(nameValue -> nameValue.length == 2 &&
                blockState.getProperties().stream()
                    .filter(property -> property.getName().equals(nameValue[0]))
                    .findFirst()
                    .map(property -> propertyMatches(blockState, property, nameValue[1]))
                    .orElse(false)
            );
    }

    private static <T extends Comparable<T>> boolean propertyMatches(BlockState state, Property<T> property, String value) {
        return BlockUtil.getPropertyStringValue(state, property).equals(value);
    }

    private static final class BlockContentConfigRule {
        private String block;
        private List<String> blocks;
        private List<String> selectors;
        private List<NbtPath> paths;

        private List<String> getSelectors() {
            List<String> allSelectors = new ArrayList<>();
            if (block != null) {
                allSelectors.add(block);
            }
            if (blocks != null) {
                allSelectors.addAll(blocks);
            }
            if (selectors != null) {
                allSelectors.addAll(selectors);
            }
            return allSelectors;
        }

        private List<NbtPath> getPaths() {
            return paths == null ? Collections.emptyList() : paths;
        }

        private boolean matches(BlockState blockState) {
            return getSelectors().stream().anyMatch(selector -> selectorMatches(selector, blockState));
        }

        private boolean matches(BlockState blockState, NbtPath path) {
            return matches(blockState) && (getPaths().isEmpty() || getPaths().contains(path));
        }
    }
}
