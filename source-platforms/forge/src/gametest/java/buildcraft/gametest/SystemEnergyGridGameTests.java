package buildcraft.gametest;

import buildcraft.api.v2.OperationMode;
import buildcraft.core.BCCoreBlocks;
import buildcraft.energy.BCEnergyBlocks;
import buildcraft.energy.tile.TileDynamoMJ;
import buildcraft.energy.tile.TileEngineFE;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.enums.EnumEngineType;
import buildcraft.lib.internal.properties.BuildCraftProperties;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.pipe.flow.PipeFlowPower;
import buildcraft.transport.tile.TilePipeHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Live FE Engine -> MJ pipes -> MJ Dynamo conversion, recovery and multiple consumers. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemEnergyGridGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final BlockPos ENGINE = new BlockPos(1, 1, 3);
    private static final BlockPos WOOD = new BlockPos(2, 1, 3);
    private static final BlockPos LINE = new BlockPos(3, 1, 3);
    private static final BlockPos FIRST = new BlockPos(4, 1, 3);
    private static final BlockPos SECOND = new BlockPos(3, 1, 4);
    // Both pipelines run for more than a thousand ticks. The previous 3k FE
    // fixture ran out before the reconnection checks, causing a false failure.
    private static final int INPUT_FE = 10_000;

    private SystemEnergyGridGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 1570)
    public static void liveFeToMjToFeNetworkRecoversFromBrokenPowerPipe(GameTestHelper helper) {
        PowerRig rig = new PowerRig(helper, false);
        int[] beforeRestore = { -1 };
        // The FE Engine stores only 10k FE. A long-lived network must receive
        // repeated real FE port transfers, not assume the initial buffer is infinite.
        // Keep a ledger of every inserted unit for conservation assertions.
        for (int tick = 505; tick <= 1305; tick += 20) {
            helper.runAfterDelay(tick, () -> rig.maintainFuel(helper));
        }
        helper.runAfterDelay(785, () -> rig.maintainFuel(helper));
        helper.runAfterDelay(1305, () -> rig.maintainFuel(helper));
        helper.runAfterDelay(65, () -> {
            require(helper, rig.engine.isRedstonePowered && rig.first.isRedstonePowered,
                "real engines did not detect power from adjacent redstone blocks");
            require(helper, rig.engine.getCurrentFacing() == Direction.EAST,
                "FE Engine did not face the actual MJ power pipe");
            require(helper, rig.wood.getPipe().isConnected(Direction.EAST)
                && rig.line.getPipe().isConnected(Direction.EAST),
                "placed MJ transport network is disconnected");
        });
        helper.runAfterDelay(490, () -> {
            require(helper, rig.first.getCurrentFe() > 0,
                "MJ Dynamo never received real energy from FE Engine through two pipes");
            require(helper, rig.engine.getCurrentFe() < INPUT_FE,
                "FE Engine did not consume FE to power the MJ pipe chain");
            rig.assertBounded(helper);
            helper.setBlock(LINE, Blocks.AIR.defaultBlockState());
            // An FE consumer can drain the first Dynamo during the outage, so
            // ensure its output buffer has actual headroom for the recovery check.
            require(helper, rig.drainFe(rig.first) > 0, "first Dynamo had no FE for its output consumer");
            rig.refuel(helper);
        });
        helper.runAfterDelay(790, () -> {
            require(helper, rig.engine.getCurrentFe() > 0,
                "fixture exhausted the fuel before network reconstruction");
            beforeRestore[0] = rig.first.getCurrentFe();
            rig.line = PipeGameTestSupport.placePipe(helper, LINE, BCTransportPipes.cobblePower);
        });
        helper.runAfterDelay(1310, () -> {
            require(helper, rig.first.getCurrentFe() > beforeRestore[0],
                "MJ/FE production failed to restart after live pipe reconstruction");
            rig.assertBounded(helper);
            helper.setBlock(ENGINE.below(), Blocks.AIR.defaultBlockState());
        });
        helper.runAfterDelay(1510, () -> {
            require(helper, !rig.engine.isRedstonePowered,
                "FE Engine stayed powered after its physical redstone signal was removed");
            rig.assertBounded(helper);
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 1470)
    public static void twoRealMjDynamoConsumersSharePowerAndSurvivorContinuesWhenPeerRemoved(GameTestHelper helper) {
        PowerRig rig = new PowerRig(helper, true);
        int[] survivorBefore = { -1 };
        helper.runAfterDelay(640, () -> {
            require(helper, rig.first.getCurrentFe() > 0 && rig.second.getCurrentFe() > 0,
                "one MJ Dynamo starved while two connected receivers requested power");
            rig.assertBounded(helper);
            // A physical FE consumer drains the surviving Dynamo's output so a
            // full storage buffer cannot masquerade as a stalled MJ network.
            require(helper, rig.drainFe(rig.second) > 0, "second Dynamo had no FE to deliver to its consumer");
            survivorBefore[0] = rig.second.getCurrentFe();
            helper.setBlock(FIRST, Blocks.AIR.defaultBlockState());
            rig.refuel(helper);
            // The first Dynamo's stored FE is intentionally destroyed with the machine;
            // the surviving branch must not duplicate that resource.
        });
        helper.runAfterDelay(1260, () -> {
            require(helper, rig.second.getCurrentFe() > survivorBefore[0],
                "removing one of two real MJ consumers stalled the surviving power branch");
            require(helper, rig.engine.getCurrentFe() + rig.second.getCurrentFe()
                + rig.exportedFe <= rig.totalInputFe,
                "power network duplicated FE after an MJ endpoint was removed");
            helper.succeed();
        });
    }

    private static final class PowerRig {
        private final TileEngineFE engine;
        private final TilePipeHolder wood;
        private TilePipeHolder line;
        private final TileDynamoMJ first;
        private final TileDynamoMJ second;
        private final boolean hasSecond;
        private long totalInputFe = INPUT_FE;
        private long exportedFe;

        PowerRig(GameTestHelper helper, boolean twoReceivers) {
            hasSecond = twoReceivers;
            helper.setBlock(ENGINE, BCCoreBlocks.ENGINE_BC8.get().defaultBlockState()
                .setValue(BuildCraftProperties.ENGINE_TYPE, EnumEngineType.FE));
            engine = tile(helper, ENGINE, TileEngineFE.class);
            wood = PipeGameTestSupport.placePipe(helper, WOOD, BCTransportPipes.woodPower);
            line = PipeGameTestSupport.placePipe(helper, LINE, BCTransportPipes.cobblePower);
            helper.setBlock(FIRST, BCEnergyBlocks.DYNAMO_MJ.get().defaultBlockState());
            first = tile(helper, FIRST, TileDynamoMJ.class);
            if (twoReceivers) {
                helper.setBlock(SECOND, BCEnergyBlocks.DYNAMO_MJ.get().defaultBlockState());
                second = tile(helper, SECOND, TileDynamoMJ.class);
                helper.setBlock(SECOND.below(), Blocks.REDSTONE_BLOCK.defaultBlockState());
            } else second = null;
            helper.setBlock(FIRST.below(), Blocks.REDSTONE_BLOCK.defaultBlockState());
            helper.setBlock(ENGINE.below(), Blocks.REDSTONE_BLOCK.defaultBlockState());
            require(helper, engine.externalEnergyPort(Direction.NORTH).orElseThrow()
                .insert(INPUT_FE, OperationMode.EXECUTE) == INPUT_FE,
                "FE Engine refused initial real FE buffer");
            helper.runAfterDelay(15, () -> engine.preferDirectionOnPlacement(Direction.EAST));
        }

        void refuel(GameTestHelper helper) {
            long refill = INPUT_FE - engine.getCurrentFe();
            long inserted = engine.externalEnergyPort(Direction.NORTH).orElseThrow()
                .insert(refill, OperationMode.EXECUTE);
            require(helper, inserted == refill && inserted > 0,
                "FE Engine refused external FE refill while network is disconnected");
            totalInputFe += inserted;
        }

        void maintainFuel(GameTestHelper helper) {
            long missing = INPUT_FE - engine.getCurrentFe();
            require(helper, missing >= 0, "FE Engine exceeded its real buffer capacity");
            if (missing == 0) return;
            long inserted = engine.externalEnergyPort(Direction.NORTH).orElseThrow()
                .insert(missing, OperationMode.EXECUTE);
            require(helper, inserted == missing,
                "real FE input port refused the scheduled replenishment");
            totalInputFe += inserted;
        }

        long drainFe(TileDynamoMJ dynamo) {
            long extracted = dynamo.externalEnergyPort(dynamo.getCurrentDirection()).orElseThrow()
                .extract(dynamo.getCurrentFe(), OperationMode.EXECUTE);
            exportedFe += extracted;
            return extracted;
        }

        void assertBounded(GameTestHelper helper) {
            long dynamoFe = first.getCurrentFe() + (hasSecond ? second.getCurrentFe() : 0);
            require(helper, engine.getCurrentFe() >= 0 && dynamoFe >= 0,
                "energy conversion produced a negative FE buffer");
            require(helper, engine.getCurrentFe() + dynamoFe + exportedFe <= totalInputFe,
                "FE/MJ/FE live chain duplicated source FE");
            require(helper, engine.getEnergyStored() >= 0 && first.getMjStored() >= 0,
                "MJ buffers became negative during grid reconfiguration");
        }
    }

    private static <T> T tile(GameTestHelper helper, BlockPos relative, Class<T> type) {
        Object entity = GameTestCompat.getBlockEntity(helper, relative);
        if (!type.isInstance(entity)) {
            helper.fail("Missing " + type.getSimpleName() + " at " + relative);
            throw new IllegalStateException("missing Block Entity");
        }
        return type.cast(entity);
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}
