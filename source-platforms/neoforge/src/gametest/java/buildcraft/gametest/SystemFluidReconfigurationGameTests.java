package buildcraft.gametest;

import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.tile.TileTank;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.transport.BCTransportConfig;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.block.BlockPipeHolder;
import buildcraft.transport.pipe.behaviour.PipeBehaviourWood;
import buildcraft.transport.pipe.flow.PipeFlowFluids;
import buildcraft.transport.tile.TilePipeHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** World-ticking fluid routes with physical disconnections, waterlogging and incompatible fluids. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemFluidReconfigurationGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final int BUCKET = FluidType.BUCKET_VOLUME;
    private SystemFluidReconfigurationGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 1020)
    public static void waterloggedColouredPipeNetworkRecoversWithoutInventingWater(GameTestHelper helper) {
        FluidLine line = new FluidLine(helper, 1, 3, Fluids.WATER, BUCKET);
        line.wood.getPipe().setColour(DyeColor.RED);
        line.transit.getPipe().setColour(DyeColor.BLUE);
        // A pipe can carry an internal fluid independently of the vanilla water
        // source occupying the same waterlogged BlockPipeHolder.
        BlockPos position = new BlockPos(3, 1, 3);
        BlockPos absolute = helper.absolutePos(position);
        helper.getLevel().setBlock(absolute,
            helper.getLevel().getBlockState(absolute).setValue(BlockPipeHolder.WATERLOGGED, true), 3);
        for (int tick = 45; tick <= 225; tick += 60) {
            helper.runAfterDelay(tick, () -> line.pulse(helper, 250));
        }
        helper.runAfterDelay(290, () -> {
            require(helper, line.target.tank.isEmpty(),
                "mismatched pipe colours allowed liquid to cross a disconnected endpoint");
            require(helper, line.total() == BUCKET,
                "fluid disappeared from the source/blocked pipes while colours disagreed");
            require(helper, helper.getLevel().getFluidState(absolute).getType() == Fluids.WATER
                && helper.getLevel().getBlockState(absolute).getValue(BlockPipeHolder.WATERLOGGED),
                "vanilla waterlogging was lost while an incompatible pipe joint was blocked");
            line.transit.getPipe().setColour(DyeColor.RED);
        });
        for (int tick = 350; tick <= 620; tick += 45) {
            helper.runAfterDelay(tick, () -> line.pulse(helper, 250));
        }
        helper.runAfterDelay(970, () -> {
            require(helper, line.wood.getPipe().isConnected(Direction.EAST)
                && line.transit.getPipe().isConnected(Direction.WEST),
                "repainted pipes did not restore a two-way transport connection");
            require(helper, line.source.tank.isEmpty()
                && line.target.tank.getFluidAmount() == BUCKET
                && line.target.tank.getFluid().getFluid() == Fluids.WATER,
                "fluid failed to resume through physically repainted waterlogged pipe");
            require(helper, line.inFlight() == 0 && line.total() == BUCKET,
                "reconfigured waterlogged network duplicated or stranded liquid");
            require(helper, helper.getLevel().getFluidState(absolute).getType() == Fluids.WATER,
                "transport consumed the ambient waterlogging fluid state");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 1120)
    public static void twoPipedFluidVariantsStayIsolatedAfterTankColumnBridgeInserted(GameTestHelper helper) {
        FluidLine water = new FluidLine(helper, 0, 3, Fluids.WATER, BUCKET);
        FluidLine lava = new FluidLine(helper, 2, 3, Fluids.LAVA, BUCKET);
        BlockPos bridge = new BlockPos(4, 1, 3);
        for (int tick = 30; tick <= 240; tick += 45) {
            helper.runAfterDelay(tick, () -> {water.pulse(helper, 250); lava.pulse(helper, 250);});
        }
        final TileTank[] middle = { null };
        helper.runAfterDelay(355, () -> {
            require(helper, water.target.tank.getFluidAmount() == BUCKET
                && lava.target.tank.getFluidAmount() == BUCKET
                && water.inFlight() == 0 && lava.inFlight() == 0,
                "isolated real pipes did not deliver their two different source fluids");
            middle[0] = tank(helper, bridge);
        });
        helper.runAfterDelay(435, () -> {
            require(helper, water.target.tank.getFluid().getFluid() == Fluids.WATER
                && lava.target.tank.getFluid().getFluid() == Fluids.LAVA
                && middle[0].tank.isEmpty(),
                "empty bridging tank merged two incompatible live fluid columns");
            require(helper, water.source.tank.fill(new FluidStack(Fluids.WATER, 500), FluidAction.EXECUTE) == 500
                && lava.source.tank.fill(new FluidStack(Fluids.LAVA, 500), FluidAction.EXECUTE) == 500,
                "failed to replenish both sources after inserting the bridging tank");
        });
        for (int tick = 465; tick <= 650; tick += 50) {
            helper.runAfterDelay(tick, () -> {water.pulse(helper, 200); lava.pulse(helper, 200);});
        }
        helper.runAfterDelay(1050, () -> {
            require(helper, water.target.tank.getFluid().getFluid() == Fluids.WATER
                && lava.target.tank.getFluid().getFluid() == Fluids.LAVA,
                "fluid columns transmuted or mixed source identities after a second live delivery");
            require(helper, water.source.tank.isEmpty() && lava.source.tank.isEmpty()
                && water.inFlight() == 0 && lava.inFlight() == 0,
                "physical pipeline left undelivered fluid after a tank-column topology change");
            require(helper, water.total() + middle[0].tank.getFluidAmount() == 1500,
                "water ledger changed across insertion of incompatible bridge tank");
            require(helper, lava.total() + middle[0].tank.getFluidAmount() == 1500,
                "lava ledger changed across insertion of incompatible bridge tank");
            helper.succeed();
        });
    }

    private static final class FluidLine {
        final TileTank source;
        final TileTank target;
        final TilePipeHolder wood;
        final TilePipeHolder transit;
        FluidLine(GameTestHelper helper, int y, int z, net.minecraft.world.level.material.Fluid fluid, int amount) {
            source = tank(helper, new BlockPos(1, y, z));
            wood = PipeGameTestSupport.placePipe(helper, new BlockPos(2, y, z), BCTransportPipes.woodFluid);
            transit = PipeGameTestSupport.placePipe(helper, new BlockPos(3, y, z), BCTransportPipes.cobbleFluid);
            target = tank(helper, new BlockPos(4, y, z));
            require(helper, source.tank.fill(new FluidStack(fluid, amount), FluidAction.EXECUTE) == amount,
                "failed to seed a finite real-world tank source");
        }
        void pulse(GameTestHelper helper, int amount) {
            require(helper, wood.getPipe().isConnected(Direction.WEST),
                "wooden pipe disconnected from its fluid source");
            PipeBehaviourWood extractor = (PipeBehaviourWood) wood.getPipe().getBehaviour();
            require(helper, extractor.getCurrentDir() == Direction.WEST,
                "wooden fluid pipe points away from its source");
            extractor.receivePower(BCTransportConfig.mjPerMillibucket * amount, FluidAction.EXECUTE);
        }
        int inFlight() { return stored(wood) + stored(transit); }
        int total() { return source.tank.getFluidAmount() + target.tank.getFluidAmount() + inFlight(); }
    }
    private static int stored(TilePipeHolder pipe) {
        int amount = 0;
        CompoundTag tag = ((PipeFlowFluids) pipe.getPipe().getFlow()).writeToNbt();
        for (EnumPipePart part : EnumPipePart.VALUES) {
            amount += Math.max(0, GameTestCompat.readInt(tag.getCompound("tank[" + part.getIndex() + "]"), "capacity"));
        }
        return amount;
    }
    private static TileTank tank(GameTestHelper helper, BlockPos position) {
        helper.setBlock(position, BCFactoryBlocks.TANK_BLOCK.get().defaultBlockState());
        Object entity = GameTestCompat.getBlockEntity(helper, position);
        if (!(entity instanceof TileTank t)) throw new IllegalStateException("missing real tank Block Entity");
        return t;
    }
    private static void require(GameTestHelper helper, boolean ok, String message) {
        if (!ok) helper.fail(message);
    }
}
