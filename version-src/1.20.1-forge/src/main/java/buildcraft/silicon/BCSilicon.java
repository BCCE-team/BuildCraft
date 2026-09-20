/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon;

import buildcraft.lib.platform.client.PlatformClientModels;
import buildcraft.lib.platform.client.PlatformClientRegistration;
import buildcraft.lib.platform.registry.RegistryBinding;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.platform.config.ConfigBinding;
import java.util.List;

import buildcraft.lib.internal.module.BCModules;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.core.BCCore;
import buildcraft.lib.CreativeTabManager;
import buildcraft.lib.CreativeTabManager.CreativeTabBC;
import buildcraft.silicon.plug.FacadeBlockStateInfo;
import buildcraft.silicon.plug.FacadeInstance;
import buildcraft.silicon.plug.FacadeStateManager;
import buildcraft.transport.BCTransport;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ModelEvent.ModifyBakingResult;
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

    public static final CreativeTabBC tabPlugs = BCTransport.tabPlugs;
    public static final CreativeTabBC tabFacades = CreativeTabManager.createTab("buildcraft.facades")
        .setRecipeFolderName("facades");

    private static final BCDeferredRegister<CreativeModeTab> CREATIVE_TABS =
        BCDeferredRegister.create("minecraft:creative_mode_tab", "buildcraft");
    public static final BCRegistryEntry<CreativeModeTab> FACADES_TAB = CREATIVE_TABS.register("facades", () ->
        CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.buildcraft.facades"))
            .icon(tabFacades::makeIcon)
            .displayItems((parameters, output) -> tabFacades.accept(List.of(), output::accept))
            .build());

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
        RegistryBinding.register(CREATIVE_TABS, modEventBus);

        BCCore.BUILDCRAFT_TAB.addItemProvider(BCSiliconItems::getMainTabItems);
        tabPlugs.addItemProvider(BCSiliconItems::getPlugTabItems);
        tabFacades.addItemProvider(BCSiliconItems::getFacadeTabItems);

        MinecraftForge.EVENT_BUS.register(this);
    }

    public static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            BCSiliconConfig.reloadConfig(MODID);
            FacadeStateManager.init();
        });
    }

    public static void postInit(FMLLoadCompleteEvent event) {
        event.enqueueWork(() -> {
            if (BCSiliconConfig.enableFacades && BCSiliconItems.PLUG_FACADE_ITEM.isPresent()) {
                FacadeBlockStateInfo state = FacadeStateManager.previewState;
                if (state != null) {
                    FacadeInstance instance = FacadeInstance.createSingle(state, false);
                    tabFacades.setItem(BCSiliconItems.PLUG_FACADE_ITEM.get().createItemStack(instance));
                }
            }
            if (!BCModules.TRANSPORT.isLoaded() && BCSiliconItems.PLUG_GATE_ITEM.isPresent()) {
                tabPlugs.setItem(BCSiliconItems.PLUG_GATE_ITEM.get());
            }
        });
    }

    public static void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(
            event.includeServer(),
            new BCSiliconRecipesProvider(event.getGenerator().getPackOutput())
        );
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        static {
            BCSiliconSprites.fmlPreInit();
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            PlatformClientRegistration.screens(event, BCSiliconClientGuis::clientInit);
            event.enqueueWork(() -> {
                BCSiliconItems.registerItemProperties();
                BCSiliconModels.init();
                BCSiliconClientRenderers.layers(PlatformClientRegistration.layers());
            });
        }

        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            BCSiliconModels.onBlockEntityRender(PlatformClientRegistration.renderers(event));
        }

        @SubscribeEvent
        public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
            BCSiliconModels.registerItemColor(PlatformClientRegistration.itemColours(event));
        }

        @SubscribeEvent
        public static void onTextureStitchPost(TextureStitchEvent.Post event) {
            if (InventoryMenu.BLOCK_ATLAS.equals(event.getAtlas().location())) {
                // Pluggable baked quads keep atlas-relative UVs, so they must not survive an atlas reload.
                BCSiliconModels.clearAtlasDependentCaches();
            }
        }

        @SubscribeEvent
        public static void onModelBake(ModifyBakingResult event) {
            BCSiliconModels.onModelBake(PlatformClientModels.models(event));
        }
    }
}
