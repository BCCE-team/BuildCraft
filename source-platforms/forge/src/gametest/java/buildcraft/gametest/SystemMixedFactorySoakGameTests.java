package buildcraft.gametest;

import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.tile.TileAutoWorkbenchItems;
import buildcraft.factory.tile.TileTank;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.transport.BCTransportConfig;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.pipe.behaviour.PipeBehaviourWood;
import buildcraft.transport.pipe.flow.PipeFlowFluids;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.tile.TilePipeHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** IT12: concurrent, tick-driven crafting, MJ extraction, cargo routing and fluid transfer. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemMixedFactorySoakGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final int BUCKET = FluidType.BUCKET_VOLUME;

    private SystemMixedFactorySoakGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 1240)
    public static void concurrentProductionFluidTransferAndPipeReplacementStayConservative(GameTestHelper helper) {
        Workshop oak = new Workshop(helper, 1, Items.OAK_LOG, Items.OAK_PLANKS);
        Workshop spruce = new Workshop(helper, 5, Items.SPRUCE_LOG, Items.SPRUCE_PLANKS);
        TileTank waterSource = tank(helper, new BlockPos(1, 1, 3));
        TilePipeHolder waterWood = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 3), BCTransportPipes.woodFluid);
        TilePipeHolder waterTransit = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.cobbleFluid);
        TileTank waterSink = tank(helper, new BlockPos(4, 1, 3));
        require(helper, waterSource.tank.fill(new FluidStack(Fluids.WATER, BUCKET), FluidAction.EXECUTE) == BUCKET,
            "mixed-factory water input rejected the test bucket");

        // Distinct networks work during the SAME server ticks. MJ is offered at
        // real wooden pipe ports; materials/fluids must move via BCCE gameplay.
        for (int tick = 35; tick <= 655; tick += 70) {
            helper.runAfterDelay(tick, () -> {
                require(helper, waterWood.getPipe().isConnected(Direction.WEST)
                    && waterWood.getPipe().isConnected(Direction.EAST)
                    && waterTransit.getPipe().isConnected(Direction.EAST),
                    "water lane lost its connected real pipe/tank topology");
                PipeBehaviourWood behaviour = (PipeBehaviourWood) waterWood.getPipe().getBehaviour();
                require(helper, behaviour.getCurrentDir() == Direction.WEST,
                    "water extractor chose the wrong source");
                behaviour.receivePower(BCTransportConfig.mjPerMillibucket * 250L, FluidAction.EXECUTE);
            });
        }

        helper.runAfterDelay(270, () -> {
            oak.extractFour(helper);
            spruce.extractFour(helper);
        });
        helper.runAfterDelay(520, () -> {
            require(helper, oak.delivered() == 4 && spruce.delivered() == 4,
                "concurrent crafting branches failed to deliver their first real outputs");
            require(helper, !oak.inFlight() && !spruce.inFlight(),
                "test cannot safely reconfigure a branch with unsent cargo");
            require(helper, waterSource.tank.getFluidAmount() + waterSink.tank.getFluidAmount()
                + fluidInPipe(waterWood) + fluidInPipe(waterTransit) == BUCKET,
                "concurrent factory changed its water volume while goods were in transit");
            // A real transport section is removed AFTER its cargo reaches the
            // warehouse, while both the other crafting line and water continue.
            helper.setBlock(new BlockPos(3, 1, 1), Blocks.AIR.defaultBlockState());
            oak.bench.invMaterials.setStackInSlot(0, new ItemStack(Items.OAK_LOG));
        });
        helper.runAfterDelay(575, () -> {
            oak.transit = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 1), BCTransportPipes.cobbleItem);
        });
        helper.runAfterDelay(875, () -> {
            require(helper, oak.wood.getPipe().isConnected(Direction.EAST)
                && oak.transit.getPipe().isConnected(Direction.EAST),
                "repaired live production branch never reconnected to the warehouse");
            oak.extractFour(helper);
            // The idle branch must remain powered off despite unrelated
            // construction/repair operations in the neighboring lane.
            require(helper, spruce.delivered() == 4 && !spruce.inFlight(),
                "other active production branch was affected by an unrelated repair");
        });
        helper.runAfterDelay(1170, () -> {
            require(helper, oak.delivered() == 8 && spruce.delivered() == 4,
                "mixed factory lost or duplicated the 12 produced planks");
            require(helper, PipeGameTestSupport.countItem(oak.output, Items.SPRUCE_PLANKS) == 0
                && PipeGameTestSupport.countItem(spruce.output, Items.OAK_PLANKS) == 0,
                "independent production branches cross-contaminated the warehouses");
            require(helper, oak.bench.invMaterials.getStackInSlot(0).isEmpty()
                && spruce.bench.invMaterials.getStackInSlot(0).isEmpty()
                && oak.bench.invResult.getStackInSlot(0).isEmpty()
                && spruce.bench.invResult.getStackInSlot(0).isEmpty(),
                "unfinished input/output remained after the complete production cycle");
            require(helper, !oak.inFlight() && !spruce.inFlight(),
                "mixed factory left ghost cargo in its item pipes");
            require(helper, waterSource.tank.isEmpty()
                && waterSink.tank.getFluidAmount() == BUCKET
                && waterSink.tank.getFluid().getFluid() == Fluids.WATER,
                "concurrent water lane did not complete the intended tank-to-tank transfer");
            require(helper, fluidInPipe(waterWood) == 0 && fluidInPipe(waterTransit) == 0,
                "mixed factory left hidden water in finished fluid pipes");
            SystemTrace.emit("mixed_factory_recovery",
                "oakDelivery", Integer.toString(oak.delivered()),
                "spruceDelivery", Integer.toString(spruce.delivered()),
                "waterDestination", Integer.toString(waterSink.tank.getFluidAmount()),
                "waterSource", Integer.toString(waterSource.tank.getFluidAmount()),
                "inFlight", Boolean.toString(oak.inFlight() || spruce.inFlight()),
                "waterInPipes", Integer.toString(fluidInPipe(waterWood) + fluidInPipe(waterTransit)));
            helper.succeed();
        });
    }

    private static final class Workshop {
        final Item product;
        final TileAutoWorkbenchItems bench;
        final TilePipeHolder wood;
        TilePipeHolder transit;
        final ChestBlockEntity output;

        Workshop(GameTestHelper helper, int z, Item log, Item planks) {
            product = planks;
            BlockPos machinePos = new BlockPos(1, 1, z);
            helper.setBlock(machinePos, BCFactoryBlocks.AUTO_BENCH_BLOCK.get().defaultBlockState());
            bench = tile(helper, machinePos, TileAutoWorkbenchItems.class);
            wood = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, z), BCTransportPipes.woodItem);
            transit = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, z), BCTransportPipes.cobbleItem);
            BlockPos chestPos = new BlockPos(4, 1, z);
            helper.setBlock(chestPos, Blocks.CHEST.defaultBlockState());
            output = tile(helper, chestPos, ChestBlockEntity.class);
            bench.invBlueprint.setStackInSlot(0, new ItemStack(log));
            bench.invMaterialFilter.setStackInSlot(0, new ItemStack(log));
            bench.invMaterials.setStackInSlot(0, new ItemStack(log));
        }

        void extractFour(GameTestHelper helper) {
            ItemStack crafted = bench.invResult.getStackInSlot(0);
            require(helper, crafted.is(product) && crafted.getCount() == 4,
                "Auto Workbench did not produce the expected four craft results");
            require(helper, wood.getPipe().isConnected(Direction.WEST)
                && wood.getPipe().isConnected(Direction.EAST),
                "Auto Workbench extraction endpoint is disconnected");
            PipeBehaviourWood extractor = (PipeBehaviourWood) wood.getPipe().getBehaviour();
            require(helper, extractor.getCurrentDir() == Direction.WEST,
                "wooden item pipe is not extracting from its Auto Workbench");
            long mj = BCTransportConfig.mjPerItem * 4L;
            require(helper, extractor.receivePower(mj, FluidAction.EXECUTE) < mj,
                "Auto Workbench output rejected its extraction pulse");
        }

        int delivered() { return PipeGameTestSupport.countItem(output, product); }
        boolean inFlight() {
            return ((PipeFlowItems) wood.getPipe().getFlow()).doesContainItems()
                || ((PipeFlowItems) transit.getPipe().getFlow()).doesContainItems();
        }
    }

    private static int fluidInPipe(TilePipeHolder holder) {
        CompoundTag nbt = ((PipeFlowFluids) holder.getPipe().getFlow()).writeToNbt();
        int stored = 0;
        for (EnumPipePart part : EnumPipePart.VALUES) {
            stored += Math.max(0, GameTestCompat.readInt(
                nbt.getCompound("tank[" + part.getIndex() + "]"), "capacity"));
        }
        return stored;
    }

    private static TileTank tank(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, BCFactoryBlocks.TANK_BLOCK.get().defaultBlockState());
        return tile(helper, pos, TileTank.class);
    }

    private static <T> T tile(GameTestHelper helper, BlockPos pos, Class<T> type) {
        Object blockEntity = GameTestCompat.getBlockEntity(helper, pos);
        if (!type.isInstance(blockEntity)) {
            helper.fail("Missing real " + type.getSimpleName() + " at " + pos);
            throw new IllegalStateException("missing mixed factory fixture");
        }
        return type.cast(blockEntity);
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}
