package buildcraft.lib;

import buildcraft.lib.platform.events.BCEvents;
import buildcraft.lib.platform.events.PlatformEvents;
import buildcraft.lib.platform.events.PlatformClientEvents;
import buildcraft.lib.debug.BCAdvDebugging;
import buildcraft.lib.marker.MarkerCache;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.lib.misc.MessageUtil;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.net.cache.BuildCraftObjectCaches;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.entity.Entity;

/**
 * Fabric 26.3 lifecycle bindings for the same BCCE lib gameplay behavior.
 * Client model/atlas callbacks still require their own Fabric render stage.
 */
public final class BCLibEventDist {
    private static boolean registered;

    private BCLibEventDist() {}

    public static synchronized void registerGameplayEvents() {
        if (registered) return;
        registered = true;

        ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(
            Identifier.fromNamespaceAndPath("buildcraftlib", "machine_recipes"),
            new buildcraft.lib.recipe.MachineRecipeDatapackReloadListener()
        );

        PlatformEvents.entityJoin(BCLibEventDist::onEntityJoinWorld);
        PlatformEvents.levelUnload(BCLibEventDist::onWorldUnload);
        PlatformEvents.serverTick(BCEvents.Phase.END, BCLibEventDist::serverTick);

        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (!success) return;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                MessageUtil.doDelayedServer(1,
                    () -> buildcraft.lib.net.MessageGuideRecipeDisplays.sendAll(player));
            }
        });

        if (PlatformEvents.isClient()) ClientGame.registerGameplayEvents();
    }

    public static void onEntityJoinWorld(BCEvents.EntityJoin event) {
        Entity entity = event.getEntity();
        if (entity instanceof ServerPlayer player) {
            MessageUtil.doDelayedServer(20, () -> MarkerCache.onPlayerJoinLevel(player));
            MessageUtil.doDelayedServer(7, () -> buildcraft.lib.net.MessageMachineRecipes.sendAll(player));
            MessageUtil.doDelayedServer(5, () -> buildcraft.lib.net.MessageGuideRecipeDisplays.sendAll(player));
        }
    }

    public static void onWorldUnload(BCEvents.LevelUnload event) {
        MarkerCache.onLevelUnload(event.getLevel());
        if (event.getLevel() instanceof ServerLevel level) {
            if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                MessageUtil.clearServerTasks();
            }
            FakePlayerProvider.INSTANCE.unloadWorld(level);
            buildcraft.lib.platform.chunk.BCChunkTickets.unloadWorld(level);
        }
    }

    public static void serverTick(BCEvents.ServerTick event) {
        BCAdvDebugging.INSTANCE.onServerPostTick();
        MessageUtil.postServerTick();
    }

    /** This class is resolved only on a physical client. */
    public static final class ClientGame {
        private ClientGame() {}

        static void registerGameplayEvents() {
            PlatformClientEvents.login(ClientGame::onConnect);
            PlatformClientEvents.logout(ClientGame::onDisconnect);
            PlatformClientEvents.tick(BCEvents.Phase.END, event -> clientTick());
        }

        public static void setup() {
            buildcraft.lib.client.render.DetachedRenderer.INSTANCE.addRenderer(
                buildcraft.lib.client.render.DetachedRenderer.RenderMatrixType.FROM_WORLD_ORIGIN,
                buildcraft.lib.client.render.MarkerRenderer.INSTANCE);
            buildcraft.lib.client.render.DetachedRenderer.INSTANCE.addRenderer(
                buildcraft.lib.client.render.DetachedRenderer.RenderMatrixType.FROM_WORLD_ORIGIN,
                buildcraft.lib.debug.DebugRenderHelper.INSTANCE);
            BCLibSprites.fmlPreInitClient();
            buildcraft.lib.client.sprite.SpriteHolderRegistry.bootstrapBuiltinHolders();
            BCLibConfig.configChangeListeners.add(
                buildcraft.lib.client.reload.LibConfigChangeListener.INSTANCE);
            MessageManager.setHandler(buildcraft.lib.net.MessageMarker.class,
                buildcraft.lib.net.MessageMarker.HANDLER,
                buildcraft.lib.net.BCNetworkSide.CLIENT);
            MessageManager.setHandler(buildcraft.lib.net.cache.MessageObjectCacheResponse.class,
                buildcraft.lib.net.cache.MessageObjectCacheResponse.HANDLER,
                buildcraft.lib.net.BCNetworkSide.CLIENT);
            MessageManager.setHandler(buildcraft.lib.net.MessageDebugResponse.class,
                buildcraft.lib.net.MessageDebugResponse.HANDLER,
                buildcraft.lib.net.BCNetworkSide.CLIENT);
        }

        private static void onConnect() {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            buildcraft.lib.misc.ItemStackUtil.setClientRegistryProvider(
                mc.level == null ? null : mc.level.registryAccess());
            MarkerCache.clearClientCaches();
            buildcraft.lib.net.MessageMarkerClientHandler.clearQueuedMessages();
            buildcraft.lib.net.GuideRecipeDisplayCache.clear();
            BuildCraftObjectCaches.onClientJoinServer();
        }

        private static void onDisconnect() {
            buildcraft.lib.recipe.ClientMachineRecipeCatalog.clear();
            buildcraft.lib.misc.ItemStackUtil.setClientRegistryProvider(null);
            MessageUtil.clearClientTasks();
            MarkerCache.clearClientCaches();
            buildcraft.lib.net.MessageMarkerClientHandler.clearQueuedMessages();
            buildcraft.lib.net.GuideRecipeDisplayCache.clear();
        }

        private static void clientTick() {
            BuildCraftObjectCaches.onClientTick();
            MessageUtil.postClientTick();
            buildcraft.lib.net.MessageMarkerClientHandler.flushQueuedMessages();
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            buildcraft.lib.misc.ItemStackUtil.setClientRegistryProvider(
                mc.level == null ? null : mc.level.registryAccess());
            var player = mc.player;
            if (player == null || !buildcraft.lib.item.ItemDebugger.isShowDebugInfo(player)) return;
            var hit = mc.hitResult;
            if (hit == null) return;
            var object = buildcraft.lib.debug.ClientDebuggables.getDebuggableObject(hit);
            if (object instanceof net.minecraft.world.level.block.entity.BlockEntity tile
                && hit instanceof net.minecraft.world.phys.BlockHitResult blockHit) {
                MessageManager.sendToServer(
                    new buildcraft.lib.net.MessageDebugRequest(tile.getBlockPos(), blockHit.getDirection()));
            }
        }
    }
}
