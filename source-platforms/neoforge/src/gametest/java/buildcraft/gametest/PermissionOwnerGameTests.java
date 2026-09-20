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
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
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
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** End-to-end owner identity and protection contract tests for BuildCraft automation. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class PermissionOwnerGameTests {
    private static final String EMPTY_TEMPLATE = "empty3x3x3";
    private static final UUID DENIED_OWNER_ID = UUID.fromString("67ceef18-f3af-47c4-8590-27d68fe65736");
    private static final UUID PLACEMENT_OWNER_ID = UUID.fromString("21b82fa5-6313-428e-a38a-f81e315a4fe2");
    private static final GameProfile DENIED_OWNER = new GameProfile(DENIED_OWNER_ID, "BCTestDeniedOwner");
    private static final ResourceLocation PROVIDER_ID = id("gametest_owner_denial");
    private static boolean protectionHooksInstalled;

    private PermissionOwnerGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void buildCraftAutomationPlayerUsesPlatformFakePlayer(GameTestHelper helper) {
        Player player = FakePlayerProvider.INSTANCE.getBuildCraftPlayer(helper.getLevel());
        require(helper, player instanceof FakePlayer,
            "BuildCraft automation actor is not the loader's canonical FakePlayer");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void machineOwnerIdentitySurvivesPersistenceRoundTrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, BCBuildersBlocks.QUARRY.get().defaultBlockState());
        BlockEntity blockEntity = helper.getBlockEntity(relative);
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
        require(helper, DENIED_OWNER_ID.equals(restoredOwner.getId()), "quarry owner UUID changed after NBT round-trip");
        require(helper, DENIED_OWNER.getName().equals(restoredOwner.getName()), "quarry owner name changed after NBT round-trip");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void blueprintCopyUsesPlacementActorInsteadOfSourceMachineOwner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sourceRelative = new BlockPos(1, 1, 1);
        BlockPos targetRelative = new BlockPos(2, 1, 1);
        BlockPos sourcePos = helper.absolutePos(sourceRelative);
        BlockPos targetPos = helper.absolutePos(targetRelative);
        helper.setBlock(sourceRelative, BCBuildersBlocks.QUARRY.get().defaultBlockState());
        helper.setBlock(targetRelative, Blocks.AIR.defaultBlockState());

        BlockEntity sourceEntity = helper.getBlockEntity(sourceRelative);
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
        require(helper, PLACEMENT_OWNER_ID.equals(placedOwner.getId()),
            "copied quarry cloned the source owner instead of using the placement actor");
        require(helper, !DENIED_OWNER_ID.equals(placedOwner.getId()),
            "source quarry owner leaked through copied block NBT");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void robotOwnerIdentitySurvivesPersistenceAndFeedsApi2Actor(GameTestHelper helper) {
        installPermissionProvider();
        EntityRobot original = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
        original.setOwner(DENIED_OWNER);
        CompoundTag saved = new CompoundTag();
        original.addAdditionalSaveData(saved);

        EntityRobot restored = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
        restored.readAdditionalSaveData(saved.copy());
        GameProfile restoredOwner = restored.getOwnerProfile();
        require(helper, DENIED_OWNER_ID.equals(restoredOwner.getId()), "robot owner UUID changed after NBT round-trip");
        require(helper, DENIED_OWNER.getName().equals(restoredOwner.getName()), "robot owner name changed after NBT round-trip");

        var actor = RobotAutomationSupport.actor(restored);
        require(helper, actor.type() == ActorType.MACHINE_OWNER, "owned robot did not become a MACHINE_OWNER API2 actor");
        require(helper, actor.playerId().filter(DENIED_OWNER_ID::equals).isPresent(), "robot API2 actor lost owner UUID");
        require(helper, actor.playerName().filter(DENIED_OWNER.getName()::equals).isPresent(), "robot API2 actor lost owner name");
        require(helper, !RobotAutomationSupport.permitsBlock(
            restored, helper.absolutePos(new BlockPos(1, 1, 1)), WorldOperationKind.BLOCK_BREAK, OperationMode.EXECUTE
        ), "registered API2 permission provider did not deny the owned robot");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
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

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void manualInteractionIsNotOwnerLocked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos absolute = helper.absolutePos(relative);
        helper.setBlock(relative, BCBuildersBlocks.QUARRY.get().defaultBlockState());
        BlockEntity blockEntity = helper.getBlockEntity(relative);
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

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void robotDismantleIsNotOwnerLocked(GameTestHelper helper) {
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
        require(helper, result != InteractionResult.PASS,
            "robot owner became an ACL: another player must still be able to dismantle/steal the robot");
        require(helper, robot.isRemoved(), "non-owner wrench dismantle did not convert the robot back to items");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void platformProtectionHooksReceiveMachineOwnerForBreakAndPlace(GameTestHelper helper) {
        installProtectionHooks();
        ServerLevel level = helper.getLevel();
        BlockPos breakRelative = new BlockPos(1, 1, 1);
        BlockPos placeRelative = new BlockPos(2, 1, 1);
        BlockPos breakPos = helper.absolutePos(breakRelative);
        BlockPos placePos = helper.absolutePos(placeRelative);
        helper.setBlock(breakRelative, Blocks.STONE.defaultBlockState());
        helper.setBlock(placeRelative, Blocks.AIR.defaultBlockState());

        require(helper, BlockUtil.breakBlockAndGetDrops(
            level, breakPos, new ItemStack(Items.DIAMOND_PICKAXE), DENIED_OWNER
        ).isEmpty(), "NeoForge protection hook did not cancel BuildCraft owner break");
        require(helper, level.getBlockState(breakPos).is(Blocks.STONE), "cancelled owner break still changed the world");

        Player actor = FakePlayerProvider.INSTANCE.getFakePlayer(level, DENIED_OWNER, placePos);
        require(helper, !BlockUtil.placeBlock(level, placePos, Blocks.STONE.defaultBlockState(), actor, Direction.UP, 3),
            "NeoForge protection hook did not cancel BuildCraft owner placement");
        require(helper, level.getBlockState(placePos).isAir(), "cancelled owner placement was not rolled back");
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
        NeoForge.EVENT_BUS.addListener(PermissionOwnerGameTests::onBreak);
        NeoForge.EVENT_BUS.addListener(PermissionOwnerGameTests::onPlace);
        protectionHooksInstalled = true;
    }

    private static void onBreak(BlockEvent.BreakEvent event) {
        if (DENIED_OWNER_ID.equals(event.getPlayer().getUUID())) event.setCanceled(true);
    }

    private static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player player && DENIED_OWNER_ID.equals(player.getUUID())) event.setCanceled(true);
    }

    private static CompoundTag saveMachineState(BlockEntity blockEntity, GameTestHelper helper) {
        CompoundTag tag = new CompoundTag();
        Method oneArg = findMethod(blockEntity.getClass(), "saveAdditional", 1);
        Method twoArg = findMethod(blockEntity.getClass(), "saveAdditional", 2);
        try {
            if (oneArg != null) {
                oneArg.setAccessible(true);
                oneArg.invoke(blockEntity, tag);
                return tag;
            }
            if (twoArg != null) {
                twoArg.setAccessible(true);
                twoArg.invoke(blockEntity, tag, helper.getLevel().registryAccess());
                return tag;
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot save machine owner state", e);
        }
        throw new IllegalStateException("No saveAdditional method on " + blockEntity.getClass().getName());
    }

    private static void loadMachineState(BlockEntity blockEntity, CompoundTag tag, GameTestHelper helper) {
        Method oneArg = findMethod(blockEntity.getClass(), "load", 1);
        Method twoArg = findMethod(blockEntity.getClass(), "loadAdditional", 2);
        try {
            if (oneArg != null) {
                oneArg.setAccessible(true);
                oneArg.invoke(blockEntity, tag.copy());
                return;
            }
            if (twoArg != null) {
                twoArg.setAccessible(true);
                twoArg.invoke(blockEntity, tag.copy(), helper.getLevel().registryAccess());
                return;
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot load machine owner state", e);
        }
        throw new IllegalStateException("No load method on " + blockEntity.getClass().getName());
    }

    private static Method findMethod(Class<?> start, String name, int parameterCount) {
        for (Class<?> type = start; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) return method;
            }
        }
        return null;
    }

    private static ResourceLocation id(String path) {
        return Objects.requireNonNull(ResourceLocation.tryParse("buildcraft:" + path));
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}
