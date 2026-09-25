package buildcraft.fabric;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric loader bootstrap for the 1.20.1 skeleton target.
 *
 * <p>Gameplay bootstrap is intentionally deferred to the server-foundation stage.
 * Keeping this entry point empty proves Loom/remapping/metadata wiring without
 * creating a Fabric-only copy of BuildCraft gameplay.</p>
 */
public final class BuildCraftFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // Stage 3 loader skeleton only. Common module bootstrap is wired next.
    }
}
