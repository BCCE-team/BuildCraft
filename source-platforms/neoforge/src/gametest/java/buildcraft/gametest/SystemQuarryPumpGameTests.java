package buildcraft.gametest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.builders.BCBuildersBlocks;
import buildcraft.builders.tile.TileQuarry;
import buildcraft.builders.tile.TileQuarryDrillCollision;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.tile.TilePump;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.mj.MjBattery;
import buildcraft.lib.misc.data.Box;
import buildcraft.lib.misc.FluidUtilBC;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/**
 * A real Quarry with a Pump installed on top. The lava seals the column above
 * stone, so the drill cannot cheat by digging through the liquid. All four
 * lateral sides of the lava are solid or frame blocks; it cannot spill into
 * another GameTest while waiting for the Pump.
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemQuarryPumpGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final BlockPos QUARRY = new BlockPos(1, 1, 3);
    private static final BlockPos PUMP = QUARRY.above();
    private static final BlockPos LAVA = new BlockPos(3, 1, 3);
    private static final BlockPos STONE = new BlockPos(3, 0, 3);
    private static final GameProfile OWNER = new GameProfile(
        UUID.fromString("54ca00f5-48cd-47ad-9d32-0bca71b5fcfe"), "BCTestQuarry");

    private SystemQuarryPumpGameTests() { }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 3100)
    public static void pumpOnQuarryDrainsBlockingLavaAndResumesMining(GameTestHelper helper) {
        // The configured frame and mining box fit wholly within empty7x3x7.
        helper.setBlock(QUARRY, BCBuildersBlocks.QUARRY.get().defaultBlockState());
        BlockEntity entity = GameTestCompat.getBlockEntity(helper, QUARRY);
        if (!(entity instanceof TileQuarry quarry)) {
            helper.fail("missing physical Quarry");
            return;
        }
        quarry.onPlacedBy(buildcraft.lib.misc.FakePlayerProvider.INSTANCE.getFakePlayer(
            helper.getLevel(), OWNER, helper.absolutePos(QUARRY)), ItemStack.EMPTY);
        BlockPos frameMin = helper.absolutePos(new BlockPos(2, 1, 1));
        BlockPos frameMax = helper.absolutePos(new BlockPos(5, 2, 5));
        quarry.frameBox.setMin(frameMin);
        quarry.frameBox.setMax(frameMax);
        Box mining = (Box) field(quarry, "miningBox");
        mining.setMin(helper.absolutePos(new BlockPos(3, 0, 2)));
        mining.setMax(helper.absolutePos(new BlockPos(4, 1, 4)));
        for (int x = 2; x <= 5; x++) {
            for (int y = 1; y <= 2; y++) {
                for (int z = 1; z <= 5; z++) {
                    BlockPos local = new BlockPos(x, y, z);
                    if (quarry.frameBox.isOnEdge(helper.absolutePos(local))) {
                        helper.setBlock(local, BCBuildersBlocks.FRAME.get().defaultBlockState());
                    }
                }
            }
        }
        // The three non-frame neighbours are permanent walls; the fourth is the frame.
        for (BlockPos wall : new BlockPos[]{LAVA.north(), LAVA.south(), LAVA.east()}) {
            helper.setBlock(wall, Blocks.BEDROCK.defaultBlockState());
        }
        helper.setBlock(STONE, Blocks.STONE.defaultBlockState());
        helper.setBlock(LAVA, Blocks.LAVA.defaultBlockState());
        // Match the ordinary Quarry fixture: placement callbacks are not invoked by setBlock.
        invoke(quarry, "updatePoses");

        helper.setBlock(PUMP, BCFactoryBlocks.PUMP.get().defaultBlockState());
        BlockEntity above = GameTestCompat.getBlockEntity(helper, PUMP);
        if (!(above instanceof TilePump pump)) {
            helper.fail("missing physical Pump on top of Quarry");
            return;
        }
        pump.onPlacedBy(buildcraft.lib.misc.FakePlayerProvider.INSTANCE.getFakePlayer(
            helper.getLevel(), OWNER, helper.absolutePos(PUMP)), ItemStack.EMPTY);
        require(helper, pump.getHost() == quarry, "Pump did not discover the Quarry directly below it");

        // Only the Quarry receives energy. The Pump must borrow it once the
        // Quarry has finished its initial pass and starts waiting on lava.
        MjBattery quarryPower = quarry.getBattery();
        long reserve = 10_000L * MjAmount.MICRO_MJ_PER_MJ;
        for (int tick = 10; tick <= 2840; tick += 10) {
            helper.runAfterDelay(tick, () -> {
                long missing = reserve - quarryPower.getStored();
                if (missing > 0) {
                    require(helper, quarryPower.addPowerChecking(missing, FluidAction.EXECUTE) == 0,
                        "Quarry could not accept the engine-equivalent test power");
                }
            });
        }
        helper.runAfterDelay(2850, () -> {
            boolean lavaGone = !helper.getLevel().getFluidState(helper.absolutePos(LAVA)).is(Fluids.LAVA);
            boolean stoneMined = helper.getLevel().getBlockState(helper.absolutePos(STONE)).isAir();
            if (!stoneMined && helper.getLevel().getBlockState(helper.absolutePos(STONE))
                .is(BCBuildersBlocks.QUARRY_DRILL_COLLISION.get())) {
                BlockEntity collision = GameTestCompat.getBlockEntity(helper, STONE);
                stoneMined = collision instanceof TileQuarryDrillCollision drill
                    && quarry.getBlockPos().equals(drill.getOwner());
            }
            boolean lavaCaptured = FluidUtilBC.areFluidsEqual(pump.getFluidInTank(), Fluids.LAVA);
            require(helper, lavaGone && stoneMined && lavaCaptured,
                "Quarry/Pump failed to drain blocking lava and mine the newly accessible stone"
                    + "; lavaGone=" + lavaGone + ", stoneMined=" + stoneMined
                    + ", lavaCaptured=" + lavaCaptured + ", blocking=" + quarry.getBlockingFluid()
                    + ", quarryMJ=" + quarryPower.getStored() + ", hasWork=" + quarry.hasWork());
            require(helper, pump.getLength(1.0F) == 0,
                "Pump on Quarry laid a separate tube through the quarry body");
            helper.succeed();
        });
    }

    private static Object field(Object instance, String name) {
        try {
            Field f = TileQuarry.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(instance);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not configure Quarry fixture: " + name, error);
        }
    }

    private static void invoke(TileQuarry quarry, String method) {
        try {
            Method m = TileQuarry.class.getDeclaredMethod(method);
            m.setAccessible(true);
            m.invoke(quarry);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not initialize Quarry fixture: " + method, error);
        }
    }

    private static void require(GameTestHelper helper, boolean ok, String message) {
        if (!ok) helper.fail(message);
    }
}
