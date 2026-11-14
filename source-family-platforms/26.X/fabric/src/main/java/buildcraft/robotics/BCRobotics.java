/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.robotics;

import buildcraft.core.BCCore;
import buildcraft.lib.CreativeTabManager;
import buildcraft.lib.CreativeTabManager.CreativeTabBC;
import buildcraft.lib.internal.capabilities.BCCapabilityRegistration;
import buildcraft.lib.internal.core.BuildCraftAPI;
import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.internal.statement.StatementManager;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.platform.storage.FabricEntityStorageLookups;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.RegistryBinding;
import buildcraft.robotics.ai.*;
import buildcraft.robotics.boards.*;
import buildcraft.robotics.internal.api2.RoboticsApi2Bootstrap;
import buildcraft.robotics.internal.legacy.robots.RobotManager;
import buildcraft.robotics.recipes.RobotIntegrationRecipe;
import buildcraft.robotics.statements.*;
import buildcraft.robotics.zone.*;
import java.util.List;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;

/** Fabric 26.3 Robotics bootstrap; all IDs and task costs follow 26.3 NeoForge. */
public final class BCRobotics {
    public static final String MODID = "buildcraftrobotics";
    public static final CreativeTabBC TAB_ROBOTICS = CreativeTabManager.createTab("buildcraft.boards");
    private static final BCDeferredRegister<CreativeModeTab> CREATIVE_TABS =
        BCDeferredRegister.create("minecraft:creative_mode_tab", "buildcraft");
    public static final BCRegistryEntry<CreativeModeTab> ROBOTICS_TAB = CREATIVE_TABS.register("boards", () ->
        net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab.builder()
            .title(Component.translatable("itemGroup.buildcraft.boards"))
            .icon(TAB_ROBOTICS::makeIcon)
            .displayItems((parameters, output) -> TAB_ROBOTICS.accept(List.of(), output::accept))
            .build());
    private static boolean initialized;
    private BCRobotics() {}

    public static synchronized void bootstrap() {
        if (initialized) return;
        BuildCraftAPI.withModule(MODID, BCRobotics::bootstrapInternal);
        initialized = true;
    }

    private static void bootstrapInternal() {
        BCRoboticsBoards.init();
        RoboticsApi2Bootstrap.bootstrap();
        BCRoboticsPlugs.preInit();
        BCRoboticsBlocks.BLOCKS.register(RegistryBinding.direct());
        BCRoboticsItems.registry(RegistryBinding.direct());
        BCRoboticsBlocks.BLOCK_ENTITYS.register(RegistryBinding.direct());
        BCRoboticsEntities.registry(RegistryBinding.direct());
        BCRoboticsGuis.registry(RegistryBinding.direct());
        TAB_ROBOTICS.addItemProvider(BCRoboticsItems::getRoboticsTabItems);
        BCCore.BUILDCRAFT_TAB.addItemProvider(BCRoboticsItems::getMainTabItems);
        CREATIVE_TABS.register(RegistryBinding.direct());
        FabricDefaultAttributeRegistry.register(BCRoboticsEntities.ROBOT.get(),
            buildcraft.robotics.entity.EntityRobot.createAttributes());
        // Third-party Fabric fluid machines must be able to see the robot's
        // transactional tank, not just its internal fluid carrier AI.
        FabricEntityStorageLookups.FLUIDS.registerForType(
            (robot, side) -> robot.isRemoved() ? null : robot.resourceHandler(),
            BCRoboticsEntities.ROBOT.get());
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_ITEMS, BCRoboticsBlocks.ZONE_PLANNER_TILE.get());
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_ITEMS, BCRoboticsBlocks.REQUESTER_TILE.get());

        MessageManager.registerMessageClass(
            BCModules.ROBOTICS, MessageZoneMapRequest.class,
            MessageZoneMapRequest.HANDLER, MessageZoneMapRequest::toBytes,
            MessageZoneMapRequest::new, BCNetworkSide.SERVER);
        MessageManager.registerClientboundMessageClass(
            BCModules.ROBOTICS, MessageZoneMapResponse.class, null,
            MessageZoneMapResponse::toBytes, MessageZoneMapResponse::new);
        RobotManager.registryProvider = SimpleRobotRegistryProvider.INSTANCE;
        SimpleRobotRegistryProvider.registerGameplayEvents();
        RobotManager.registerDockingStation(DockingStationPipe.class, "pipe");
        registerRoboticsAI();
        BoardRobotPicker.onServerStart();
        BCRoboticsStatements.preInit();
        StatementManager.registerActionProvider(new RobotsActionProvider());
        StatementManager.registerTriggerProvider(new RobotsTriggerProvider());
        StatementManager.registerParameter(StatementParameterRobot::readFromNbt, StatementParameterRobot::readFromBuf);
        StatementManager.registerParameter(StatementParameterMapLocation::readFromNbt, StatementParameterMapLocation::readFromBuf);
        RobotIntegrationRecipe.register();
        TAB_ROBOTICS.setItem(BCRoboticsItems.ROBOT.get());
    }

    private static void registerRoboticsAI() {
        if (RobotManager.getAIRobotName(AIRobotMain.class) != null) return;
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

    public static void bootstrapClientNetwork() {
        MessageManager.setHandler(MessageZoneMapResponse.class, MessageZoneMapResponse.HANDLER, BCNetworkSide.CLIENT);
    }
}
