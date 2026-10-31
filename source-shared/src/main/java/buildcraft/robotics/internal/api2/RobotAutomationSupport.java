package buildcraft.robotics.internal.api2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.api.v2.automation.AutomationRequest;
import buildcraft.api.v2.automation.AutomationResult;
import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.lib.misc.AutomationPermissionUtil;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/** Common owner identity and automation-policy bridge for classic robot AIs. */
public final class RobotAutomationSupport {
    private RobotAutomationSupport() {}

    public static GameProfile owner(EntityRobotBase robot) {
        return robot instanceof EntityRobot entity ? entity.getOwnerProfile() : FakePlayerProvider.NULL_PROFILE;
    }

    public static AutomationActor actor(EntityRobotBase robot) {
        return AutomationPermissionUtil.actor(owner(robot), AutomationPermissionUtil.SOURCE_ROBOT);
    }

    public static boolean permitsBlock(EntityRobotBase robot, BlockPos target, WorldOperationKind operation, OperationMode mode) {
        return permitsBlock(robot, robot.getCommandSenderWorld(), target, operation, mode);
    }

    public static boolean permitsBlock(EntityRobotBase robot, Level level, BlockPos target, WorldOperationKind operation, OperationMode mode) {
        return AutomationPermissionUtil.mayBlock(
            level, robot.blockPosition(), target, owner(robot), AutomationPermissionUtil.SOURCE_ROBOT, operation, mode
        );
    }

    /** Inventory access must be checked even when an old robot AI uses Container directly. */
    public static boolean permitsInventory(EntityRobotBase robot, DockingStation station,
                                           boolean withdrawing, OperationMode mode) {
        if (robot == null || station == null) return false;
        Level world = robot.getCommandSenderWorld();
        if (!(world instanceof net.minecraft.server.level.ServerLevel)) return false;
        BlockPos dock = new BlockPos(station.x(), station.y(), station.z());
        if (!world.hasChunkAt(dock)) return false;
        try {
            if (!permitsBlock(robot, world, dock, WorldOperationKind.INVENTORY_ACCESS, mode)) return false;
            net.minecraft.core.Direction face = withdrawing ? station.getItemInputSide() : station.getItemOutputSide();
            if (face != null) {
                BlockPos neighbor = dock.relative(face);
                if (!world.hasChunkAt(neighbor)
                    || !permitsBlock(robot, world, neighbor, WorldOperationKind.INVENTORY_ACCESS, mode)) return false;
            }
            return true;
        } catch (RuntimeException failure) {
            return false; // A broken permission provider is never treated as an allow.
        }
    }

    /**
     * Fluid transfer counterpart of permitsInventory: validates the dock and
     * the actual fluid-facing neighbor (not the item-facing neighbor).
     */
    public static boolean permitsFluidTransfer(EntityRobotBase robot, DockingStation station,
                                               boolean withdrawing, OperationMode mode) {
        if (robot == null || station == null) return false;
        Level world = robot.getCommandSenderWorld();
        if (!(world instanceof net.minecraft.server.level.ServerLevel)) return false;
        BlockPos dock = new BlockPos(station.x(), station.y(), station.z());
        WorldOperationKind kind = withdrawing ? WorldOperationKind.FLUID_DRAIN : WorldOperationKind.FLUID_PLACE;
        try {
            if (!world.hasChunkAt(dock)
                    || !permitsBlock(robot, world, dock, WorldOperationKind.INVENTORY_ACCESS, mode)
                    || !permitsBlock(robot, world, dock, kind, mode)) return false;
            net.minecraft.core.Direction face = withdrawing ? station.getFluidInputSide() : station.getFluidOutputSide();
            if (face != null) {
                BlockPos adjacent = dock.relative(face);
                if (!world.hasChunkAt(adjacent)
                        || !permitsBlock(robot, world, adjacent, WorldOperationKind.INVENTORY_ACCESS, mode)
                        || !permitsBlock(robot, world, adjacent, kind, mode)) return false;
            }
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    public static boolean permitsEntity(EntityRobotBase robot, Level level, Entity target, WorldOperationKind operation, OperationMode mode) {
        return AutomationPermissionUtil.mayEntity(
            level, robot.blockPosition(), target, owner(robot), AutomationPermissionUtil.SOURCE_ROBOT, operation, mode
        );
    }

    public static boolean permits(AutomationRequest request) {
        AutomationResult result = BuildCraftApi.service(BuildCraftServices.AUTOMATION).execute(request);
        // PASS and RETRY are not permission grants. No mutation follows an
        // automation request unless a provider explicitly accepts it.
        return result.status() == AutomationResult.Status.SUCCESS;
    }
}
