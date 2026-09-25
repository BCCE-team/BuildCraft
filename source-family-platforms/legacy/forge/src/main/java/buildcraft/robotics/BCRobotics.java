package buildcraft.robotics;

import buildcraft.lib.net.MessageManager;
import buildcraft.lib.net.LegacyNetworkCatalog;
import buildcraft.lib.platform.client.PlatformClientModels;
import buildcraft.robotics.BCRoboticsClientRenderers;
import buildcraft.lib.platform.client.PlatformClientRegistration;
import buildcraft.lib.platform.registry.RegistryBinding;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.robotics.internal.api2.RoboticsApi2Bootstrap;
import java.util.List;

import buildcraft.lib.internal.module.BCModules;
import buildcraft.core.BCCore;
import buildcraft.lib.internal.statement.StatementManager;
import buildcraft.robotics.statements.RobotsActionProvider;
import buildcraft.robotics.statements.RobotsTriggerProvider;
import buildcraft.robotics.statements.StatementParameterRobot;
import buildcraft.robotics.statements.StatementParameterMapLocation;
import buildcraft.lib.CreativeTabManager;
import buildcraft.lib.CreativeTabManager.CreativeTabBC;
import buildcraft.robotics.zone.MessageZoneMapRequest;
import buildcraft.robotics.ai.AIRobotBreak;
import buildcraft.robotics.ai.AIRobotAttack;
import buildcraft.robotics.ai.AIRobotHarvest;
import buildcraft.robotics.ai.AIRobotFetchAndEquipItemStack;
import buildcraft.robotics.ai.AIRobotDeliverRequested;
import buildcraft.robotics.ai.AIRobotFetchItem;
import buildcraft.robotics.ai.AIRobotGotoBlock;
import buildcraft.robotics.ai.AIRobotGoAndLinkToDock;
import buildcraft.robotics.ai.AIRobotGotoSleep;
import buildcraft.robotics.ai.AIRobotGotoStation;
import buildcraft.robotics.ai.AIRobotGotoStationAndLoad;
import buildcraft.robotics.ai.AIRobotGotoStationAndLoadFluids;
import buildcraft.robotics.ai.AIRobotGotoStationAndUnload;
import buildcraft.robotics.ai.AIRobotGotoStationAndUnloadFluids;
import buildcraft.robotics.ai.AIRobotGotoStationToLoad;
import buildcraft.robotics.ai.AIRobotGotoStationToLoadFluids;
import buildcraft.robotics.ai.AIRobotGotoStationToUnload;
import buildcraft.robotics.ai.AIRobotGotoStationToUnloadFluids;
import buildcraft.robotics.ai.AIRobotLoad;
import buildcraft.robotics.ai.AIRobotLoadFluids;
import buildcraft.robotics.ai.AIRobotMain;
import buildcraft.robotics.ai.AIRobotPlant;
import buildcraft.robotics.ai.AIRobotPumpBlock;
import buildcraft.robotics.ai.AIRobotRecharge;
import buildcraft.robotics.ai.AIRobotReturnToLostStation;
import buildcraft.robotics.ai.AIRobotSearchAndGotoBlock;
import buildcraft.robotics.ai.AIRobotSearchAndGotoStation;
import buildcraft.robotics.ai.AIRobotSearchBlock;
import buildcraft.robotics.ai.AIRobotSearchEntity;
import buildcraft.robotics.ai.AIRobotSearchRandomGroundBlock;
import buildcraft.robotics.ai.AIRobotSearchStackRequest;
import buildcraft.robotics.ai.AIRobotSearchStation;
import buildcraft.robotics.ai.AIRobotShutdown;
import buildcraft.robotics.ai.AIRobotStripesHandler;
import buildcraft.robotics.ai.AIRobotSleep;
import buildcraft.robotics.ai.AIRobotStraightMoveTo;
import buildcraft.robotics.ai.AIRobotUnload;
import buildcraft.robotics.ai.AIRobotUnloadFluids;
import buildcraft.robotics.ai.AIRobotUseToolOnBlock;
import buildcraft.robotics.boards.BoardRobotCarrier;
import buildcraft.robotics.boards.BoardRobotDelivery;
import buildcraft.robotics.boards.BoardRobotFluidCarrier;
import buildcraft.robotics.boards.BoardRobotHarvester;
import buildcraft.robotics.boards.BoardRobotBomber;
import buildcraft.robotics.boards.BoardRobotBuilder;
import buildcraft.robotics.boards.BoardRobotButcher;
import buildcraft.robotics.boards.BoardRobotFarmer;
import buildcraft.robotics.boards.BoardRobotLeaveCutter;
import buildcraft.robotics.boards.BoardRobotKnight;
import buildcraft.robotics.boards.BoardRobotLumberjack;
import buildcraft.robotics.boards.BoardRobotMiner;
import buildcraft.robotics.boards.BoardRobotPicker;
import buildcraft.robotics.boards.BoardRobotPlanter;
import buildcraft.robotics.boards.BoardRobotPump;
import buildcraft.robotics.boards.BoardRobotShovelman;
import buildcraft.robotics.boards.BoardRobotStripes;
import buildcraft.robotics.internal.legacy.robots.RobotManager;
import buildcraft.robotics.client.render.RenderRobot;
import buildcraft.robotics.client.render.RenderZonePlanner;
import buildcraft.robotics.zone.MessageZoneMapResponse;
import buildcraft.robotics.recipes.RobotIntegrationRecipe;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ModelEvent.BakingCompleted;
import net.minecraftforge.client.event.ModelEvent.RegisterAdditional;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * BuildCraft Robotics bootstrap for Forge 1.20.1.
 *
 * Registers the robotics creative tab, robot items, docking station, boards, zone planner,
 * client/server networking and menu bindings required by the robotics systems.
 */
