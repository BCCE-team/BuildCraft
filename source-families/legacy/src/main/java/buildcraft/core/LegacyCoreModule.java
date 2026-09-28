package buildcraft.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.core.list.ContainerList;
import buildcraft.core.marker.PathCache;
import buildcraft.core.marker.VolumeCache;
import buildcraft.energy.BCEnergyFluids;
import buildcraft.energy.tile.TileSpringOil;
import buildcraft.lib.CreativeTabManager;
import buildcraft.lib.CreativeTabManager.CreativeTabBC;
import buildcraft.lib.gui.BCContainerFactory;
import buildcraft.lib.internal.enums.EnumSpring;
import buildcraft.lib.internal.module.BCModuleBootstrapContext;
import buildcraft.lib.marker.MarkerCache;
import buildcraft.lib.net.LegacyNetworkCatalog;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;

/** Loader-neutral ownership of the legacy Core module bootstrap. */
public final class LegacyCoreModule {
    public static final String MODID = "buildcraftcore";
    public static final CreativeTabBC BUILDCRAFT_TAB = CreativeTabManager.createTab("buildcraft.main");
    public static final CreativeTabBC TAB_FLUIDS = CreativeTabManager.createTab("buildcraft.fluid");

    private static final BCDeferredRegister<CreativeModeTab> CREATIVE_TABS =
        BCDeferredRegister.create("minecraft:creative_mode_tab", "buildcraft");
    public static final BCRegistryEntry<CreativeModeTab> MAIN_TAB = CREATIVE_TABS.register("main", () ->
        CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.buildcraft.main"))
            .icon(BUILDCRAFT_TAB::makeIcon)
            .displayItems((parameters, output) -> BUILDCRAFT_TAB.accept(List.of(), output::accept))
            .build());
    public static final BCRegistryEntry<CreativeModeTab> FLUID_TAB = CREATIVE_TABS.register("fluid", () ->
        CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.buildcraft.fluid"))
            .icon(TAB_FLUIDS::makeIcon)
            .displayItems((parameters, output) -> TAB_FLUIDS.accept(List.of(), output::accept))
            .build());

    public static final Map<String, Object> ENGINE_MAP = new HashMap<>();
    public static final BCDeferredRegister<MenuType<?>> MENUS = BCDeferredRegister.create("minecraft:menu", MODID);
    public static final BCRegistryEntry<MenuType<ContainerList>> LIST_MENU = MENUS.register(
        "list_menu", () -> BCContainerFactory.create(ContainerList::new)
    );

    private LegacyCoreModule() {
    }

    public static void bootstrap(BCModuleBootstrapContext context) {
        BCCoreBlocks.registry(context.registries());
        BCCoreItems.registry(context.registries());
        BUILDCRAFT_TAB.addItemProvider(BCCoreItems::getCreativeTabItems);
        CREATIVE_TABS.register(context.registries());
        MENUS.register(context.registries());

        BCCoreConfig.registry();
        context.registerConfig(MODID, BCCoreConfig.config, BCCoreConfig::onLoadConfig, BCCoreConfig::onReloadConfig);
        LegacyNetworkCatalog.registerCore(context.messages());
        BCCoreStatements.preInit();
        context.onCommonSetup(LegacyCoreModule::commonSetup);
    }

    private static void commonSetup() {
        MarkerCache.registerCache(VolumeCache.INSTANCE);
        MarkerCache.registerCache(PathCache.INSTANCE);
        EnumSpring.OIL.liquidBlock = BCEnergyFluids.OIL_BLOCK.get(0).get().defaultBlockState();
        EnumSpring.OIL.tileConstructor = TileSpringOil::new;
        BCCoreConfig.reloadConfig(MODID);
        BUILDCRAFT_TAB.setItem(BCCoreItems.WRENCH.get());
        BuildCraftApi.registry(BuildCraftRegistries.FLUID_DROP_PROVIDERS).register(
            java.util.Objects.requireNonNull(net.minecraft.resources.ResourceLocation.tryParse(
                "buildcraftcore:fragile_fluid_shard"
            )),
            BCCoreItems.FRAGILE_FLUID_SHARD.get()
        );
    }
}
