package buildcraft.gametest;

import buildcraft.builders.BCBuildersBlocks;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.core.BCCoreBlocks;
import buildcraft.lib.BCLib;
import buildcraft.lib.block.BlockBCBase_Neptune;
import net.minecraft.core.BlockPos;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Ensures new and future BuildCraft blocks do not regress to non-waterloggable solids. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class WaterloggingGameTests {
    private WaterloggingGameTests() { }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 100)
    public static void everyBuildCraftBlockExceptFluidsExposesWaterlogging(GameTestHelper helper) {
        int checked = 0;
        for (Block block : ForgeRegistries.BLOCKS) {
            String key = ForgeRegistries.BLOCKS.getKey(block).toString();
            if (!key.startsWith("buildcraft") || block instanceof LiquidBlock) {
                continue;
            }
            BlockState dry = block.defaultBlockState();
            if (!(block instanceof SimpleWaterloggedBlock)
                    || !dry.hasProperty(BlockStateProperties.WATERLOGGED)
                    || dry.getValue(BlockStateProperties.WATERLOGGED)) {
                helper.fail("Missing dry-by-default waterlogging state: " + key);
                return;
            }
            BlockState wet = dry.setValue(BlockStateProperties.WATERLOGGED, true);
            if (wet.getBlock() != block || wet.getFluidState().getType() != Fluids.WATER) {
                helper.fail("Waterlogging did not expose a source of water: " + key);
                return;
            }
            checked++;
        }
        if (checked < 15) {
            helper.fail("Too few BuildCraft blocks were checked: " + checked);
            return;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 100)
    public static void frameAndTankRetainWaterWithoutLosingBlockIdentity(GameTestHelper helper) {
        checkWaterlogged(helper, new BlockPos(2, 1, 2), BCBuildersBlocks.FRAME.get());
        checkWaterlogged(helper, new BlockPos(4, 1, 2), BCFactoryBlocks.TANK_BLOCK.get());
        helper.succeed();
    }

    /** A dry machine must not silently act as a water source when placed from its default state. */
    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 100)
    public static void defaultMachineStatesDoNotCreateWater(GameTestHelper helper) {
        checkDry(helper, new BlockPos(2, 1, 2), BCCoreBlocks.ENGINE_BC8.get());
        checkDry(helper, new BlockPos(4, 1, 2), BCFactoryBlocks.TANK_BLOCK.get());
        checkDry(helper, new BlockPos(6, 1, 2), BCBuildersBlocks.FRAME.get());
        helper.succeed();
    }

    private static void checkDry(GameTestHelper helper, BlockPos pos, Block block) {
        BlockState dry = block.defaultBlockState();
        if (dry.getValue(BlockStateProperties.WATERLOGGED) || !dry.getFluidState().isEmpty()) {
            helper.fail("Default block state creates water: " + ForgeRegistries.BLOCKS.getKey(block));
            return;
        }
        helper.setBlock(pos, dry);
        BlockState stored = helper.getLevel().getBlockState(helper.absolutePos(pos));
        if (stored.getBlock() != block || !stored.getFluidState().isEmpty()) {
            helper.fail("Dry machine introduced water in air: " + ForgeRegistries.BLOCKS.getKey(block));
        }
    }

    private static void checkWaterlogged(GameTestHelper helper, BlockPos pos, Block block) {
        BlockState wet = block.defaultBlockState().setValue(BlockBCBase_Neptune.WATERLOGGED, true);
        helper.setBlock(pos, wet);
        BlockState stored = helper.getLevel().getBlockState(helper.absolutePos(pos));
        if (stored.getBlock() != block || !stored.getValue(BlockBCBase_Neptune.WATERLOGGED)
                || stored.getFluidState().getType() != Fluids.WATER) {
            helper.fail("Lost water or block identity at " + pos);
        }
    }
}
