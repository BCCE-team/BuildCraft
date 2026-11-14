/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.silicon;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.core.BCCore;
import buildcraft.lib.BCLibRegistries;
import buildcraft.lib.CreativeTabManager;
import buildcraft.lib.CreativeTabManager.CreativeTabBC;
import buildcraft.lib.internal.capabilities.BCCapabilityRegistration;
import buildcraft.lib.internal.capabilities.IBCCapabilityProvider;
import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.internal.mj.MjCapabilities;
import buildcraft.lib.internal.tiles.TilesAPI;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.platform.config.ConfigBinding;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.RegistryBinding;
import buildcraft.silicon.plug.FacadeBlockStateInfo;
import buildcraft.silicon.plug.FacadeInstance;
import buildcraft.silicon.plug.FacadeStateManager;
import buildcraft.transport.BCTransport;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Fabric 26.3 bootstrap for Silicon. All registrations use the common BCCE catalogues. */
public final class BCSilicon {
    public static final String MODID = "buildcraftsilicon";
    public static final CreativeTabBC tabPlugs = BCTransport.tabPlugs;
    public static final CreativeTabBC tabFacades = CreativeTabManager.createTab("buildcraft.facades")
        .setRecipeFolderName("facades");

    private static final BCDeferredRegister<CreativeModeTab> CREATIVE_TABS =
        BCDeferredRegister.create("minecraft:creative_mode_tab", "buildcraft");
    public static final BCRegistryEntry<CreativeModeTab> FACADES_TAB = CREATIVE_TABS.register("facades", () ->
        net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab.builder()
            .title(Component.translatable("itemGroup.buildcraft.facades"))
            .icon(tabFacades::makeIcon)
            .displayItems((parameters, output) -> tabFacades.accept(List.of(), output::accept))
            .build());

    private static boolean bootstrapped;
    private BCSilicon() {}

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        buildcraft.lib.internal.core.BuildCraftAPI.withModule(MODID, BCSilicon::bootstrapInternal);
        bootstrapped = true;
    }

    private static void bootstrapInternal() {
        BCLibRegistries.initApiRegistries();
        BCSiliconConfig.preInit();
        BCSiliconStatements.preInit();
        BCSiliconPlugs.preInit();
        BuildCraftApi.registry(BuildCraftRegistries.FACADE_MATERIAL_ADAPTERS).register(
            Identifier.fromNamespaceAndPath("buildcraft", "facade_materials/builtin"), FacadeStateManager.INSTANCE);

        BCSiliconBlocks.registry(RegistryBinding.direct());
        BCSiliconItems.registry(RegistryBinding.direct());
        BCSiliconGuis.preInit(RegistryBinding.direct());
        BCSiliconRecipes.preInit(RegistryBinding.direct());
        BCSiliconRecipeSync.bootstrap();
        CREATIVE_TABS.register(RegistryBinding.direct());

        BCCore.BUILDCRAFT_TAB.addItemProvider(BCSiliconItems::getMainTabItems);
        tabPlugs.addItemProvider(BCSiliconItems::getPlugTabItems);
        tabFacades.addItemProvider(BCSiliconItems::getFacadeTabItems);

        ConfigBinding.listen(MODID, BCSiliconConfig::onLoadConfig, BCSiliconConfig::onReloadConfig);
        ConfigBinding.bind(MODID, BCSiliconConfig.config);
        BCSiliconConfig.reloadConfig(MODID);

        registerCapabilities();
        if (!BCModules.TRANSPORT.isLoaded()) {
            tabPlugs.setItem(BCSiliconItems.PLUG_GATE_ITEM.get());
        }
        ServerLifecycleEvents.SERVER_STARTED.register(server -> initializeFacadeStates());
    }

    private static void registerCapabilities() {
        registerItemHandler(BCSiliconBlocks.ASSEMBLY_TABLE_TILE.get());
        registerItemHandler(BCSiliconBlocks.CHARGING_TABLE_TILE.get());
        registerItemHandler(BCSiliconBlocks.INTERGRATION_TABLE_TILE.get());
        registerItemHandler(BCSiliconBlocks.ADVANCED_CRAFTING_TABLE_TILE.get());
        registerItemHandler(BCSiliconBlocks.PROGRAMMING_TABLE_TILE.get());

        registerLaserTableCapabilities(BCSiliconBlocks.ASSEMBLY_TABLE_TILE.get());
        registerLaserTableCapabilities(BCSiliconBlocks.CHARGING_TABLE_TILE.get());
        registerLaserTableCapabilities(BCSiliconBlocks.INTERGRATION_TABLE_TILE.get());
        registerLaserTableCapabilities(BCSiliconBlocks.ADVANCED_CRAFTING_TABLE_TILE.get());
        registerLaserTableCapabilities(BCSiliconBlocks.PROGRAMMING_TABLE_TILE.get());

        registerMjCapabilities(BCSiliconBlocks.LASER_TILE.get());
        registerMjCapabilities(BCSiliconBlocks.CHARGING_TABLE_TILE.get());
        registerMjCapabilities(BCSiliconBlocks.ADVANCED_CRAFTING_TABLE_TILE.get());
    }

    private static <BE extends BlockEntity & IBCCapabilityProvider> void registerItemHandler(BlockEntityType<BE> type) {
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_ITEMS, type);
    }

    private static <BE extends BlockEntity & IBCCapabilityProvider> void registerLaserTableCapabilities(BlockEntityType<BE> type) {
        BCCapabilityRegistration.registerBlockEntity(TilesAPI.CAP_HAS_WORK, type);
    }

    private static <BE extends BlockEntity & IBCCapabilityProvider> void registerMjCapabilities(BlockEntityType<BE> type) {
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_CONNECTOR, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_RECEIVER, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_REDSTONE_RECEIVER, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_READABLE, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_PASSIVE_PROVIDER, type);
    }

    /** Called independently on the logical server and physical client after registries are available. */
    public static void initializeFacadeStates() {
        if (!BCSiliconConfig.enableFacades || !BCSiliconItems.PLUG_FACADE_ITEM.isBound()) return;
        FacadeStateManager.init();
        FacadeBlockStateInfo state = FacadeStateManager.previewState;
        if (state != null && state != FacadeStateManager.defaultState) {
            tabFacades.setItem(BCSiliconItems.PLUG_FACADE_ITEM.get().createItemStack(FacadeInstance.createSingle(state, false)));
        }
    }
}
