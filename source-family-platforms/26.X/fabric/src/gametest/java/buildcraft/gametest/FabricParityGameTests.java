package buildcraft.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Native Fabric registration of the 26.3 gameplay parity suite. */
public final class FabricParityGameTests {
    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 40)
    public void abortedNavigationReleasesReservedBlock(GameTestHelper helper) throws Exception {
        buildcraft.gametest.RobotAiRegressionGameTests.abortedNavigationReleasesReservedBlock(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void api2PermissionProviderSeesOwnerAcrossWorldOperationKinds(GameTestHelper helper) throws Exception {
        buildcraft.gametest.PermissionOwnerGameTests.api2PermissionProviderSeesOwnerAcrossWorldOperationKinds(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 200)
    public void apiV2FacadeRulesUsePriorityAndDefensiveCopies(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.apiV2FacadeRulesUsePriorityAndDefensiveCopies(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 200)
    public void apiV2ItemTransferResultUsesDefensiveCopies(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.apiV2ItemTransferResultUsesDefensiveCopies(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void automaticFeCompatibilityConfigActuallyGatesAdapters(GameTestHelper helper) throws Exception {
        buildcraft.gametest.FeMjAdversarialGameTests.automaticFeCompatibilityConfigActuallyGatesAdapters(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void autoWorkbenchContainerNeverExposesPhantomFilters(GameTestHelper helper) throws Exception {
        buildcraft.gametest.CriticalGameplayGameTests.autoWorkbenchContainerNeverExposesPhantomFilters(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 700)
    public void blockedFarmerPreservesWorldAndToolWhileAuthorizedFarmCompletes(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemRobotFarmWarehouseGameTests.blockedFarmerPreservesWorldAndToolWhileAuthorizedFarmCompletes(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 900)
    public void blockedFloodGatePlacementRetainsPipedWaterUntilObstructionRemoved(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemFloodGatePipelineGameTests.blockedFloodGatePlacementRetainsPipedWaterUntilObstructionRemoved(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 1120)
    public void blockedOldResultDelaysNewRecipeWithoutConsumingItsIngredients(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemRecipeMutationGameTests.blockedOldResultDelaysNewRecipeWithoutConsumingItsIngredients(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 40)
    public void blockedStraightMovementFailsWithoutTeleporting(GameTestHelper helper) throws Exception {
        buildcraft.gametest.RobotAiRegressionGameTests.blockedStraightMovementFailsWithoutTeleporting(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void blueprintCopyUsesPlacementActorInsteadOfSourceMachineOwner(GameTestHelper helper) throws Exception {
        buildcraft.gametest.PermissionOwnerGameTests.blueprintCopyUsesPlacementActorInsteadOfSourceMachineOwner(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void blueprintPipeDropsLiveStateAndOwner(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BlueprintSecurityGameTests.blueprintPipeDropsLiveStateAndOwner(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 465)
    public void brokenPipeDoesNotDuplicateCargoAndCanBeRebuilt(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemTransportGameTests.brokenPipeDoesNotDuplicateCargoAndCanBeRebuilt(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 30)
    public void bufferlessConsumersCreateDemandOnMultipleSides(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.PipeForgeEnergyGameTests.bufferlessConsumersCreateDemandOnMultipleSides(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void buildCraftAutomationPlayerUsesPlatformFakePlayer(GameTestHelper helper) throws Exception {
        buildcraft.gametest.PermissionOwnerGameTests.buildCraftAutomationPlayerUsesPlatformFakePlayer(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 450)
    public void builderRequiresActualNeighborSignalWhenModuleInstalled(GameTestHelper helper) throws Exception {
        buildcraft.gametest.RedstoneControlModuleGameTests.builderRequiresActualNeighborSignalWhenModuleInstalled(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void builderResourceReservationRollsBackAfterMidTransactionFailure(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.builderResourceReservationRollsBackAfterMidTransactionFailure(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void bulkItemTransactorInsertionCarriesRemainderAcrossSlots(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.bulkItemTransactorInsertionCarriesRemainderAcrossSlots(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 570)
    public void cancellingHalfBuiltTemplateDoesNotPlacePhantomBlocks(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemBuilderLifecycleGameTests.cancellingHalfBuiltTemplateDoesNotPlacePhantomBlocks(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 100)
    public void carrierPartiallyDeliversTaggedStockAndRetriesWithoutDuplication(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemRobotCargoGameTests.carrierPartiallyDeliversTaggedStockAndRetriesWithoutDuplication(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void chainedAutomaticConvertersPreserveWholeFeAndMjRemainder(GameTestHelper helper) throws Exception {
        buildcraft.gametest.FeMjAdversarialGameTests.chainedAutomaticConvertersPreserveWholeFeAndMjRemainder(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 1050)
    public void changingRecipeDuringDeliveryKeepsBothCommittedOutputs(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemRecipeMutationGameTests.changingRecipeDuringDeliveryKeepsBothCommittedOutputs(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 100)
    public void chargedItemVariantsNeverMergeAcrossRobotDelivery(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemRobotCargoGameTests.chargedItemVariantsNeverMergeAcrossRobotDelivery(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 320)
    public void clayPipePrefersInventoryOverAnotherValidPipe(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftPipeTransportGameTests.clayPipePrefersInventoryOverAnotherValidPipe(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 400)
    public void colouredLineDisconnectsAndRecoversWithoutLosingData(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemTransportGameTests.colouredLineDisconnectsAndRecoversWithoutLosingData(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void combustionEngineConsumesBuildCraftFuelContainersOnDirectUse(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.combustionEngineConsumesBuildCraftFuelContainersOnDirectUse(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void combustionEngineGuiReturnsEmptyBucketForBuildCraftFuel(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.combustionEngineGuiReturnsEmptyBucketForBuildCraftFuel(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void commonWrenchTagToggleControlsExternalTaggedItemsOnly(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.commonWrenchTagToggleControlsExternalTaggedItemsOnly(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 1240)
    public void concurrentProductionFluidTransferAndPipeReplacementStayConservative(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemMixedFactorySoakGameTests.concurrentProductionFluidTransferAndPipeReplacementStayConservative(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 370)
    public void conflictingActiveGateActionsCommitOnlyFinalPhysicalDirection(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemGateAutomationGameTests.conflictingActiveGateActionsCommitOnlyFinalPhysicalDirection(helper);
    }

    //? if >=26.3 {
    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void conflictingLegacyAndModernFieldsAreRejected(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SavedBlockStateCompatibilityGameTests.conflictingLegacyAndModernFieldsAreRejected(helper);
    }
    //?}

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 220)
    public void connectedFluidPipesConserveWaterWhileTicking(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.PipeFluidPowerGameTests.connectedFluidPipesConserveWaterWhileTicking(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 30)
    public void converterMachineStateSurvivesPersistenceRoundTrip(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.converterMachineStateSurvivesPersistenceRoundTrip(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void copiedMachineCannotHydrateStoredResources(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BlueprintSecurityGameTests.copiedMachineCannotHydrateStoredResources(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void createPotionFiltersKeepPotionIdentityWhenCreateIsLoaded(GameTestHelper helper) throws Exception {
        buildcraft.gametest.FluidCompatibilityGameTests.createPotionFiltersKeepPotionIdentityWhenCreateIsLoaded(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 900)
    public void createTankSuppliesBCCEThroughPoweredWoodPipe(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemCreateFluidInteropGameTests.createTankSuppliesBCCEThroughPoweredWoodPipe(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void creativeBuilderCancellationDoesNotMintDisplayRequirements(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.creativeBuilderCancellationDoesNotMintDisplayRequirements(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void creativeEngineLegacyBlockStateRoundTrip(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SavedBlockStateCompatibilityGameTests.creativeEngineLegacyBlockStateRoundTrip(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 100)
    public void defaultMachineStatesDoNotCreateWater(GameTestHelper helper) throws Exception {
        buildcraft.gametest.WaterloggingGameTests.defaultMachineStatesDoNotCreateWater(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void defaultSchematicCannotSubstituteExpensivePlacement(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BlueprintSecurityGameTests.defaultSchematicCannotSubstituteExpensivePlacement(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void defaultSchematicRejectsForgedNeighborUpdateOffsets(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BlueprintSecurityGameTests.defaultSchematicRejectsForgedNeighborUpdateOffsets(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 560)
    public void deniedRobotCannotWithdrawWhileAllowedRobotDeliversChargedStackViaPipes(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemOwnerWarehouseGameTests.deniedRobotCannotWithdrawWhileAllowedRobotDeliversChargedStackViaPipes(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 120)
    public void deniedRobotUnloadDoesNotTouchDestinationOrOtherOwnersCargo(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemOwnerWarehouseGameTests.deniedRobotUnloadDoesNotTouchDestinationOrOtherOwnersCargo(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 20)
    public void diamondBehaviourNbtRoundTripPreservesEveryFilterSlot(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.behaviour.PipeBehaviourGameTests.diamondBehaviourNbtRoundTripPreservesEveryFilterSlot(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 20)
    public void diamondFiltersPreferExactMatchesAndKeepUnfilteredFallback(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.behaviour.PipeBehaviourGameTests.diamondFiltersPreferExactMatchesAndKeepUnfilteredFallback(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 20)
    public void diamondFluidFiltersMatchingSidesAndKeepsUnfilteredFallback(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.PipeFluidPowerGameTests.diamondFluidFiltersMatchingSidesAndKeepsUnfilteredFallback(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 300)
    public void diamondFullPreferredDestinationFallsBackWithoutLoss(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftPipeTransportGameTests.diamondFullPreferredDestinationFallsBackWithoutLoss(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 20)
    public void diamondWeightedSplitUsesFilterStackCountsAndReducesRatio(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.behaviour.PipeBehaviourGameTests.diamondWeightedSplitUsesFilterStackCountsAndReducesRatio(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 100)
    public void doubleChestInsertionStartsAtGlobalSlotZeroFromEitherHalf(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftPipeTransportGameTests.doubleChestInsertionStartsAtGlobalSlotZeroFromEitherHalf(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 430)
    public void enchantedChargedToolSurvivesRobotPipeWarehouseHandoffs(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemRobotPipeDeliveryGameTests.enchantedChargedToolSurvivesRobotPipeWarehouseHandoffs(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 1950)
    public void enchantedQuarryUsesSilkTouchAndKeepsEnchantmentsOnBreak(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemQuarryMiningGameTests.enchantedQuarryUsesSilkTouchAndKeepsEnchantmentsOnBreak(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void energyFluidFamiliesAreFullyRegistered(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftGameTests.energyFluidFamiliesAreFullyRegistered(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 1050)
    public void engineStrokeExtractionPausesAndRestartsWithRealRedstone(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemEngineExtractionGameTests.engineStrokeExtractionPausesAndRestartsWithRealRedstone(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 100)
    public void everyBuildCraftBlockExceptFluidsExposesWaterlogging(GameTestHelper helper) throws Exception {
        buildcraft.gametest.WaterloggingGameTests.everyBuildCraftBlockExceptFluidsExposesWaterlogging(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 200)
    public void everyShapeHandlesSmallAndNonCubicTemplates(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.everyShapeHandlesSmallAndNonCubicTemplates(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 45)
    public void everySupportedMachineReturnsExactlyOneInstalledModule(GameTestHelper helper) throws Exception {
        buildcraft.gametest.RedstoneControlModuleGameTests.everySupportedMachineReturnsExactlyOneInstalledModule(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 880)
    public void extractedWaterWaitsAtPoweredFloodGateThenPlacesExactlyOneSource(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemFloodGatePipelineGameTests.extractedWaterWaitsAtPoweredFloodGateThenPlacesExactlyOneSource(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void failedGridClearNeverOverwritesTransientItems(GameTestHelper helper) throws Exception {
        buildcraft.gametest.WorkbenchRollbackGameTests.failedGridClearNeverOverwritesTransientItems(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 680)
    public void farmerHoesPlantsHarvestsAndObsidianMovesDropsIntoWarehouse(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemRobotFarmWarehouseGameTests.farmerHoesPlantsHarvestsAndObsidianMovesDropsIntoWarehouse(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 40)
    public void farmerPermissionDenialLeavesBlockAndToolIntact(GameTestHelper helper) throws Exception {
        buildcraft.gametest.RobotAiRegressionGameTests.farmerPermissionDenialLeavesBlockAndToolIntact(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 40)
    public void farmerTillsOneBlockWithOneToolUse(GameTestHelper helper) throws Exception {
        buildcraft.gametest.RobotAiRegressionGameTests.farmerTillsOneBlockWithOneToolUse(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 20)
    public void feLimiterModesClampPersistAndDisableTransfer(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.PipeForgeEnergyGameTests.feLimiterModesClampPersistAndDisableTransfer(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 30)
    public void feMjConverterRoundTripConservesEnergy(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.feMjConverterRoundTripConservesEnergy(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void feMjConverterSimulationDoesNotMutateMachineBuffers(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.feMjConverterSimulationDoesNotMutateMachineBuffers(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 20)
    public void feOverflowRequestsAreSaturatedInsteadOfWrapping(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.PipeForgeEnergyGameTests.feOverflowRequestsAreSaturatedInsteadOfWrapping(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 20)
    public void feReceiverRejectsUndemandedEnergyAndPersistsBoundedBuffer(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.PipeForgeEnergyGameTests.feReceiverRejectsUndemandedEnergyAndPersistsBoundedBuffer(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void fillerTreatsReplaceableBlocksAsPlacementTargets(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.fillerTreatsReplaceableBlocksAsPlacementTargets(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 100)
    public void floodGateClosedOutputDoesNotConsumeWater(GameTestHelper helper) throws Exception {
        buildcraft.gametest.FloodGateGameTests.floodGateClosedOutputDoesNotConsumeWater(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 100)
    public void floodGateDoesNotConsumeWaterWhenOutputIsBlocked(GameTestHelper helper) throws Exception {
        buildcraft.gametest.FloodGateGameTests.floodGateDoesNotConsumeWaterWhenOutputIsBlocked(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 120)
    public void floodGatePausesWhenPoweredAndResumesAfterUnpowering(GameTestHelper helper) throws Exception {
        buildcraft.gametest.FloodGateGameTests.floodGatePausesWhenPoweredAndResumesAfterUnpowering(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 100)
    public void floodGatePlacesBucketWaterAndConsumesOneBucket(GameTestHelper helper) throws Exception {
        buildcraft.gametest.FloodGateGameTests.floodGatePlacesBucketWaterAndConsumesOneBucket(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 100)
    public void floodGatePlacesWaterFromFluidHandlerAndConsumesOneBucket(GameTestHelper helper) throws Exception {
        buildcraft.gametest.FloodGateGameTests.floodGatePlacesWaterFromFluidHandlerAndConsumesOneBucket(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 20)
    public void fluidCapabilityReportsTankContentsCapacityAndValidity(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.PipeFluidPowerGameTests.fluidCapabilityReportsTankContentsCapacityAndValidity(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 140)
    public void fluidDoesNotEnterSectionTowardDisconnectedColouredPipe(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.PipeFluidPowerGameTests.fluidDoesNotEnterSectionTowardDisconnectedColouredPipe(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 20)
    public void fluidForceExtractionHonoursMinMaxAndSimulation(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.PipeFluidPowerGameTests.fluidForceExtractionHonoursMinMaxAndSimulation(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 20)
    public void fluidForceInsertionSimulationMixingAndNbtRoundTrip(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.PipeFluidPowerGameTests.fluidForceInsertionSimulationMixingAndNbtRoundTrip(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void petroleumBucketsExposeNativeFluidHandlers(GameTestHelper helper) throws Exception {
        buildcraft.gametest.FluidCompatibilityGameTests.petroleumBucketsExposeNativeFluidHandlers(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void distillerAcceptsOilFromRegisteredBucket(GameTestHelper helper) throws Exception {
        buildcraft.gametest.FluidCompatibilityGameTests.distillerAcceptsOilFromRegisteredBucket(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void classicOilAcceptsAllTemperaturesAndEjectsObsoleteInput(GameTestHelper helper) throws Exception {
        buildcraft.gametest.FluidCompatibilityGameTests.classicOilAcceptsAllTemperaturesAndEjectsObsoleteInput(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void fluidItemServiceResolvesStandardContainersAndRejectsNonFluidItems(GameTestHelper helper) throws Exception {
        buildcraft.gametest.FluidCompatibilityGameTests.fluidItemServiceResolvesStandardContainersAndRejectsNonFluidItems(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 40)
    public void fluidNeighborPermissionIsCheckedSeparatelyFromDock(GameTestHelper helper) throws Exception {
        buildcraft.gametest.RobotAiRegressionGameTests.fluidNeighborPermissionIsCheckedSeparatelyFromDock(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 70)
    public void fluidSmootherSynchronizesVariantChangeWithoutAmountChange(GameTestHelper helper) throws Exception {
        buildcraft.gametest.FluidCompatibilityGameTests.fluidSmootherSynchronizesVariantChangeWithoutAmountChange(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 40)
    public void fluidStationAccessHonorsRobotOwner(GameTestHelper helper) throws Exception {
        buildcraft.gametest.RobotAiRegressionGameTests.fluidStationAccessHonorsRobotOwner(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void fluidVariantRoundTripPreservesNeoForgeComponents(GameTestHelper helper) throws Exception {
        buildcraft.gametest.FluidCompatibilityGameTests.fluidVariantRoundTripPreservesNeoForgeComponents(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void forgedFluidTypeCannotPlaceOrdinaryBlocks(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BlueprintSecurityGameTests.forgedFluidTypeCannotPlaceOrdinaryBlocks(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 100)
    public void frameAndTankRetainWaterWithoutLosingBlockIdentity(GameTestHelper helper) throws Exception {
        buildcraft.gametest.WaterloggingGameTests.frameAndTankRetainWaterWithoutLosingBlockIdentity(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 20)
    public void fullForceExtractionThenRefillDoesNotGhostJamPipe(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.PipeFluidPowerGameTests.fullForceExtractionThenRefillDoesNotGhostJamPipe(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 380)
    public void fullItemDataSurvivesThreeRealPipeHandoffs(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemTransportGameTests.fullItemDataSurvivesThreeRealPipeHandoffs(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 260)
    public void goldLineRetriesTransientPipeHandoffWithoutDropping(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftPipeTransportGameTests.goldLineRetriesTransientPipeHandoffWithoutDropping(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void guideBookItemAndRecipeAreRegistered(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftGameTests.guideBookItemAndRecipeAreRegistered(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 90)
    public void guideNetworkFiltersInvalidEntriesAndRetainsValidNeighbors(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemGuideRecipeNetworkGameTests.guideNetworkFiltersInvalidEntriesAndRetainsValidNeighbors(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 90)
    public void guideNetworkSkipsCorruptFramedRecipeAndRecoversOnFreshIndex(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemGuideRecipeNetworkGameTests.guideNetworkSkipsCorruptFramedRecipeAndRecoversOnFreshIndex(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 200)
    public void hollowHemispheresMatchTheCorrespondingHalfOfAFullSphere(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.hollowHemispheresMatchTheCorrespondingHalfOfAFullSphere(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 810)
    public void hopperRobotAndPoweredPipeShareChestThroughLiveInventoryMutation(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemSharedInventoryGameTests.hopperRobotAndPoweredPipeShareChestThroughLiveInventoryMutation(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 60)
    public void idleBuilderAndQuarryMachineTicksRemainPowerNeutral(GameTestHelper helper) throws Exception {
        buildcraft.gametest.PerformanceSmokeGameTests.idleBuilderAndQuarryMachineTicksRemainPowerNeutral(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 390)
    public void independentParallelLinesPreserveAllCargoState(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemTransportGameTests.independentParallelLinesPreserveAllCargoState(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 680)
    public void inFlightCargoRestoresIntoRealPipeAndReachesWarehouseExactlyOnce(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemCargoRecoveryGameTests.inFlightCargoRestoresIntoRealPipeAndReachesWarehouseExactlyOnce(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 850)
    public void invalidBlueprintDoesNotDebitMaterialsAndValidReplacementCompletesNormally(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemRecipeMutationGameTests.invalidBlueprintDoesNotDebitMaterialsAndValidReplacementCompletesNormally(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 90)
    public void invalidGuideFrameLengthRetainsValidPrefixAndDoesNotContaminateReconnect(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemGuideRecipeBatchGameTests.invalidGuideFrameLengthRetainsValidPrefixAndDoesNotContaminateReconnect(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 20)
    public void ironPipeEnforcesOutputBounceAndFluidInputDirection(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.behaviour.PipeBehaviourGameTests.ironPipeEnforcesOutputBounceAndFluidInputDirection(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 810)
    public void isolatedWaterAndLavaNetworksResumeIndependently(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemFluidNetworksGameTests.isolatedWaterAndLavaNetworksResumeIndependently(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 180)
    public void itemInjectionSimulationAndAcceptedCountAreConsistent(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftPipeTransportGameTests.itemInjectionSimulationAndAcceptedCountAreConsistent(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void itemTransactorInsertExtractAndSimulation(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.itemTransactorInsertExtractAndSimulation(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void itemTransactorLimitedInventoryReturnsOverflow(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.itemTransactorLimitedInventoryReturnsOverflow(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void itemTransactorNbtRoundTrip(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.itemTransactorNbtRoundTrip(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void itemTransferUsesExecutedSourceAmountWithoutDuplication(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.itemTransferUsesExecutedSourceAmountWithoutDuplication(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void jsonInlineCopiesAreIndependent(GameTestHelper helper) throws Exception {
        buildcraft.gametest.CoreIntegrityGameTests.jsonInlineCopiesAreIndependent(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 20)
    public void lapisAndDaizuliKeepTheBc8ColourContract(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.behaviour.PipeBehaviourGameTests.lapisAndDaizuliKeepTheBc8ColourContract(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 60)
    public void largeGateNetworkStateRoundTripsStayLinear(GameTestHelper helper) throws Exception {
        buildcraft.gametest.PerformanceSmokeGameTests.largeGateNetworkStateRoundTripsStayLinear(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 60)
    public void largeZoneAndChunkStyleRoundTripStaysBounded(GameTestHelper helper) throws Exception {
        buildcraft.gametest.PerformanceSmokeGameTests.largeZoneAndChunkStyleRoundTripStaysBounded(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void legacyFluidStateRoundTrip(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SavedBlockStateCompatibilityGameTests.legacyFluidStateRoundTrip(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void legacyGlassFacadeStateRoundTrip(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SavedBlockStateCompatibilityGameTests.legacyGlassFacadeStateRoundTrip(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 700)
    public void liquidObstructionDoesNotStarveIndependentPlacement(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemBuilderLifecycleGameTests.liquidObstructionDoesNotStarveIndependentPlacement(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 1570)
    public void liveFeToMjToFeNetworkRecoversFromBrokenPowerPipe(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemEnergyGridGameTests.liveFeToMjToFeNetworkRecoversFromBrokenPowerPipe(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 680)
    public void liveFluidTransportRecoversAfterDestinationBackpressure(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.PipeFluidPowerGameTests.liveFluidTransportRecoversAfterDestinationBackpressure(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 1900)
    public void liveQuarryMinesInsideAreaExportsDropsAndSkipsUnbreakables(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemQuarryMiningGameTests.liveQuarryMinesInsideAreaExportsDropsAndSkipsUnbreakables(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 2400)
    public void liveQuarryRevisitsReplacedStoneAndKeepsExportLedgerConservative(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemQuarryMiningGameTests.liveQuarryRevisitsReplacedStoneAndKeepsExportLedgerConservative(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void machineOwnerIdentitySurvivesPersistenceRoundTrip(GameTestHelper helper) throws Exception {
        buildcraft.gametest.PermissionOwnerGameTests.machineOwnerIdentitySurvivesPersistenceRoundTrip(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void malformedFeAcceptanceCannotCreateMjOrBreakMetadata(GameTestHelper helper) throws Exception {
        buildcraft.gametest.FeMjAdversarialGameTests.malformedFeAcceptanceCannotCreateMjOrBreakMetadata(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void manualInteractionIsNotOwnerLocked(GameTestHelper helper) throws Exception {
        buildcraft.gametest.PermissionOwnerGameTests.manualInteractionIsNotOwnerLocked(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 60)
    public void manyIdleRobotsKeepChargingStateStable(GameTestHelper helper) throws Exception {
        buildcraft.gametest.PerformanceSmokeGameTests.manyIdleRobotsKeepChargingStateStable(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 20)
    public void mergeRequiresEquivalentCargoPathAndCloseArrivalTime(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.TravellingItemGameTests.mergeRequiresEquivalentCargoPathAndCloseArrivalTime(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 40)
    public void miningBreaksBlockWithEquippedTool(GameTestHelper helper) throws Exception {
        buildcraft.gametest.RobotAiRegressionGameTests.miningBreaksBlockWithEquippedTool(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 40)
    public void miningPermissionDenialLeavesWorldIntact(GameTestHelper helper) throws Exception {
        buildcraft.gametest.RobotAiRegressionGameTests.miningPermissionDenialLeavesWorldIntact(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void mixedFluidColumnsDoNotMergeThroughEmptyBridgeTank(GameTestHelper helper) throws Exception {
        buildcraft.gametest.GameplayIntegrityGameTests.mixedFluidColumnsDoNotMergeThroughEmptyBridgeTank(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 160)
    public void multiPacketGuideIndexSkipsBadMiddleDisplayAndAcceptsFreshCompleteIndex(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemGuideRecipeBatchGameTests.multiPacketGuideIndexSkipsBadMiddleDisplayAndAcceptsFreshCompleteIndex(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 20)
    public void nbtRoundTripPreservesCargoColourMotionAndTriedSides(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.TravellingItemGameTests.nbtRoundTripPreservesCargoColourMotionAndTriedSides(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 430)
    public void nearlyFullDestinationPreservesPartialRemainder(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemTransportGameTests.nearlyFullDestinationPreservesPartialRemainder(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 760)
    public void obsidianRecoversAfterLiveDownstreamPipeReplacement(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemObsidianPickupGameTests.obsidianRecoversAfterLiveDownstreamPipeReplacement(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 750)
    public void obsidianSucksTwoWorldEntitiesWithoutMixingTheirFullItemState(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemObsidianPickupGameTests.obsidianSucksTwoWorldEntitiesWithoutMixingTheirFullItemState(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 120)
    public void occupiedBedHeadNeverGetsOverwritten(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuilderTwoPartPlacementGameTests.occupiedBedHeadNeverGetsOverwritten(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 100)
    public void oilSourceDoesNotDestroyWater(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftGameTests.oilSourceDoesNotDestroyWater(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 310)
    public void openEndedTransportEjectsOneIntactCargoEntity(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemTransportGameTests.openEndedTransportEjectsOneIntactCargoEntity(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void oversizedBlueprintAllocationFailsFast(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BlueprintSecurityGameTests.oversizedBlueprintAllocationFailsFast(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void packetBooleansPackAcrossPrimitiveWrites(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.packetBooleansPackAcrossPrimitiveWrites(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void packetEnumsUseCompactBitEncoding(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.packetEnumsUseCompactBitEncoding(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void packetFixedBitsRoundTrip(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.packetFixedBitsRoundTrip(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void packetInvalidBitLengthsAreRejected(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.packetInvalidBitLengthsAreRejected(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 770)
    public void parallelAutocraftersPreserveRecipeOutputAndIndependentPowerControl(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemParallelProductionGameTests.parallelAutocraftersPreserveRecipeOutputAndIndependentPowerControl(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 20)
    public void passiveProviderExtractionUsesDryRunAndNeverOverfillsSection(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.PipeFluidPowerGameTests.passiveProviderExtractionUsesDryRunAndNeverOverfillsSection(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 20)
    public void pipeColourCompatibilityIsSymmetric(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.behaviour.PipeBehaviourGameTests.pipeColourCompatibilityIsSymmetric(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 1000)
    public void pipedWaterFillsStackedTanksThenCompactsAfterPhysicalDrain(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemFluidNetworksGameTests.pipedWaterFillsStackedTanksThenCompactsAfterPhysicalDrain(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void pipeHolderWaterloggingPreservesTheWaterFluidState(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.pipeHolderWaterloggingPreservesTheWaterFluidState(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void platformProtectionHooksReceiveMachineOwnerForBreakAndPlace(GameTestHelper helper) throws Exception {
        buildcraft.gametest.PermissionOwnerGameTests.platformProtectionHooksReceiveMachineOwnerForBreakAndPlace(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 30)
    public void powerDistributionAbovePipeLimitDoesNotDivideByZero(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.PipeFluidPowerGameTests.powerDistributionAbovePipeLimitDoesNotDivideByZero(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 1110)
    public void poweredDiamondNetworkSwitchesFromFullToAvailableDestination(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemEngineExtractionGameTests.poweredDiamondNetworkSwitchesFromFullToAvailableDestination(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 1470)
    public void poweredGateSwitchesLiveIronPipeRoutesWhenRedstoneChanges(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemGateAutomationGameTests.poweredGateSwitchesLiveIronPipeRoutesWhenRedstoneChanges(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 380)
    public void poweredWoodFluidPipeMovesWaterIntoTankWithoutLoss(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.PipeFluidPowerGameTests.poweredWoodFluidPipeMovesWaterIntoTankWithoutLoss(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 20)
    public void powerLimiterModesClampPersistAndDisableTransfer(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.PipeFluidPowerGameTests.powerLimiterModesClampPersistAndDisableTransfer(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 20)
    public void powerPipeBuffersSurviveNbtRoundTrip(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.PipeFluidPowerGameTests.powerPipeBuffersSurviveNbtRoundTrip(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 30)
    public void powerReceiverSimulationAndDistributionAreConservative(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.PipeFluidPowerGameTests.powerReceiverSimulationAndDistributionAreConservative(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 40)
    public void powerTraversesNonReceiverPipeAndReachesSink(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.PipeFluidPowerGameTests.powerTraversesNonReceiverPipeAndReachesSink(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 40)
    public void pumpContinuesOilWhenWaterReplacesIntake(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.pumpContinuesOilWhenWaterReplacesIntake(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 40)
    public void pumpDoesNotScanThroughStone(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.pumpDoesNotScanThroughStone(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 3100)
    public void pumpOnQuarryDrainsBlockingLavaAndResumesMining(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemQuarryPumpGameTests.pumpOnQuarryDrainsBlockingLavaAndResumesMining(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 40)
    public void pumpPreservesDetectedInfiniteWaterSource(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.pumpPreservesDetectedInfiniteWaterSource(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 40)
    public void pumpPrioritizesOilButDoesNotMixLiquids(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.pumpPrioritizesOilButDoesNotMixLiquids(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 40)
    public void pumpScansThroughWaterToOilBelow(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.pumpScansThroughWaterToOilBelow(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 40)
    public void pumpSelectsWaterOnlyAfterAllNonWaterCandidates(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.pumpSelectsWaterOnlyAfterAllNonWaterCandidates(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void quarryCancelledTaskRefundsExactWithdrawnPower(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.quarryCancelledTaskRefundsExactWithdrawnPower(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void quarryFluidTraversalMatchesBc8ViscosityRules(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.quarryFluidTraversalMatchesBc8ViscosityRules(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void quarryFramePlannerReplacesFluidsAndExcavatesSolidObstacles(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.quarryFramePlannerReplacesFluidsAndExcavatesSolidObstacles(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 1160)
    public void realPumpFeedsDisabledFloodGateThenResumesWithOneWaterSource(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemPumpToFloodGateGameTests.realPumpFeedsDisabledFloodGateThenResumesWithOneWaterSource(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 710)
    public void redstoneGateCyclesEmzuliPresetsAndRemovalExpiresExtraction(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemEmzuliAutomationGameTests.redstoneGateCyclesEmzuliPresetsAndRemovalExpiresExtraction(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void relatedGateActionVariantsTargetOneSetting(GameTestHelper helper) throws Exception {
        buildcraft.gametest.CoreIntegrityGameTests.relatedGateActionVariantsTargetOneSetting(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 180)
    public void replacingEndRestoresChainWithoutTouchingMiddleOrStart(GameTestHelper helper) throws Exception {
        buildcraft.gametest.HeatExchangerReassemblyGameTests.replacingEndRestoresChainWithoutTouchingMiddleOrStart(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 550)
    public void replacingGateDuringActiveTransitClearsOldActionAndPreservesCargo(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemGateAutomationGameTests.replacingGateDuringActiveTransitClearsOldActionAndPreservesCargo(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 180)
    public void replacingMiddleRestoresChainWithoutTouchingItsEnds(GameTestHelper helper) throws Exception {
        buildcraft.gametest.HeatExchangerReassemblyGameTests.replacingMiddleRestoresChainWithoutTouchingItsEnds(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 180)
    public void replacingStartRestoresChainWithoutTouchingMiddleOrEnd(GameTestHelper helper) throws Exception {
        buildcraft.gametest.HeatExchangerReassemblyGameTests.replacingStartRestoresChainWithoutTouchingMiddleOrEnd(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 730)
    public void requesterChangedWhileParcelTravelsRejectsStaleOrderWithoutLoss(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemRequesterPipeGameTests.requesterChangedWhileParcelTravelsRejectsStaleOrderWithoutLoss(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 780)
    public void requesterRejectsSameItemWithWrongCustomChargeThenAcceptsMatchingVariant(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemRequesterPipeGameTests.requesterRejectsSameItemWithWrongCustomChargeThenAcceptsMatchingVariant(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 740)
    public void requesterRobotPipelineCompletesStaggeredTaggedOrder(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemRequesterPipeGameTests.requesterRobotPipelineCompletesStaggeredTaggedOrder(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 650)
    public void requesterSeparateOrdersDoNotMergeDistinctRobotParcels(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemRequesterPipeGameTests.requesterSeparateOrdersDoNotMergeDistinctRobotParcels(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void robotDismantleRequiresOwnership(GameTestHelper helper) throws Exception {
        buildcraft.gametest.PermissionOwnerGameTests.robotDismantleRequiresOwnership(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void robotOwnerIdentitySurvivesPersistenceAndFeedsApi2Actor(GameTestHelper helper) throws Exception {
        buildcraft.gametest.PermissionOwnerGameTests.robotOwnerIdentitySurvivesPersistenceAndFeedsApi2Actor(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 570)
    public void robotRetriesBusyPipeWithDistinctChargedStackVariants(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemRobotPipeDeliveryGameTests.robotRetriesBusyPipeWithDistinctChargedStackVariants(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 100)
    public void robotTransfersDamagedEnergyBearingWeaponAcrossStations(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemRobotCargoGameTests.robotTransfersDamagedEnergyBearingWeaponAcrossStations(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 380)
    public void sandstoneDoesNotLeakCargoIntoAdjacentInventory(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemTransportGameTests.sandstoneDoesNotLeakCargoIntoAdjacentInventory(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 1100)
    public void saturatedCreateTankUnblocksWithoutFluidLoss(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemCreateFluidInteropGameTests.saturatedCreateTankUnblocksWithoutFluidLoss(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 430)
    public void savedRobotCargoPreservesFullItemStateThroughPipeDelivery(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemCargoRecoveryGameTests.savedRobotCargoPreservesFullItemStateThroughPipeDelivery(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 960)
    public void savedTankRestoredIntoWorldStillAcceptsFuturePipedFluidWithoutDuplication(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemTankRestoreGameTests.savedTankRestoredIntoWorldStillAcceptsFuturePipedFluidWithoutDuplication(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void schematicLeavesBecomePersistentAfterSerializationRoundTrip(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.schematicLeavesBecomePersistentAfterSerializationRoundTrip(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void schematicPlacementHonoursVanillaCanSurvive(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.schematicPlacementHonoursVanillaCanSurvive(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 180)
    public void shorteningFourSectionsRetainsValidThreeSectionStructure(GameTestHelper helper) throws Exception {
        buildcraft.gametest.HeatExchangerReassemblyGameTests.shorteningFourSectionsRetainsValidThreeSectionStructure(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void snapshotBuilderIgnoresMissingBuildingInfo(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.snapshotBuilderIgnoresMissingBuildingInfo(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 490)
    public void sortedNetworkRecoversAfterBackpressure(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemTransportGameTests.sortedNetworkRecoversAfterBackpressure(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 850)
    public void starvedTemplateBuilderResumesFromRealWoodenPipeMaterialDelivery(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemBuilderLifecycleGameTests.starvedTemplateBuilderResumesFromRealWoodenPipeMaterialDelivery(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 40)
    public void stationsOnTheSameBlockReserveIndependently(GameTestHelper helper) throws Exception {
        buildcraft.gametest.RobotAiRegressionGameTests.stationsOnTheSameBlockReserveIndependently(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 360)
    public void straightItemLineConservesTaggedCargo(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftPipeTransportGameTests.straightItemLineConservesTaggedCargo(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 40)
    public void straightMovementClampsTheFinalStep(GameTestHelper helper) throws Exception {
        buildcraft.gametest.RobotAiRegressionGameTests.straightMovementClampsTheFinalStep(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void stripesBlockPlacementReportsRejectedUseOnAsFailure(GameTestHelper helper) throws Exception {
        buildcraft.gametest.GameplayIntegrityGameTests.stripesBlockPlacementReportsRejectedUseOnAsFailure(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void stripesEntityInteractCommitsConsumingServerResult(GameTestHelper helper) throws Exception {
        buildcraft.gametest.CriticalGameplayGameTests.stripesEntityInteractCommitsConsumingServerResult(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 40)
    public void stripesPassDoesNotCommitAHandlerWorkingStack(GameTestHelper helper) throws Exception {
        buildcraft.gametest.RobotAiRegressionGameTests.stripesPassDoesNotCommitAHandlerWorkingStack(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 40)
    public void successfulNavigationHandsReservationToBoard(GameTestHelper helper) throws Exception {
        buildcraft.gametest.RobotAiRegressionGameTests.successfulNavigationHandsReservationToBoard(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 40)
    public void sugarCaneAdapterHarvestsOnlyGrowthAboveTheBase(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.sugarCaneAdapterHarvestsOnlyGrowthAboveTheBase(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void supportedMjPerFeRatiosRemainConservative(GameTestHelper helper) throws Exception {
        buildcraft.gametest.FeMjAdversarialGameTests.supportedMjPerFeRatiosRemainConservative(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void tankBlockEntityAcceptsAndSimulatesFluid(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftGameTests.tankBlockEntityAcceptsAndSimulatesFluid(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void tankColumnBalanceCompactsMultipleSourcesInOnePass(GameTestHelper helper) throws Exception {
        buildcraft.gametest.FluidCompatibilityGameTests.tankColumnBalanceCompactsMultipleSourcesInOnePass(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void tankColumnInitialServerTickCompactsLoadedContents(GameTestHelper helper) throws Exception {
        buildcraft.gametest.FluidCompatibilityGameTests.tankColumnInitialServerTickCompactsLoadedContents(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 170)
    public void tankColumnTransitionsFromIsolatedVariantsToMergedWater(GameTestHelper helper) throws Exception {
        buildcraft.gametest.GameplayIntegrityGameTests.tankColumnTransitionsFromIsolatedVariantsToMergedWater(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void tankManagerFillSpillsAndDrainCombines(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.tankManagerFillSpillsAndDrainCombines(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void tankManagerForgeViewReportsContentsAndCapacity(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.tankManagerForgeViewReportsContentsAndCapacity(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void tankManagerSimulationAndNbtRoundTrip(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.tankManagerSimulationAndNbtRoundTrip(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void tankMenuSynchronizesWholeColumnState(GameTestHelper helper) throws Exception {
        buildcraft.gametest.FluidCompatibilityGameTests.tankMenuSynchronizesWholeColumnState(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 60)
    public void thousandPipeStateRoundTripsStayIdleAndBounded(GameTestHelper helper) throws Exception {
        buildcraft.gametest.PerformanceSmokeGameTests.thousandPipeStateRoundTripsStayIdleAndBounded(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 480)
    public void timerDrivenGateReorientsLiveItemNetworkWithoutManualGateTicks(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemGateAutomationGameTests.timerDrivenGateReorientsLiveItemNetworkWithoutManualGateTicks(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void toolMatcherGroupsToolsByAction(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuildCraftLogicGameTests.toolMatcherGroupsToolsByAction(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 1120)
    public void twoPipedFluidVariantsStayIsolatedAfterTankColumnBridgeInserted(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemFluidReconfigurationGameTests.twoPipedFluidVariantsStayIsolatedAfterTankColumnBridgeInserted(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 1470)
    public void twoRealMjDynamoConsumersSharePowerAndSurvivorContinuesWhenPeerRemoved(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemEnergyGridGameTests.twoRealMjDynamoConsumersSharePowerAndSurvivorContinuesWhenPeerRemoved(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 120)
    public void twoWhiteBedsConsumeExactlyTwoItemsAndPlaceFourBlocks(GameTestHelper helper) throws Exception {
        buildcraft.gametest.BuilderTwoPartPlacementGameTests.twoWhiteBedsConsumeExactlyTwoItemsAndPlaceFourBlocks(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 850)
    public void twoWoodenExtractorsCompeteForOneTaggedWarehouseWithoutDuplication(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemSharedInventoryGameTests.twoWoodenExtractorsCompeteForOneTaggedWarehouseWithoutDuplication(helper);
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void unavailableBlueprintElementsRoundTripLosslessly(GameTestHelper helper) throws Exception {
        buildcraft.gametest.CoreIntegrityGameTests.unavailableBlueprintElementsRoundTripLosslessly(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 760)
    public void unbreakableTemplateCellDoesNotStarveIndependentPlacement(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemBuilderLifecycleGameTests.unbreakableTemplateCellDoesNotStarveIndependentPlacement(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 40)
    public void unloadedRobotRetainsAndReclaimsItsWorkLease(GameTestHelper helper) throws Exception {
        buildcraft.gametest.RobotAiRegressionGameTests.unloadedRobotRetainsAndReclaimsItsWorkLease(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 2540)
    public void unpoweredQuarryPausesThenMinesAfterRefuelWithoutDuplicateDrops(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemQuarryMiningGameTests.unpoweredQuarryPausesThenMinesAfterRefuelWithoutDuplicateDrops(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 430)
    public void voidBranchDisposesOnlyFilteredItemAndPreservesOtherCargo(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemTransportGameTests.voidBranchDisposesOnlyFilteredItemAndPreservesOtherCargo(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 1020)
    public void waterloggedColouredPipeNetworkRecoversWithoutInventingWater(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemFluidReconfigurationGameTests.waterloggedColouredPipeNetworkRecoversWithoutInventingWater(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 20)
    public void woodenFeExtractionUsesSimulationAndNeverOverfills(GameTestHelper helper) throws Exception {
        buildcraft.transport.pipe.flow.PipeForgeEnergyGameTests.woodenFeExtractionUsesSimulationAndNeverOverfills(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 570)
    public void workbenchCraftsAndPoweredWoodPipeExportsIntoRealWarehouse(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemAutocraftingGameTests.workbenchCraftsAndPoweredWoodPipeExportsIntoRealWarehouse(helper);
    }

    @GameTest(structure = "buildcraftlib:empty7x3x7", maxTicks = 510)
    public void worldTemplateBuilderStarvesThenResumesWithoutFreeBlocks(GameTestHelper helper) throws Exception {
        buildcraft.gametest.SystemBuilderLifecycleGameTests.worldTemplateBuilderStarvesThenResumesWithoutFreeBlocks(helper);
    }

}
