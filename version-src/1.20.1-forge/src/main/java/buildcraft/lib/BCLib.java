/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.lib;

import buildcraft.lib.platform.registry.RegistryBinding;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.module.ModuleInfo;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.block.VanillaRotationHandlers;
import buildcraft.lib.internal.mj.MjApi2PlatformBridge;
import buildcraft.lib.internal.api.v2.platform.PlatformApi2Bootstrap;
import buildcraft.lib.chunkload.ChunkLoaderManager;
import buildcraft.lib.expression.ExpressionDebugManager;
import buildcraft.lib.list.VanillaListHandlers;
import buildcraft.lib.marker.MarkerCache;
import buildcraft.lib.misc.ExpressionCompat;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.net.BuildCraftTarget;
import buildcraft.lib.net.cache.BuildCraftObjectCaches;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(BCLib.MODID)
public class BCLib {
    public static final String MODID = "buildcraftlib";
    public static final String VERSION = BuildCraftTarget.MOD_VERSION;
    public static final String MC_VERSION = BuildCraftTarget.MINECRAFT_VERSION;
    public static final String GIT_BRANCH = BuildCraftTarget.GIT_BRANCH;
    public static final String GIT_COMMIT_HASH = BuildCraftTarget.GIT_COMMIT_HASH;
    public static final String GIT_COMMIT_MSG = BuildCraftTarget.GIT_COMMIT_MESSAGE;
    public static final String GIT_COMMIT_AUTHOR = BuildCraftTarget.GIT_COMMIT_AUTHOR;

    public static final boolean DEV = !FMLEnvironment.production || Boolean.getBoolean("buildcraft.dev");

    public BCLib() {
        MjApi2PlatformBridge.install();
        PlatformApi2Bootstrap.install();
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();


        modEventBus.addListener(this::init);
        modEventBus.addListener(this::postInit);

        try {
            BCLog.logger.info("");
        } catch (NoSuchFieldError e) {
            throw throwBadClass(e, BCLog.class);
        }
        BCLog.logger.info("Starting BuildCraft " + BCLib.VERSION);
        BCLog.logger.info("Copyright (c) the BuildCraft team, 2011-2018");
        BCLog.logger.info("https://www.mod-buildcraft.com");
        if (!GIT_COMMIT_HASH.isBlank() && !"unknown".equals(GIT_COMMIT_HASH)) {
            BCLog.logger.info("Detailed Build Information:");
            BCLog.logger.info("  Branch " + GIT_BRANCH);
            BCLog.logger.info("  Commit " + GIT_COMMIT_HASH);
            BCLog.logger.info("    " + GIT_COMMIT_MSG);
            BCLog.logger.info("    committed by " + GIT_COMMIT_AUTHOR);
        }
        BCLog.logger.info("");
        var moduleService = BuildCraftApi.service(BuildCraftServices.MODULES);
        BCLog.logger.info("Loaded Modules:");
        for (ModuleInfo module : moduleService.modules()) {
            if (module.loaded()) {
                BCLog.logger.info("  - " + module.id().getPath());
            }
        }
        BCLog.logger.info("Missing Modules:");
        for (ModuleInfo module : moduleService.modules()) {
            if (!module.loaded()) {
                BCLog.logger.info("  - " + module.id().getPath());
            }
        }
        BCLibItems.registry(RegistryBinding.on(modEventBus));
        BCLibRegistries.fmlPreInit();

        // Register library network messages during mod construction, before any sided setup event
        // can attempt to replace their client handlers.
        BCLibProxy.MessageRegistry();

        ExpressionDebugManager.logger = BCLog.logger::info;
        ExpressionCompat.setup();
        BuildCraftObjectCaches.fmlPreInit();

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(BCLibEventDist.class);
        BCLibEventDist.registerGameplayEvents();

    }

    public void gatherData(GatherDataEvent event) {
        var output = event.getGenerator().getPackOutput();
        var lookupProvider = event.getLookupProvider();
        var existingFiles = event.getExistingFileHelper();
        event.getGenerator().addProvider(event.includeServer(), new BCTagsProvider.BlockTag(output, lookupProvider, existingFiles));
        event.getGenerator().addProvider(event.includeServer(), new BCTagsProvider.FluidTag(output, lookupProvider, existingFiles));
    }

    public void init(final FMLCommonSetupEvent event) {
        event.enqueueWork(ChunkLoaderManager::init);
        BCLibRegistries.fmlInit();
        VanillaListHandlers.fmlInit();
  //  	VanillaPaintHandlers.fmlInit();
        VanillaRotationHandlers.fmlInit();
    }

    public void postInit(FMLLoadCompleteEvent evt) {
        initOptionalCompat("ic2", "buildcraft.compat.ic2.Ic2Compat");
        initOptionalCompat("forestry", "buildcraft.compat.forestry.ForestryCompat");
//        ReloadableRegistryManager.loadAll();

//        VanillaListHandlers.fmlPostInit();
        MarkerCache.postInit();
        BuildCraftObjectCaches.fmlPostInit();
        MessageManager.fmlPostInit();
        evt.enqueueWork(BCLibRegistries::fmlPostInit);
    }

    private static void initOptionalCompat(String modId, String className) {
        if (!ModList.get().isLoaded(modId)) {
            return;
        }
        try {
            Class.forName(className).getMethod("init").invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            BCLog.logger.error("Failed to initialise optional compatibility for {}", modId, e);
        }
    }

    public static Error throwBadClass(Error e, Class<?> cls) throws Error {
        throw new Error(
            "Bad " + cls + " loaded from " + cls.getClassLoader() + " domain: " + cls.getProtectionDomain(), e
        );
    }

}
