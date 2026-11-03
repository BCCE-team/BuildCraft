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
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;

/** IT11: live Create fluid endpoint, actual BuildCraft extraction and backpressure. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemCreateFluidInteropGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final int BUCKET = FluidType.BUCKET_VOLUME;
    private SystemCreateFluidInteropGameTests() {}

    /** Verifies an external Create source can be drained through a real powered BCCE line. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 900)
    public static void createTankSuppliesBCCEThroughPoweredWoodPipe(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            require(helper, !Boolean.getBoolean("bcce.gametest.require_create"),
                "Create compatibility profile silently ran without Create installed");
            helper.succeed();
            return;
        }
        BlockPos origin = new BlockPos(1, 1, 3);
        IFluidHandler external = createTank(helper, origin);
        int supplied = external.fill(new FluidStack(Fluids.WATER, BUCKET), FluidAction.EXECUTE);
        require(helper, supplied == BUCKET, "Create fluid handler rejected a real water bucket");
        TilePipeHolder wood = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 3), BCTransportPipes.woodFluid);
        TilePipeHolder transit = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.cobbleFluid);
        TileTank warehouse = tank(helper, new BlockPos(4, 1, 3));
        for (int tick = 30; tick <= 380; tick += 45) {
            helper.runAfterDelay(tick, () -> pulse(helper, wood, transit, 250));
        }
        helper.runAfterDelay(800, () -> {
            int source = external.getFluidInTank(0).getAmount();
            int destination = warehouse.tank.getFluidAmount();
            int transitVolume = buffered(wood) + buffered(transit);
            require(helper, source == 0 && destination == BUCKET && transitVolume == 0,
                "Create -> BCCE transfer did not complete: source=" + source
                + " destination=" + destination + " pipes=" + transitVolume);
            require(helper, warehouse.tank.getFluid().getFluid() == Fluids.WATER,
                "cross-mod extraction changed the transferred fluid type");
            helper.succeed();
        });
    }

    /** A full Create tank must not void BCCE fluid; draining it allows delivery to resume. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 1100)
    public static void saturatedCreateTankUnblocksWithoutFluidLoss(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            require(helper, !Boolean.getBoolean("bcce.gametest.require_create"),
                "Create compatibility profile silently ran without Create installed");
            helper.succeed();
            return;
        }
        TileTank source = tank(helper, new BlockPos(1, 1, 3));
        require(helper, source.tank.fill(new FluidStack(Fluids.WATER, BUCKET), FluidAction.EXECUTE) == BUCKET,
            "failed to seed real BCCE source tank");
        TilePipeHolder wood = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 3), BCTransportPipes.woodFluid);
        TilePipeHolder transit = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.cobbleFluid);
        IFluidHandler external = createTank(helper, new BlockPos(4, 1, 3));
        int capacity = external.getTankCapacity(0);
        require(helper, capacity >= BUCKET && capacity <= 1_000_000,
            "Create tank exposed implausible external capacity " + capacity);
        require(helper, external.fill(new FluidStack(Fluids.WATER, capacity), FluidAction.EXECUTE) == capacity,
            "failed to fill Create tank to the advertised capacity");
        for (int tick = 30; tick <= 920; tick += 45) {
            helper.runAfterDelay(tick, () -> pulse(helper, wood, transit, 250));
        }
        helper.runAfterDelay(340, () -> {
            require(helper, external.getFluidInTank(0).getAmount() == capacity,
                "full Create endpoint changed while no capacity was available");
            require(helper, source.tank.getFluidAmount() + buffered(wood) + buffered(transit) == BUCKET,
                "backpressure lost or duplicated water inside BuildCraft's line");
            FluidStack removed = external.drain(new FluidStack(Fluids.WATER, BUCKET / 2), FluidAction.EXECUTE);
            require(helper, removed.getAmount() == BUCKET / 2 && removed.getFluid() == Fluids.WATER,
                "Create external drain returned the wrong amount/type");
        });
        helper.runAfterDelay(1000, () -> {
            int inside = source.tank.getFluidAmount() + buffered(wood) + buffered(transit);
            require(helper, external.getFluidInTank(0).getAmount() == capacity,
                "Create tank failed to refill the freed capacity during live pipe operation");
            require(helper, inside == BUCKET / 2,
                "cross-mod fluid ledger changed after partial external drain: remaining=" + inside);
            require(helper, source.tank.getFluid().isEmpty() || source.tank.getFluid().getFluid() == Fluids.WATER,
                "backpressure transmuted remaining BuildCraft fluid");
            helper.succeed();
        });
    }

    private static IFluidHandler createTank(GameTestHelper helper, BlockPos relativePos) {
        // The Forge registry API is available on both supported legacy versions.
        // Resolve the optional mod's block without linking against Create classes.
        Block createBlock = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("create", "fluid_tank"));
        require(helper, createBlock != null && createBlock != Blocks.AIR,
            "Create is installed but create:fluid_tank is not registered");
        helper.setBlock(relativePos, createBlock.defaultBlockState());
        IFluidHandler handler = helper.getBlockEntity(relativePos).getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.WEST).orElse(null);
        require(helper, handler != null && handler.getTanks() > 0,
            "real Create fluid tank exposes no fluid handler to a neighboring BCCE pipe");
        return handler;
    }

    private static TileTank tank(GameTestHelper helper, BlockPos relativePos) {
        helper.setBlock(relativePos, BCFactoryBlocks.TANK_BLOCK.get().defaultBlockState());
        Object entity = helper.getBlockEntity(relativePos);
        if (!(entity instanceof TileTank tank)) {
            helper.fail("real BCCE tank BlockEntity was not created at " + relativePos);
            throw new IllegalStateException("missing BCCE tank");
        }
        return tank;
    }

    private static void pulse(GameTestHelper helper, TilePipeHolder wood, TilePipeHolder transit, int volume) {
        require(helper, wood.getPipe().isConnected(Direction.WEST)
            && wood.getPipe().isConnected(Direction.EAST)
            && transit.getPipe().isConnected(Direction.EAST),
            "Create/BCCE fluid network lacks a real source, pipe or destination connection");
        PipeBehaviourWood extractor = (PipeBehaviourWood) wood.getPipe().getBehaviour();
        require(helper, extractor.getCurrentDir() == Direction.WEST,
            "Create/BCCE wooden extractor faced away from its source");
        extractor.receivePower(BCTransportConfig.mjPerMillibucket * volume, FluidAction.EXECUTE);
    }

    private static int buffered(TilePipeHolder holder) {
        CompoundTag data = ((PipeFlowFluids) holder.getPipe().getFlow()).writeToNbt();
        int volume = 0;
        for (EnumPipePart part : EnumPipePart.VALUES) {
            volume += Math.max(0, GameTestCompat.readInt(
                data.getCompound("tank[" + part.getIndex() + "]"), "capacity"));
        }
        return volume;
    }

    private static void require(GameTestHelper helper, boolean condition, String error) {
        if (!condition) helper.fail(error);
    }
}
