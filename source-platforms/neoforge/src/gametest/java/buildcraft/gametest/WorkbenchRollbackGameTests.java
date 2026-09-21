package buildcraft.gametest;

import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.tile.TileAutoWorkbenchItems;
import buildcraft.lib.BCLib;
import buildcraft.lib.tile.craft.WorkbenchCrafting;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class WorkbenchRollbackGameTests {
    private static final String EMPTY_TEMPLATE = "empty3x3x3";

    private WorkbenchRollbackGameTests() {
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void failedGridClearNeverOverwritesTransientItems(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, BCFactoryBlocks.AUTO_BENCH_BLOCK.get().defaultBlockState());
        if (!(GameTestCompat.getBlockEntity(helper, pos) instanceof TileAutoWorkbenchItems tile)) {
            helper.fail("Auto Workbench block entity was not created");
            return;
        }

        WorkbenchCrafting crafting = tile.getWorkbenchCrafting();
        tile.invBlueprint.setStackInSlot(0, new ItemStack(Items.OAK_LOG));
        for (int slot = 0; slot < tile.invMaterials.getSlots(); slot++) {
            tile.invMaterialFilter.setStackInSlot(slot, new ItemStack(Items.OAK_LOG));
            tile.invMaterials.setStackInSlot(slot, new ItemStack(Items.OAK_LOG, 64));
        }

        crafting.tick();
        require(helper, crafting.canCraft(), "test setup did not produce a craftable oak-log recipe");

        crafting.setItem(0, new ItemStack(Items.DIAMOND));
        boolean crafted = crafting.craft();

        require(helper, !crafted, "craft unexpectedly committed after transient-grid rollback failed");
        ItemStack transientStack = crafting.getItem(0);
        require(helper, transientStack.is(Items.DIAMOND) && transientStack.getCount() == 1,
            "failed clearInventory() allowed craftExact() to overwrite the transient crafting slot");
        require(helper, tile.invResult.getStackInSlot(0).isEmpty(),
            "failed rollback produced an output item");
        helper.succeed();
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }
}
