/*
 * Copyright (c) 2026 the BuildCraft Community Edition contributors.
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.core;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.core.item.FragileFluidStorage;
import buildcraft.core.list.ContainerList;
import buildcraft.core.marker.PathCache;
import buildcraft.core.marker.VolumeCache;
import buildcraft.core.marker.volume.BCCoreVolumeBoxEvents;
import buildcraft.core.marker.volume.MessageVolumeBoxes;
import buildcraft.lib.CreativeTabManager;
import buildcraft.lib.CreativeTabManager.CreativeTabBC;
import buildcraft.lib.gui.BCContainerFactory;
import buildcraft.lib.internal.capabilities.BCCapabilityRegistration;
import buildcraft.lib.internal.capabilities.IBCCapabilityProvider;
import buildcraft.lib.internal.core.BuildCraftAPI;
import buildcraft.lib.internal.mj.MjCapabilities;
import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.marker.MarkerCache;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.platform.config.ConfigBinding;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.RegistryBinding;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fabric 26.3 bootstrap of the actual BuildCraft Core gameplay catalog.
 *
 * <p>Ownership and registry IDs are identical to NeoForge 26.3. No attempt is
 * made to initialize the Energy, Factory, Builders or Transport modules here:
 * their own loader bootstraps must register them independently.</p>
 */
public final class BCCore {
    public static final String MODID = "buildcraftcore";
    public static final CreativeTabBC BUILDCRAFT_TAB = CreativeTabManager.createTab("buildcraft.main");
    public static final CreativeTabBC tabFluids = CreativeTabManager.createTab("buildcraft.fluid");

    private static final BCDeferredRegister<CreativeModeTab> CREATIVE_TABS =
        BCDeferredRegister.create("minecraft:creative_mode_tab", "buildcraft");
    public static final BCRegistryEntry<CreativeModeTab> MAIN_TAB = CREATIVE_TABS.register("main", () ->
        net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab.builder()
            .title(Component.translatable("itemGroup.buildcraft.main"))
            .icon(BUILDCRAFT_TAB::makeIcon)
            .displayItems((parameters, output) -> BUILDCRAFT_TAB.accept(List.of(), output::accept))
            .build());
    public static final BCRegistryEntry<CreativeModeTab> FLUID_TAB = CREATIVE_TABS.register("fluid", () ->
        net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab.builder()
            .title(Component.translatable("itemGroup.buildcraft.fluid"))
            .icon(tabFluids::makeIcon)
            .displayItems((parameters, output) -> tabFluids.accept(List.of(), output::accept))
            .build());

    public static final Map<String, Object> ENGINE_MAP = new HashMap<>();
    public static final BCDeferredRegister<MenuType<?>> MENUS =
        BCDeferredRegister.create("minecraft:menu", MODID);
    public static final BCRegistryEntry<MenuType<ContainerList>> LIST_MENU =
        MENUS.register("list_menu", () -> BCContainerFactory.create(ContainerList::new));

    private static boolean bootstrapped;

    private BCCore() {}

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        BuildCraftAPI.withModule(MODID, BCCore::bootstrapInternal);
        bootstrapped = true;
    }

    private static void bootstrapInternal() {
        // Native Fabric registration must finish before wiring block-entity
        // transfer capabilities and referencing block/item registry values.
        BCCoreBlocks.registry(RegistryBinding.direct());
        BCCoreItems.registry(RegistryBinding.direct());
        BUILDCRAFT_TAB.addItemProvider(BCCoreItems::getCreativeTabItems);
        CREATIVE_TABS.register(RegistryBinding.direct());
        MENUS.register(RegistryBinding.direct());

        registerCapabilities();
        FluidStorage.ITEM.registerForItems(
            (stack, context) -> new FragileFluidStorage(context),
            BCCoreItems.FRAGILE_FLUID_SHARD.get()
        );

        ConfigBinding.listen(MODID, BCCoreConfig::onLoadConfig, BCCoreConfig::onReloadConfig);
        BCCoreConfig.registry();
        ConfigBinding.bind(MODID, BCCoreConfig.config);

        MessageManager.registerClientboundMessageClass(
            BCModules.CORE, MessageVolumeBoxes.class, null,
            MessageVolumeBoxes::toBytes, MessageVolumeBoxes::new);
        BCCoreVolumeBoxEvents.register();
        BCCoreStatements.preInit();
        CoreFabricInteractions.register();
        initializeCore();
    }

    private static void initializeCore() {
        MarkerCache.registerCache(VolumeCache.INSTANCE);
        MarkerCache.registerCache(PathCache.INSTANCE);
        BCCoreConfig.reloadConfig(MODID);
        BUILDCRAFT_TAB.setItem(BCCoreItems.WRENCH.get());
        BuildCraftApi.registry(BuildCraftRegistries.FLUID_DROP_PROVIDERS).register(
            Identifier.fromNamespaceAndPath(MODID, "fragile_fluid_shard"),
            BCCoreItems.FRAGILE_FLUID_SHARD.get());

        // EnumSpring.OIL's fluid block/tile are owned by Energy. Energy must
        // connect those in its Fabric bootstrap once its registries are valid.
    }

    private static void registerCapabilities() {
        registerEngineCapabilities(BCCoreBlocks.ENGINE_REDSTONE_TILE_BC8.get());
        registerEngineCapabilities(BCCoreBlocks.ENGINE_CREATIVE_TILE_BC8.get());
    }

    private static <BE extends BlockEntity & IBCCapabilityProvider> void registerEngineCapabilities(
        BlockEntityType<BE> type
    ) {
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_CONNECTOR, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_RECEIVER, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_REDSTONE_RECEIVER, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_READABLE, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_PASSIVE_PROVIDER, type);
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_ITEMS, type);
    }
}
