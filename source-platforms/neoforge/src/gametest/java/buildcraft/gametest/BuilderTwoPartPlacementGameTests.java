package buildcraft.gametest;

import buildcraft.builders.internal.schematic.legacy.SchematicBlockContext;
import buildcraft.builders.snapshot.SchematicBlockDefault;
import buildcraft.lib.BCLib;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
//? if >=26.2 {
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
//? }
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real-world regression for the 1-bed-item / 2-block-position BuildCraft blueprint contract. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class BuilderTwoPartPlacementGameTests {
    private BuilderTwoPartPlacementGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE,
        timeoutTicks = 120)
    public static void twoWhiteBedsConsumeExactlyTwoItemsAndPlaceFourBlocks(GameTestHelper helper) {
        var level = helper.getLevel();
        Block bedBlock = whiteBedBlock();
        Item bedItem = whiteBedItem();
        if (bedBlock == Blocks.AIR || bedItem == Items.AIR) {
            helper.fail("White bed is missing from the block or item registry");
            return;
        }
        BlockState footState = bedBlock.defaultBlockState()
            .setValue(BlockStateProperties.BED_PART, BedPart.FOOT)
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        BlockState headState = footState.setValue(BlockStateProperties.BED_PART, BedPart.HEAD);
        int required = 0;
        for (int x : new int[] {2, 4}) {
            BlockPos footRel = new BlockPos(x, 2, 2);
            BlockPos headRel = footRel.north();
            helper.setBlock(footRel.below(), Blocks.STONE.defaultBlockState());
            helper.setBlock(headRel.below(), Blocks.STONE.defaultBlockState());
            BlockPos foot = helper.absolutePos(footRel);
            BlockPos head = helper.absolutePos(headRel);
            SchematicBlockDefault primary = schematic(level, foot, footState);
            SchematicBlockDefault secondary = schematic(level, head, headState);
            ItemStack cost = primary.computeRequiredItemsForPlacement(level).stream().findFirst()
                .orElse(ItemStack.EMPTY);
            if (!cost.is(bedItem) || cost.getCount() != 1
                    || !secondary.computeRequiredItemsForPlacement(level).isEmpty()) {
                helper.fail("A two-block bed must require one bed item at FOOT and none at HEAD");
                return;
            }
            required += cost.getCount();
            if (!secondary.getRequiredBlockOffsets().contains(BlockPos.ZERO.south())
                    || !primary.getRequiredBlockOffsets().contains(new BlockPos(0, -1, -1))) {
                helper.fail("Bed head/foot dependencies were not restored from BlockState");
                return;
            }
            if (!primary.build(level, foot, null)
                    || !level.getBlockState(foot).equals(footState)
                    || !level.getBlockState(head).equals(headState)) {
                helper.fail("Bed FOOT failed to place both halves as one operation");
                return;
            }
        }
        if (required != 2) {
            helper.fail("Two complete beds requested " + required + " items instead of 2");
            return;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE,
        timeoutTicks = 120)
    public static void occupiedBedHeadNeverGetsOverwritten(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos footRel = new BlockPos(3, 2, 3);
        BlockPos headRel = footRel.north();
        helper.setBlock(footRel.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(headRel.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(headRel, Blocks.DIAMOND_BLOCK.defaultBlockState());
        BlockPos foot = helper.absolutePos(footRel);
        Block bedBlock = whiteBedBlock();
        if (bedBlock == Blocks.AIR) {
            helper.fail("White bed is missing from the block registry");
            return;
        }
        BlockState footState = bedBlock.defaultBlockState()
            .setValue(BlockStateProperties.BED_PART, BedPart.FOOT)
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        SchematicBlockDefault primary = schematic(level, foot, footState);
        if (primary.canBuild(level, foot) || primary.build(level, foot, null)
                || !level.getBlockState(foot).isAir()
                || !level.getBlockState(helper.absolutePos(headRel)).is(Blocks.DIAMOND_BLOCK)) {
            helper.fail("A blocked bed head must prevent placement without modifying either cell");
            return;
        }
        helper.succeed();
    }

    private static Block whiteBedBlock() {
//? if >=26.2 {
        // 26.2+ retains the registry entry, but no longer exposes the static bed constant.
        return BuiltInRegistries.BLOCK.get(Identifier.parse("minecraft:white_bed"))
            .map(net.minecraft.core.Holder.Reference::value).orElse(Blocks.AIR);
//? } else {
        return Blocks.WHITE_BED;
//? }
    }

    private static Item whiteBedItem() {
//? if >=26.2 {
        return BuiltInRegistries.ITEM.get(Identifier.parse("minecraft:white_bed"))
            .map(net.minecraft.core.Holder.Reference::value).orElse(Items.AIR);
//? } else {
        return Items.WHITE_BED;
//? }
    }

    private static SchematicBlockDefault schematic(net.minecraft.world.level.Level level,
                                                    BlockPos pos, BlockState state) {
        SchematicBlockDefault result = new SchematicBlockDefault();
        result.init(new SchematicBlockContext(level, BlockPos.ZERO, pos, state, state.getBlock()));
        return result;
    }
}
