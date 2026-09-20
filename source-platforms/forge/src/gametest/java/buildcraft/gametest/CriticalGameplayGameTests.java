package buildcraft.gametest;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.automation.AutomationResult;
import buildcraft.api.v2.automation.StripesContext;
import buildcraft.api.v2.automation.StripesOutput;
import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.tile.TileAutoWorkbenchItems;
import buildcraft.lib.BCLib;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.transport.api2.StripesApi2Bridge;
import buildcraft.transport.stripes.StripesHandlerEntityInteract;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Runtime coverage for critical gameplay and economy invariants. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class CriticalGameplayGameTests {
    private static final String EMPTY_TEMPLATE = "empty3x3x3";
    private static final GameProfile STRIPES_ACTOR = new GameProfile(
        UUID.fromString("a7ca9f60-c19e-4c89-97ef-4c94703547ce"), "BCTestCritical"
    );

    private CriticalGameplayGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void stripesEntityInteractCommitsConsumingServerResult(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pipePos = helper.absolutePos(new BlockPos(0, 1, 1));
        Direction direction = Direction.EAST;
        BlockPos target = pipePos.relative(direction);

        EntityRobot robot = new EntityRobot(level, BCRoboticsBoards.EMPTY);
        robot.setPos(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        require(helper, level.addFreshEntity(robot), "failed to spawn robot interaction target");

        Player player = FakePlayerProvider.INSTANCE.getFakePlayer(level, STRIPES_ACTOR, pipePos);
        player.getInventory().clearContent();
        ItemStack helmets = new ItemStack(Items.IRON_HELMET, 2);
        player.getInventory().setItem(player.getInventory().selected, helmets);

        StripesContext context = new StripesContext(
            level,
            pipePos,
            direction,
            helmets,
            AutomationActor.player(player.getUUID(), player.getGameProfile().getName()),
            OperationMode.EXECUTE,
            player,
            StripesOutput.discard()
        );
        AutomationResult result = StripesApi2Bridge.item(StripesHandlerEntityInteract.INSTANCE::handle).activate(context);

        require(helper, result.status() == AutomationResult.Status.SUCCESS,
            "server-side consuming entity interaction was treated as PASS");
        require(helper, robot.getWearables().size() == 1, "robot did not receive the wearable side effect");
        require(helper, context.stack().getCount() == 1,
            "consumed wearable was not committed to the Stripes working stack");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void autoWorkbenchContainerNeverExposesPhantomFilters(GameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, BCFactoryBlocks.AUTO_BENCH_BLOCK.get().defaultBlockState());
        BlockEntity blockEntity = helper.getBlockEntity(relative);
        if (!(blockEntity instanceof TileAutoWorkbenchItems tile)) {
            helper.fail("Auto Workbench block did not create TileAutoWorkbenchItems");
            return;
        }

        tile.invMaterialFilter.setStackInSlot(0, new ItemStack(Items.DIAMOND));
        tile.invResult.setStackInSlot(0, new ItemStack(Items.GOLD_INGOT, 2));

        require(helper, tile.getContainerSize() == 1,
            "Auto Workbench external Container still exposes phantom configuration slots");
        require(helper, tile.getItem(1).isEmpty(),
            "phantom material filter is visible through the external Container");
        require(helper, tile.removeItem(1, 1).isEmpty(),
            "phantom material filter can be extracted through the external Container");
        require(helper, tile.invMaterialFilter.getStackInSlot(0).is(Items.DIAMOND),
            "probing the external Container mutated the phantom material filter");

        ItemStack extracted = tile.removeItem(0, 1);
        require(helper, extracted.is(Items.GOLD_INGOT) && extracted.getCount() == 1,
            "legitimate crafted-result extraction stopped working");
        require(helper, tile.invResult.getStackInSlot(0).getCount() == 1,
            "crafted-result extraction removed the wrong amount");
        helper.succeed();
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }
}
