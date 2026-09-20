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
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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
        BlockPos lowerPos = new BlockPos(1, 1, 1);
        BlockPos bridgePos = new BlockPos(1, 2, 1);
        BlockPos upperPos = new BlockPos(1, 3, 1);
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
