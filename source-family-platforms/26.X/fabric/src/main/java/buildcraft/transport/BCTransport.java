/* BuildCraft Transport common bootstrap for Fabric 26.3. MPL-2.0. */
package buildcraft.transport;

import buildcraft.lib.BCLibRegistries;
import buildcraft.lib.CreativeTabManager;
import buildcraft.lib.CreativeTabManager.CreativeTabBC;
import buildcraft.lib.internal.core.BuildCraftAPI;
import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.internal.mj.MjCapabilities;
import buildcraft.lib.internal.capabilities.BCCapabilityRegistration;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.platform.config.ConfigBinding;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.RegistryBinding;
import buildcraft.transport.api2.TransportApi2;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.net.MessageMultiPipeItem;
import buildcraft.transport.net.MessagePipeAttachmentAttack;
import buildcraft.transport.pipe.SchematicBlockPipe;
import buildcraft.transport.wire.MessageWireSystems;
import buildcraft.transport.wire.MessageWireSystemsPowered;
import buildcraft.builders.internal.schematic.legacy.SchematicBlockFactoryRegistry;
import buildcraft.core.BCCore;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** No NeoForge event bus: registration is immediate and ordered on Fabric. */
public final class BCTransport {
    public static final String MODID = "buildcrafttransport";
    public static final CreativeTabBC tabPipes = (CreativeTabBC) CreativeTabManager.createTab("buildcraft.pipes").setRecipeFolderName("pipes");
    public static final CreativeTabBC tabPlugs = (CreativeTabBC) CreativeTabManager.createTab("buildcraft.plugs").setRecipeFolderName("plugs");
    private static final BCDeferredRegister<CreativeModeTab> CREATIVE_TABS =
        BCDeferredRegister.create("minecraft:creative_mode_tab", "buildcraft");
    public static final BCRegistryEntry<CreativeModeTab> PIPES_TAB = CREATIVE_TABS.register("pipes", () ->
        net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab.builder().title(Component.translatable("itemGroup.buildcraft.pipes"))
            .icon(tabPipes::makeIcon).displayItems((p, o) -> tabPipes.accept(List.of(), o::accept)).build());
    public static final BCRegistryEntry<CreativeModeTab> PLUGS_TAB = CREATIVE_TABS.register("plugs", () ->
        net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab.builder().title(Component.translatable("itemGroup.buildcraft.plugs"))
            .icon(tabPlugs::makeIcon).displayItems((p, o) -> tabPlugs.accept(List.of(), o::accept)).build());
    private static boolean bootstrapped;
    private BCTransport() { }

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        BuildCraftAPI.withModule(MODID, BCTransport::bootstrapInternal);
        bootstrapped = true;
    }

    private static void bootstrapInternal() {
        BCLibRegistries.initApiRegistries();
        TransportApi2.install();
        BCTransportRegistries.preInit();
        BCTransportConfig.preInit();
        BCTransportRecipes.preInit(RegistryBinding.direct());
        BCTransportPipes.preInit();
        BCTransportPlugs.preInit();
        BCTransportBlocks.registry(RegistryBinding.direct());
        BCTransportItems.registry(RegistryBinding.direct());
        tabPipes.addItemProvider(BCTransportItems::getPipeTabItems);
        tabPlugs.addItemProvider(BCTransportItems::getPlugTabItems);
        BCCore.BUILDCRAFT_TAB.addItemProvider(BCTransportBlocks::getCreativeTabItems);
        BCTransportGuis.preInit(RegistryBinding.direct());
        CREATIVE_TABS.register(RegistryBinding.direct());
        BCTransportStatements.preInit();
        ConfigBinding.listen(MODID, BCTransportConfig::onConfigLoad, BCTransportConfig::onConfigReload);
        ConfigBinding.bind(MODID, BCTransportConfig.config);
        MessageManager.registerClientboundMessageClass(BCModules.TRANSPORT,
            MessageWireSystems.class, null, MessageWireSystems::toBytes, MessageWireSystems::new);
        MessageManager.registerClientboundMessageClass(BCModules.TRANSPORT,
            MessageWireSystemsPowered.class, null, MessageWireSystemsPowered::toBytes, MessageWireSystemsPowered::new);
        MessageManager.registerClientboundMessageClass(BCModules.TRANSPORT,
            MessageMultiPipeItem.class, null, MessageMultiPipeItem::toBytes, MessageMultiPipeItem::new);
        MessageManager.registerMessageClass(BCModules.TRANSPORT,
            MessagePipeAttachmentAttack.class, MessagePipeAttachmentAttack.HANDLER,
            MessagePipeAttachmentAttack::toBytes, MessagePipeAttachmentAttack::new,
            buildcraft.lib.net.BCNetworkSide.SERVER);
        BCTransportEventDist.registerGameplayEvents();
        SchematicBlockFactoryRegistry.registerFactory("pipe", 300,
            SchematicBlockPipe::predicate, SchematicBlockPipe::new);
        registerCapabilities();
        BCTransportConfig.reloadConfig();
        BCTransportRegistries.init();
        tabPipes.setItem(BCTransportItems.PIPE_ITEM_DIAMOND.get());
        tabPlugs.setItem(BCTransportItems.plugBlocker.get());
    }

    private static void registerCapabilities() {
        BlockEntityType<buildcraft.transport.tile.TilePipeHolder> type = BCTransportBlocks.PIPE_HOLDER_BE.get();
        BCCapabilityRegistration.registerBlockEntity(PipeApi.CAP_PIPE_HOLDER, type);
        BCCapabilityRegistration.registerBlockEntity(PipeApi.CAP_PIPE, type);
        BCCapabilityRegistration.registerBlockEntity(PipeApi.CAP_PLUG, type);
        BCCapabilityRegistration.registerBlockEntity(PipeApi.CAP_INJECTABLE, type);
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_ITEM_TRANSACTOR, type);
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_ITEMS, type);
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_FLUIDS, type);
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_FE, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_CONNECTOR, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_RECEIVER, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_REDSTONE_RECEIVER, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_READABLE, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_PASSIVE_PROVIDER, type);
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_ITEMS,
            BCTransportBlocks.FILTERREDBUFFER_BE.get());
    }
}
