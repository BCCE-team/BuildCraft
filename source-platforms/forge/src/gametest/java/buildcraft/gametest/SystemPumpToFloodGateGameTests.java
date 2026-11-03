package buildcraft.gametest;

import java.lang.reflect.Field;

import buildcraft.api.v2.energy.MjAmount;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.tile.TileFloodGate;
import buildcraft.factory.tile.TileMiner;
import buildcraft.factory.tile.TilePump;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.mj.MjBattery;
import buildcraft.transport.BCTransportConfig;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.pipe.behaviour.PipeBehaviourWood;
import buildcraft.transport.tile.TilePipeHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Real-world pump mining -> fluid pipes -> redstone-controlled Flood Gate -> placed water. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemPumpToFloodGateGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final BlockPos PUMP = new BlockPos(1, 2, 3);
    private static final BlockPos INPUT = new BlockPos(1, 1, 3);
    private static final BlockPos WOOD = new BlockPos(2, 2, 3);
    private static final BlockPos TRANSIT = new BlockPos(3, 2, 3);
    private static final BlockPos GATE = new BlockPos(4, 2, 3);
    private static final BlockPos OUTPUT = new BlockPos(4, 1, 3);
    private static final BlockPos SIGNAL = new BlockPos(4, 2, 4);

    private SystemPumpToFloodGateGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 1160)
    public static void realPumpFeedsDisabledFloodGateThenResumesWithOneWaterSource(GameTestHelper helper) {
        // Enclose the single water source so vanilla flowing updates cannot create
        // secondary sources or escape the physical GameTest bounds.
        helper.setBlock(INPUT.below(), Blocks.STONE.defaultBlockState());
        for (Direction side : Direction.Plane.HORIZONTAL) {
            helper.setBlock(INPUT.relative(side), Blocks.STONE.defaultBlockState());
            helper.setBlock(OUTPUT.relative(side), Blocks.STONE.defaultBlockState());
        }
        helper.setBlock(INPUT, Blocks.WATER.defaultBlockState());
        helper.setBlock(OUTPUT.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(PUMP, BCFactoryBlocks.PUMP_BLOCK.get().defaultBlockState());
        TilePump pump = tile(helper, PUMP, TilePump.class);
        TilePipeHolder wood = PipeGameTestSupport.placePipe(helper, WOOD, BCTransportPipes.woodFluid);
        TilePipeHolder transport = PipeGameTestSupport.placePipe(helper, TRANSIT, BCTransportPipes.cobbleFluid);
        helper.setBlock(GATE, BCFactoryBlocks.FLOOD_GATE_BLOCK.get().defaultBlockState());
        TileFloodGate gate = tile(helper, GATE, TileFloodGate.class);
        gate.openSides.clear();
        gate.openSides.add(Direction.DOWN);
        gate.onOpenSidesChanged();
        helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK.defaultBlockState());

        MjBattery battery = battery(pump);
        long input = 250L * MjAmount.MICRO_MJ_PER_MJ;
        long added = input - battery.addPowerChecking(input, FluidAction.EXECUTE);
        require(helper, added > 0, "pump battery would not accept physical MJ for mining");
        // The pump also pushes to adjacent fluid handlers on its own; the wood
        // pipe receives a bounded pulse if the source still has buffered water.
        for (int tick = 60; tick <= 440; tick += 45) {
            helper.runAfterDelay(tick, () -> {
                require(helper, wood.getPipe().isConnected(Direction.WEST)
                    && wood.getPipe().isConnected(Direction.EAST)
                    && transport.getPipe().isConnected(Direction.EAST),
                    "pump-to-gate physical fluid path lost a connection");
                ((PipeBehaviourWood) wood.getPipe().getBehaviour()).receivePower(
                    BCTransportConfig.mjPerMillibucket * 300L, FluidAction.EXECUTE);
            });
        }
        helper.runAfterDelay(600, () -> {
            require(helper, helper.getLevel().hasNeighborSignal(gate.getBlockPos()),
                "Flood Gate never detected the actual redstone block");
            require(helper, helper.getLevel().getFluidState(helper.absolutePos(INPUT)).isEmpty(),
                "powered pump did not drain the original non-renewable water source");
            require(helper, gate.tankManager.get(0).getFluidAmount() == FluidType.BUCKET_VOLUME,
                "real pump and pipes did not deliver one complete bucket to the stopped Flood Gate");
            require(helper, helper.getLevel().getFluidState(helper.absolutePos(OUTPUT)).isEmpty(),
                "powered Flood Gate placed water despite redstone backpressure");
            helper.setBlock(SIGNAL, Blocks.AIR.defaultBlockState());
            gate.onOpenSidesChanged();
        });
        helper.runAfterDelay(1090, () -> {
            var worldOutput = helper.getLevel().getFluidState(helper.absolutePos(OUTPUT));
            require(helper, worldOutput.getType() == Fluids.WATER && worldOutput.isSource(),
                "unpowered Flood Gate did not turn pumped water into one world source");
            require(helper, gate.tankManager.get(0).isEmpty(),
                "gate retained a ghost bucket after successful placement");
            require(helper, helper.getLevel().getFluidState(helper.absolutePos(INPUT)).isEmpty(),
                "pump regenerated the extracted world source");
            require(helper, battery.getStored() < added,
                "pump mined the world source without consuming MJ");
            helper.succeed();
        });
    }

    private static MjBattery battery(TilePump pump) {
        try {
            Field field = TileMiner.class.getDeclaredField("battery");
            field.setAccessible(true);
            return (MjBattery) field.get(pump);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("could not seed real pump power buffer", ex);
        }
    }

    private static <T> T tile(GameTestHelper helper, BlockPos pos, Class<T> cls) {
        Object entity = GameTestCompat.getBlockEntity(helper, pos);
        if (!cls.isInstance(entity)) {
            helper.fail("missing " + cls.getSimpleName() + " at " + pos);
            throw new IllegalStateException("missing pump/flood gate");
        }
        return cls.cast(entity);
    }

    private static void require(GameTestHelper helper, boolean ok, String error) {
        if (!ok) helper.fail(error);
    }
}