@Mod(BCRobotics.MODID)
public class BCRobotics {
    public static final String MODID = "buildcraftrobotics";

    /** Robotics creative tab using the stable "buildcraft.boards" identifier. */
    public static final CreativeTabBC TAB_ROBOTICS = CreativeTabManager.createTab("buildcraft.boards");

    private static final BCDeferredRegister<CreativeModeTab> CREATIVE_TABS =
            BCDeferredRegister.create("minecraft:creative_mode_tab", "buildcraft");
    public static final BCRegistryEntry<CreativeModeTab> ROBOTICS_TAB = CREATIVE_TABS.register("boards", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.buildcraft.boards"))
                    .icon(TAB_ROBOTICS::makeIcon)
                    .displayItems((parameters, output) -> TAB_ROBOTICS.accept(List.of(), output::accept))
                    .build());

    public BCRobotics() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::init);
        modEventBus.addListener(BCRobotics::registerEntityAttributes);

        BCRoboticsBoards.init();
        RoboticsApi2Bootstrap.bootstrap();
        BCRoboticsPlugs.preInit();
        BCRoboticsBlocks.registry(RegistryBinding.on(modEventBus));
        BCRoboticsItems.registry(RegistryBinding.on(modEventBus));
        BCRoboticsEntities.registry(RegistryBinding.on(modEventBus));
        BCRoboticsGuis.registry(RegistryBinding.on(modEventBus));
        TAB_ROBOTICS.addItemProvider(BCRoboticsItems::getRoboticsTabItems);
        BCCore.BUILDCRAFT_TAB.addItemProvider(BCRoboticsItems::getMainTabItems);
        RegistryBinding.register(CREATIVE_TABS, modEventBus);

        // Register the zone planner messages used for map request and synchronization.
        LegacyNetworkCatalog.registerRobotics(MessageManager::registerCatalogMessage);

        RobotManager.registryProvider = SimpleRobotRegistryProvider.INSTANCE;
        SimpleRobotRegistryProvider.registerGameplayEvents();
        RobotManager.registerDockingStation(DockingStationPipe.class, "pipe");
        registerRoboticsAI();
        BoardRobotPicker.onServerStart();

        // No config is registered yet; this keeps the module bootstrap deliberately small.
    }

    private void init(final FMLCommonSetupEvent event) {
        // Register robot statement providers and parameter types
        BCRoboticsStatements.preInit();
        StatementManager.registerActionProvider(new RobotsActionProvider());
        StatementManager.registerTriggerProvider(new RobotsTriggerProvider());
        // Register via the reader overload so both NBT persistence and GUI/network buffer sync are available.
        // Work/load area actions store a Map Location item as a parameter; without the buffer reader, gates can save
        // the parameter but fail to sync/open correctly when that action is configured.
        StatementManager.registerParameter(StatementParameterRobot::readFromNbt);
        StatementManager.registerParameter(StatementParameterMapLocation::readFromNbt);

        RobotIntegrationRecipe.register();
        TAB_ROBOTICS.setItem(BCRoboticsItems.ROBOT.get());
    }

    private static void registerEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(BCRoboticsEntities.ROBOT.get(), buildcraft.robotics.entity.EntityRobot.createAttributes().build());
    }


    private static void registerRoboticsAI() {
        if (RobotManager.getAIRobotName(AIRobotMain.class) != null) {
            return;
        }
        RobotManager.registerAIRobot(AIRobotMain.class, "main", "buildcraft.robotics.ai.AIRobotMain");
        RobotManager.registerAIRobot(BoardRobotPicker.class, "boardPicker", "buildcraft.robotics.boards.BoardRobotPicker");
        RobotManager.registerAIRobot(BoardRobotCarrier.class, "boardCarrier", "buildcraft.robotics.boards.BoardRobotCarrier");
        RobotManager.registerAIRobot(BoardRobotFluidCarrier.class, "boardFluidCarrier", "buildcraft.robotics.boards.BoardRobotFluidCarrier");
        RobotManager.registerAIRobot(BoardRobotLumberjack.class, "boardLumberjack", "buildcraft.robotics.boards.BoardRobotLumberjack");
        RobotManager.registerAIRobot(BoardRobotHarvester.class, "boardHarvester", "buildcraft.robotics.boards.BoardRobotHarvester");
        RobotManager.registerAIRobot(BoardRobotMiner.class, "boardMiner", "buildcraft.robotics.boards.BoardRobotMiner");
        RobotManager.registerAIRobot(BoardRobotPlanter.class, "boardPlanter", "buildcraft.robotics.boards.BoardRobotPlanter");
        RobotManager.registerAIRobot(BoardRobotFarmer.class, "boardFarmer", "buildcraft.robotics.boards.BoardRobotFarmer");
        RobotManager.registerAIRobot(BoardRobotLeaveCutter.class, "boardLeaveCutter", "buildcraft.robotics.boards.BoardRobotLeaveCutter");
        RobotManager.registerAIRobot(BoardRobotButcher.class, "boardButcher", "buildcraft.robotics.boards.BoardRobotButcher");
        RobotManager.registerAIRobot(BoardRobotShovelman.class, "boardShovelman", "buildcraft.robotics.boards.BoardRobotShovelman");
        RobotManager.registerAIRobot(BoardRobotPump.class, "boardPump", "buildcraft.robotics.boards.BoardRobotPump");
        RobotManager.registerAIRobot(BoardRobotDelivery.class, "boardRobotDelivery", "buildcraft.robotics.boards.BoardRobotDelivery");
        RobotManager.registerAIRobot(BoardRobotKnight.class, "boardKnight", "buildcraft.robotics.boards.BoardRobotKnight");
        RobotManager.registerAIRobot(BoardRobotBomber.class, "boardBomber", "buildcraft.robotics.boards.BoardRobotBomber");
        RobotManager.registerAIRobot(BoardRobotStripes.class, "boardStripes", "buildcraft.robotics.boards.BoardRobotStripes");
        RobotManager.registerAIRobot(BoardRobotBuilder.class, "boardBuilder", "buildcraft.robotics.boards.BoardRobotBuilder");
        RobotManager.registerAIRobot(AIRobotFetchItem.class, "fetchItem", "buildcraft.robotics.ai.AIRobotFetchItem");
        RobotManager.registerAIRobot(AIRobotFetchAndEquipItemStack.class, "fetchAndEquipItemStack", "buildcraft.robotics.ai.AIRobotFetchAndEquipItemStack");
        RobotManager.registerAIRobot(AIRobotSearchBlock.class, "searchBlock", "buildcraft.robotics.ai.AIRobotSearchBlock");
        RobotManager.registerAIRobot(AIRobotSearchRandomGroundBlock.class, "searchRandomGroundBlock", "buildcraft.robotics.ai.AIRobotSearchRandomGroundBlock");
        RobotManager.registerAIRobot(AIRobotSearchEntity.class, "searchEntity", "buildcraft.robotics.ai.AIRobotSearchEntity");
        RobotManager.registerAIRobot(AIRobotSearchAndGotoBlock.class, "searchAndGotoBlock", "buildcraft.robotics.ai.AIRobotSearchAndGotoBlock");
        RobotManager.registerAIRobot(AIRobotBreak.class, "break", "buildcraft.robotics.ai.AIRobotBreak");
        RobotManager.registerAIRobot(AIRobotPumpBlock.class, "pumpBlock", "buildcraft.robotics.ai.AIRobotPumpBlock");
        RobotManager.registerAIRobot(AIRobotAttack.class, "attack", "buildcraft.robotics.ai.AIRobotAttack");
        RobotManager.registerAIRobot(AIRobotHarvest.class, "harvest", "buildcraft.robotics.ai.AIRobotHarvest");
        RobotManager.registerAIRobot(AIRobotPlant.class, "plant", "buildcraft.robotics.ai.AIRobotPlant");
        RobotManager.registerAIRobot(AIRobotUseToolOnBlock.class, "useToolOnBlock", "buildcraft.robotics.ai.AIRobotUseToolOnBlock");
        RobotManager.registerAIRobot(AIRobotStripesHandler.class, "stripesHandler", "buildcraft.robotics.ai.AIRobotStripesHandler");
        RobotManager.registerAIRobot(AIRobotGotoBlock.class, "gotoBlock", "buildcraft.robotics.ai.AIRobotGotoBlock");
        RobotManager.registerAIRobot(AIRobotStraightMoveTo.class, "straightMoveTo", "buildcraft.robotics.ai.AIRobotStraightMoveTo");
        RobotManager.registerAIRobot(AIRobotGotoStation.class, "gotoStation", "buildcraft.robotics.ai.AIRobotGotoStation");
        RobotManager.registerAIRobot(AIRobotGoAndLinkToDock.class, "goAndLinkToDock", "buildcraft.robotics.ai.AIRobotGoAndLinkToDock");
        RobotManager.registerAIRobot(AIRobotGotoStationToLoad.class, "gotoStationToLoad", "buildcraft.robotics.ai.AIRobotGotoStationToLoad");
        RobotManager.registerAIRobot(AIRobotGotoStationAndLoad.class, "gotoStationAndLoad", "buildcraft.robotics.ai.AIRobotGotoStationAndLoad");
        RobotManager.registerAIRobot(AIRobotGotoStationToLoadFluids.class, "gotoStationToLoadFluids", "buildcraft.robotics.ai.AIRobotGotoStationToLoadFluids");
        RobotManager.registerAIRobot(AIRobotGotoStationAndLoadFluids.class, "gotoStationAndLoadFluids", "buildcraft.robotics.ai.AIRobotGotoStationAndLoadFluids");
        RobotManager.registerAIRobot(AIRobotGotoStationToUnload.class, "gotoStationToUnload", "buildcraft.robotics.ai.AIRobotGotoStationToUnload");
        RobotManager.registerAIRobot(AIRobotGotoStationAndUnload.class, "gotoStationAndUnload", "buildcraft.robotics.ai.AIRobotGotoStationAndUnload");
        RobotManager.registerAIRobot(AIRobotGotoStationToUnloadFluids.class, "gotoStationToUnloadFluids", "buildcraft.robotics.ai.AIRobotGotoStationToUnloadFluids");
        RobotManager.registerAIRobot(AIRobotGotoStationAndUnloadFluids.class, "gotoStationAndUnloadFluids", "buildcraft.robotics.ai.AIRobotGotoStationAndUnloadFluids");
        RobotManager.registerAIRobot(AIRobotSearchStackRequest.class, "searchStackRequest", "buildcraft.robotics.ai.AIRobotSearchStackRequest");
        RobotManager.registerAIRobot(AIRobotSearchStation.class, "searchStation", "buildcraft.robotics.ai.AIRobotSearchStation");
        RobotManager.registerAIRobot(AIRobotSearchAndGotoStation.class, "searchAndGotoStation", "buildcraft.robotics.ai.AIRobotSearchAndGotoStation");
        RobotManager.registerAIRobot(AIRobotLoad.class, "load", "buildcraft.robotics.ai.AIRobotLoad");
        RobotManager.registerAIRobot(AIRobotLoadFluids.class, "loadFluids", "buildcraft.robotics.ai.AIRobotLoadFluids");
        RobotManager.registerAIRobot(AIRobotDeliverRequested.class, "deliverRequested", "buildcraft.robotics.ai.AIRobotDeliverRequested");
        RobotManager.registerAIRobot(AIRobotUnload.class, "unload", "buildcraft.robotics.ai.AIRobotUnload");
        RobotManager.registerAIRobot(AIRobotUnloadFluids.class, "unloadFluids", "buildcraft.robotics.ai.AIRobotUnloadFluids");
        RobotManager.registerAIRobot(AIRobotGotoSleep.class, "gotoSleep", "buildcraft.robotics.ai.AIRobotGotoSleep");
        RobotManager.registerAIRobot(AIRobotSleep.class, "sleep", "buildcraft.robotics.ai.AIRobotSleep");
        RobotManager.registerAIRobot(AIRobotRecharge.class, "recharge", "buildcraft.robotics.ai.AIRobotRecharge");
        RobotManager.registerAIRobot(AIRobotReturnToLostStation.class, "returnToLostStation");
        RobotManager.registerAIRobot(AIRobotShutdown.class, "shutdown", "buildcraft.robotics.ai.AIRobotShutdown");
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        private static final ResourceLocation ROBOT_MODEL = new ResourceLocation(MODID, "robot");
        private static final ResourceLocation BOARD_MODEL = new ResourceLocation(MODID, "board");

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            PlatformClientRegistration.screens(event, BCRoboticsClientGuis::clientInit);
            BCRoboticsSprites.preInit();
            event.enqueueWork(() -> {
                ItemProperties.register(BCRoboticsItems.ROBOT.get(), ROBOT_MODEL,
                        (stack, level, entity, seed) -> BCRoboticsBoards.getRobotModelValue(stack));
                ItemProperties.register(BCRoboticsItems.REDSTONE_BOARD.get(), BOARD_MODEL,
                        (stack, level, entity, seed) -> BCRoboticsBoards.getBoardModelValue(stack));
                BCRoboticsModels.init();
            });
        }

        @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        BCRoboticsClientRenderers.register(PlatformClientRegistration.renderers(event));
    }


        @SubscribeEvent
        public static void onModelBakePre(RegisterAdditional event) {
            BCRoboticsModels.onModelBakePre(PlatformClientModels.additional(event));
        }

        @SubscribeEvent
        public static void onModelBake(BakingCompleted event) {
            BCRoboticsModels.onModelBake(PlatformClientModels.completed(event));
        }
    }
}
