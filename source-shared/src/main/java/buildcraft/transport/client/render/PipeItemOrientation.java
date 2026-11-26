package buildcraft.transport.client.render;

import buildcraft.lib.BCLibConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.Direction;
//? if <1.20 {
import com.mojang.math.Vector3f;
//?} else {
import com.mojang.math.Axis;
//?}

/** Applies the client's item orientation preference around the transported item's centre. */
final class PipeItemOrientation {
    private PipeItemOrientation() {}

    static void apply(PoseStack pose, Direction direction) {
        Direction facing = BCLibConfig.rotateTravelingItems.changeFacing(direction == null ? Direction.EAST : direction);
        float yaw = switch (facing) {
            case NORTH -> 90;
            case SOUTH -> -90;
            case WEST -> 180;
            default -> 0;
        };
        float roll = facing == Direction.UP ? 90 : facing == Direction.DOWN ? -90 : 0;
//? if <1.20 {
        if (yaw != 0) pose.mulPose(Vector3f.YP.rotationDegrees(yaw));
        if (roll != 0) pose.mulPose(Vector3f.ZP.rotationDegrees(roll));
//?} else {
        if (yaw != 0) pose.mulPose(Axis.YP.rotationDegrees(yaw));
        if (roll != 0) pose.mulPose(Axis.ZP.rotationDegrees(roll));
//?}
    }
}
