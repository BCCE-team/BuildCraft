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

    public static boolean permitsEntity(EntityRobotBase robot, Level level, Entity target, WorldOperationKind operation, OperationMode mode) {
        return AutomationPermissionUtil.mayEntity(
            level, robot.blockPosition(), target, owner(robot), AutomationPermissionUtil.SOURCE_ROBOT, operation, mode
        );
    }

    public static boolean permits(AutomationRequest request) {
        AutomationResult result = BuildCraftApi.service(BuildCraftServices.AUTOMATION).execute(request);
        return result.status() != AutomationResult.Status.DENIED && result.status() != AutomationResult.Status.FAILED;
    }
}
