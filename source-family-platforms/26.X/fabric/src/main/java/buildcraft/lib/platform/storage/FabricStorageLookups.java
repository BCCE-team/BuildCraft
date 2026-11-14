/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.platform.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Registers the BuildCraft block-storage contract after every BCCE module has
 * registered its block-entity types. Fabric lookups then expose the same
 * sided endpoint to BuildCraft pipes, third-party pipes and vanilla transfer
 * consumers.
 *
 * <p>Only BCCE-owned types are bulk-registered. Addons can call
 * {@link #registerAddonType(BlockEntityType)} for their own block entities.</p>
 */
public final class FabricStorageLookups {
    private static boolean installed;
    private static final Set<BlockEntityType<?>> REGISTERED =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private FabricStorageLookups() {}

    public static synchronized void install() {
        if (installed) return;
        List<BlockEntityType<?>> buildcraftTypes = BuiltInRegistries.BLOCK_ENTITY_TYPE.stream()
            .filter(type -> {
                var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
                return id != null && id.getNamespace().startsWith("buildcraft");
            })
            .toList();
        if (!buildcraftTypes.isEmpty()) {
            registerTypes(buildcraftTypes.toArray(BlockEntityType<?>[]::new));
        }
        // Another Fabric mod may already own a block-specific provider. Our
        // registerTypes() deliberately does not replace it, but without these
        // fallbacks its null result would hide the BCCE tile's own storages.
        // A non-null native provider always retains priority.
        ItemStorage.SIDED.registerFallback((level, pos, state, tile, side) ->
            tile instanceof FabricBlockStorageProvider provider
                ? provider.buildcraftItemStorage(side) : null);
        FluidStorage.SIDED.registerFallback((level, pos, state, tile, side) ->
            tile instanceof FabricBlockStorageProvider provider
                ? provider.buildcraftFluidStorage(side) : null);
        team.reborn.energy.api.EnergyStorage.SIDED.registerFallback((level, pos, state, tile, side) ->
            tile instanceof FabricBlockStorageProvider provider
                ? provider.buildcraftEnergyStorage(side) : null);
        installed = true;
    }

    /** Register a non-BCCE block-entity type whose instance implements the provider contract. */
    public static synchronized void registerAddonType(BlockEntityType<?> type) {
        if (type == null) return;
        registerTypes(type);
    }

    /**
     * Vanilla keeps BlockEntityType.validBlocks private (NeoForge adds a getter).
     * Resolve the actual block set through the public isValid(BlockState) API;
     * this also works for two engine types that share the same engine block.
     */
    public static List<Block> validBlocksFor(BlockEntityType<?> type) {
        List<Block> blocks = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (type.isValid(block.defaultBlockState())) blocks.add(block);
        }
        return blocks;
    }

    private static synchronized void registerTypes(BlockEntityType<?>... types) {
        for (BlockEntityType<?> type : types) {
            if (type == null || REGISTERED.contains(type)) continue;
            var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
            List<Block> blocks = validBlocksFor(type);
            if (blocks.isEmpty()) {
                buildcraft.lib.internal.debug.BCLog.logger.warn(
                    "Could not register Fabric storage for block entity {}: no registered blocks", id);
                continue;
            }
            for (Block block : blocks) {
                // Fabric lookups are keyed by *block*. Use a generic provider
                // for every valid BE type and install it only once per block.
                // This preserves the actual runtime BE subtype (including
                // creative and redstone engines on the same engine block).
                if (ItemStorage.SIDED.getProvider(block) == null) {
                    ItemStorage.SIDED.registerForBlocks(
                        (level, pos, state, blockEntity, side) ->
                            blockEntity instanceof FabricBlockStorageProvider provider
                                ? provider.buildcraftItemStorage(side) : null,
                        block
                    );
                }
                if (FluidStorage.SIDED.getProvider(block) == null) {
                    FluidStorage.SIDED.registerForBlocks(
                        (level, pos, state, blockEntity, side) ->
                            blockEntity instanceof FabricBlockStorageProvider provider
                                ? provider.buildcraftFluidStorage(side) : null,
                        block
                    );
                }
                if (team.reborn.energy.api.EnergyStorage.SIDED.getProvider(block) == null) {
                    team.reborn.energy.api.EnergyStorage.SIDED.registerForBlocks(
                        (level, pos, state, blockEntity, side) ->
                            blockEntity instanceof FabricBlockStorageProvider provider
                                ? provider.buildcraftEnergyStorage(side) : null,
                        block
                    );
                }
            }
            REGISTERED.add(type);
        }
    }
}
