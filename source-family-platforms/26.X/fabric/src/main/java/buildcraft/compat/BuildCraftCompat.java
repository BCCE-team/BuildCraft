/* Copyright (c) 2026 the BCCE contributors. Licensed under MPL-2.0. */
package buildcraft.compat;

import net.fabricmc.api.ModInitializer;

/**
 * Optional integration module for Fabric 26.3. JEI and Jade discover their
 * entrypoints only when the corresponding mod is installed; this initializer
 * does not eagerly load their client classes or require either dependency.
 */
public final class BuildCraftCompat implements ModInitializer {
    public static final String MODID = "buildcraftcompat";

    @Override
    public void onInitialize() {
        // Mod-neutral integrations are registered by BuildCraft API v2.
        // Optional JEI/Jade integration is discovered from fabric.mod.json.
        // No Forestry/Create API for this target is linked here.
    }
}
