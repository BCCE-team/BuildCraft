package buildcraft.fabric;

import net.fabricmc.api.ClientModInitializer;

/** Client-side Fabric bootstrap slot; client gameplay/render registration is intentionally deferred. */
public final class BuildCraftFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Stage 3 loader skeleton only. Rendering integration is a later stage.
    }
}
