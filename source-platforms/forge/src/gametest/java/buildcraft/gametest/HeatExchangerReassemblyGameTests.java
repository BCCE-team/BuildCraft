package buildcraft.gametest;

import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.block.BlockHeatExchange;
import buildcraft.factory.block.BlockHeatExchange.EnumExchangePart;
import buildcraft.factory.tile.TileHeatExchange;
import buildcraft.lib.BCLib;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Real-world placement and removal regression for a 3-5-section heat exchanger. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class HeatExchangerReassemblyGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final int Y = 1;
    private static final int Z = 3;

    private HeatExchangerReassemblyGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 180)
    public static void replacingMiddleRestoresChainWithoutTouchingItsEnds(GameTestHelper helper) {
        place(helper, 1);
        place(helper, 2);
        place(helper, 3);
        helper.runAfterDelay(30, () -> {
            assertChain(helper, 1, 3);
            helper.setBlock(at(2), Blocks.AIR.defaultBlockState());
        });
        helper.runAfterDelay(55, () -> {
            assertUnformed(helper, 1);
            assertUnformed(helper, 3);
            place(helper, 2);
        });
        helper.runAfterDelay(85, () -> {
            assertChain(helper, 1, 3);
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 180)
    public static void replacingEndRestoresChainWithoutTouchingMiddleOrStart(GameTestHelper helper) {
        place(helper, 1);
        place(helper, 2);
        place(helper, 3);
        helper.runAfterDelay(30, () -> {
            assertChain(helper, 1, 3);
            helper.setBlock(at(1), Blocks.AIR.defaultBlockState());
        });
        helper.runAfterDelay(55, () -> {
            assertUnformed(helper, 2);
            assertUnformed(helper, 3);
            place(helper, 1);
        });
        helper.runAfterDelay(85, () -> {
            assertChain(helper, 1, 3);
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 180)
    public static void replacingStartRestoresChainWithoutTouchingMiddleOrEnd(GameTestHelper helper) {
        place(helper, 1);
        place(helper, 2);
        place(helper, 3);
        helper.runAfterDelay(30, () -> {
            assertChain(helper, 1, 3);
            helper.setBlock(at(3), Blocks.AIR.defaultBlockState());
        });
        helper.runAfterDelay(55, () -> {
            assertUnformed(helper, 1);
            assertUnformed(helper, 2);
            place(helper, 3);
        });
        helper.runAfterDelay(85, () -> {
            assertChain(helper, 1, 3);
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 180)
    public static void shorteningFourSectionsRetainsValidThreeSectionStructure(GameTestHelper helper) {
        for (int x = 1; x <= 4; x++) place(helper, x);
        helper.runAfterDelay(30, () -> {
            assertChain(helper, 1, 4);
            helper.setBlock(at(1), Blocks.AIR.defaultBlockState());
        });
        helper.runAfterDelay(65, () -> {
            assertChain(helper, 2, 4);
            helper.succeed();
        });
    }

    private static BlockPos at(int x) { return new BlockPos(x, Y, Z); }

    private static void place(GameTestHelper helper, int x) {
        helper.setBlock(at(x), BCFactoryBlocks.HEATEXCHANGE_BLOCK.get().defaultBlockState()
            .setValue(BlockHeatExchange.PROP_FACING, Direction.NORTH));
    }

    private static TileHeatExchange tile(GameTestHelper helper, int x) {
        Object be = helper.getLevel().getBlockEntity(helper.absolutePos(at(x)));
        if (be instanceof TileHeatExchange heat) return heat;
        helper.fail("Missing Heat Exchanger BE at x=" + x);
        throw new IllegalStateException("Missing Heat Exchanger BE at x=" + x);
    }

    private static void assertUnformed(GameTestHelper helper, int x) {
        TileHeatExchange heat = tile(helper, x);
        BlockState state = helper.getLevel().getBlockState(helper.absolutePos(at(x)));
        require(helper, heat.getSection() == null && state.getValue(BlockHeatExchange.PROP_PART) == EnumExchangePart.MIDDLE,
            "Part " + x + " retained an endpoint after its chain was broken");
    }

    private static void assertChain(GameTestHelper helper, int minX, int maxX) {
        require(helper, tile(helper, maxX).isStart(), "Chain start not at x=" + maxX);
        require(helper, tile(helper, minX).isEnd(), "Chain end not at x=" + minX);
        for (int x = minX; x <= maxX; x++) {
            EnumExchangePart expected = x == maxX ? EnumExchangePart.START
                : x == minX ? EnumExchangePart.END : EnumExchangePart.MIDDLE;
            BlockState state = helper.getLevel().getBlockState(helper.absolutePos(at(x)));
            require(helper, state.getValue(BlockHeatExchange.PROP_PART) == expected,
                "Wrong visual part at x=" + x + ": expected " + expected);
            require(helper, state.getValue(BlockHeatExchange.PROP_CONNECTED_LEFT) == (x < maxX)
                && state.getValue(BlockHeatExchange.PROP_CONNECTED_RIGHT) == (x > minX),
                "Physical connection flags not updated at x=" + x);
        }
    }

    private static void require(GameTestHelper helper, boolean ok, String message) {
        if (!ok) helper.fail(message);
    }
}
