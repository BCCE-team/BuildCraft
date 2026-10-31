package buildcraft.gametest;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.automation.AutomationResult;
import buildcraft.api.v2.automation.StripesContext;
import buildcraft.api.v2.automation.StripesOutput;
import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.api.v2.permission.PermissionDecision;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.lib.internal.statement.StatementSlot;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotBreak;
import buildcraft.robotics.ai.AIRobotGotoBlock;
import buildcraft.robotics.ai.AIRobotSearchAndGotoBlock;
import buildcraft.robotics.ai.AIRobotStraightMoveTo;
import buildcraft.robotics.ai.AIRobotUseToolOnBlock;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.IRobotRegistry;
import buildcraft.robotics.internal.legacy.robots.ResourceIdBlock;
import buildcraft.transport.api2.StripesApi2Bridge;
import com.mojang.authlib.GameProfile;
import java.util.Collections;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-world regression checks for robot AI. These tests intentionally exercise the real AI
 * implementations instead of only inspecting source text or serializing a detached board.
 * The small test structure isolates mutations, while manual AI updates avoid dependence
 * on server TPS and on the availability of an MJ charging station.
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class RobotAiRegressionGameTests {
    private static final String TEMPLATE = "empty7x3x7";
    private static final UUID DENIED_ID = UUID.fromString("4ba86260-6449-4cda-b29f-673cb398d891");
    private static final UUID NEIGHBOR_DENIED_ID = UUID.fromString("8871cf07-4d4e-4dbb-9f3d-df5dc5b0cf5d");
    private static final GameProfile NEIGHBOR_DENIED_OWNER = new GameProfile(NEIGHBOR_DENIED_ID, "BCTestFluidNeighbor");
    private static final ResourceLocation NEIGHBOR_DENIAL_PROVIDER = ResourceLocation.tryParse("buildcraftlib:gametest_robot_fluid_neighbor_denial");
    private static final GameProfile DENIED_OWNER = new GameProfile(DENIED_ID, "BCTestRobotDenied");
    private static final ResourceLocation DENIAL_PROVIDER = ResourceLocation.tryParse("buildcraftlib:gametest_robot_ai_denial");

    private RobotAiRegressionGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 40)
    public static void straightMovementClampsTheFinalStep(GameTestHelper helper) {
        EntityRobot robot = detachedRobot(helper);
        BlockPos origin = helper.absolutePos(new BlockPos(2, 1, 2));
        robot.setPos(origin.getX() + 0.5D, origin.getY() + 0.5D, origin.getZ() + 0.5D);
        double x = robot.getX() + 0.19D;
        double y = robot.getY();
        double z = robot.getZ();
        AIRobotStraightMoveTo movement = new AIRobotStraightMoveTo(robot, x, y, z);
        movement.start();
        for (int tick = 0; tick < 12 && robot.distanceToSqr(x, y, z) > 0.000001D; tick++) {
            movement.update();
            Vec3 step = robot.getDeltaMovement();
            double distance = Math.sqrt(robot.distanceToSqr(x, y, z));
            require(helper, step.length() <= distance + 0.000001D,
                "short-range docking movement overshot the remaining distance");
            robot.setPos(robot.getX() + step.x, robot.getY() + step.y, robot.getZ() + step.z);
        }
        movement.update();
        require(helper, movement.success(), "straight docking AI failed after reaching its target");
        require(helper, robot.distanceToSqr(x, y, z) < 0.000001D, "straight docking did not reach the exact target");
        require(helper, robot.getDeltaMovement().lengthSqr() < 0.000001D, "arrival left residual robot velocity");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 40)
    public static void blockedStraightMovementFailsWithoutTeleporting(GameTestHelper helper) {
        EntityRobot robot = detachedRobot(helper);
        BlockPos origin = helper.absolutePos(new BlockPos(2, 1, 2));
        robot.setPos(origin.getX() + 0.5D, origin.getY() + 0.5D, origin.getZ() + 0.5D);
        Vec3 start = robot.position();
        AIRobotStraightMoveTo movement = new AIRobotStraightMoveTo(robot, start.x + 3.0D, start.y, start.z);
        movement.start();
        // Deliberately do not apply velocity: an obstructed movement cannot claim success.
        for (int tick = 0; tick < 24 && movement.success(); tick++) {
            movement.update();
        }
        require(helper, !movement.success(), "stalled docking AI incorrectly reported successful arrival");
        require(helper, robot.position().distanceToSqr(start) < 0.000001D,
            "stalled docking AI teleported the robot instead of failing");
        require(helper, robot.getDeltaMovement().lengthSqr() < 0.000001D, "failed docking retained velocity");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 40)
    public static void farmerTillsOneBlockWithOneToolUse(GameTestHelper helper) {
        BlockPos relative = new BlockPos(2, 1, 2);
        helper.setBlock(relative, Blocks.DIRT.defaultBlockState());
        helper.setBlock(relative.above(), Blocks.AIR.defaultBlockState());
        EntityRobot robot = detachedRobot(helper);
        robot.setPos(helper.absolutePos(new BlockPos(2, 2, 3)).getX() + 0.5D,
            helper.absolutePos(new BlockPos(2, 2, 3)).getY(),
            helper.absolutePos(new BlockPos(2, 2, 3)).getZ() + 0.5D);
        robot.setItemInUse(new ItemStack(Items.IRON_HOE));
        AIRobotUseToolOnBlock use = new AIRobotUseToolOnBlock(robot, new BlockIndex(helper.absolutePos(relative)));
        use.start();
        // The scheduler stops invoking an AI after terminate(). Stop on the first
        // successful till instead of updating the already-completed task again.
        BlockPos target = helper.absolutePos(relative);
        for (int tick = 0; tick < 20 && !helper.getLevel().getBlockState(target).is(Blocks.FARMLAND); tick++) {
            use.update();
        }
        require(helper, helper.getLevel().getBlockState(target).is(Blocks.FARMLAND),
            "farmer failed to create farmland");
        require(helper, use.success(), "farmer reported failure after successful tilling");
        ItemStack tool = robot.getItemBySlot(EquipmentSlot.MAINHAND);
        require(helper, tool.is(Items.IRON_HOE) && tool.getDamageValue() == 1,
            "farmer consumed or damaged its hoe more than once");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 40)
    public static void farmerPermissionDenialLeavesBlockAndToolIntact(GameTestHelper helper) {
        installDenialProvider();
        BlockPos relative = new BlockPos(2, 1, 2);
        helper.setBlock(relative, Blocks.DIRT.defaultBlockState());
        helper.setBlock(relative.above(), Blocks.AIR.defaultBlockState());
        EntityRobot robot = detachedRobot(helper);
        robot.setOwner(DENIED_OWNER);
        robot.setPos(helper.absolutePos(new BlockPos(2, 2, 3)).getX() + 0.5D,
            helper.absolutePos(new BlockPos(2, 2, 3)).getY(),
            helper.absolutePos(new BlockPos(2, 2, 3)).getZ() + 0.5D);
        robot.setItemInUse(new ItemStack(Items.IRON_HOE));
        AIRobotUseToolOnBlock use = new AIRobotUseToolOnBlock(robot, new BlockIndex(helper.absolutePos(relative)));
        use.start();
        for (int tick = 0; tick < 15; tick++) use.update();
        require(helper, !use.success(), "farmer accepted a denied item-use operation");
        require(helper, helper.getLevel().getBlockState(helper.absolutePos(relative)).is(Blocks.DIRT),
            "denied farmer operation changed the block");
        require(helper, robot.getItemBySlot(EquipmentSlot.MAINHAND).getDamageValue() == 0,
            "denied farmer operation damaged the hoe");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 40)
    public static void miningBreaksBlockWithEquippedTool(GameTestHelper helper) {
        BlockPos relative = new BlockPos(2, 1, 2);
        helper.setBlock(relative, Blocks.STONE.defaultBlockState());
        EntityRobot robot = detachedRobot(helper);
        robot.setPos(helper.absolutePos(new BlockPos(2, 2, 3)).getX() + 0.5D,
            helper.absolutePos(new BlockPos(2, 2, 3)).getY(),
            helper.absolutePos(new BlockPos(2, 2, 3)).getZ() + 0.5D);
        robot.setItemInUse(new ItemStack(Items.DIAMOND_PICKAXE));
        AIRobotBreak breaking = new AIRobotBreak(robot, new BlockIndex(helper.absolutePos(relative)));
        breaking.start();
        for (int tick = 0; tick < 75 && !helper.getLevel().getBlockState(helper.absolutePos(relative)).isAir(); tick++) {
            breaking.update();
        }
        require(helper, breaking.success(), "mining AI failed with an appropriate equipped pickaxe");
        require(helper, helper.getLevel().getBlockState(helper.absolutePos(relative)).isAir(),
            "mining AI failed to remove its target block");
        require(helper, robot.getItemBySlot(EquipmentSlot.MAINHAND).getDamageValue() == 1,
            "mining AI did not use its actual pickaxe exactly once");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 40)
    public static void miningPermissionDenialLeavesWorldIntact(GameTestHelper helper) {
        installDenialProvider();
        BlockPos relative = new BlockPos(2, 1, 2);
        helper.setBlock(relative, Blocks.STONE.defaultBlockState());
        EntityRobot robot = detachedRobot(helper);
        robot.setOwner(DENIED_OWNER);
        robot.setItemInUse(new ItemStack(Items.DIAMOND_PICKAXE));
        AIRobotBreak breaking = new AIRobotBreak(robot, new BlockIndex(helper.absolutePos(relative)));
        breaking.start();
        breaking.update();
        require(helper, !breaking.success(), "mining AI accepted a denied break operation");
        require(helper, helper.getLevel().getBlockState(helper.absolutePos(relative)).is(Blocks.STONE),
            "denied mining operation changed the world");
        require(helper, robot.getItemBySlot(EquipmentSlot.MAINHAND).getDamageValue() == 0,
            "denied mining operation damaged the tool");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 40)
    public static void abortedNavigationReleasesReservedBlock(GameTestHelper helper) {
        EntityRobot robot = detachedRobot(helper);
        IRobotRegistry registry = requireRegistry(helper, robot);
        ResourceIdBlock resource = new ResourceIdBlock(new BlockIndex(helper.absolutePos(new BlockPos(2, 1, 2))));
        try {
            registry.registerRobot(robot);
            require(helper, registry.take(resource, robot), "failed to establish robot block lease");
            AIRobotSearchAndGotoBlock ai = preparedNavigation(robot, helper.absolutePos(new BlockPos(2, 1, 2)));
            ai.abort();
            require(helper, !registry.isTaken(resource), "abort left a stale work-block reservation");
        } finally {
            registry.killRobot(robot);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 40)
    public static void successfulNavigationHandsReservationToBoard(GameTestHelper helper) {
        EntityRobot robot = detachedRobot(helper);
        IRobotRegistry registry = requireRegistry(helper, robot);
        BlockPos target = helper.absolutePos(new BlockPos(2, 1, 2));
        ResourceIdBlock resource = new ResourceIdBlock(new BlockIndex(target));
        try {
            registry.registerRobot(robot);
            require(helper, registry.take(resource, robot), "failed to establish the navigation reservation");
            AIRobotSearchAndGotoBlock ai = preparedNavigation(robot, target);
            AIRobotGotoBlock successfulMove = new AIRobotGotoBlock(robot) {
                @Override public boolean success() { return true; }
            };
            ai.delegateAIEnded(successfulMove);
            require(helper, ai.success(), "successful arrival was not handed to the board");
            require(helper, registry.isTaken(resource), "successful arrival prematurely released the work block");
            ai.abort();
            require(helper, registry.isTaken(resource), "board-owned work block was released by completed navigation");
        } finally {
            registry.release(resource);
            registry.killRobot(robot);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 40)
    public static void stationsOnTheSameBlockReserveIndependently(GameTestHelper helper) {
        EntityRobot first = detachedRobot(helper);
        EntityRobot second = detachedRobot(helper);
        IRobotRegistry registry = requireRegistry(helper, first);
        BlockIndex index = new BlockIndex(helper.absolutePos(new BlockPos(3, 1, 3)));
        ProbeStation east = new ProbeStation(index, Direction.EAST);
        ProbeStation west = new ProbeStation(index, Direction.WEST);
        east.setLevel(helper.getLevel());
        west.setLevel(helper.getLevel());
        try {
            registry.registerRobot(first);
            registry.registerRobot(second);
            registry.registerStation(east);
            registry.registerStation(west);
            require(helper, east.take(first), "first robot could not reserve the first pipe station");
            require(helper, !east.take(second), "second robot stole an occupied station");
            require(helper, west.take(second), "other station on the same pipe incorrectly appeared occupied");
            east.release(first);
            require(helper, !east.isTaken(), "released station remained occupied");
            require(helper, west.isTaken(), "releasing one station also released the other");
            require(helper, east.take(first), "first robot could not reclaim its released station");
        } finally {
            east.forceRelease();
            west.forceRelease();
            registry.removeStation(east);
            registry.removeStation(west);
            registry.killRobot(first);
            registry.killRobot(second);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 40)
    public static void fluidStationAccessHonorsRobotOwner(GameTestHelper helper) {
        installDenialProvider();
        EntityRobot robot = detachedRobot(helper);
        robot.setOwner(DENIED_OWNER);
        BlockIndex index = new BlockIndex(helper.absolutePos(new BlockPos(3, 1, 3)));
        ProbeStation station = new ProbeStation(index, Direction.UP);
        station.setLevel(helper.getLevel());
        for (boolean withdrawing : new boolean[] {true, false}) {
            for (OperationMode mode : new OperationMode[] {OperationMode.SIMULATE, OperationMode.EXECUTE}) {
                require(helper, !RobotAutomationSupport.permitsFluidTransfer(robot, station, withdrawing, mode),
                    "denied owner gained access to a robot fluid station");
            }
        }
        helper.succeed();
    }


    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 40)
    public static void unloadedRobotRetainsAndReclaimsItsWorkLease(GameTestHelper helper) {
        EntityRobot robot = detachedRobot(helper);
        IRobotRegistry registry = requireRegistry(helper, robot);
        ResourceIdBlock resource = new ResourceIdBlock(new BlockIndex(helper.absolutePos(new BlockPos(2, 1, 2))));
        try {
            registry.registerRobot(robot);
            require(helper, registry.take(resource, robot), "failed to establish a lease before simulated unload");
            registry.unloadRobot(robot);
            require(helper, registry.isTaken(resource), "temporary chunk unload discarded a live work reservation");
            registry.registerRobot(robot);
            require(helper, registry.isTaken(resource), "reloaded robot did not reclaim its reservation");
            registry.release(resource);
            require(helper, !registry.isTaken(resource), "completed work lease could not be released");
        } finally {
            registry.killRobot(robot);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 40)
    public static void fluidNeighborPermissionIsCheckedSeparatelyFromDock(GameTestHelper helper) {
        installNeighborDenialProvider();
        EntityRobot robot = detachedRobot(helper);
        robot.setOwner(NEIGHBOR_DENIED_OWNER);
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 3));
        robot.setPos(origin.getX() + 0.5D, origin.getY() + 0.5D, origin.getZ() + 0.5D);
        BlockPos dock = helper.absolutePos(new BlockPos(3, 1, 3));
        BlockPos neighbor = dock.relative(Direction.EAST);
        ProbeStation station = new ProbeStation(new BlockIndex(dock), Direction.UP);
        station.setLevel(helper.getLevel());
        require(helper, RobotAutomationSupport.permitsBlock(
            robot, dock, WorldOperationKind.FLUID_DRAIN, OperationMode.SIMULATE
        ), "neighbor-only denial accidentally blocked the docking station itself");
        require(helper, !RobotAutomationSupport.permitsBlock(
            robot, neighbor, WorldOperationKind.FLUID_DRAIN, OperationMode.SIMULATE
        ), "neighbor-only denial did not apply to the adjacent fluid inventory");
        require(helper, !RobotAutomationSupport.permitsFluidTransfer(
            robot, station, true, OperationMode.SIMULATE
        ), "robot ignored a neighbor-only fluid-drain denial during simulation");
        require(helper, !RobotAutomationSupport.permitsFluidTransfer(
            robot, station, true, OperationMode.EXECUTE
        ), "robot ignored a neighbor-only fluid-drain denial during execution");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 40)
    public static void stripesPassDoesNotCommitAHandlerWorkingStack(GameTestHelper helper) {
        BlockPos pipe = helper.absolutePos(new BlockPos(2, 1, 2));
        Player player = FakePlayerProvider.INSTANCE.getFakePlayer(helper.getLevel(),
            new GameProfile(UUID.fromString("16958fb8-4d1d-463f-ac07-7cfe71e98b03"), "BCTestStripesPass"), pipe);
        ItemStack items = new ItemStack(Items.DIRT, 2);
        StripesContext context = new StripesContext(helper.getLevel(), pipe, Direction.EAST, items,
            AutomationActor.player(player.getUUID(), player.getGameProfile().getName()),
            OperationMode.EXECUTE, player, StripesOutput.discard());
        AutomationResult result = StripesApi2Bridge.item((level, pos, side, stack, actor, output) -> {
            stack.shrink(1);
            return false;
        }).activate(context);
        require(helper, result.status() == AutomationResult.Status.PASS, "a non-handling Stripes action claimed success");
        require(helper, context.stack().getCount() == 2 && items.getCount() == 2,
            "PASS committed a handler's speculative item mutation");
        helper.succeed();
    }

    private static EntityRobot detachedRobot(GameTestHelper helper) {
        return new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
    }

    private static IRobotRegistry requireRegistry(GameTestHelper helper, EntityRobot robot) {
        IRobotRegistry registry = robot.getRegistry();
        require(helper, registry != null, "robot registry unavailable in GameTest world");
        return registry;
    }

    private static AIRobotSearchAndGotoBlock preparedNavigation(EntityRobot robot, BlockPos target) {
        AIRobotSearchAndGotoBlock ai = new AIRobotSearchAndGotoBlock(robot);
        CompoundTag data = new CompoundTag();
        CompoundTag index = new CompoundTag();
        new BlockIndex(target).writeTo(index);
        data.put("indexStored", index);
        ai.loadSelfFromNBT(data);
        return ai;
    }

    private static synchronized void installDenialProvider() {
        var service = BuildCraftApi.service(BuildCraftServices.PERMISSIONS);
        if (service.providers().stream().anyMatch(provider -> DENIAL_PROVIDER.equals(provider.id()))) return;
        service.register(DENIAL_PROVIDER, 110_000, context ->
            context.actor().playerId().filter(DENIED_ID::equals).isPresent()
                ? PermissionDecision.deny(DENIAL_PROVIDER, "robot GameTest owner denied")
                : PermissionDecision.pass()
        );
    }


    private static synchronized void installNeighborDenialProvider() {
        var service = BuildCraftApi.service(BuildCraftServices.PERMISSIONS);
        if (service.providers().stream().anyMatch(provider -> NEIGHBOR_DENIAL_PROVIDER.equals(provider.id()))) return;
        service.register(NEIGHBOR_DENIAL_PROVIDER, 110_000, context ->
            context.actor().playerId().filter(NEIGHBOR_DENIED_ID::equals).isPresent()
                && context.operation() == WorldOperationKind.FLUID_DRAIN
                && context.target().blockPos().filter(pos -> pos.getX() == context.origin().getX() + 4).isPresent()
                ? PermissionDecision.deny(NEIGHBOR_DENIAL_PROVIDER, "robot GameTest fluid neighbor denied")
                : PermissionDecision.pass()
        );
    }

    private static void require(GameTestHelper helper, boolean ok, String message) {
        if (!ok) helper.fail(message);
    }

    private static final class ProbeStation extends DockingStation {
        private ProbeStation(BlockIndex pos, Direction side) { super(pos, side); }
        @Override public Iterable<StatementSlot> getActiveActions() { return Collections.emptyList(); }
        @Override public Direction getFluidInputSide() { return Direction.EAST; }
        @Override public Direction getFluidOutputSide() { return Direction.WEST; }
    }
}
