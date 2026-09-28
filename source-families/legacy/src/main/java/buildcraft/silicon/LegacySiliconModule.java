package buildcraft.silicon;

import java.util.List;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.core.LegacyCoreModule;
import buildcraft.lib.CreativeTabManager;
import buildcraft.lib.CreativeTabManager.CreativeTabBC;
import buildcraft.lib.internal.module.BCModuleBootstrapContext;
import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.silicon.plug.FacadeBlockStateInfo;
import buildcraft.silicon.plug.FacadeInstance;
import buildcraft.silicon.plug.FacadeStateManager;
import buildcraft.transport.LegacyTransportModule;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;

/** Loader-neutral ownership of the legacy Silicon module bootstrap. */
public final class LegacySiliconModule {
    public static final String MODID = "buildcraftsilicon";
    public static final CreativeTabBC TAB_PLUGS = LegacyTransportModule.TAB_PLUGS;
    public static final CreativeTabBC TAB_FACADES = CreativeTabManager.createTab("buildcraft.facades")
        .setRecipeFolderName("facades");

    private static final BCDeferredRegister<CreativeModeTab> CREATIVE_TABS =
        BCDeferredRegister.create("minecraft:creative_mode_tab", "buildcraft");
    public static final BCRegistryEntry<CreativeModeTab> FACADES_TAB = CREATIVE_TABS.register("facades", () ->
        CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.buildcraft.facades"))
            .icon(TAB_FACADES::makeIcon)
            .displayItems((parameters, output) -> TAB_FACADES.accept(List.of(), output::accept))
            .build());

    private LegacySiliconModule() {
    }

    public static void bootstrap(BCModuleBootstrapContext context) {
        BuildCraftApi.registry(BuildCraftRegistries.FACADE_MATERIAL_ADAPTERS).register(
            java.util.Objects.requireNonNull(
                net.minecraft.resources.ResourceLocation.tryParse("buildcraft:facade_materials/builtin")
            ),
            FacadeStateManager.INSTANCE
        );

        BCSiliconConfig.preInit();
        context.registerConfig(MODID, BCSiliconConfig.config, BCSiliconConfig::onLoadConfig, BCSiliconConfig::onReloadConfig);
        BCSiliconStatements.preInit();
        BCSiliconPlugs.preInit();
        BCSiliconBlocks.registry(context.registries());
        BCSiliconItems.registry(context.registries());
        BCSiliconGuis.preInit(context.registries());
        BCSiliconRecipes.preInit(context.registries());
        CREATIVE_TABS.register(context.registries());

        LegacyCoreModule.BUILDCRAFT_TAB.addItemProvider(BCSiliconItems::getMainTabItems);
        TAB_PLUGS.addItemProvider(BCSiliconItems::getPlugTabItems);
        TAB_FACADES.addItemProvider(BCSiliconItems::getFacadeTabItems);

        context.onCommonSetup(() -> {
            BCSiliconConfig.reloadConfig(MODID);
            FacadeStateManager.init();
        });
        context.onLoadComplete(() -> {
            if (BCSiliconConfig.enableFacades && BCSiliconItems.PLUG_FACADE_ITEM.isPresent()) {
                FacadeBlockStateInfo state = FacadeStateManager.previewState;
                if (state != null) {
                    FacadeInstance instance = FacadeInstance.createSingle(state, false);
                    TAB_FACADES.setItem(BCSiliconItems.PLUG_FACADE_ITEM.get().createItemStack(instance));
                }
            }
            if (!BCModules.TRANSPORT.isLoaded() && BCSiliconItems.PLUG_GATE_ITEM.isPresent()) {
                TAB_PLUGS.setItem(BCSiliconItems.PLUG_GATE_ITEM.get());
            }
        });
    }
}
