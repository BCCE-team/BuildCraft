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
import net.minecraftforge.client.event.ModelEvent.ModifyBakingResult;
import net.minecraftforge.client.event.ModelEvent.RegisterAdditional;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

public class BCTransportEventDist {

    @Mod.EventBusSubscriber(modid = BCTransport.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        public static final ResourceLocation TRUNK_LIGHT = new ResourceLocation("buildcraftcore:blocks/engine/trunk_light");
        public static final ResourceLocation CHAMBER = new ResourceLocation("buildcraftcore:blocks/engine/chamber_base");

        private static void ensureClientRegistry() {
            if (PipeApiClient.registry == null) {
                PipeApiClient.registry = PipeRegistryClient.INSTANCE;
            }
            BCTransportModels.init();
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
            ensureClientRegistry();
            PlatformClientRegistration.screens(event, BCTransportClientGuis::clientInit);
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
            ensureClientRegistry();
            BCTransportModels.onModelBakePre(PlatformClientModels.additional(event));
        }

        @SubscribeEvent
        public static void onModelBake(ModifyBakingResult event) {
            ensureClientRegistry();
            BCTransportModels.onModelBake(PlatformClientModels.models(event));
        }

        @SubscribeEvent
        public static void onModelBakeComplete(BakingCompleted event) {
            // Do not resolve sprites through Minecraft's global ModelManager here: during a reload it may still
            // expose the previous atlas generation. The fresh atlas is captured directly in TextureStitchEvent.Post.
            clearAtlasDependentPipeCaches();
            BCTransportModels.onModelBakeComplete();
        }

        @SubscribeEvent
        public static void onTextureStitchPost(TextureStitchEvent.Post event) {
            if (InventoryMenu.BLOCK_ATLAS.equals(event.getAtlas().location())) {
                PipeBaseModelGenStandard.loadSpritesCache(event.getAtlas());
                clearAtlasDependentPipeCaches();
            }
        }

        private static void clearAtlasDependentPipeCaches() {
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
        WorldSavedDataWireSystems.get(event.getLevel()).changedPlayers.add(event.getPlayer());
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
