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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Block Entity state reconstruction in the middle of a physical fluid supply lifecycle. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemTankRestoreGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final BlockPos SOURCE = new BlockPos(1, 1, 3);
    private static final BlockPos WOOD = new BlockPos(2, 1, 3);
    private static final BlockPos TRANSIT = new BlockPos(3, 1, 3);
    private static final BlockPos TARGET = new BlockPos(4, 1, 3);
    private SystemTankRestoreGameTests() {}

    /**
     * A save/restore fixture (not a full process restart) exercises native Block Entity
     * persistence, world neighbour updates and a continued real pipe supply. Unknown
     * future payload fields must not erase a tank's already committed water.
     */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 960)
    public static void savedTankRestoredIntoWorldStillAcceptsFuturePipedFluidWithoutDuplication(GameTestHelper helper) {
        TileTank source = placeTank(helper, SOURCE);
        TilePipeHolder wood = PipeGameTestSupport.placePipe(helper, WOOD, BCTransportPipes.woodFluid);
        TilePipeHolder transit = PipeGameTestSupport.placePipe(helper, TRANSIT, BCTransportPipes.cobbleFluid);
        TileTank firstTarget = placeTank(helper, TARGET);
        final TileTank[] target = { firstTarget };
        require(helper, source.tank.fill(new FluidStack(Fluids.WATER, 1500), FluidAction.EXECUTE) == 1500,
            "failed to seed fluid source before saving the real receiver");

        for (int tick = 25; tick <= 275; tick += 50) {
            helper.runAfterDelay(tick, () -> pulse(helper, wood, transit, 250));
        }
        helper.runAfterDelay(465, () -> {
            require(helper, source.tank.isEmpty() && firstTarget.tank.getFluidAmount() == 1500
                && pipeVolume(wood) + pipeVolume(transit) == 0,
                "first fluid production cycle did not finish before Block Entity snapshot");
            CompoundTag stored = GameTestCompat.saveBlockEntity(firstTarget, helper);
            require(helper, !stored.isEmpty(), "receiver Block Entity saved no persistent fluid state");
            stored.putString("bcce_future_addon_ignored_field", "unknown data must not corrupt water");
            // Replace the test fixture's Block Entity and reconstruct it from persisted bytes.
            // A real server restart is exercised by the separate client/server integration harness.
            helper.setBlock(TARGET, Blocks.AIR.defaultBlockState());
            target[0] = placeTank(helper, TARGET);
            GameTestCompat.loadBlockEntity(target[0], stored, helper);
        });
        helper.runAfterDelay(510, () -> {
            require(helper, target[0].tank.getFluidAmount() == 1500
                && target[0].tank.getFluid().getFluid() == Fluids.WATER,
                "save/restore changed a real tank's committed water volume or variant");
            require(helper, source.tank.fill(new FluidStack(Fluids.WATER, 500), FluidAction.EXECUTE) == 500,
                "source rejected the second delivery cycle after output Block Entity restoration");
        });
        helper.runAfterDelay(535, () -> pulse(helper, wood, transit, 250));
        helper.runAfterDelay(585, () -> pulse(helper, wood, transit, 250));
        helper.runAfterDelay(885, () -> {
            int stored = source.tank.getFluidAmount() + target[0].tank.getFluidAmount()
                + pipeVolume(wood) + pipeVolume(transit);
            require(helper, target[0].tank.getFluidAmount() == 2000
                && target[0].tank.getFluid().getFluid() == Fluids.WATER,
                "restored receiver rejected new real deliveries or transmuted fluid variant");
            require(helper, source.tank.isEmpty() && pipeVolume(wood) + pipeVolume(transit) == 0,
                "restored receiver's second production cycle left a phantom liquid volume");
            require(helper, stored == 2000,
                "fluid network minted or lost water across Block Entity persistence: " + stored);
            helper.succeed();
        });
    }

    private static void pulse(GameTestHelper helper, TilePipeHolder wood, TilePipeHolder transit, int amount) {
        require(helper, wood.getPipe().isConnected(Direction.WEST)
            && wood.getPipe().isConnected(Direction.EAST)
            && transit.getPipe().isConnected(Direction.EAST),
            "real fluid network disconnected after its output tank was restored");
        PipeBehaviourWood extractor = (PipeBehaviourWood) wood.getPipe().getBehaviour();
        require(helper, extractor.getCurrentDir() == Direction.WEST,
            "wooden fluid extractor no longer faces the source tank");
        long offered = BCTransportConfig.mjPerMillibucket * amount;
        extractor.receivePower(offered, FluidAction.EXECUTE);
    }

    private static int pipeVolume(TilePipeHolder holder) {
        CompoundTag tag = ((PipeFlowFluids) holder.getPipe().getFlow()).writeToNbt();
        int total = 0;
        for (EnumPipePart part : EnumPipePart.VALUES) {
            total += Math.max(0, GameTestCompat.readInt(
                tag.getCompound("tank[" + part.getIndex() + "]"), "capacity"));
        }
        return total;
    }

    private static TileTank placeTank(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, BCFactoryBlocks.TANK_BLOCK.get().defaultBlockState());
        Object tile = GameTestCompat.getBlockEntity(helper, pos);
        if (!(tile instanceof TileTank tank)) {
            helper.fail("missing real Tank Block Entity at " + pos);
            throw new IllegalStateException("missing tank");
        }
        return tank;
    }

    private static void require(GameTestHelper helper, boolean ok, String message) {
        if (!ok) helper.fail(message);
    }
}
