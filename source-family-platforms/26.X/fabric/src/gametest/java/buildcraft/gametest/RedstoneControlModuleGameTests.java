package buildcraft.gametest;

import java.util.BitSet;
import java.util.Date;
import java.util.UUID;

import buildcraft.api.v2.energy.MjAmount;
import buildcraft.builders.BCBuildersBlocks;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.snapshot.GlobalSavedDataSnapshots;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.snapshot.Template;
import buildcraft.builders.tile.TileBuilder;
import buildcraft.core.BCCoreItems;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.lib.BCLib;
import buildcraft.lib.block.BlockBCBase_Neptune;
import buildcraft.lib.block.RedstoneModuleState;
import buildcraft.lib.internal.enums.EnumSnapshotType;
import buildcraft.lib.tile.TileBC_Neptune;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import buildcraft.lib.compat.neoforge263.fluids.IFluidHandler.FluidAction;
/** Exercises the real module storage, drops and powered construction without changing vanilla redstone. */
public final class RedstoneControlModuleGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final UUID AUTHOR = UUID.fromString("b222bbc4-6b3d-45b2-b8ea-cd0052ec0081");
    private RedstoneControlModuleGameTests() { }

    public static void builderRequiresActualNeighborSignalWhenModuleInstalled(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 3);
        helper.setBlock(pos, BCBuildersBlocks.BUILDER.get().defaultBlockState()
            .setValue(BlockBCBase_Neptune.PROP_FACING, Direction.WEST));
        BlockEntity blockEntity = GameTestCompat.getBlockEntity(helper, pos);
        require(helper, blockEntity instanceof TileBuilder, "expected a Builder block entity");
        TileBuilder builder = (TileBuilder) blockEntity;
        builder.onPlacedBy(buildcraft.lib.misc.FakePlayerProvider.INSTANCE.getFakePlayer(
            helper.getLevel(), new com.mojang.authlib.GameProfile(AUTHOR, "RCMGameTest"),
            helper.absolutePos(pos)), ItemStack.EMPTY);

        Template template = new Template();
        template.size = new BlockPos(2, 1, 1);
        template.offset = BlockPos.ZERO;
        template.facing = Direction.WEST;
        template.data = new BitSet(2);
        template.data.set(0, 2);
        template.computeKey();
        GlobalSavedDataSnapshots.cacheServerSnapshot(helper.getLevel(), template);
        Snapshot.Header header = new Snapshot.Header(template.key, AUTHOR, new Date(0),
            "Redstone Module Test", false, false, true);
        ItemStack item = ItemSnapshot.getUsed(EnumSnapshotType.TEMPLATE, header);
        builder.invSnapshot.setStackInSlot(0, item.copy());
        require(helper, builder.getTemplateBuildingInfo() != null, "failed to install test template");
        BlockPos[] targets = new BlockPos[2];
        for (int i = 0; i < targets.length; i++) {
            BlockPos worldPos = builder.getTemplateBuildingInfo().toWorld(new BlockPos(i, 0, 0));
            targets[i] = worldPos.subtract(helper.absolutePos(BlockPos.ZERO));
            helper.setBlock(targets[i], Blocks.AIR.defaultBlockState());
        }
        builder.invResources.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 2));
        builder.getBattery().addPowerChecking(1000L * MjAmount.MICRO_MJ_PER_MJ,
            FluidAction.EXECUTE);
        require(helper, builder.canWorkWithRedstoneControl(), "unmodified machine must operate without a signal");
        require(helper, builder.installRedstoneControlModule(), "cannot install module on Builder");
        require(helper, !builder.canWorkWithRedstoneControl(), "module failed to gate the unpowered Builder");
        helper.runAfterDelay(100, () -> {
            require(helper, count(helper, targets) == 0, "module allowed construction without redstone");
            helper.setBlock(new BlockPos(1, 1, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());
            require(helper, builder.canWorkWithRedstoneControl(), "adjacent power did not enable module");
        });
        helper.runAfterDelay(340, () -> {
            require(helper, count(helper, targets) == 2, "powered module did not resume construction");
            require(helper, builder.hasRedstoneControlModule(), "construction consumed installed module");
            require(helper, builder.removeRedstoneControlModule(), "installed module could not be removed");
            helper.setBlock(new BlockPos(1, 1, 2), Blocks.AIR.defaultBlockState());
            require(helper, builder.canWorkWithRedstoneControl(), "module removal failed to restore normal behavior");
            helper.succeed();
        });
    }

    public static void everySupportedMachineReturnsExactlyOneInstalledModule(GameTestHelper helper) {
        Block[] blocks = new Block[] {
            BCBuildersBlocks.BUILDER.get(), BCBuildersBlocks.FILLER.get(), BCBuildersBlocks.QUARRY.get(),
            BCFactoryBlocks.MINING_WELL_BLOCK.get(), BCFactoryBlocks.PUMP_BLOCK.get()
        };
        for (int i = 0; i < blocks.length; i++) {
            BlockPos pos = new BlockPos(1 + i, 1, 1);
            helper.setBlock(pos, blocks[i].defaultBlockState());
            BlockEntity be = GameTestCompat.getBlockEntity(helper, pos);
            require(helper, be instanceof TileBC_Neptune, "missing BCCE machine: " + blocks[i]);
            TileBC_Neptune tile = (TileBC_Neptune) be;
            require(helper, tile.supportsRedstoneControlModule(), "module rejected " + blocks[i]);
            require(helper, tile.installRedstoneControlModule(), "module installation failed");
            require(helper, helper.getLevel().getBlockState(helper.absolutePos(pos))
                .getValue(RedstoneModuleState.INSTALLED), "installed module did not switch the machine model");
            require(helper, !tile.installRedstoneControlModule(), "duplicate module was accepted");
            NonNullList<ItemStack> drops = NonNullList.create();
            tile.addDrops(drops, 0);
            long modules = drops.stream().filter(stack -> stack.is(BCCoreItems.REDSTONE_CONTROL_MODULE.get()))
                .mapToInt(ItemStack::getCount).sum();
            require(helper, modules == 1, "machine did not return exactly one installed module");
            require(helper, tile.removeRedstoneControlModule(), "module extraction failed");
            require(helper, !helper.getLevel().getBlockState(helper.absolutePos(pos))
                .getValue(RedstoneModuleState.INSTALLED), "removed module did not restore the original model");
            drops.clear();
            tile.addDrops(drops, 0);
            require(helper, drops.stream().noneMatch(stack -> stack.is(BCCoreItems.REDSTONE_CONTROL_MODULE.get())),
                "removed module is still in machine drops");
        }
        helper.succeed();
    }

    private static int count(GameTestHelper helper, BlockPos[] targets) {
        int count = 0;
        for (BlockPos pos : targets) {
            if (helper.getLevel().getBlockState(helper.absolutePos(pos)).is(Blocks.COBBLESTONE)) count++;
        }
        return count;
    }

    private static void require(GameTestHelper helper, boolean ok, String message) {
        if (!ok) helper.fail(message);
    }
}
