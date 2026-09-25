/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport;

import buildcraft.lib.platform.registry.RegistryBinding;
import buildcraft.lib.platform.config.ConfigBinding;
import buildcraft.lib.internal.module.BCModules;
import buildcraft.builders.internal.schematic.legacy.SchematicBlockFactoryRegistry;
import buildcraft.lib.BCLibRegistries;
import buildcraft.lib.CreativeTabManager;
import buildcraft.lib.CreativeTabManager.CreativeTabBC;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.net.LegacyNetworkCatalog;
import buildcraft.transport.net.MessageMultiPipeItem;
import buildcraft.transport.api2.TransportApi2;
import buildcraft.transport.pipe.SchematicBlockPipe;
import buildcraft.transport.wire.MessageWireSystems;
import buildcraft.transport.wire.MessageWireSystemsPowered;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

//@formatter:off
@Mod(BCTransport.MODID)
//@formatter:on
public class BCTransport {
    public static final String MODID = "buildcrafttransport";


    public static final CreativeTabBC tabPipes = (CreativeTabBC) CreativeTabManager.createTab("buildcraft.pipes").setRecipeFolderName("pipes");
    public static final CreativeTabBC tabPlugs = (CreativeTabBC) CreativeTabManager.createTab("buildcraft.plugs").setRecipeFolderName("plugs");

    public BCTransport() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::init);
        modEventBus.addListener(this::gatherData);//DataGenerator
        ConfigBinding.listen(modEventBus, BCTransportConfig::onConfigLoad, BCTransportConfig::onConfigReload);


        BCLibRegistries.initApiRegistries();
        TransportApi2.install();
        BCTransportRegistries.preInit();
        BCTransportConfig.preInit();
        BCTransportPipes.preInit();
        BCTransportPlugs.preInit();
        BCTransportBlocks.registry(RegistryBinding.on(modEventBus));
        BCTransportItems.registry(RegistryBinding.on(modEventBus));
        BCTransportRecipes.preInit(RegistryBinding.on(modEventBus));
        BCTransportGuis.preInit(RegistryBinding.on(modEventBus));
        BCTransportStatements.preInit();

        ModLoadingContext.get().registerConfig(Type.COMMON, ConfigBinding.bind(BCTransportConfig.config));
        LegacyNetworkCatalog.registerTransport(MessageManager::registerCatalogMessage);
        BCTransportEventDist.registerGameplayEvents();

        SchematicBlockFactoryRegistry.registerFactory("pipe", 300, SchematicBlockPipe::predicate,
                SchematicBlockPipe::new);
    }

    public void init(final FMLCommonSetupEvent event) {
        BCTransportConfig.reloadConfig();
        BCTransportRegistries.init();
        tabPipes.setItem(BCTransportItems.PIPE_ITEM_DIAMOND.get());
        tabPlugs.setItem(BCTransportItems.plugBlocker.get());
    }

    public void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(
            event.includeServer(),
            new BCTransportRecipesProvider(event.getGenerator())
        );
    }
}
