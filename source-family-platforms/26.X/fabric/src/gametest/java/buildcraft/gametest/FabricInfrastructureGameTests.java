package buildcraft.gametest;

import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.tile.TileTank;
import buildcraft.lib.compat.transfer.TransferJournal;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.material.Fluids;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Production server boot, Fabric entrypoints, and world-backed GameTest smoke. */
public final class FabricInfrastructureGameTests implements CustomTestMethodInvoker {
    @GameTest
    public void fabricRuntimeHasBuildCraftAndWorld(GameTestHelper helper) {
        if (!FabricLoader.getInstance().isModLoaded("buildcraftlib")) {
            throw new AssertionError("BuildCraft Fabric entrypoint did not load");
        }
        helper.assertBlockPresent(Blocks.AIR, 0, 0, 0);
        helper.succeed();
    }

    @GameTest(structure = "buildcraftlib:empty3x3x3", maxTicks = 20)
    public void ownedTankFinalCommitDoesNotReenterTransaction(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, BCFactoryBlocks.TANK_BLOCK.get().defaultBlockState());
        if (!(GameTestCompat.getBlockEntity(helper, pos) instanceof TileTank tank)) {
            throw new AssertionError("Missing real Tank Block Entity");
        }
        var storage = FluidStorage.SIDED.find(helper.getLevel(), helper.absolutePos(pos), Direction.NORTH);
        if (storage == null) throw new AssertionError("Missing native Fabric Tank storage");
        var water = FluidVariant.of(Fluids.WATER);
        long bucket = FluidConstants.BUCKET;
        long quarter = bucket / 4;
        AtomicInteger notifications = new AtomicInteger();
        Runnable notification = () -> TransferJournal.notifyAfterCommit(notifications::incrementAndGet);

        // A root abort must restore the actual Tank and suppress both direct
        // and nested notifications, even if a child committed first.
        try (Transaction outer = Transaction.openOuter()) {
            try (Transaction child = Transaction.openNested(outer)) {
                check(storage.insert(water, bucket, child) == bucket, "Aborted fixture fill rejected");
                TransferJournal.notifyAfterCommit(notification);
                child.commit();
            }
            check(tank.tank.getFluidAmount() == 1000, "Tentative volume missing");
            check(notifications.get() == 0, "Nested commit published before root commit");
        }
        check(tank.tank.isEmpty(), "Root abort leaked fluid");
        check(notifications.get() == 0, "Root abort published a notification");

        try (Transaction outer = Transaction.openOuter()) {
            check(storage.insert(water, bucket, outer) == bucket, "Committed fixture fill rejected");
            TransferJournal.notifyAfterCommit(notification);
            try (Transaction child = Transaction.openNested(outer)) {
                check(storage.extract(water, quarter, child) == quarter, "Nested extraction rejected");
                TransferJournal.notifyAfterCommit(notification);
            }
            check(tank.tank.getFluidAmount() == 1000, "Nested abort lost committed-parent fluid");
            check(notifications.get() == 0, "Notification ran before outer commit");
            // Exercises Tank.onContentsChanged -> TileBC_Neptune.markChunkDirty
            // and FabricOwnedStorageAdapters' deferred dirty callback.
            outer.commit();
        }
        check(tank.tank.getFluidAmount() == 1000, "Commit lost or duplicated fluid");
        check(notifications.get() == 1, "Final callback lost or repeated notification");
        try (Transaction outer = Transaction.openOuter()) {
            check(storage.extract(water, quarter, outer) == quarter, "Committed extraction rejected");
            outer.commit();
        }
        check(tank.tank.getFluidAmount() == 750, "Committed extraction did not conserve volume");
        helper.succeed();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @Override
    public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
        helper.setBlock(0, 0, 0, Blocks.AIR);
        method.invoke(this, helper);
    }
}
