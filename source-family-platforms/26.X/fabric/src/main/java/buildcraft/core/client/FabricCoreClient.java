/*
 * Copyright (c) 2026 the BuildCraft Community Edition contributors.
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.core.client;

import buildcraft.core.BCCore;
import buildcraft.core.BCCoreClientRenderers;
import buildcraft.core.BCCoreItems;
import buildcraft.core.BCCoreSprites;
import buildcraft.core.client.render.RenderVolumeBoxes;
import buildcraft.core.list.GuiList;
import buildcraft.core.marker.volume.MessageVolumeBoxes;
import buildcraft.core.marker.volume.MessageVolumeBoxesClientHandler;
import buildcraft.lib.client.render.DetachedRenderer;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.platform.client.PlatformClientRegistration;

/**
 * Fabric counterpart to NeoForge 26.3's BCCoreClientModEvents.
 * Kept out of the common entrypoint to preserve dedicated-server class safety.
 */
public final class FabricCoreClient {
    private static boolean bootstrapped;

    private FabricCoreClient() {}

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;

        PlatformClientRegistration.screens().register(BCCore.LIST_MENU.get(), GuiList::new);
        BCCoreItems.registerItemModelProperties(PlatformClientRegistration.rangeProperties());
        PlatformClientRegistration.tintSources().register(
            net.minecraft.resources.Identifier.fromNamespaceAndPath(BCCore.MODID, "fluid_shard_contents"),
            FluidShardTintSource.MAP_CODEC);
        BCCoreClientRenderers.register(PlatformClientRegistration.renderers());
        BCCoreSprites.init();
        DetachedRenderer.INSTANCE.addRenderer(
            DetachedRenderer.RenderMatrixType.FROM_WORLD_ORIGIN,
            RenderVolumeBoxes.INSTANCE);
        MessageManager.setHandler(MessageVolumeBoxes.class,
            MessageVolumeBoxesClientHandler::handle, BCNetworkSide.CLIENT);
        CoreWorldGeometry.register();
    }
}
