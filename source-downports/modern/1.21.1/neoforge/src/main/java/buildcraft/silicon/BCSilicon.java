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
import buildcraft.lib.platform.config.ConfigScreenRegistration;
import buildcraft.lib.internal.mj.MjCapabilities;

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
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.ModelEvent.ModifyBakingResult;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import buildcraft.lib.internal.capabilities.BCCapabilityRegistration;
import buildcraft.lib.internal.capabilities.IBCCapabilityProvider;
import buildcraft.lib.internal.tiles.TilesAPI;

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

    public BCSilicon(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(BCSilicon::commonSetup);
        modEventBus.addListener(BCSilicon::postInit);
        modEventBus.addListener(BCSilicon::gatherData);
        modEventBus.addListener(BCSilicon::registerCapabilities);
        ConfigBinding.listen(modEventBus, BCSiliconConfig::onLoadConfig, BCSiliconConfig::onReloadConfig);



        BuildCraftApi.registry(BuildCraftRegistries.FACADE_MATERIAL_ADAPTERS).register(
            java.util.Objects.requireNonNull(net.minecraft.resources.ResourceLocation.tryParse("buildcraft:facade_materials/builtin")),
            FacadeStateManager.INSTANCE
        );

        BCSiliconConfig.preInit();
        modContainer.registerConfig(Type.COMMON, ConfigBinding.bind(BCSiliconConfig.config));
        ConfigScreenRegistration.register(modContainer);
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

    }


    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        registerItemHandler(event, BCSiliconBlocks.ASSEMBLY_TABLE_TILE.get());
        registerItemHandler(event, BCSiliconBlocks.CHARGING_TABLE_TILE.get());
        registerItemHandler(event, BCSiliconBlocks.INTERGRATION_TABLE_TILE.get());
        registerItemHandler(event, BCSiliconBlocks.ADVANCED_CRAFTING_TABLE_TILE.get());
        registerItemHandler(event, BCSiliconBlocks.PROGRAMMING_TABLE_TILE.get());

        registerLaserTableCapabilities(event, BCSiliconBlocks.ASSEMBLY_TABLE_TILE.get());
        registerLaserTableCapabilities(event, BCSiliconBlocks.CHARGING_TABLE_TILE.get());
        registerLaserTableCapabilities(event, BCSiliconBlocks.INTERGRATION_TABLE_TILE.get());
        registerLaserTableCapabilities(event, BCSiliconBlocks.ADVANCED_CRAFTING_TABLE_TILE.get());
        registerLaserTableCapabilities(event, BCSiliconBlocks.PROGRAMMING_TABLE_TILE.get());

        registerMjCapabilities(event, BCSiliconBlocks.LASER_TILE.get());
        registerMjCapabilities(event, BCSiliconBlocks.CHARGING_TABLE_TILE.get());
        registerMjCapabilities(event, BCSiliconBlocks.ADVANCED_CRAFTING_TABLE_TILE.get());
    }

    private static <BE extends BlockEntity & IBCCapabilityProvider> void registerItemHandler(
        RegisterCapabilitiesEvent event, BlockEntityType<BE> type
    ) {
        BCCapabilityRegistration.registerBlockEntity(event, Capabilities.ItemHandler.BLOCK, type);
    }

    private static <BE extends BlockEntity & IBCCapabilityProvider> void registerLaserTableCapabilities(
        RegisterCapabilitiesEvent event, BlockEntityType<BE> type
    ) {
        BCCapabilityRegistration.registerBlockEntity(event, TilesAPI.CAP_HAS_WORK, type);
    }

    private static <BE extends BlockEntity & IBCCapabilityProvider> void registerMjCapabilities(
        RegisterCapabilitiesEvent event, BlockEntityType<BE> type
    ) {
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_CONNECTOR, type);
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_RECEIVER, type);
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_REDSTONE_RECEIVER, type);
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_READABLE, type);
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_PASSIVE_PROVIDER, type);
    }

    public static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            BCSiliconConfig.reloadConfig(MODID);
            FacadeStateManager.init();
        });
    }

    public static void postInit(FMLLoadCompleteEvent event) {
        event.enqueueWork(() -> {
            if (BCSiliconConfig.enableFacades && BCSiliconItems.PLUG_FACADE_ITEM.isBound()) {
                FacadeBlockStateInfo state = FacadeStateManager.previewState;
                if (state != null) {
                    FacadeInstance instance = FacadeInstance.createSingle(state, false);
                    tabFacades.setItem(BCSiliconItems.PLUG_FACADE_ITEM.get().createItemStack(instance));
                }
            }
            if (!BCModules.TRANSPORT.isLoaded() && BCSiliconItems.PLUG_GATE_ITEM.isBound()) {
                tabPlugs.setItem(BCSiliconItems.PLUG_GATE_ITEM.get());
            }
        });
    }

    public static void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(
            event.includeServer(),
            new BCSiliconRecipesProvider(event.getGenerator().getPackOutput(), event.getLookupProvider())
        );
    }

    @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        static {
            BCSiliconSprites.fmlPreInit();
        }

        @SubscribeEvent
        public static void registerMenuScreens(RegisterMenuScreensEvent event) {
            BCSiliconClientGuis.clientInit(PlatformClientRegistration.screens(event));
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {

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
        public static void onTextureStitchPost(TextureAtlasStitchedEvent event) {
            if (InventoryMenu.BLOCK_ATLAS.equals(event.getAtlas().location())) {
                BCSiliconModels.clearAtlasDependentCaches();
            }
        }

        @SubscribeEvent
        public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
            BCSiliconModels.registerItemColor(PlatformClientRegistration.itemColours(event));
        }

        @SubscribeEvent
        public static void onModelBake(ModifyBakingResult event) {
            BCSiliconModels.onModelBake(PlatformClientModels.models(event));
        }
    }
}
