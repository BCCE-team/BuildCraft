/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.lib;

import buildcraft.lib.platform.client.PlatformClientRegistration;
import buildcraft.lib.platform.client.PlatformClientModels;
import buildcraft.lib.platform.events.PlatformClientEvents;
import buildcraft.lib.platform.events.PlatformEvents;
import buildcraft.lib.platform.events.BCEvents;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;

import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.lib.client.model.ModelHolderRegistry;
import buildcraft.lib.client.model.json.VariablePartLed;
import buildcraft.lib.client.reload.LibConfigChangeListener;
import buildcraft.lib.client.reload.ReloadManager;
import buildcraft.lib.client.render.DetachedRenderer;
import buildcraft.lib.client.render.laser.LaserRenderer_BC8;
import buildcraft.lib.client.render.DetachedRenderer.RenderMatrixType;
import buildcraft.lib.client.render.MarkerRenderer;
import buildcraft.lib.client.render.fluid.FluidRenderer;
import buildcraft.lib.client.sprite.SpriteHolderRegistry;
import buildcraft.lib.debug.BCAdvDebugging;
import buildcraft.lib.debug.ClientDebuggables;
import buildcraft.lib.debug.DebugRenderHelper;
import buildcraft.lib.item.ItemDebugger;
import buildcraft.lib.marker.MarkerCache;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.lib.misc.MessageUtil;
import buildcraft.lib.misc.data.ModelVariableData;
import buildcraft.lib.misc.SpriteUtil;
import buildcraft.lib.net.MessageDebugRequest;
import buildcraft.lib.net.MessageDebugResponse;
import buildcraft.lib.net.BCNetwork;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.net.MessageMarker;
import buildcraft.lib.net.MessageMarkerClientHandler;
import buildcraft.lib.net.cache.BuildCraftObjectCaches;
import buildcraft.lib.net.cache.MessageObjectCacheResponse;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ModelEvent.BakingCompleted;
import net.minecraftforge.client.event.ModelEvent.RegisterAdditional;
import net.minecraftforge.client.event.RegisterTextureAtlasSpriteLoadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;


public class BCLibEventDist {

