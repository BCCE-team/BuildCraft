/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.builders;

import buildcraft.builders.BCBuildersClientRenderers;
import buildcraft.lib.platform.client.PlatformClientRegistration;
import buildcraft.lib.platform.registry.RegistryBinding;
import buildcraft.lib.platform.config.ConfigBinding;
import buildcraft.lib.internal.mj.MjCapabilities;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import buildcraft.builders.snapshot.RulesLoader;
import buildcraft.lib.internal.capabilities.BCCapabilityRegistration;
import buildcraft.lib.internal.capabilities.IBCCapabilityProvider;
import buildcraft.lib.internal.tiles.TilesAPI;
import buildcraft.lib.misc.CapUtil;
import buildcraft.core.BCCore;
import buildcraft.lib.internal.module.BCModules;
import buildcraft.builders.client.render.RenderArchitectTable;
import buildcraft.builders.client.render.RenderBuilder;
import buildcraft.builders.client.render.RenderConstructionMarker;
import buildcraft.builders.client.render.RenderFiller;
import buildcraft.builders.client.render.RenderQuarry;
import buildcraft.builders.snapshot.MessageSnapshotRequest;
import buildcraft.builders.snapshot.MessageSnapshotResponse;
import buildcraft.builders.tile.TileArchitectTable;
import buildcraft.builders.tile.TileBuilder;
import buildcraft.builders.tile.TileConstructionMarker;
import buildcraft.builders.tile.TileElectronicLibrary;
import buildcraft.builders.tile.TileFiller;
import buildcraft.builders.tile.TileQuarry;
import buildcraft.builders.tile.TileReplacer;
import buildcraft.lib.net.MessageManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
//? if >=1.21.4 {
import net.neoforged.neoforge.client.event.RegisterRangeSelectItemModelPropertyEvent;
//?}
import net.neoforged.neoforge.client.event.ModelEvent.RegisterAdditional;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

//@formatter:off
@Mod(BCBuilders.MODID)
//@formatter:on
public class BCBuilders {
    public static final String MODID = "buildcraftbuilders";
    static final Logger LOGGER = LogUtils.getLogger();

    public BCBuilders(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(BCBuilders::commonSetup);
        modEventBus.addListener(this::registerCapabilities);
//        modEventBus.addListener(this::gatherData); // DataGenerator
        BCBuildersBlocks.registry(RegistryBinding.on(modEventBus));
        BCBuildersItems.registry(RegistryBinding.on(modEventBus));
        BCCore.BUILDCRAFT_TAB.addItemProvider(BCBuildersItems::getCreativeTabItems);
        BCBuildersSchematics.preInit();
        BCBuildersConfig.preInit();
        BCBuildersRegistries.preInit();
        BCBuildersGuis.preInit(RegistryBinding.on(modEventBus));
        modContainer.registerConfig(Type.COMMON, ConfigBinding.bind(BCBuildersConfig.config));
        ConfigBinding.listen(modEventBus, BCBuildersConfig::onLoadConfig, BCBuildersConfig::onReloadConfig);


        MessageManager.registerMessageClass(BCModules.BUILDERS, MessageSnapshotRequest.class,
            MessageSnapshotRequest.HANDLER, MessageSnapshotRequest::toBytes, MessageSnapshotRequest::new,
            Dist.DEDICATED_SERVER);
        MessageManager.registerMessageClass(BCModules.BUILDERS, MessageSnapshotResponse.class,
            MessageSnapshotResponse.HANDLER, MessageSnapshotResponse::toBytes, MessageSnapshotResponse::new,
            Dist.CLIENT);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            BCBuildersEventDist.registerGameplayEvents();
        }
        BCBuildersStatements.preInit();
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        BlockEntityType<TileArchitectTable> architect = BCBuildersBlocks.ARCHITECT_TILE_BC8.get();
        BlockEntityType<TileBuilder> builder = BCBuildersBlocks.BUILDER_TILE_BC8.get();
        BlockEntityType<TileConstructionMarker> constructionMarker =
            BCBuildersBlocks.CONSTRUCTION_MARKER_TILE_BC8.get();
        BlockEntityType<TileElectronicLibrary> library = BCBuildersBlocks.LIBRARY_TILE_BC8.get();
        BlockEntityType<TileFiller> filler = BCBuildersBlocks.FILLER_TILE_BC8.get();
        BlockEntityType<TileQuarry> quarry = BCBuildersBlocks.QUARRY_TILE_BC8.get();
        BlockEntityType<TileReplacer> replacer = BCBuildersBlocks.REPLACER_TILE_BC8.get();

        registerMachineCapabilities(event, builder);
        registerMachineCapabilities(event, filler);
        registerMachineCapabilities(event, quarry);

        BCCapabilityRegistration.registerBlockEntity(event, Capabilities.ItemHandler.BLOCK, architect);
        BCCapabilityRegistration.registerBlockEntity(event, Capabilities.ItemHandler.BLOCK, builder);
        BCCapabilityRegistration.registerBlockEntity(event, Capabilities.ItemHandler.BLOCK, constructionMarker);
        BCCapabilityRegistration.registerBlockEntity(event, Capabilities.ItemHandler.BLOCK, library);
        BCCapabilityRegistration.registerBlockEntity(event, Capabilities.ItemHandler.BLOCK, filler);
        BCCapabilityRegistration.registerBlockEntity(event, Capabilities.ItemHandler.BLOCK, replacer);

        BCCapabilityRegistration.registerBlockEntity(event, CapUtil.CAP_FLUIDS, builder);
        BCCapabilityRegistration.registerBlockEntity(event, TilesAPI.CAP_HAS_WORK, builder);
        BCCapabilityRegistration.registerBlockEntity(event, TilesAPI.CAP_CONTROLLABLE, filler);
        BCCapabilityRegistration.registerBlockEntity(event, TilesAPI.CAP_HAS_WORK, quarry);
        BCCapabilityRegistration.registerBlockEntity(event, CapUtil.CAP_ITEM_TRANSACTOR, quarry);
    }

    private static <BE extends BlockEntity & IBCCapabilityProvider> void registerMachineCapabilities(
        RegisterCapabilitiesEvent event, BlockEntityType<BE> type
    ) {
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_CONNECTOR, type);
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_RECEIVER, type);
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_REDSTONE_RECEIVER, type);
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_READABLE, type);
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_PASSIVE_PROVIDER, type);
    }

    public void gatherData(GatherDataEvent event) {
    }

    public static void commonSetup(final FMLCommonSetupEvent event) {
        BCBuildersConfig.reloadConfig(MODID);
        BCBuildersRegistries.init();
        RulesLoader.loadAll();
    }


    @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        @SubscribeEvent
        public static void registerMenuScreens(RegisterMenuScreensEvent event) {
            BCBuildersClientGuis.clientInit(PlatformClientRegistration.screens(event));
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event){
            BCBuildersSprites.init();

        //? if <1.21.4 {
            event.enqueueWork(BCBuildersItems::registerItemProperties);
        //?}
        }

        //? if >=1.21.4 {
        @SubscribeEvent
        public static void registerItemModelProperties(RegisterRangeSelectItemModelPropertyEvent event) {
            BCBuildersItems.registerItemModelProperties(PlatformClientRegistration.rangeProperties(event));
        }

        //?}
        @SubscribeEvent
    public static void registryRender(EntityRenderersEvent.RegisterRenderers e) {
        BCBuildersClientRenderers.register(PlatformClientRegistration.renderers(e));
    }

        @SubscribeEvent
        public static void onModelBakePre(RegisterAdditional event) {
        }

    }
}
