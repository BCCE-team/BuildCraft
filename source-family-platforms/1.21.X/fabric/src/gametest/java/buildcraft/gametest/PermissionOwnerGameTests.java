package buildcraft.gametest;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.ActorType;
import buildcraft.api.v2.permission.PermissionDecision;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.builders.BCBuildersBlocks;
import buildcraft.builders.tile.TileQuarry;
import buildcraft.builders.internal.schematic.legacy.SchematicBlockContext;
import buildcraft.builders.snapshot.SchematicBlockDefault;
import buildcraft.core.BCCoreItems;
import buildcraft.lib.BCLib;
import buildcraft.lib.misc.AutomationPermissionUtil;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import com.mojang.authlib.GameProfile;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
/** End-to-end owner identity and protection contract tests for BuildCraft automation. */
public final class PermissionOwnerGameTests {
    private static final String EMPTY_TEMPLATE = "empty3x3x3";
    private static final UUID DENIED_OWNER_ID = UUID.fromString("67ceef18-f3af-47c4-8590-27d68fe65736");
    private static final UUID PLACEMENT_OWNER_ID = UUID.fromString("21b82fa5-6313-428e-a38a-f81e315a4fe2");
    private static final GameProfile DENIED_OWNER = new GameProfile(DENIED_OWNER_ID, "BCTestDeniedOwner");
    private static final Identifier PROVIDER_ID = id("gametest_owner_denial");
    private static boolean protectionHooksInstalled;
    private static ServerLevel protectedLevel;
    private static BlockPos protectedBreak;
    private static BlockPos protectedClick;

    private PermissionOwnerGameTests() {}

    public static void buildCraftAutomationPlayerUsesPlatformFakePlayer(GameTestHelper helper) {
        Player player = FakePlayerProvider.INSTANCE.getBuildCraftPlayer(helper.getLevel());
        require(helper, player instanceof FakePlayer,
            "BuildCraft automation actor is not the loader's canonical FakePlayer");
        helper.succeed();
    }

