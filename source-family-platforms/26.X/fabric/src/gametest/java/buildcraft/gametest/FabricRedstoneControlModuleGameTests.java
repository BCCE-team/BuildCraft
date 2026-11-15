/* Copyright (c) 2026 BuildCraft Community Edition contributors. MPL 2.0. */
package buildcraft.gametest;

import buildcraft.builders.BCBuildersBlocks;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.core.BCCoreItems;
import buildcraft.lib.block.RedstoneModuleState;
import buildcraft.lib.tile.TileBC_Neptune;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Exercises native Fabric blockstate/BE upgrade wiring in the real server world. */
public final class FabricRedstoneControlModuleGameTests implements CustomTestMethodInvoker {
    @GameTest
    public void moduleInstallRemovalAndDropsOnAllFiveMachines(GameTestHelper helper) {
        BlockPos pos = new BlockPos(0, 0, 0);
        Block[] machines = {
            BCBuildersBlocks.BUILDER.get(), BCBuildersBlocks.FILLER.get(), BCBuildersBlocks.QUARRY.get(),
            BCFactoryBlocks.MINING_WELL_BLOCK.get(), BCFactoryBlocks.PUMP_BLOCK.get()
        };
        for (Block block : machines) {
            BlockState initial = block.defaultBlockState();
            require(initial.hasProperty(RedstoneModuleState.INSTALLED),
                "Missing redstone_module BlockState property: " + block);
            require(!initial.getValue(RedstoneModuleState.INSTALLED),
                "Fresh machine must render its normal model: " + block);
            helper.setBlock(pos, initial);
            BlockEntity blockEntity = helper.getLevel().getBlockEntity(helper.absolutePos(pos));
            require(blockEntity instanceof TileBC_Neptune,
                "Machine is missing native Fabric TileBC_Neptune: " + block);
            TileBC_Neptune tile = (TileBC_Neptune) blockEntity;
            require(tile.installRedstoneControlModule(), "Failed to install module into " + block);
            require(tile.hasRedstoneControlModule(), "Module slot did not retain upgrade: " + block);
            require(helper.getLevel().getBlockState(helper.absolutePos(pos))
                    .getValue(RedstoneModuleState.INSTALLED),
                "Installing module did not update the block model: " + block);
            NonNullList<ItemStack> drops = NonNullList.create();
            tile.addDrops(drops, 0);
            require(drops.stream().filter(stack -> stack.is(BCCoreItems.REDSTONE_CONTROL_MODULE.get()))
                    .mapToInt(ItemStack::getCount).sum() == 1,
                "Breaking machine would lose or duplicate installed module: " + block);
            require(tile.removeRedstoneControlModule(), "Failed to remove module: " + block);
            require(!tile.hasRedstoneControlModule(), "Module persisted after removal: " + block);
            require(!helper.getLevel().getBlockState(helper.absolutePos(pos))
                    .getValue(RedstoneModuleState.INSTALLED),
                "Removing module did not restore the normal block model: " + block);
            helper.setBlock(pos, Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    private static void require(boolean valid, String reason) {
        if (!valid) throw new AssertionError(reason);
    }

    @Override
    public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
        method.invoke(this, helper);
    }
}
