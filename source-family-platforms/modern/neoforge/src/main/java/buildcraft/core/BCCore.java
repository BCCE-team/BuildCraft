//? source if >=1.21.1
package buildcraft.core;

import buildcraft.lib.platform.registry.RegistryBinding;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.platform.config.ConfigBinding;
import buildcraft.lib.platform.config.ConfigScreenRegistration;
import buildcraft.lib.internal.mj.MjCapabilities;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.internal.enums.EnumSpring;
import buildcraft.lib.internal.capabilities.BCCapabilityRegistration;
import buildcraft.lib.internal.capabilities.IBCCapabilityProvider;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.core.list.ContainerList;
import buildcraft.core.marker.PathCache;
import buildcraft.core.marker.VolumeCache;
import buildcraft.core.marker.volume.MessageVolumeBoxes;
import buildcraft.energy.BCEnergyFluids;
import buildcraft.energy.tile.TileSpringOil;
import buildcraft.lib.CreativeTabManager;
import buildcraft.lib.CreativeTabManager.CreativeTabBC;
import buildcraft.lib.gui.BCContainerFactory;
import buildcraft.lib.marker.MarkerCache;
import buildcraft.lib.net.MessageManager;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import buildcraft.lib.misc.CapUtil;

@Mod(BCCore.MODID)
public class BCCore {
    public static final String MODID = "buildcraftcore";
    public static final CreativeTabBC BUILDCRAFT_TAB = CreativeTabManager.createTab("buildcraft.main");
    public static final CreativeTabBC tabFluids = CreativeTabManager.createTab("buildcraft.fluid");

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
            .icon(tabFluids::makeIcon)
            .displayItems((parameters, output) -> tabFluids.accept(List.of(), output::accept))
            .build());

    public static final Map<String,Object> ENGINE_MAP = new HashMap<>();
    public static final BCDeferredRegister<MenuType<?>> MENUS = BCDeferredRegister.create("minecraft:menu", BCCore.MODID);
    public static final BCRegistryEntry<MenuType<ContainerList>> LIST_MENU = MENUS.register("list_menu",
        () -> BCContainerFactory.create(ContainerList::new));

    public BCCore(IEventBus modEventBus, ModContainer modContainer) {
        ConfigBinding.listen(modEventBus, BCCoreConfig::onLoadConfig, BCCoreConfig::onReloadConfig);

        modEventBus.addListener(this::init);
        modEventBus.addListener(this::registerCapabilities);

        BCCoreBlocks.registry(RegistryBinding.on(modEventBus));
        BCCoreItems.registry(RegistryBinding.on(modEventBus));
        BUILDCRAFT_TAB.addItemProvider(BCCoreItems::getCreativeTabItems);

        RegistryBinding.register(CREATIVE_TABS, modEventBus);
        RegistryBinding.register(MENUS, modEventBus);
        BCCoreConfig.registry();
        modContainer.registerConfig(Type.COMMON, ConfigBinding.bind(BCCoreConfig.config));
        ConfigScreenRegistration.register(modContainer);
        MessageManager.registerClientboundMessageClass(BCModules.CORE, MessageVolumeBoxes.class, MessageVolumeBoxes.HANDLER, MessageVolumeBoxes::toBytes, MessageVolumeBoxes::new);
        BCCoreStatements.preInit();
    }


    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerItem(
            Capabilities.Fluid.ITEM,
            (stack, context) -> new buildcraft.core.item.FragileFluidResourceHandler(context),
            BCCoreItems.FRAGILE_FLUID_SHARD.get()
        );

        registerEngineCapabilities(event, BCCoreBlocks.ENGINE_REDSTONE_TILE_BC8.get());
        registerEngineCapabilities(event, BCCoreBlocks.ENGINE_CREATIVE_TILE_BC8.get());
    }

    private static <BE extends BlockEntity & IBCCapabilityProvider> void registerEngineCapabilities(
        RegisterCapabilitiesEvent event, BlockEntityType<BE> blockEntityType
    ) {
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_CONNECTOR, blockEntityType);
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_RECEIVER, blockEntityType);
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_REDSTONE_RECEIVER, blockEntityType);
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_READABLE, blockEntityType);
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_PASSIVE_PROVIDER, blockEntityType);
        BCCapabilityRegistration.registerBlockEntity(event, CapUtil.CAP_ITEMS, blockEntityType);
    }

    public void init(final FMLCommonSetupEvent event)
    {
        MarkerCache.registerCache(VolumeCache.INSTANCE);
        MarkerCache.registerCache(PathCache.INSTANCE);
        EnumSpring.OIL.liquidBlock = BCEnergyFluids.OIL_BLOCK.get(0).get().defaultBlockState();
        EnumSpring.OIL.tileConstructor = TileSpringOil::new;
        BCCoreConfig.reloadConfig(MODID);
        BUILDCRAFT_TAB.setItem(BCCoreItems.WRENCH.get());
        BuildCraftApi.registry(BuildCraftRegistries.FLUID_DROP_PROVIDERS).register(
            java.util.Objects.requireNonNull(net.minecraft.resources.Identifier.tryParse("buildcraftcore:fragile_fluid_shard")),
            BCCoreItems.FRAGILE_FLUID_SHARD.get()
        );
    }

}
