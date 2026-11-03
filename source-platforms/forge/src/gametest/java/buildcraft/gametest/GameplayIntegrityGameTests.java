package buildcraft.gametest;

import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.tile.TileTank;
import buildcraft.lib.BCLib;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.transport.stripes.StripesHandlerPlaceBlock;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Runtime coverage for high-impact gameplay integrity invariants. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class GameplayIntegrityGameTests {
    private static final String EMPTY_TEMPLATE = "empty3x3x3";
    private static final GameProfile STRIPES_ACTOR = new GameProfile(
        UUID.fromString("cc751a10-ec63-4e10-a891-e16ce1ef441b"), "BCTestIntegrity"
    );

    private GameplayIntegrityGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void mixedFluidColumnsDoNotMergeThroughEmptyBridgeTank(GameTestHelper helper) {
        // Keep all three blocks inside the 3-high structure. The previous upper tank at y=3
        // was outside the test template and could interfere with neighboring GameTests.
        BlockPos lowerPos = new BlockPos(1, 0, 1);
        BlockPos bridgePos = new BlockPos(1, 1, 1);
        BlockPos upperPos = new BlockPos(1, 2, 1);
        TileTank lower = placeTank(helper, lowerPos);
        TileTank upper = placeTank(helper, upperPos);

        require(helper, lower.tank.fill(new FluidStack(Fluids.WATER, FluidType.BUCKET_VOLUME), FluidAction.EXECUTE)
            == FluidType.BUCKET_VOLUME, "failed to seed lower water tank");
        require(helper, upper.tank.fill(new FluidStack(Fluids.LAVA, FluidType.BUCKET_VOLUME), FluidAction.EXECUTE)
            == FluidType.BUCKET_VOLUME, "failed to seed upper lava tank");

        TileTank bridge = placeTank(helper, bridgePos);
        require(helper, lower.getConnectedTanks().size() == 1,
            "water column connected through an empty bridge into incompatible lava");
        require(helper, upper.getConnectedTanks().size() == 1,
            "lava column connected through an empty bridge into incompatible water");
        require(helper, bridge.getConnectedTanks().size() == 1,
            "bridge tank joined incompatible column halves into one logical tank");
        require(helper, lower.tank.getFluidAmount() == FluidType.BUCKET_VOLUME
            && lower.tank.getFluid().getFluid() == Fluids.WATER,
            "creating the bridge tank lost or changed the lower fluid");
        require(helper, upper.tank.getFluidAmount() == FluidType.BUCKET_VOLUME
            && upper.tank.getFluid().getFluid() == Fluids.LAVA,
            "creating the bridge tank lost or changed the upper fluid");
        require(helper, bridge.tank.isEmpty(), "empty bridge tank acquired incompatible fluid");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void stripesBlockPlacementReportsRejectedUseOnAsFailure(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pipePos = helper.absolutePos(new BlockPos(0, 2, 1));
        Direction direction = Direction.EAST;
        BlockPos target = pipePos.relative(direction);
        Player player = FakePlayerProvider.INSTANCE.getFakePlayer(level, STRIPES_ACTOR, pipePos);
        ItemStack door = new ItemStack(Items.OAK_DOOR);

        boolean handled = StripesHandlerPlaceBlock.INSTANCE.handle(
            level, pipePos, direction, door, player, buildcraft.api.v2.automation.StripesOutput.discard()
        );
        require(helper, !handled, "Stripes reported successful placement even though useOn was rejected");
        require(helper, level.isEmptyBlock(target), "failed Stripes placement changed the target block");
        require(helper, door.getCount() == 1, "failed Stripes placement consumed the item");
        helper.succeed();
    }


    /** Two incompatible tank columns may be separated, repaired and merged without duplicating liquid. */
    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 170)
    public static void tankColumnTransitionsFromIsolatedVariantsToMergedWater(GameTestHelper helper) {
        BlockPos low = new BlockPos(1, 0, 1);
        BlockPos bridge = new BlockPos(1, 1, 1);
        BlockPos high = new BlockPos(1, 2, 1);
        TileTank bottom = placeTank(helper, low);
        TileTank top = placeTank(helper, high);
        require(helper, bottom.tank.fill(new FluidStack(Fluids.WATER, 750), FluidAction.EXECUTE) == 750,
            "could not fill lower water container");
        require(helper, top.tank.fill(new FluidStack(Fluids.LAVA, 750), FluidAction.EXECUTE) == 750,
            "could not fill upper lava container");
        TileTank middle = placeTank(helper, bridge);
        helper.runAfterDelay(15, () -> {
            require(helper, bottom.getConnectedTanks().size() == 1 && top.getConnectedTanks().size() == 1,
                "mixed columns incorrectly connected through empty bridge");
            require(helper, middle.tank.getFluidAmount() == 0, "bridge acquired incompatible liquid");
            helper.setBlock(bridge, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        });
        helper.runAfterDelay(35, () -> {
            require(helper, bottom.tank.getFluidAmount() == 750 && top.tank.getFluidAmount() == 750,
                "removing empty bridge destroyed source fluid");
            require(helper, top.tank.drain(750, FluidAction.EXECUTE).getAmount() == 750,
                "could not drain upper lava container for state transition");
            require(helper, top.tank.fill(new FluidStack(Fluids.WATER, 750), FluidAction.EXECUTE) == 750,
                "could not refill upper container with compatible water");
            placeTank(helper, bridge);
        });
        helper.runAfterDelay(95, () -> {
            TileTank restored = placeOrGetTank(helper, bridge);
            require(helper, bottom.getConnectedTanks().size() == 3
                && top.getConnectedTanks().size() == 3 && restored.getConnectedTanks().size() == 3,
                "compatible tank column did not reconnect after rebuilding bridge");
            int total = bottom.tank.getFluidAmount() + restored.tank.getFluidAmount() + top.tank.getFluidAmount();
            require(helper, total == 1500, "column merge changed liquid count: " + total);
            require(helper, (bottom.tank.isEmpty() || bottom.tank.getFluid().getFluid() == Fluids.WATER)
                && (restored.tank.isEmpty() || restored.tank.getFluid().getFluid() == Fluids.WATER)
                && (top.tank.isEmpty() || top.tank.getFluid().getFluid() == Fluids.WATER),
                "repaired water column retained an incorrect variant");
            helper.succeed();
        });
    }

    private static TileTank placeOrGetTank(GameTestHelper helper, BlockPos pos) {
        if (GameTestCompat.getBlockEntity(helper, pos) instanceof TileTank tank) return tank;
        helper.fail("missing repaired tank at " + pos);
        throw new IllegalStateException("missing repaired tank");
    }

    private static TileTank placeTank(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, BCFactoryBlocks.TANK_BLOCK.get().defaultBlockState());
        if (!(helper.getBlockEntity(pos) instanceof TileTank tank)) {
            helper.fail("tank block did not create TileTank at " + pos);
            throw new IllegalStateException("missing TileTank");
        }
        return tank;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}
