/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.silicon.client;

import buildcraft.lib.platform.client.PlatformClientRegistration;
import buildcraft.silicon.BCSilicon;
import buildcraft.silicon.BCSiliconClientGuis;
import buildcraft.silicon.BCSiliconClientRenderers;
import buildcraft.silicon.BCSiliconItems;
import buildcraft.silicon.BCSiliconModels;
import buildcraft.silicon.BCSiliconSprites;
import buildcraft.silicon.client.model.NativePluggableItemModels2612;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/** Client-only Silicon registration. No client types are loaded by the common bootstrap. */
public final class FabricSiliconClient {
    private static boolean bootstrapped;
    private FabricSiliconClient() {}

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;

        BCSiliconSprites.fmlPreInit();
        BCSiliconModels.fmlPreInit();
        BCSiliconModels.init();
        BCSiliconItems.registerItemModelProperties(PlatformClientRegistration.rangeProperties());
        PlatformClientRegistration.tintSources().register(
            Identifier.fromNamespaceAndPath(BCSilicon.MODID, "lens_colour"),
            LensItemTintSource.MAP_CODEC);
        BCSiliconClientGuis.clientInit(PlatformClientRegistration.screens());
        BCSiliconClientRenderers.register(PlatformClientRegistration.renderers());
        // Scan facade materials on JOIN, after the client registries and API
        // extensions are ready; early packet reads preserve saved BlockStates.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> BCSilicon.initializeFacadeStates());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
            buildcraft.silicon.BCSiliconRecipeSync.Client.clear());

        ModelLoadingPlugin.register(context -> context.modifyItemModelAfterBake().register(
            (model, info) -> NativePluggableItemModels2612.replace(model, info.itemId())));

        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            Identifier.fromNamespaceAndPath(BCSilicon.MODID, "silicon_model_cache"),
            new SimplePreparableReloadListener<Void>() {
                @Override
                protected Void prepare(ResourceManager manager, ProfilerFiller profiler) {
                    return null;
                }

                @Override
                protected void apply(Void prepared, ResourceManager manager, ProfilerFiller profiler) {
                    BCSiliconModels.clearAtlasDependentCaches();
                }
            });
    }
}