    public static void machineOwnerIdentitySurvivesPersistenceRoundTrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, BCBuildersBlocks.QUARRY.get().defaultBlockState());
        BlockEntity blockEntity = GameTestCompat.getBlockEntity(helper, relative);
        if (!(blockEntity instanceof TileQuarry quarry)) {
            helper.fail("quarry block did not create TileQuarry");
            return;
        }

        Player owner = FakePlayerProvider.INSTANCE.getFakePlayer(level, DENIED_OWNER, helper.absolutePos(relative));
        quarry.onPlacedBy(owner, ItemStack.EMPTY);
        CompoundTag saved = saveMachineState(quarry, helper);
        TileQuarry restored = new TileQuarry(helper.absolutePos(new BlockPos(2, 1, 1)), quarry.getBlockState());
        loadMachineState(restored, saved, helper);

        GameProfile restoredOwner = restored.getKnownOwner();
        require(helper, restoredOwner != null, "quarry owner disappeared after NBT round-trip");
        require(helper, DENIED_OWNER_ID.equals(GameTestCompat.profileId(restoredOwner)), "quarry owner UUID changed after NBT round-trip");
        require(helper, GameTestCompat.profileName(DENIED_OWNER).equals(GameTestCompat.profileName(restoredOwner)), "quarry owner name changed after NBT round-trip");
        helper.succeed();
    }

    public static void blueprintCopyUsesPlacementActorInsteadOfSourceMachineOwner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sourceRelative = new BlockPos(1, 1, 1);
        BlockPos targetRelative = new BlockPos(2, 1, 1);
        BlockPos sourcePos = helper.absolutePos(sourceRelative);
        BlockPos targetPos = helper.absolutePos(targetRelative);
        helper.setBlock(sourceRelative, BCBuildersBlocks.QUARRY.get().defaultBlockState());
        helper.setBlock(targetRelative, Blocks.AIR.defaultBlockState());

        BlockEntity sourceEntity = GameTestCompat.getBlockEntity(helper, sourceRelative);
        if (!(sourceEntity instanceof TileQuarry sourceQuarry)) {
            helper.fail("source quarry block did not create TileQuarry");
            return;
        }
        Player sourceOwner = FakePlayerProvider.INSTANCE.getFakePlayer(level, DENIED_OWNER, sourcePos);
        sourceQuarry.onPlacedBy(sourceOwner, ItemStack.EMPTY);

        BlockState sourceState = level.getBlockState(sourcePos);
        SchematicBlockDefault schematic = new SchematicBlockDefault();
        schematic.init(new SchematicBlockContext(level, sourcePos, sourcePos, sourceState, sourceState.getBlock()));

        GameProfile placementOwner = new GameProfile(PLACEMENT_OWNER_ID, "BCTestPlacementOwner");
        Player actor = FakePlayerProvider.INSTANCE.getFakePlayer(level, placementOwner, targetPos);
        require(helper, schematic.build(level, targetPos, actor), "copied quarry failed to place");

        BlockEntity placedEntity = level.getBlockEntity(targetPos);
        if (!(placedEntity instanceof TileQuarry placedQuarry)) {
            helper.fail("copied quarry did not create TileQuarry");
            return;
        }
        GameProfile placedOwner = placedQuarry.getKnownOwner();
        require(helper, placedOwner != null, "copied quarry has no placement owner");
        require(helper, PLACEMENT_OWNER_ID.equals(GameTestCompat.profileId(placedOwner)),
            "copied quarry cloned the source owner instead of using the placement actor");
        require(helper, !DENIED_OWNER_ID.equals(GameTestCompat.profileId(placedOwner)),
            "source quarry owner leaked through copied block NBT");
        helper.succeed();
    }

    public static void robotOwnerIdentitySurvivesPersistenceAndFeedsApi2Actor(GameTestHelper helper) {
        installPermissionProvider();
        EntityRobot original = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
        original.setOwner(DENIED_OWNER);
        CompoundTag saved = GameTestCompat.saveEntity(original);

        EntityRobot restored = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
        GameTestCompat.loadEntity(restored, saved.copy());
        GameProfile restoredOwner = restored.getOwnerProfile();
        require(helper, DENIED_OWNER_ID.equals(GameTestCompat.profileId(restoredOwner)), "robot owner UUID changed after NBT round-trip");
        require(helper, GameTestCompat.profileName(DENIED_OWNER).equals(GameTestCompat.profileName(restoredOwner)), "robot owner name changed after NBT round-trip");

        var actor = RobotAutomationSupport.actor(restored);
        require(helper, actor.type() == ActorType.MACHINE_OWNER, "owned robot did not become a MACHINE_OWNER API2 actor");
        require(helper, actor.playerId().filter(DENIED_OWNER_ID::equals).isPresent(), "robot API2 actor lost owner UUID");
        require(helper, actor.playerName().filter(GameTestCompat.profileName(DENIED_OWNER)::equals).isPresent(), "robot API2 actor lost owner name");
        require(helper, !RobotAutomationSupport.permitsBlock(
            restored, helper.absolutePos(new BlockPos(1, 1, 1)), WorldOperationKind.BLOCK_BREAK, OperationMode.EXECUTE
        ), "registered API2 permission provider did not deny the owned robot");
        helper.succeed();
    }

    public static void api2PermissionProviderSeesOwnerAcrossWorldOperationKinds(GameTestHelper helper) {
        installPermissionProvider();
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos target = helper.absolutePos(new BlockPos(2, 1, 1));
        for (WorldOperationKind kind : new WorldOperationKind[] {
            WorldOperationKind.BLOCK_BREAK,
            WorldOperationKind.BLOCK_PLACE,
            WorldOperationKind.ITEM_USE,
            WorldOperationKind.FLUID_DRAIN
        }) {
            require(helper, !AutomationPermissionUtil.mayBlock(
                level, origin, target, DENIED_OWNER, AutomationPermissionUtil.SOURCE_BUILDER, kind, OperationMode.EXECUTE
            ), "API2 permission provider failed to deny " + kind);
        }
        ItemEntity entityTarget = new ItemEntity(level, target.getX(), target.getY(), target.getZ(), new ItemStack(Items.DIRT));
        require(helper, !AutomationPermissionUtil.mayEntity(
            level, origin, entityTarget, DENIED_OWNER, AutomationPermissionUtil.SOURCE_ROBOT,
            WorldOperationKind.ENTITY_ATTACK, OperationMode.EXECUTE
        ), "API2 permission provider failed to deny ENTITY_ATTACK");

        GameProfile otherOwner = new GameProfile(UUID.fromString("68732f53-9f41-44dd-87e3-9ccfd3054676"), "BCTestAllowedOwner");
        require(helper, AutomationPermissionUtil.mayBlock(
            level, origin, target, otherOwner, AutomationPermissionUtil.SOURCE_QUARRY,
            WorldOperationKind.BLOCK_BREAK, OperationMode.EXECUTE
        ), "owner-scoped GameTest permission provider leaked to another owner");
        helper.succeed();
    }

    public static void manualInteractionIsNotOwnerLocked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos absolute = helper.absolutePos(relative);
        helper.setBlock(relative, BCBuildersBlocks.QUARRY.get().defaultBlockState());
        BlockEntity blockEntity = GameTestCompat.getBlockEntity(helper, relative);
        if (!(blockEntity instanceof TileQuarry quarry)) {
            helper.fail("quarry block did not create TileQuarry");
            return;
        }

        Player owner = FakePlayerProvider.INSTANCE.getFakePlayer(level, DENIED_OWNER, absolute);
        quarry.onPlacedBy(owner, ItemStack.EMPTY);
        GameProfile otherProfile = new GameProfile(
            UUID.fromString("68732f53-9f41-44dd-87e3-9ccfd3054676"), "BCTestOtherPlayer"
        );
        Player otherPlayer = FakePlayerProvider.INSTANCE.getFakePlayer(level, otherProfile, absolute);

        require(helper, !DENIED_OWNER_ID.equals(otherPlayer.getUUID()), "test player unexpectedly matches machine owner");
        require(helper, quarry.canInteractWith(otherPlayer),
            "machine owner became an ACL: another nearby player must still be able to interact with/steal the machine");
        helper.succeed();
    }

    public static void robotDismantleRequiresOwnership(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(new BlockPos(1, 1, 1));
        EntityRobot robot = new EntityRobot(level, BCRoboticsBoards.EMPTY);
        robot.setOwner(DENIED_OWNER);
        robot.setPos(absolute.getX() + 0.5D, absolute.getY(), absolute.getZ() + 0.5D);
        level.addFreshEntity(robot);

        GameProfile otherProfile = new GameProfile(
            UUID.fromString("7db6ac5a-38f7-45a9-9eea-7c30f17b04e0"), "BCTestRobotThief"
        );
        Player otherPlayer = FakePlayerProvider.INSTANCE.getFakePlayer(level, otherProfile, absolute);
        otherPlayer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BCCoreItems.WRENCH.get()));
        otherPlayer.setShiftKeyDown(true);

        InteractionResult result = robot.interact(otherPlayer, InteractionHand.MAIN_HAND);
        require(helper, result == InteractionResult.FAIL,
            "non-owner wrench dismantle must be explicitly denied");
        require(helper, !robot.isRemoved(), "non-owner wrench dismantle removed the protected robot");

        Player ownerPlayer = FakePlayerProvider.INSTANCE.getFakePlayer(level, DENIED_OWNER, absolute);
        ownerPlayer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BCCoreItems.WRENCH.get()));
        ownerPlayer.setShiftKeyDown(true);

        InteractionResult ownerResult = robot.interact(ownerPlayer, InteractionHand.MAIN_HAND);
        require(helper, ownerResult != InteractionResult.PASS && ownerResult != InteractionResult.FAIL,
            "owner wrench dismantle must be accepted");
        require(helper, robot.isRemoved(), "owner wrench dismantle did not convert the robot back to items");
        helper.succeed();
    }

    public static void platformProtectionHooksReceiveMachineOwnerForBreakAndPlace(GameTestHelper helper) {
        installProtectionHooks();
        ServerLevel level = helper.getLevel();
        BlockPos breakRelative = new BlockPos(1, 1, 1);
        BlockPos placeRelative = new BlockPos(2, 1, 1);
        BlockPos breakPos = helper.absolutePos(breakRelative);
        BlockPos placePos = helper.absolutePos(placeRelative);
        helper.setBlock(breakRelative, Blocks.STONE.defaultBlockState());
        helper.setBlock(placeRelative, Blocks.AIR.defaultBlockState());

        protectedLevel = level;
        protectedBreak = breakPos;
        protectedClick = placePos.below();
        try {
        require(helper, BlockUtil.breakBlockAndGetDrops(
            level, breakPos, new ItemStack(Items.DIAMOND_PICKAXE), DENIED_OWNER
        ).isEmpty(), "Fabric protection hook did not cancel BuildCraft owner break");
        require(helper, level.getBlockState(breakPos).is(Blocks.STONE), "cancelled owner break still changed the world");

        Player actor = FakePlayerProvider.INSTANCE.getFakePlayer(level, DENIED_OWNER, placePos);
        require(helper, !BlockUtil.placeBlock(level, placePos, Blocks.STONE.defaultBlockState(), actor, Direction.UP, 3),
            "Fabric protection hook did not cancel BuildCraft owner placement");
        require(helper, level.getBlockState(placePos).isAir(), "cancelled owner placement changed the world");
        } finally {
            protectedLevel = null;
            protectedBreak = null;
            protectedClick = null;
        }
        helper.succeed();
    }

    private static synchronized void installPermissionProvider() {
        var registry = BuildCraftApi.service(BuildCraftServices.PERMISSIONS);
        if (registry.providers().stream().anyMatch(provider -> PROVIDER_ID.equals(provider.id()))) return;
        registry.register(PROVIDER_ID, 100_000, context ->
            context.actor().playerId().filter(DENIED_OWNER_ID::equals).isPresent()
                ? PermissionDecision.deny(PROVIDER_ID, "gametest owner denial")
                : PermissionDecision.pass()
        );
    }

    private static synchronized void installProtectionHooks() {
        if (protectionHooksInstalled) return;
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, tile) ->
            world != protectedLevel || !pos.equals(protectedBreak) || !DENIED_OWNER_ID.equals(player.getUUID()));
        UseBlockCallback.EVENT.register((player, world, hand, hit) ->
            world == protectedLevel && hit.getBlockPos().equals(protectedClick)
                && DENIED_OWNER_ID.equals(player.getUUID()) ? InteractionResult.FAIL : InteractionResult.PASS);
        protectionHooksInstalled = true;
    }

    private static CompoundTag saveMachineState(BlockEntity blockEntity, GameTestHelper helper) {
        return GameTestCompat.saveBlockEntity(blockEntity, helper);
    }

    private static void loadMachineState(BlockEntity blockEntity, CompoundTag tag, GameTestHelper helper) {
        GameTestCompat.loadBlockEntity(blockEntity, tag, helper);
    }

    private static Identifier id(String path) {
        return Objects.requireNonNull(Identifier.tryParse("buildcraft:" + path));
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}
