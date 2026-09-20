/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport;

import buildcraft.lib.platform.client.PlatformClientModels;
import buildcraft.lib.platform.client.PlatformClientRegistration;
import buildcraft.lib.platform.events.PlatformEvents;
import buildcraft.lib.platform.events.BCEvents;
import buildcraft.transport.internal.pipe.PipeApiClient;
import buildcraft.transport.client.PipeRegistryClient;
import buildcraft.transport.client.model.ModelPipe;
import buildcraft.transport.client.model.PipeBaseModelGenStandard;
import buildcraft.transport.client.model.PipeModelCacheAll;
import buildcraft.transport.client.render.PipeFlowRendererFE;
import buildcraft.transport.client.render.PipeFlowRendererPower;
import buildcraft.transport.net.PipeItemMessageQueue;
import buildcraft.transport.wire.WorldSavedDataWireSystems;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.client.event.ModelEvent.BakingCompleted;
import net.minecraftforge.client.event.ModelEvent.RegisterAdditional;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

public class BCTransportEventDist {

    @Mod.EventBusSubscriber(modid = BCTransport.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        public static final ResourceLocation TRUNK_LIGHT = new ResourceLocation("buildcraftcore:blocks/engine/trunk_light");
        public static final ResourceLocation CHAMBER = new ResourceLocation("buildcraftcore:blocks/engine/chamber_base");

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
            BCTransportModels.init();
            PlatformClientRegistration.screens(event, BCTransportClientGuis::clientInit);
        }

        @SubscribeEvent
        public static void onClientCommonSetup(FMLCommonSetupEvent event)
        {
            PipeApiClient.registry = PipeRegistryClient.INSTANCE;
        }

        @SubscribeEvent
        public static void registryRender(EntityRenderersEvent.RegisterRenderers e) {
            BCTransportModels.onBlockEntityRender(PlatformClientRegistration.renderers(e));
        }

        @SubscribeEvent
        public static void onBlockColor(RegisterColorHandlersEvent.Block event) {
            BCTransportModels.onBlockColor(PlatformClientRegistration.blockColours(event));
        }

        @SubscribeEvent
        public static void onModelBakePre(RegisterAdditional event) {
            BCTransportModels.onModelBakePre(PlatformClientModels.additional(event));
        }

        @SubscribeEvent
        public static void onModelBake(BakingCompleted event) {
            // BakingCompleted may run before Minecraft's global ModelManager exposes the new atlas.
            // Only invalidate baked data here; sprites are refreshed from TextureStitchEvent.Post.
            clearAtlasDependentPipeCaches();
            BCTransportModels.onModelBake(PlatformClientModels.completed(event));
        }
        @SubscribeEvent
        public static void registryTexture(TextureStitchEvent.Pre e) {
            BCTransportSprites.onTextureStitchPre(PlatformClientRegistration.atlas(e));
        }

        @SubscribeEvent
        public static void registryTexturePost(TextureStitchEvent.Post event) {
            if (InventoryMenu.BLOCK_ATLAS.equals(event.getAtlas().location())) {
                // Post supplies the atlas that has just finished stitching and uploading. Using it directly avoids
                // querying Minecraft's global ModelManager while it is still swapping reload generations.
                PipeBaseModelGenStandard.loadSpritesCache(event.getAtlas());
                clearAtlasDependentPipeCaches();
            }
        }

        private static void clearAtlasDependentPipeCaches() {
            // Baked quads store atlas-relative UV coordinates and must not survive a resource reload.
            PipeModelCacheAll.clearModels();
            ModelPipe.clearTextureCache();
            PipeFlowRendererPower.clearTextureCache();
            PipeFlowRendererFE.clearTextureCache();
        }

    }

    public static void onWorldTick(BCEvents.LevelTick event) {
        if (!event.level().isClientSide && event.level().getServer() != null) {
            WorldSavedDataWireSystems.get(event.level()).tick();
        }
    }


    public static void onServerTick(BCEvents.ServerTick event) {
        PipeItemMessageQueue.serverTick();
    }


    public static void onChunkWatch(BCEvents.ChunkWatch event) {
        WorldSavedDataWireSystems.get(event.getPlayer().level).changedPlayers.add(event.getPlayer());
    }
    private static boolean gameplayEventsRegistered;
    public static synchronized void registerGameplayEvents() {
        if (gameplayEventsRegistered) return;
        gameplayEventsRegistered = true;
        PlatformEvents.levelTick(BCEvents.Phase.END, BCTransportEventDist::onWorldTick);
        PlatformEvents.levelTick(BCEvents.Phase.START, BCTransportEventDist::onWorldTick);
        PlatformEvents.serverTick(BCEvents.Phase.END, BCTransportEventDist::onServerTick);
        PlatformEvents.serverTick(BCEvents.Phase.START, BCTransportEventDist::onServerTick);
        PlatformEvents.chunkWatch(BCTransportEventDist::onChunkWatch);
    }
}
