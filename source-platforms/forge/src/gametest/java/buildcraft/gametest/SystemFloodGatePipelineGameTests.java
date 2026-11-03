package buildcraft.gametest;

import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.tile.TileFloodGate;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Fluid extraction, in-world pipe flow and Flood Gate world placement as one lifecycle. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemFloodGatePipelineGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final BlockPos SOURCE = new BlockPos(1, 1, 3);
    private static final BlockPos WOOD = new BlockPos(2, 1, 3);
    private static final BlockPos TRANSIT = new BlockPos(3, 1, 3);
    private static final BlockPos GATE = new BlockPos(4, 1, 3);
    private static final BlockPos OUTPUT = new BlockPos(4, 0, 3);
    private static final BlockPos POWER = new BlockPos(4, 2, 3);
    private static final int WATER = FluidType.BUCKET_VOLUME;

    private SystemFloodGatePipelineGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 880)
    public static void extractedWaterWaitsAtPoweredFloodGateThenPlacesExactlyOneSource(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        helper.setBlock(POWER, Blocks.REDSTONE_BLOCK.defaultBlockState());
        rig.startExtraction(helper);
        helper.runAfterDelay(520, () -> {
            require(helper, helper.getLevel().hasNeighborSignal(rig.gate.getBlockPos()),
                "redstone-blocked Flood Gate did not detect world power");
            rig.assertConserved(helper, 0);
            require(helper, rig.extracted[0] == WATER, "wooden fluid pipe did not extract a full bucket from its Tank");
            require(helper, rig.gate.tankManager.get(0).getFluidAmount() == WATER,
                "Flood Gate did not retain transported water while powered");
            require(helper, helper.getLevel().getFluidState(helper.absolutePos(OUTPUT)).isEmpty(),
                "Flood Gate placed water despite physical redstone shutdown");
            helper.setBlock(POWER, Blocks.AIR.defaultBlockState());
            rig.gate.onOpenSidesChanged();
        });
        helper.runAfterDelay(820, () -> {
            require(helper, isWaterSource(helper), "Flood Gate never placed the transported water after restart");
            require(helper, rig.gate.tankManager.get(0).isEmpty(),
                "Flood Gate kept a ghost bucket after placing the actual source");
            require(helper, rig.source.tank.isEmpty() && rig.inPipes() == 0,
                "water remained in the extraction pipeline after the world placement");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 900)
    public static void blockedFloodGatePlacementRetainsPipedWaterUntilObstructionRemoved(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        helper.setBlock(OUTPUT, Blocks.STONE.defaultBlockState());
        rig.startExtraction(helper);
        helper.runAfterDelay(520, () -> {
            rig.assertConserved(helper, 0);
            require(helper, rig.extracted[0] == WATER, "fluid network did not transport one real bucket");
            require(helper, rig.gate.tankManager.get(0).getFluidAmount() == WATER,
                "blocked placement consumed buffered fluid or lost it upstream");
            require(helper, helper.getLevel().getBlockState(helper.absolutePos(OUTPUT)).is(Blocks.STONE),
                "Flood Gate overwrote an unreplaceable output block");
            helper.setBlock(OUTPUT, Blocks.AIR.defaultBlockState());
            rig.gate.onOpenSidesChanged();
        });
        helper.runAfterDelay(840, () -> {
            require(helper, isWaterSource(helper), "unblocked world output did not resume fluid placement");
            require(helper, rig.gate.tankManager.get(0).isEmpty() && rig.source.tank.isEmpty()
                    && rig.inPipes() == 0,
                "fluid pipeline retained or duplicated the bucket after clearing its output");
            helper.succeed();
        });
    }

    /** Fully physical machine/capability/pipe arrangement; only the MJ supply is a deterministic pulse fixture. */
    private static final class Rig {
        private final TileTank source;
        private final TilePipeHolder wood;
        private final TilePipeHolder transit;
        private final TileFloodGate gate;
        private final int[] extracted = { 0 };

        Rig(GameTestHelper helper) {
            helper.setBlock(SOURCE, BCFactoryBlocks.TANK_BLOCK.get().defaultBlockState());
            source = requireTile(helper, SOURCE, TileTank.class);
            wood = PipeGameTestSupport.placePipe(helper, WOOD, BCTransportPipes.woodFluid);
            transit = PipeGameTestSupport.placePipe(helper, TRANSIT, BCTransportPipes.cobbleFluid);
            helper.setBlock(GATE, BCFactoryBlocks.FLOOD_GATE_BLOCK.get().defaultBlockState());
            gate = requireTile(helper, GATE, TileFloodGate.class);
            gate.openSides.clear();
            gate.openSides.add(Direction.DOWN);
            require(helper, source.tank.fill(new FluidStack(Fluids.WATER, WATER), FluidAction.EXECUTE) == WATER,
                "failed to seed the physical source tank");
            gate.onOpenSidesChanged();
        }

        void startExtraction(GameTestHelper helper) {
            for (int tick = 20; tick <= 400; tick += 38) {
                helper.runAfterDelay(tick, () -> {
                    require(helper, wood.getPipe().isConnected(Direction.WEST)
                        && wood.getPipe().isConnected(Direction.EAST)
                        && transit.getPipe().isConnected(Direction.EAST),
                        "fluid pipe chain disconnected from physical source/Flood Gate");
                    PipeBehaviourWood behaviour = (PipeBehaviourWood) wood.getPipe().getBehaviour();
                    require(helper, behaviour.getCurrentDir() == Direction.WEST,
                        "wooden extraction pipe did not face its real source Tank");
                    long offered = BCTransportConfig.mjPerMillibucket * 250L;
                    long remainder = behaviour.receivePower(offered, FluidAction.EXECUTE);
                    extracted[0] += (int) ((offered - remainder) / BCTransportConfig.mjPerMillibucket);
                    require(helper, extracted[0] <= WATER, "wooden extractor minted fluid beyond source capacity");
                });
            }
        }

        int inPipes() {
            return stored((PipeFlowFluids) wood.getPipe().getFlow())
                + stored((PipeFlowFluids) transit.getPipe().getFlow());
        }

        void assertConserved(GameTestHelper helper, int alreadyPlaced) {
            int total = source.tank.getFluidAmount() + gate.tankManager.get(0).getFluidAmount() + inPipes()
                + alreadyPlaced;
            require(helper, total == WATER, "source/wood/transport/Flood Gate created or lost water: " + total);
            require(helper, source.tank.getFluidAmount() == WATER - extracted[0],
                "source Tank consumption disagreed with MJ extraction receipts");
        }
    }

    private static int stored(PipeFlowFluids pipe) {
        CompoundTag nbt = pipe.writeToNbt();
        int total = 0;
        for (EnumPipePart part : EnumPipePart.VALUES) {
            total += Math.max(0, GameTestCompat.readInt(
                nbt.getCompound("tank[" + part.getIndex() + "]"), "capacity"));
        }
        return total;
    }

    private static boolean isWaterSource(GameTestHelper helper) {
        var state = helper.getLevel().getFluidState(helper.absolutePos(OUTPUT));
        return state.getType() == Fluids.WATER && state.isSource();
    }

    private static <T> T requireTile(GameTestHelper helper, BlockPos relative, Class<T> type) {
        Object value = GameTestCompat.getBlockEntity(helper, relative);
        if (!type.isInstance(value)) {
            helper.fail("Expected " + type.getSimpleName() + " at " + relative);
            throw new IllegalStateException("missing block entity");
        }
        return type.cast(value);
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}
