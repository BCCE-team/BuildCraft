/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon;

import buildcraft.lib.platform.client.PlatformClientModels;
import buildcraft.lib.platform.client.PlatformClientRegistration;
import buildcraft.lib.platform.registry.RegistryBinding;
import buildcraft.lib.platform.config.ConfigBinding;
import buildcraft.lib.internal.module.BCModules;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.lib.CreativeTabManager;
import buildcraft.lib.CreativeTabManager.CreativeTabBC;
import buildcraft.silicon.plug.FacadeBlockStateInfo;
import buildcraft.silicon.plug.FacadeInstance;
import buildcraft.silicon.plug.FacadeStateManager;
import buildcraft.transport.BCTransport;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ModelEvent.BakingCompleted;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(BCSilicon.MODID)
public class BCSilicon {
    public static final String MODID = "buildcraftsilicon";

    public static CreativeTabBC tabPlugs = BCTransport.tabPlugs;
    public static CreativeTabBC tabFacades = (CreativeTabBC) CreativeTabManager.createTab("buildcraft.facades").setRecipeFolderName("facades");

    public BCSilicon() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(BCSilicon::commonSetup);
        modEventBus.addListener(BCSilicon::postInit);
        modEventBus.addListener(BCSilicon::gatherData);
    ConfigBinding.listen(modEventBus, BCSiliconConfig::onLoadConfig, BCSiliconConfig::onReloadConfig);


        BuildCraftApi.registry(BuildCraftRegistries.FACADE_MATERIAL_ADAPTERS).register(
            java.util.Objects.requireNonNull(net.minecraft.resources.ResourceLocation.tryParse("buildcraft:facade_materials/builtin")),
            FacadeStateManager.INSTANCE
        );

        BCSiliconConfig.preInit();
        ModLoadingContext.get().registerConfig(Type.COMMON, ConfigBinding.bind(BCSiliconConfig.config));
        BCSiliconStatements.preInit();
        BCSiliconPlugs.preInit();
        BCSiliconBlocks.registry(RegistryBinding.on(modEventBus));
        BCSiliconItems.registry(RegistryBinding.on(modEventBus));
        BCSiliconGuis.preInit(RegistryBinding.on(modEventBus));
        BCSiliconRecipes.preInit(RegistryBinding.on(modEventBus));



        MinecraftForge.EVENT_BUS.register(this);

    }

    public static void commonSetup(FMLCommonSetupEvent evt) {
            BCSiliconConfig.reloadConfig(MODID);
            FacadeStateManager.init();
    }

    public static void postInit(FMLLoadCompleteEvent evt) {
        if (BCSiliconConfig.enableFacades && BCSiliconItems.PLUG_FACADE_ITEM.isPresent()) {
            FacadeBlockStateInfo state = FacadeStateManager.previewState;
            if (state != null) {
                FacadeInstance inst = FacadeInstance.createSingle(state, false);
                tabFacades.setItem(BCSiliconItems.PLUG_FACADE_ITEM.get().createItemStack(inst));
            }
        }

        if (!BCModules.TRANSPORT.isLoaded()) {
            tabPlugs.setItem(BCSiliconItems.PLUG_GATE_ITEM.get());
        }
    }
    public static void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(
            event.includeServer(),
            new BCSiliconRecipesProvider(event.getGenerator())
        );
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        static {
            // Ensure sprite/model holders exist before texture stitching. Forge loads the subscriber class
            // without guaranteeing constructor execution, so registration belongs in static initialization.
            BCSiliconSprites.fmlPreInit();
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
            PlatformClientRegistration.screens(event, BCSiliconClientGuis::clientInit);
            event.enqueueWork(BCSiliconItems::registerItemProperties);
            BCSiliconModels.init();
        }

        @SubscribeEvent
        public static void registryRender(EntityRenderersEvent.RegisterRenderers e) {
            BCSiliconModels.onBlockEntityRender(PlatformClientRegistration.renderers(e));

        }

        @SubscribeEvent
        public static void RegisterItemColor(RegisterColorHandlersEvent.Item event) {
            BCSiliconModels.RegisterItemColor(PlatformClientRegistration.itemColours(event));
        }

        @SubscribeEvent
        public static void onModelBake(BakingCompleted event) {
            BCSiliconModels.onModelBake(PlatformClientModels.completed(event));
        }

        @SubscribeEvent
        public static void onTextureStitchPost(TextureStitchEvent.Post event) {
            if (InventoryMenu.BLOCK_ATLAS.equals(event.getAtlas().location())) {
                BCSiliconModels.clearAtlasDependentCaches();
            }
        }

    }


}