    @Mod.EventBusSubscriber(modid = BCLib.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class Client {

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
/*            ReloadableRegistryManager manager = ReloadableRegistryManager.RESOURCE_PACKS;
            BuildCraftRegistryManager.managerResourcePacks = manager;
            manager.registerRegistry(GuidePageRegistry.INSTANCE);*/

            DetachedRenderer.INSTANCE.addRenderer(RenderMatrixType.FROM_WORLD_ORIGIN, MarkerRenderer.INSTANCE);
            // various sprite registers
            BCLibSprites.fmlPreInitClient();
            BCLibConfig.configChangeListeners.add(LibConfigChangeListener.INSTANCE);

            MessageManager.setHandler(MessageMarker.class, MessageMarker.HANDLER, Dist.CLIENT);
            MessageManager.setHandler(MessageObjectCacheResponse.class, MessageObjectCacheResponse.HANDLER, Dist.CLIENT);
            MessageManager.setHandler(MessageDebugResponse.class, MessageDebugResponse.HANDLER, Dist.CLIENT);
        }

        @SubscribeEvent
        public static void onTextureAtlasSpriteLoadersSetup(RegisterTextureAtlasSpriteLoadersEvent event)
        {
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void textureStitchPre(TextureStitchEvent.Pre event) {
            if (InventoryMenu.BLOCK_ATLAS.equals(event.getAtlas().location())) {
                // Some mods start an additional/earlier resource reload. Make sure the library sprites exist
                // before the final holder-registration pass, instead of relying on FMLClientSetup ordering.
                BCLibSprites.fmlPreInitClient();
                event.addSprite(new ResourceLocation("buildcraftlib", "model/led_fallback"));
                ReloadManager.INSTANCE.preReloadResources();
                // Variable models are reparsed in this pass, so pre-reload expression-node arrays are no longer valid.
                ModelVariableData.onModelBake();
                SpriteHolderRegistry.onTextureStitchPre(PlatformClientRegistration.atlas(event));
                ModelHolderRegistry.onTextureStitchPre(PlatformClientRegistration.atlas(event));
                FluidRenderer.onTextureStitchPre(PlatformClientRegistration.atlas(event));
            }
        }

/*	    @SubscribeEvent(priority = EventPriority.LOWEST)
        @OnlyIn(Dist.CLIENT)
        public static void textureStitchPreLow(TextureStitchEvent.Pre event) {
            if("textures/atlas/blocks.png".equals(event.getAtlas().location().getPath())) {

            }

        }*/

        @SubscribeEvent
        public static void textureStitchPost(TextureStitchEvent.Post event) {
            if (InventoryMenu.BLOCK_ATLAS.equals(event.getAtlas().location())) {
                SpriteHolderRegistry.onTextureStitchPost(PlatformClientRegistration.atlas(event));
            SpriteUtil.clearAtlasCache();
            DebugRenderHelper.clearTextureCache();

                // Laser vertex buffers contain absolute UV coordinates from the atlas.
                // Rebuild them after every stitch, including extra reloads started by other mods.
                LaserRenderer_BC8.clearModels();
                FluidRenderer.onTextureStitchPost(PlatformClientRegistration.atlas(event));
                VariablePartLed.onTextureStitchPost(PlatformClientRegistration.atlas(event));
            }
        }

        @SubscribeEvent
        public static void preModelBake(RegisterAdditional event) {
            ModelHolderRegistry.preModelBake(PlatformClientModels.additional(event));
        }

        @SubscribeEvent
        public static void onModelBake(BakingCompleted event) {
            SpriteHolderRegistry.exportTextureMap();
            LaserRenderer_BC8.clearModels();
            ModelHolderRegistry.onModelBake(PlatformClientModels.completed(event));
        }


    }

    @SubscribeEvent
    @OnlyIn(Dist.CLIENT)
    public static void renderWorldLast(RenderLevelStageEvent event) {
        if(event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return ;
        }
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;
        PoseStack pose = event.getPoseStack();
        Matrix4f matrix = event.getProjectionMatrix();
        float partialTicks = event.getPartialTick();

        LaserRenderer_BC8.setupLaserRenderState();
        DetachedRenderer.INSTANCE.renderWorldLastEvent(pose, matrix, player, partialTicks);
    }


    public static void onEntityJoinWorld(BCEvents.EntityJoin event) {
        Entity entity = event.getEntity();
        if (entity instanceof ServerPlayer) {
            ServerPlayer playerMP = (ServerPlayer) entity;
            // Delay sending join messages as it makes it work when in single-player.
            // Send a few sync passes because marker messages can otherwise arrive before the client world exists
            // during integrated-server login; the client queues those messages too, but these extra passes make
            // reconnecting to an already loaded marker network deterministic.
            MessageUtil.doDelayedServer(1, () -> MarkerCache.onPlayerJoinLevel(playerMP));
            MessageUtil.doDelayedServer(5, () -> MarkerCache.onPlayerJoinLevel(playerMP));
            MessageUtil.doDelayedServer(20, () -> MarkerCache.onPlayerJoinLevel(playerMP));
            MessageUtil.doDelayedServer(60, () -> MarkerCache.onPlayerJoinLevel(playerMP));
        }
    }


    public static void onWorldUnload(BCEvents.LevelUnload event) {
        MarkerCache.onLevelUnload(event.getLevel());
        if (event.getLevel() instanceof ServerLevel) {
            FakePlayerProvider.INSTANCE.unloadWorld((ServerLevel) event.getLevel());
            buildcraft.lib.platform.chunk.BCChunkTickets.unloadWorld((ServerLevel) event.getLevel());
        }
    }


    @OnlyIn(Dist.CLIENT)
    public static void onConnectToServer() {
        MarkerCache.clearClientCaches();
        MessageMarkerClientHandler.clearQueuedMessages();
        BuildCraftObjectCaches.onClientJoinServer();
    }


    @OnlyIn(Dist.CLIENT)
    public static void onDisconnectFromServer() {
        MarkerCache.clearClientCaches();
        MessageMarkerClientHandler.clearQueuedMessages();
    }



    public static void serverTick(BCEvents.ServerTick event) {
        if (event.phase() == BCEvents.Phase.END) {
            BCAdvDebugging.INSTANCE.onServerPostTick();
            MessageUtil.postServerTick();
        }
    }


    @OnlyIn(Dist.CLIENT)
    public static void clientTick(BCEvents.ClientTick event) {
        if (event.phase() == BCEvents.Phase.END) {
            BuildCraftObjectCaches.onClientTick();
            MessageUtil.postClientTick();
            MessageMarkerClientHandler.flushQueuedMessages();
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player != null && ItemDebugger.isShowDebugInfo(player)) {
                HitResult mouseOver = mc.hitResult;
                if (mouseOver != null) {
                    IDebuggable debuggable = ClientDebuggables.getDebuggableObject(mouseOver);
                    if (debuggable instanceof BlockEntity) {
                        BlockEntity tile = (BlockEntity) debuggable;
                        BCNetwork.sendToServer(new MessageDebugRequest(tile.getBlockPos(), Direction.getNearest(mouseOver.getLocation().x, mouseOver.getLocation().y, mouseOver.getLocation().z)));
                    } else if (debuggable instanceof Entity) {
                        // Entity debug-info requests are intentionally ignored by this block-only debug handler.
                    }
                }
            }
        }
    }
    private static boolean gameplayEventsRegistered;
    public static synchronized void registerGameplayEvents() {
        if (gameplayEventsRegistered) return;
        gameplayEventsRegistered = true;
        PlatformEvents.entityJoin(BCLibEventDist::onEntityJoinWorld);
        PlatformEvents.levelUnload(BCLibEventDist::onWorldUnload);
        PlatformEvents.serverTick(BCEvents.Phase.END, BCLibEventDist::serverTick);
        if (PlatformEvents.isClient()) {
            PlatformClientEvents.login(BCLibEventDist::onConnectToServer);
            PlatformClientEvents.logout(BCLibEventDist::onDisconnectFromServer);
            PlatformClientEvents.tick(BCEvents.Phase.END, BCLibEventDist::clientTick);
        }
    }
}
