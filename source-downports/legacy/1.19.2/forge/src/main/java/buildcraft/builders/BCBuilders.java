/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.builders;

import buildcraft.builders.BCBuildersClientRenderers;
import buildcraft.lib.platform.client.PlatformClientRegistration;
import buildcraft.lib.platform.registry.RegistryBinding;
import buildcraft.lib.platform.config.ConfigBinding;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import buildcraft.builders.snapshot.RulesLoader;
import buildcraft.lib.internal.module.BCModules;
import buildcraft.builders.client.render.RenderArchitectTable;
import buildcraft.builders.client.render.RenderBuilder;
import buildcraft.builders.client.render.RenderConstructionMarker;
import buildcraft.builders.client.render.RenderFiller;
import buildcraft.builders.client.render.RenderQuarry;
import buildcraft.builders.snapshot.MessageSnapshotRequest;
import buildcraft.builders.snapshot.MessageSnapshotResponse;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.net.LegacyNetworkCatalog;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

//@formatter:off
@Mod(BCBuilders.MODID)
//@formatter:on
public class BCBuilders {
    public static final String MODID = "buildcraftbuilders";
    static final Logger LOGGER = LogUtils.getLogger();

    public BCBuilders() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(BCBuilders::commonSetup);
        ConfigBinding.listen(modEventBus, BCBuildersConfig::onLoadConfig, BCBuildersConfig::onReloadConfig);

        BCBuildersBlocks.registry(RegistryBinding.on(modEventBus));
        BCBuildersItems.registry(RegistryBinding.on(modEventBus));
        BCBuildersSchematics.preInit();
        BCBuildersConfig.preInit();
        BCBuildersRegistries.preInit();
        BCBuildersGuis.preInit(RegistryBinding.on(modEventBus));
        ModLoadingContext.get().registerConfig(Type.COMMON, ConfigBinding.bind(BCBuildersConfig.config));
        LegacyNetworkCatalog.registerBuilders(MessageManager::registerCatalogMessage);

        MinecraftForge.EVENT_BUS.register(this);
        BCBuildersEventDist.registerGameplayEvents();
        BCBuildersStatements.preInit();

    }

    public static void commonSetup(final FMLCommonSetupEvent event) {
        BCBuildersConfig.reloadConfig(MODID);
        BCBuildersRegistries.init();
        RulesLoader.loadAll();
    }


    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event){
            BCBuildersSprites.init();
            PlatformClientRegistration.screens(event, BCBuildersClientGuis::clientInit);
            event.enqueueWork(BCBuildersItems::registerItemProperties);
        }

        @SubscribeEvent
    public static void registryRender(EntityRenderersEvent.RegisterRenderers e) {
        BCBuildersClientRenderers.register(PlatformClientRegistration.renderers(e));
    }

        @SubscribeEvent
        public static void registryTexture(TextureStitchEvent.Pre e){
            BCBuildersSprites.onTextureStitchPre(PlatformClientRegistration.atlas(e));
        }
    }
}
