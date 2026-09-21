package buildcraft.transport.pipe.flow;

import buildcraft.api.v2.energy.MjAmount;
import buildcraft.lib.internal.mj.MjCapabilities;

import java.math.BigInteger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.mj.IMjConnector;
import buildcraft.lib.internal.mj.IMjPassiveProvider;
import buildcraft.lib.internal.mj.IMjReceiver;
import buildcraft.transport.internal.pipe.IPipe.ConnectedType;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pipe.PipeEventFluid;
import buildcraft.transport.internal.pipe.PipeEventPower;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.tile.TileTank;
import buildcraft.gametest.PipeGameTestSupport;
import buildcraft.gametest.GameTestCompat;
import buildcraft.gametest.PipeGameTestSupport.TestPipe;
import buildcraft.lib.BCLib;
import buildcraft.lib.fluid.FluidCompatRegistry;
import buildcraft.lib.fluid.Tank;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.FluidStackUtil;
import buildcraft.transport.BCTransportConfig;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.pipe.behaviour.PipeBehaviourDiamond;
import buildcraft.transport.pipe.behaviour.PipeBehaviourDiamondFluid;
import buildcraft.transport.pipe.behaviour.PipeBehaviourLimiter;
import buildcraft.transport.pipe.behaviour.PipeBehaviourWood;
import buildcraft.transport.tile.TilePipeHolder;

