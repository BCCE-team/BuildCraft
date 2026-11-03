package buildcraft.gametest;

import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.tile.TileTank;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.transport.BCTransportConfig;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.pipe.behaviour.PipeBehaviourWood;
import buildcraft.transport.pipe.flow.PipeFlowFluids;
import buildcraft.transport.tile.TilePipeHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Full-world fluid networks, multi-tick delivery, tank balancing and variant isolation. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemFluidNetworksGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final int BUCKET = FluidType.BUCKET_VOLUME;

    private SystemFluidNetworksGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 810)
    public static void isolatedWaterAndLavaNetworksResumeIndependently(GameTestHelper helper) {
        Line water = new Line(helper, 1, Fluids.WATER, BUCKET);
        Line lava = new Line(helper, 5, Fluids.LAVA, BUCKET);
        water.pulse(helper, 20, 240, 40, 250);
        helper.runAfterDelay(250, () -> {
            require(helper, lava.source.tank.getFluidAmount() == BUCKET
                && lava.target.tank.getFluidAmount() == 0, "paused lava lane moved without MJ");
            require(helper, water.source.tank.isEmpty(), "powered water lane did not extract its source");
        });
        lava.pulse(helper, 280, 500, 40, 250);
        helper.runAfterDelay(730, () -> {
            require(helper, water.target.tank.getFluidAmount() == BUCKET
                && water.target.tank.getFluid().getFluid() == Fluids.WATER,
                "independent water destination lost its identity or its bucket");
            require(helper, lava.target.tank.getFluidAmount() == BUCKET
                && lava.target.tank.getFluid().getFluid() == Fluids.LAVA,
                "independent lava destination lost its identity or its bucket");
            require(helper, water.source.tank.isEmpty() && lava.source.tank.isEmpty(),
                "source tanks retained fluid after powered extraction");
            require(helper, water.inFlight() == 0 && lava.inFlight() == 0,
                "fluid pipes retained in-transit volume after delivery");
            require(helper, water.total() == BUCKET && lava.total() == BUCKET,
                "two independent fluid pipelines generated, lost or transmuted fluid");
            SystemTrace.emit("isolated_fluid_pipelines",
                "waterDestination", Integer.toString(water.target.tank.getFluidAmount()),
                "lavaDestination", Integer.toString(lava.target.tank.getFluidAmount()),
                "waterSource", Integer.toString(water.source.tank.getFluidAmount()),
                "lavaSource", Integer.toString(lava.source.tank.getFluidAmount()),
                "inFlight", Integer.toString(water.inFlight() + lava.inFlight()),
                "waterIsWater", Boolean.toString(water.target.tank.getFluid().getFluid() == Fluids.WATER),
                "lavaIsLava", Boolean.toString(lava.target.tank.getFluid().getFluid() == Fluids.LAVA));
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 1000)
    public static void pipedWaterFillsStackedTanksThenCompactsAfterPhysicalDrain(GameTestHelper helper) {
        Line line = new Line(helper, 3, Fluids.WATER, 2 * BUCKET);
        TileTank top = tank(helper, new BlockPos(4, 2, 3));
        line.target.tank.setFluid(new FluidStack(Fluids.WATER, 15 * BUCKET));
        line.pulse(helper, 25, 370, 35, 250);
        helper.runAfterDelay(715, () -> {
            require(helper, line.source.tank.isEmpty() && line.inFlight() == 0,
                "stacked receiver did not accept all 2 buckets from powered pipes");
            require(helper, line.target.tank.getFluidAmount() == 16 * BUCKET
                && top.tank.getFluidAmount() == BUCKET,
                "incoming fluid did not compact bottom-first across real tank blocks");
            require(helper, line.total() + top.tank.getFluidAmount() == 17 * BUCKET,
                "tank connection/pipe flow changed the original 17-bucket ledger");
            // Drain through the public column handler: directly draining the local
            // backing tank bypasses stack-wide ordering and rebalance semantics.
            FluidStack removed = line.target.drain(16 * BUCKET, FluidAction.EXECUTE);
            require(helper, removed.getFluid() == Fluids.WATER && removed.getAmount() == 16 * BUCKET,
                "external tank drain returned the wrong real volume");
        });
        helper.runAfterDelay(910, () -> {
            require(helper, line.target.tank.getFluidAmount() == BUCKET && top.tank.isEmpty(),
                "column failed to recompact its remaining bucket after bottom tank drain");
            require(helper, line.total() + top.tank.getFluidAmount() == BUCKET,
                "tank compaction after real drain leaked or duplicated water");
            helper.succeed();
        });
    }

    private static final class Line {
        final TileTank source;
        final TileTank target;
        final TilePipeHolder wood;
        final TilePipeHolder transit;

        Line(GameTestHelper helper, int z, net.minecraft.world.level.material.Fluid fluid, int amount) {
            source = tank(helper, new BlockPos(1, 1, z));
            wood = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, z), BCTransportPipes.woodFluid);
            transit = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, z), BCTransportPipes.cobbleFluid);
            target = tank(helper, new BlockPos(4, 1, z));
            require(helper, source.tank.fill(new FluidStack(fluid, amount), FluidAction.EXECUTE) == amount,
                "failed to fill fluid line's real source tank");
        }

        void pulse(GameTestHelper helper, int first, int last, int interval, int millibuckets) {
            for (int tick = first; tick <= last; tick += interval) {
                helper.runAfterDelay(tick, () -> {
                    require(helper, wood.getPipe().isConnected(Direction.WEST)
                        && wood.getPipe().isConnected(Direction.EAST)
                        && transit.getPipe().isConnected(Direction.EAST),
                        "fluid network lost a physical pipe/tank connection");
                    PipeBehaviourWood extractor = (PipeBehaviourWood) wood.getPipe().getBehaviour();
                    require(helper, extractor.getCurrentDir() == Direction.WEST,
                        "fluid extractor aimed away from its tank");
                    extractor.receivePower(BCTransportConfig.mjPerMillibucket * millibuckets,
                        FluidAction.EXECUTE);
                });
            }
        }

        int inFlight() {
            return stored((PipeFlowFluids) wood.getPipe().getFlow())
                + stored((PipeFlowFluids) transit.getPipe().getFlow());
        }

        int total() {
            return source.tank.getFluidAmount() + target.tank.getFluidAmount() + inFlight();
        }
    }

    private static int stored(PipeFlowFluids flow) {
        CompoundTag tag = flow.writeToNbt();
        int amount = 0;
        for (EnumPipePart part : EnumPipePart.VALUES) {
            amount += Math.max(0, GameTestCompat.readInt(
                tag.getCompound("tank[" + part.getIndex() + "]"), "capacity"));
        }
        return amount;
    }

    private static TileTank tank(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, BCFactoryBlocks.TANK_BLOCK.get().defaultBlockState());
        if (!(GameTestCompat.getBlockEntity(helper, pos) instanceof TileTank tile)) {
            helper.fail("missing live tank at " + pos);
            throw new IllegalStateException("missing tank");
        }
        return tile;
    }

    private static void require(GameTestHelper helper, boolean ok, String message) {
        if (!ok) helper.fail(message);
    }
}