@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class PipeFluidPowerGameTests {
    private PipeFluidPowerGameTests() {
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void fluidForceInsertionSimulationMixingAndNbtRoundTrip(GameTestHelper helper) {
        TestPipe pipe = new TestPipe(helper.getLevel(), BCTransportPipes.cobbleFluid);
        PipeFlowFluids flow = new PipeFlowFluids(pipe);
        pipe.setFlow(flow);

        int rate = PipeApi.getFluidTransferInfo(BCTransportPipes.cobbleFluid).transferPerTick;
        FluidStack water = new FluidStack(net.minecraft.world.level.material.Fluids.WATER, rate + 25);

        int simulated = flow.insertFluidsForce(water, Direction.WEST, FluidAction.SIMULATE);
        require(helper, simulated == rate, "fluid simulation ignored the per-tick transfer limit");
        require(helper, totalFluid(flow) == 0, "fluid simulation mutated the pipe");

        int inserted = flow.insertFluidsForce(water, Direction.WEST, FluidAction.EXECUTE);
        require(helper, inserted == simulated, "executed fluid insertion disagreed with simulation");
        require(helper, totalFluid(flow) == rate, "executed insertion stored the wrong amount");

        int mixed = flow.insertFluidsForce(
            new FluidStack(net.minecraft.world.level.material.Fluids.LAVA, rate),
            Direction.EAST,
            FluidAction.EXECUTE
        );
        require(helper, mixed == 0, "pipe accepted lava while it already contained water");
        require(helper, totalFluid(flow) == rate, "rejected mixed fluid changed the stored amount");

        int delay = Math.max(1, (int) Math.ceil(
            PipeApi.getFluidTransferInfo(BCTransportPipes.cobbleFluid).transferDelayMultiplier
        ));
        int phaseTicks = delay > 1 ? Math.min(3, delay - 1) : 0;
        for (int i = 0; i < phaseTicks; i++) {
            flow.onTick();
        }

        CompoundTag nbt = flow.writeToNbt();
        String centreKey = "tank[" + EnumPipePart.CENTER.getIndex() + "]";
        CompoundTag centreNbt = nbt.getCompound(centreKey);
        require(helper, centreNbt.contains("currentTime"), "fluid delay phase was not written to NBT");
        int savedPhase = GameTestCompat.readInt(centreNbt, "currentTime");
        require(helper, savedPhase == phaseTicks % delay, "fluid delay phase advanced unexpectedly before save");
        TestPipe restoredPipe = new TestPipe(helper.getLevel(), BCTransportPipes.cobbleFluid);
        PipeFlowFluids restored = new PipeFlowFluids(restoredPipe, nbt);
        restoredPipe.setFlow(restored);

        require(helper, totalFluid(restored) == rate, "fluid amount changed after NBT round-trip");
        require(helper, containsFluid(restored, net.minecraft.world.level.material.Fluids.WATER),
            "fluid identity changed after NBT round-trip");
        CompoundTag restoredNbt = restored.writeToNbt();
        require(helper, GameTestCompat.readInt(restoredNbt.getCompound(centreKey), "currentTime") == savedPhase,
            "fluid delay phase changed after NBT round-trip");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void fluidCapabilityReportsTankContentsCapacityAndValidity(GameTestHelper helper) {
        TestPipe pipe = new TestPipe(helper.getLevel(), BCTransportPipes.cobbleFluid)
            .connect(Direction.EAST, ConnectedType.PIPE);
        PipeFlowFluids flow = new PipeFlowFluids(pipe);
        pipe.setFlow(flow);

        IFluidHandler handler = flow.getCapability(CapUtil.CAP_FLUIDS, Direction.EAST);
        require(helper, handler != null, "fluid pipe did not expose its fluid capability");
        require(helper, handler.getTanks() == 1, "fluid pipe capability reported no tanks");
        require(helper, handler.getTankCapacity(0) == flow.capacity, "fluid pipe reported the wrong tank capacity");
        require(helper, handler.getFluidInTank(0).isEmpty(), "empty pipe reported non-empty tank contents");
        require(helper, handler.isFluidValid(0, new FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1)),
            "empty connected fluid pipe rejected a valid fluid");

        int rate = PipeApi.getFluidTransferInfo(BCTransportPipes.cobbleFluid).transferPerTick;
        int inserted = handler.fill(
            new FluidStack(net.minecraft.world.level.material.Fluids.WATER, rate),
            FluidAction.EXECUTE
        );
        require(helper, inserted == rate, "fluid capability filled the wrong amount");
        FluidStack reported = handler.getFluidInTank(0);
        require(helper, reported.getAmount() == rate, "fluid capability reported the wrong stored amount");
        require(helper, reported.getFluid() == net.minecraft.world.level.material.Fluids.WATER,
            "fluid capability reported the wrong stored fluid");
        require(helper, !handler.isFluidValid(0, new FluidStack(net.minecraft.world.level.material.Fluids.LAVA, 1)),
            "water-filled pipe advertised lava as valid");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void fluidForceExtractionHonoursMinMaxAndSimulation(GameTestHelper helper) {
        TestPipe pipe = new TestPipe(helper.getLevel(), BCTransportPipes.stoneFluid);
        PipeFlowFluids flow = new PipeFlowFluids(pipe);
        pipe.setFlow(flow);

        int rate = PipeApi.getFluidTransferInfo(BCTransportPipes.stoneFluid).transferPerTick;
        int inserted = flow.insertFluidsForce(
            new FluidStack(net.minecraft.world.level.material.Fluids.WATER, rate),
            null,
            FluidAction.EXECUTE
        );
        require(helper, inserted == rate, "test setup failed to fill the centre section");

        FluidStack belowMinimum = flow.extractFluidsForce(rate + 1, rate + 1, null, FluidAction.EXECUTE);
        require(helper, belowMinimum.isEmpty(), "force extraction ignored its minimum");
        require(helper, totalFluid(flow) == rate, "failed minimum check still drained fluid");

        int requested = Math.max(1, rate / 2);
        FluidStack simulated = flow.extractFluidsForce(0, requested, null, FluidAction.SIMULATE);
        require(helper, simulated.getAmount() == requested, "simulated extraction returned the wrong amount");
        require(helper, totalFluid(flow) == rate, "simulated extraction mutated the pipe");

        FluidStack executed = flow.extractFluidsForce(0, requested, null, FluidAction.EXECUTE);
        require(helper, executed.getAmount() == requested, "executed extraction returned the wrong amount");
        require(helper, FluidCompatRegistry.areEquivalent(simulated, executed),
            "simulation and execution selected different fluids");
        require(helper, totalFluid(flow) == rate - requested, "executed extraction drained the wrong amount");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void fullForceExtractionThenRefillDoesNotGhostJamPipe(GameTestHelper helper) {
        int rate = PipeApi.getFluidTransferInfo(BCTransportPipes.stoneFluid).transferPerTick;
        Tank sink = new Tank("sink", rate * 4, null);
        TestPipe pipe = new TestPipe(helper.getLevel(), BCTransportPipes.stoneFluid)
            .connect(Direction.EAST, ConnectedType.TILE)
            .exposeCapability(Direction.EAST, CapUtil.CAP_FLUIDS, sink);
        PipeFlowFluids flow = new PipeFlowFluids(pipe);
        pipe.setFlow(flow);

        require(helper, flow.insertFluidsForce(
            new FluidStack(net.minecraft.world.level.material.Fluids.WATER, rate), null, FluidAction.EXECUTE
        ) == rate, "test setup failed to seed fluid pipe");
        require(helper, flow.extractFluidsForce(0, rate, null, FluidAction.EXECUTE).getAmount() == rate,
            "force extraction did not fully empty the pipe");
        require(helper, totalFluid(flow) == 0, "force extraction left fluid behind");
        require(helper, flow.insertFluidsForce(
            new FluidStack(net.minecraft.world.level.material.Fluids.WATER, rate), null, FluidAction.EXECUTE
        ) == rate, "pipe could not be refilled after force extraction");

        int delay = Math.max(1, (int) Math.ceil(
            PipeApi.getFluidTransferInfo(BCTransportPipes.stoneFluid).transferDelayMultiplier
        ));
        for (int i = 0; i < delay * 2 + 6; i++) {
            flow.onTick();
        }

        require(helper, sink.getFluidAmount() > 0,
            "refilled pipe remained ghost-jammed after its delayed fluid should have matured");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE,
        timeoutTicks = 220)
    public static void connectedFluidPipesConserveWaterWhileTicking(GameTestHelper helper) {
        TilePipeHolder first = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 3), BCTransportPipes.cobbleFluid);
        TilePipeHolder second = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.cobbleFluid);
        int[] inserted = { 0 };

        helper.runAfterDelay(5, () -> {
            require(helper, first.getPipe().isConnected(Direction.EAST), "first fluid pipe did not connect east");
            require(helper, second.getPipe().isConnected(Direction.WEST), "second fluid pipe did not connect west");
            PipeFlowFluids flow = (PipeFlowFluids) first.getPipe().getFlow();
            int rate = PipeApi.getFluidTransferInfo(BCTransportPipes.cobbleFluid).transferPerTick;
            inserted[0] = flow.insertFluidsForce(
                new FluidStack(net.minecraft.world.level.material.Fluids.WATER, rate),
                Direction.WEST,
                FluidAction.EXECUTE
            );
            require(helper, inserted[0] == rate, "failed to seed the fluid network");
        });

        helper.runAfterDelay(180, () -> {
            PipeFlowFluids firstFlow = (PipeFlowFluids) first.getPipe().getFlow();
            PipeFlowFluids secondFlow = (PipeFlowFluids) second.getPipe().getFlow();
            int total = totalFluid(firstFlow) + totalFluid(secondFlow);
            require(helper, total == inserted[0],
                "two-pipe fluid network changed total fluid from " + inserted[0] + " to " + total);
            require(helper, containsOnlyFluid(firstFlow, net.minecraft.world.level.material.Fluids.WATER),
                "first pipe changed the fluid type");
            require(helper, containsOnlyFluid(secondFlow, net.minecraft.world.level.material.Fluids.WATER),
                "second pipe changed the fluid type");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE,
        timeoutTicks = 140)
    public static void fluidDoesNotEnterSectionTowardDisconnectedColouredPipe(GameTestHelper helper) {
        TilePipeHolder first = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 3), BCTransportPipes.stoneFluid);
        TilePipeHolder second = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.stoneFluid);
        first.getPipe().setColour(DyeColor.PINK);
        second.getPipe().setColour(DyeColor.LIME);
        first.getPipe().markForUpdate();
        second.getPipe().markForUpdate();

        helper.runAfterDelay(5, () -> {
            require(helper, !first.getPipe().isConnected(Direction.EAST),
                "differently coloured fluid pipes unexpectedly connected");
            require(helper, !second.getPipe().isConnected(Direction.WEST),
                "differently coloured fluid connection was asymmetric");
        });

        for (int tick = 8; tick < 68; tick++) {
            helper.runAfterDelay(tick, () -> {
                PipeFlowFluids flow = (PipeFlowFluids) first.getPipe().getFlow();
                int rate = PipeApi.getFluidTransferInfo(BCTransportPipes.stoneFluid).transferPerTick;
                flow.insertFluidsForce(
                    new FluidStack(net.minecraft.world.level.material.Fluids.WATER, rate),
                    null,
                    FluidAction.EXECUTE
                );
            });
        }

        helper.runAfterDelay(110, () -> {
            PipeFlowFluids firstFlow = (PipeFlowFluids) first.getPipe().getFlow();
            PipeFlowFluids secondFlow = (PipeFlowFluids) second.getPipe().getFlow();
            int eastAmount = sectionFluid(firstFlow, Direction.EAST);
            require(helper, eastAmount == 0,
                "fluid accumulated in disconnected east section: " + eastAmount + " mB");
            require(helper, totalFluid(secondFlow) == 0,
                "fluid leaked into differently coloured neighbouring pipe");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE,
        timeoutTicks = 380)
    public static void poweredWoodFluidPipeMovesWaterIntoTankWithoutLoss(GameTestHelper helper) {
        TileTank source = placeTank(helper, new BlockPos(1, 1, 3));
        TilePipeHolder wood = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 3), BCTransportPipes.woodFluid);
        TilePipeHolder transport = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.cobbleFluid);
        TileTank destination = placeTank(helper, new BlockPos(4, 1, 3));

        int initial = FluidType.BUCKET_VOLUME;
        int filled = source.tank.fill(
            new FluidStack(net.minecraft.world.level.material.Fluids.WATER, initial),
            FluidAction.EXECUTE
        );
        require(helper, filled == initial, "failed to fill source tank");
        int[] extracted = { 0 };

        helper.runAfterDelay(8, () -> {
            require(helper, wood.getPipe().isConnected(Direction.WEST), "wood fluid pipe did not connect to source tank");
            require(helper, wood.getPipe().isConnected(Direction.EAST), "wood fluid pipe did not connect to transport pipe");
            require(helper, transport.getPipe().isConnected(Direction.EAST), "transport pipe did not connect to destination tank");

            PipeBehaviourWood behaviour = (PipeBehaviourWood) wood.getPipe().getBehaviour();
            require(helper, behaviour.getCurrentDir() == Direction.WEST,
                "wood fluid pipe faced " + behaviour.getCurrentDir() + " instead of source tank");

            long offered = BCTransportConfig.mjPerMillibucket * 250L;
            long leftover = behaviour.receivePower(offered, FluidAction.EXECUTE);
            extracted[0] = (int) ((offered - leftover) / BCTransportConfig.mjPerMillibucket);
            require(helper, extracted[0] > 0, "powered wood fluid pipe extracted nothing");
        });

        helper.runAfterDelay(340, () -> {
            PipeFlowFluids woodFlow = (PipeFlowFluids) wood.getPipe().getFlow();
            PipeFlowFluids transportFlow = (PipeFlowFluids) transport.getPipe().getFlow();
            int inPipes = totalFluid(woodFlow) + totalFluid(transportFlow);
            int sourceAmount = source.tank.getFluidAmount();
            int destinationAmount = destination.tank.getFluidAmount();
            int total = sourceAmount + destinationAmount + inPipes;

            require(helper, total == initial,
                "fluid extraction network changed total water from " + initial + " to " + total);
            require(helper, sourceAmount == initial - extracted[0],
                "source tank lost a different amount than the wood pipe reported extracting");
            require(helper, destinationAmount == extracted[0],
                "destination received " + destinationAmount + " of " + extracted[0] + " extracted mB");
            require(helper, inPipes == 0, "fluid remained stuck in pipes after the transfer window");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void diamondFluidFiltersMatchingSidesAndKeepsUnfilteredFallback(GameTestHelper helper) {
        TestPipe pipe = new TestPipe(helper.getLevel(), BCTransportPipes.diamondFluid)
            .connect(Direction.EAST, ConnectedType.PIPE)
            .connect(Direction.SOUTH, ConnectedType.PIPE)
            .connect(Direction.WEST, ConnectedType.PIPE);
        PipeFlowFluids flow = new PipeFlowFluids(pipe);
        PipeBehaviourDiamondFluid behaviour = new PipeBehaviourDiamondFluid(pipe);
        pipe.setFlow(flow);
        pipe.setBehaviour(behaviour);

        behaviour.filters.setStackInSlot(
            Direction.EAST.ordinal() * PipeBehaviourDiamond.FILTERS_PER_SIDE,
            new ItemStack(Items.WATER_BUCKET)
        );
        behaviour.filters.setStackInSlot(
            Direction.SOUTH.ordinal() * PipeBehaviourDiamond.FILTERS_PER_SIDE,
            new ItemStack(Items.LAVA_BUCKET)
        );

        PipeEventFluid.SideCheck water = new PipeEventFluid.SideCheck(
            pipe.getHolder(), flow, new FluidStack(net.minecraft.world.level.material.Fluids.WATER, 100)
        );
        water.disallowAllExcept(Direction.EAST, Direction.SOUTH, Direction.WEST);
        behaviour.sideCheck(water);
        require(helper, water.isAllowed(Direction.EAST), "water filter rejected water");
        require(helper, !water.isAllowed(Direction.SOUTH), "lava filter accepted water");
        require(helper, water.isAllowed(Direction.WEST), "unfiltered fluid fallback was removed");
        require(helper, water.getOrder().equals(java.util.EnumSet.of(Direction.EAST)),
            "matching filtered side did not outrank the unfiltered fallback");

        PipeEventFluid.SideCheck lava = new PipeEventFluid.SideCheck(
            pipe.getHolder(), flow, new FluidStack(net.minecraft.world.level.material.Fluids.LAVA, 100)
        );
        lava.disallowAllExcept(Direction.EAST, Direction.SOUTH, Direction.WEST);
        behaviour.sideCheck(lava);
        require(helper, !lava.isAllowed(Direction.EAST), "water filter accepted lava");
        require(helper, lava.isAllowed(Direction.SOUTH), "lava filter rejected lava");
        require(helper, lava.getOrder().equals(java.util.EnumSet.of(Direction.SOUTH)),
            "lava did not select its filtered side");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 30)
    public static void powerReceiverSimulationAndDistributionAreConservative(GameTestHelper helper) {
        long mj = MjAmount.MICRO_MJ_PER_MJ;
        PipeApi.PowerTransferInfo transferInfo = PipeApi.getPowerTransferInfo(BCTransportPipes.woodPower);
        FakeReceiver east = new FakeReceiver(4 * mj);
        FakeReceiver south = new FakeReceiver(12 * mj);
        TestPipe pipe = new TestPipe(helper.getLevel(), BCTransportPipes.woodPower)
            .connect(Direction.WEST, ConnectedType.TILE)
            .connect(Direction.EAST, ConnectedType.TILE)
            .connect(Direction.SOUTH, ConnectedType.TILE)
            .exposeCapability(Direction.EAST, MjCapabilities.CAP_RECEIVER, east)
            .exposeCapability(Direction.SOUTH, MjCapabilities.CAP_RECEIVER, south);
        PipeFlowPower flow = new PipeFlowPower(pipe);
        pipe.setFlow(flow);
        flow.reconfigure();

        helper.runAfterDelay(1, flow::onTick);
        helper.runAfterDelay(2, () -> {
            flow.onTick();
            PipeFlowPower.Section input = flow.getSection(Direction.WEST);
            long offered = 20 * mj;
            long expectedAccepted = transferInfo.transferPerTick;

            long simulatedLeftover = input.receivePower(offered, FluidAction.SIMULATE);
            require(helper, input.internalNextPower == 0, "simulated MJ insertion mutated the power buffer");
            require(helper, offered - simulatedLeftover == expectedAccepted,
                "simulated MJ insertion ignored request or pipe transfer limit");

            long executedLeftover = input.receivePower(offered, FluidAction.EXECUTE);
            require(helper, executedLeftover == simulatedLeftover,
                "MJ simulation and execution returned different leftovers");
            require(helper, input.internalNextPower == expectedAccepted,
                "executed MJ insertion buffered the wrong amount");
        });

        helper.runAfterDelay(3, () -> {
            flow.onTick();
            long expectedEast = applyPowerResistance(4 * mj, transferInfo);
            long expectedSouth = applyPowerResistance(12 * mj, transferInfo);
            require(helper, east.accepted == expectedEast,
                "east receiver got " + east.accepted + " instead of " + expectedEast + " microjoules after resistance");
            require(helper, south.accepted == expectedSouth,
                "south receiver got " + south.accepted + " instead of " + expectedSouth + " microjoules after resistance");
            require(helper, east.accepted + south.accepted == expectedEast + expectedSouth,
                "power distribution created energy or applied the wrong resistance");
            require(helper, flow.getSection(Direction.WEST).internalPower == 0,
                "input section retained power after satisfying all requests");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 30)
    public static void powerDistributionAbovePipeLimitDoesNotDivideByZero(GameTestHelper helper) {
        PipeApi.PowerTransferInfo transferInfo = PipeApi.getPowerTransferInfo(BCTransportPipes.woodPower);
        long maxPower = transferInfo.transferPerTick;
        FakeReceiver up = new FakeReceiver(maxPower);
        FakeReceiver south = new FakeReceiver(maxPower);
        FakeReceiver east = new FakeReceiver(maxPower);
        TestPipe pipe = new TestPipe(helper.getLevel(), BCTransportPipes.woodPower)
            .connect(Direction.WEST, ConnectedType.TILE)
            .connect(Direction.UP, ConnectedType.TILE)
            .connect(Direction.SOUTH, ConnectedType.TILE)
            .connect(Direction.EAST, ConnectedType.TILE)
            .exposeCapability(Direction.UP, MjCapabilities.CAP_RECEIVER, up)
            .exposeCapability(Direction.SOUTH, MjCapabilities.CAP_RECEIVER, south)
            .exposeCapability(Direction.EAST, MjCapabilities.CAP_RECEIVER, east);
        PipeFlowPower flow = new PipeFlowPower(pipe);
        pipe.setFlow(flow);
        flow.reconfigure();

        helper.runAfterDelay(1, flow::onTick);
        helper.runAfterDelay(2, () -> {
            flow.onTick();
            long leftover = flow.getSection(Direction.WEST).receivePower(maxPower, FluidAction.EXECUTE);
            require(helper, leftover == 0, "power pipe rejected power despite three downstream requests");
        });
        helper.runAfterDelay(3, () -> {
            flow.onTick();
            long accepted = up.accepted + south.accepted + east.accepted;
            long firstShare = maxPower / 3;
            long secondShare = (maxPower - firstShare) / 2;
            long thirdShare = maxPower - firstShare - secondShare;
            long expectedAccepted = applyPowerResistance(firstShare, transferInfo)
                + applyPowerResistance(secondShare, transferInfo)
                + applyPowerResistance(thirdShare, transferInfo);
            require(helper, accepted == expectedAccepted,
                "oversubscribed power distribution transferred " + accepted + " instead of " + expectedAccepted
                    + " microjoules after resistance");
            require(helper, up.accepted > 0 && south.accepted > 0 && east.accepted > 0,
                "oversubscribed power distribution starved one of the requested outputs");
            require(helper, flow.getSection(Direction.WEST).internalPower == 0,
                "input section retained power after oversubscribed distribution");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 40)
    public static void powerTraversesNonReceiverPipeAndReachesSink(GameTestHelper helper) {
        long requested = 8 * MjAmount.MICRO_MJ_PER_MJ;
        PipeApi.PowerTransferInfo sourceTransferInfo = PipeApi.getPowerTransferInfo(BCTransportPipes.woodPower);
        PipeApi.PowerTransferInfo transportTransferInfo = PipeApi.getPowerTransferInfo(BCTransportPipes.stonePower);
        long expectedAtTransport = applyPowerResistance(requested, sourceTransferInfo);
        long expectedAtSink = applyPowerResistance(expectedAtTransport, transportTransferInfo);
        FakeReceiver sink = new FakeReceiver(requested);

        TestPipe sourcePipe = new TestPipe(helper.getLevel(), BCTransportPipes.woodPower)
            .connect(Direction.WEST, ConnectedType.TILE);
        TestPipe transportPipe = new TestPipe(helper.getLevel(), BCTransportPipes.stonePower)
            .connect(Direction.EAST, ConnectedType.TILE)
            .exposeCapability(Direction.EAST, MjCapabilities.CAP_RECEIVER, sink);
        sourcePipe.connectPipe(Direction.EAST, transportPipe);
        transportPipe.connectPipe(Direction.WEST, sourcePipe);

        PipeFlowPower source = new PipeFlowPower(sourcePipe);
        PipeFlowPower transport = new PipeFlowPower(transportPipe);
        sourcePipe.setFlow(source);
        transportPipe.setFlow(transport);
        source.reconfigure();
        transport.reconfigure();

        helper.runAfterDelay(1, () -> {
            transport.onTick();
            source.onTick();
        });
        helper.runAfterDelay(2, () -> {
            transport.onTick();
            source.onTick();
        });
        helper.runAfterDelay(3, () -> {
            transport.onTick();
            source.onTick();
            long leftover = source.getSection(Direction.WEST).receivePower(requested, FluidAction.EXECUTE);
            require(helper, leftover == 0, "source power pipe rejected downstream demand");
        });
        helper.runAfterDelay(4, () -> {
            source.onTick();
            require(helper, transport.getSection(Direction.WEST).internalNextPower == expectedAtTransport,
                "non-receiver transport pipe queued " + transport.getSection(Direction.WEST).internalNextPower
                    + " instead of " + expectedAtTransport + " microjoules after source-pipe resistance");
        });
        helper.runAfterDelay(5, () -> {
            transport.onTick();
            require(helper, sink.accepted == expectedAtSink,
                "sink received " + sink.accepted + " instead of " + expectedAtSink
                    + " microjoules after two pipe hops");
            require(helper, source.getSection(Direction.WEST).internalPower == 0,
                "source retained power after forwarding it");
            require(helper, transport.getSection(Direction.WEST).internalPower == 0,
                "transport pipe retained power after satisfying the sink");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void passiveProviderExtractionUsesDryRunAndNeverOverfillsSection(GameTestHelper helper) {
        long maxPower = PipeApi.getPowerTransferInfo(BCTransportPipes.woodPower).transferPerTick;
        FakePassiveProvider provider = new FakePassiveProvider(maxPower * 3);
        ChestBlockEntity providerTile = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());

        TestPipe pipe = new TestPipe(helper.getLevel(), BCTransportPipes.woodPower)
            .connectTile(Direction.WEST, providerTile)
            .connect(Direction.EAST, ConnectedType.TILE)
            .exposeCapability(Direction.WEST, MjCapabilities.CAP_PASSIVE_PROVIDER, provider);
        PipeFlowPower flow = new PipeFlowPower(pipe);
        pipe.setFlow(flow);
        flow.reconfigure();

        helper.runAfterDelay(1, () -> {
            flow.getSection(Direction.EAST).nextPowerQuery = maxPower * 2;
            long accepted = flow.tryExtractPower(maxPower * 2, Direction.WEST);
            require(helper, accepted == maxPower, "wood power pipe extracted beyond or below its section capacity");
            require(helper, provider.simulateCalls == 1,
                "passive provider was not queried exactly once before extraction");
            require(helper, provider.executeCalls == 1, "passive provider was not executed exactly once");
            require(helper, provider.lastSimulatedMax == maxPower, "dry-run extraction used the wrong maximum");
            require(helper, provider.lastExecutedMax == maxPower, "executed extraction exceeded dry-run result");
            require(helper, flow.getSection(Direction.WEST).internalNextPower == maxPower,
                "extracted MJ was not queued in the input section");

            long second = flow.tryExtractPower(maxPower, Direction.WEST);
            require(helper, second == 0, "full power section accepted a second extraction in the same tick");
            require(helper, provider.executeCalls == 1, "provider was executed despite the section being full");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void powerPipeBuffersSurviveNbtRoundTrip(GameTestHelper helper) {
        TestPipe pipe = new TestPipe(helper.getLevel(), BCTransportPipes.cobblePower);
        PipeFlowPower flow = new PipeFlowPower(pipe);
        pipe.setFlow(flow);
        flow.reconfigure();

        long maxPower = PipeApi.getPowerTransferInfo(BCTransportPipes.cobblePower).transferPerTick;
        long stored = Math.max(1L, maxPower / 3L);
        long queued = Math.max(1L, maxPower / 4L);
        flow.getSection(Direction.WEST).internalPower = stored;
        flow.getSection(Direction.WEST).internalNextPower = queued;

        CompoundTag saved = flow.writeToNbt();
        PipeFlowPower restored = new PipeFlowPower(pipe, saved);
        pipe.setFlow(restored);
        restored.reconfigure();

        require(helper, restored.getSection(Direction.WEST).internalPower == stored,
            "power pipe lost its current MJ buffer across NBT round-trip");
        require(helper, restored.getSection(Direction.WEST).internalNextPower == queued,
            "power pipe lost its queued MJ buffer across NBT round-trip");
        require(helper, restored.requiresPeriodicSave(),
            "buffered power pipe did not request periodic persistence");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void powerLimiterModesClampPersistAndDisableTransfer(GameTestHelper helper) {
        long base = PipeApi.getPowerTransferInfo(BCTransportPipes.ironPower).transferPerTick;
        TestPipe pipe = new TestPipe(helper.getLevel(), BCTransportPipes.ironPower);
        PipeFlowPower flow = new PipeFlowPower(pipe);
        pipe.setFlow(flow);

        CompoundTag halfNbt = new CompoundTag();
        halfNbt.putInt("limitShift", 1);
        PipeBehaviourLimiter half = new PipeBehaviourLimiter(pipe, halfNbt);
        PipeEventPower.Configure halfEvent = configuredPowerEvent(pipe, flow, base);
        half.configurePower(halfEvent);
        require(helper, halfEvent.getMaxPower() == base / 2, "iron power limiter shift=1 did not halve transfer");
        require(helper, !halfEvent.isTransferDisabled(), "half-power limiter disabled transfer");
        require(helper, GameTestCompat.readInt(half.writeToNbt(), "limitShift") == 1, "limiter mode was not persisted");

        CompoundTag disabledNbt = new CompoundTag();
        disabledNbt.putInt("limitShift", PipeBehaviourLimiter.MAX_SHIFT);
        PipeBehaviourLimiter disabled = new PipeBehaviourLimiter(pipe, disabledNbt);
        PipeEventPower.Configure disabledEvent = configuredPowerEvent(pipe, flow, base);
        disabled.configurePower(disabledEvent);
        require(helper, disabledEvent.isTransferDisabled(), "maximum limiter mode did not disable transfer");

        CompoundTag oversizedNbt = new CompoundTag();
        oversizedNbt.putInt("limitShift", 999);
        PipeBehaviourLimiter clamped = new PipeBehaviourLimiter(pipe, oversizedNbt);
        require(helper, GameTestCompat.readInt(clamped.writeToNbt(), "limitShift") == PipeBehaviourLimiter.MAX_SHIFT,
            "limiter NBT did not clamp an oversized mode");
        helper.succeed();
    }

    private static PipeEventPower.Configure configuredPowerEvent(TestPipe pipe, PipeFlowPower flow, long maxPower) {
        PipeEventPower.Configure event = new PipeEventPower.Configure(pipe.getHolder(), flow);
        event.setMaxPower(maxPower);
        return event;
    }

    private static TileTank placeTank(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, BCFactoryBlocks.TANK_BLOCK.get().defaultBlockState());
        if (!(GameTestCompat.getBlockEntity(helper, pos) instanceof TileTank tank)) {
            helper.fail("tank block did not create TileTank at " + pos);
            throw new IllegalStateException("missing TileTank");
        }
        return tank;
    }

    private static int totalFluid(PipeFlowFluids flow) {
        CompoundTag nbt = flow.writeToNbt();
        int total = 0;
        for (EnumPipePart part : EnumPipePart.VALUES) {
            CompoundTag section = nbt.getCompound("tank[" + part.getIndex() + "]");
            total += Math.max(0, GameTestCompat.readInt(section, "capacity"));
        }
        return total;
    }

    private static int sectionFluid(PipeFlowFluids flow, Direction direction) {
        CompoundTag nbt = flow.writeToNbt();
        return Math.max(0, GameTestCompat.readInt(nbt.getCompound("tank[" + direction.get3DDataValue() + "]"), "capacity"));
    }

    private static boolean containsFluid(PipeFlowFluids flow, net.minecraft.world.level.material.Fluid fluid) {
        CompoundTag nbt = flow.writeToNbt();
        if (!nbt.contains("fluid")) {
            return false;
        }
        FluidStack stack = FluidStackUtil.parseOptional(nbt.getCompound("fluid"));
        return !stack.isEmpty() && stack.getFluid() == fluid;
    }

    private static boolean containsOnlyFluid(PipeFlowFluids flow, net.minecraft.world.level.material.Fluid fluid) {
        return totalFluid(flow) == 0 || containsFluid(flow, fluid);
    }

    private static long applyPowerResistance(long amount, PipeApi.PowerTransferInfo transferInfo) {
        if (amount <= 0) {
            return 0;
        }
        long resistance = Math.max(0, Math.min(MjAmount.MICRO_MJ_PER_MJ, transferInfo.resistancePerTick));
        if (resistance <= 0) {
            return amount;
        }
        if (resistance >= MjAmount.MICRO_MJ_PER_MJ) {
            return 0;
        }
        long retained = BigInteger.valueOf(amount)
            .multiply(BigInteger.valueOf(MjAmount.MICRO_MJ_PER_MJ - resistance))
            .divide(BigInteger.valueOf(MjAmount.MICRO_MJ_PER_MJ))
            .longValue();
        return Math.max(1, retained);
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }

    private static final class FakeReceiver implements IMjReceiver {
        private final long capacity;
        private long accepted;

        private FakeReceiver(long capacity) {
            this.capacity = capacity;
        }

        @Override
        public long getPowerRequested() {
            return Math.max(0, capacity - accepted);
        }

        @Override
        public long receivePower(long microJoules, FluidAction action) {
            if (microJoules <= 0) {
                return microJoules;
            }
            long canAccept = Math.min(microJoules, getPowerRequested());
            if (action.execute()) {
                accepted += canAccept;
            }
            return microJoules - canAccept;
        }

        @Override
        public boolean canConnect(@Nonnull IMjConnector other) {
            return true;
        }
    }

    private static final class FakePassiveProvider implements IMjPassiveProvider {
        private long available;
        private int simulateCalls;
        private int executeCalls;
        private long lastSimulatedMax;
        private long lastExecutedMax;

        private FakePassiveProvider(long available) {
            this.available = available;
        }

        @Override
        public long extractPower(long min, long max, boolean doExtract) {
            long extracted = Math.min(Math.max(0, max), available);
            if (extracted < min) {
                return 0;
            }
            if (doExtract) {
                executeCalls++;
                lastExecutedMax = max;
                available -= extracted;
            } else {
                simulateCalls++;
                lastSimulatedMax = max;
            }
            return extracted;
        }

        @Override
        public boolean canConnect(@Nonnull IMjConnector other) {
            return true;
        }
    }

}
