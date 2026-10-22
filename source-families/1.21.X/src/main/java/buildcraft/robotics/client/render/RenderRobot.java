//? source if >=1.21.1
package buildcraft.robotics.client.render;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import buildcraft.lib.compat.RenderCompat;
import buildcraft.robotics.entity.EntityRobot;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/** Native Minecraft 1.21.11 renderer for BuildCraft robots. */
public class RenderRobot extends EntityRenderer<EntityRobot, RenderRobot.RobotRenderState> {
    private static final Identifier OVERLAY_RED = Identifier.fromNamespaceAndPath(
        "buildcraftrobotics", "textures/entities/overlay_side.png"
    );
    private static final Identifier OVERLAY_CYAN = Identifier.fromNamespaceAndPath(
        "buildcraftrobotics", "textures/entities/overlay_bottom.png"
    );
    private static final float MIN = -4.0F / 16.0F;
    private static final float MAX = 4.0F / 16.0F;
    private static final float TEX_SIZE = 32.0F;

    public RenderRobot(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.15F;
    }

    public RobotRenderState createRenderState() {
        return new RobotRenderState();
    }

    public void extractRenderState(EntityRobot robot, RobotRenderState state, float partialTick) {
        // This call is mandatory in 1.21.11. Among other things it fills entityType, world position,
        // light, fire/name-tag state and shadow data. Leaving it out makes EntityRenderDispatcher
        // fail to resolve this renderer as soon as the freshly placed robot reaches the client.
        super.extractRenderState(robot, state, partialTick);

        state.yaw = robot.getYRot(partialTick);
        state.texture = robot.getTexture();
        state.asleep = robot.isAsleepForRendering();
        state.energy = robot.getEnergyForRendering();
    }

    public void submit(RobotRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
        CameraRenderState cameraState) {
        // Preserve vanilla entity features (name tags / leash state) before submitting BC geometry.
        super.submit(state, poseStack, collector, cameraState);

        Identifier texture = state.texture;
        if (texture == null) {
            return;
        }

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - state.yaw));

        final int light = state.lightCoords;
        collector.submitCustomGeometry(
            poseStack,
            RenderCompat.entityCutoutNoCull(texture),
            (pose, consumer) -> renderRobotCube(consumer, pose, light, 1.0F, 1.0F)
        );

        if (!state.asleep) {
            float storagePercent = Math.max(0.0F, Math.min(1.0F, state.energy / (float) EntityRobot.MAX_ENERGY));
            collector.submitCustomGeometry(
                poseStack,
                RenderCompat.entityTranslucent(OVERLAY_RED),
                (pose, consumer) -> renderRobotCube(
                    consumer, pose, LightTexture.FULL_BRIGHT, 1.0F, storagePercent
                )
            );
            collector.submitCustomGeometry(
                poseStack,
                RenderCompat.entityTranslucent(OVERLAY_CYAN),
                (pose, consumer) -> renderRobotCube(
                    consumer, pose, LightTexture.FULL_BRIGHT, 1.0F, 1.0F
                )
            );
        }

        poseStack.popPose();
    }

    private static void renderRobotCube(VertexConsumer builder, PoseStack.Pose pose, int light,
        float alpha, float brightness) {
        // BuildCraft 7.1.x used ModelRenderer(model, 0, 0).addBox(-4, -4, -4, 8, 8, 8) with 32x32 robot
        // textures. Keep the same UV layout as the 1.21.1 renderer.
        quad(builder, pose, light,
            MIN, MAX, MIN, MAX, MAX, MIN, MAX, MAX, MAX, MIN, MAX, MAX,
            16, 0, 24, 8, 0, 1, 0, alpha, brightness);
        quad(builder, pose, light,
            MIN, MIN, MAX, MAX, MIN, MAX, MAX, MIN, MIN, MIN, MIN, MIN,
            8, 0, 16, 8, 0, -1, 0, alpha, brightness);
        quad(builder, pose, light,
            MIN, MIN, MIN, MAX, MIN, MIN, MAX, MAX, MIN, MIN, MAX, MIN,
            8, 8, 16, 16, 0, 0, -1, alpha, brightness);
        quad(builder, pose, light,
            MAX, MIN, MAX, MIN, MIN, MAX, MIN, MAX, MAX, MAX, MAX, MAX,
            24, 8, 32, 16, 0, 0, 1, alpha, brightness);
        quad(builder, pose, light,
            MIN, MIN, MAX, MIN, MIN, MIN, MIN, MAX, MIN, MIN, MAX, MAX,
            0, 8, 8, 16, -1, 0, 0, alpha, brightness);
        quad(builder, pose, light,
            MAX, MIN, MIN, MAX, MIN, MAX, MAX, MAX, MAX, MAX, MAX, MIN,
            16, 8, 24, 16, 1, 0, 0, alpha, brightness);
    }

    private static void quad(VertexConsumer builder, PoseStack.Pose pose, int light,
        float x1, float y1, float z1,
        float x2, float y2, float z2,
        float x3, float y3, float z3,
        float x4, float y4, float z4,
        float u1, float v1, float u2, float v2,
        float nx, float ny, float nz, float alpha, float brightness) {
        // 1.21.11 entityCutoutNoCull/entityTranslucent use PER_FACE_LIGHTING. The shader chooses front/back lighting
        // from gl_FrontFacing, so quad winding must agree with the supplied outward normal. Submit the vertices in the
        // matching winding while preserving each corner's UV orientation.
        vertex(builder, pose, light, x1, y1, z1, u1 / TEX_SIZE, v2 / TEX_SIZE, nx, ny, nz, alpha, brightness);
        vertex(builder, pose, light, x4, y4, z4, u1 / TEX_SIZE, v1 / TEX_SIZE, nx, ny, nz, alpha, brightness);
        vertex(builder, pose, light, x3, y3, z3, u2 / TEX_SIZE, v1 / TEX_SIZE, nx, ny, nz, alpha, brightness);
        vertex(builder, pose, light, x2, y2, z2, u2 / TEX_SIZE, v2 / TEX_SIZE, nx, ny, nz, alpha, brightness);
    }

    private static void vertex(VertexConsumer builder, PoseStack.Pose pose, int light,
        float x, float y, float z, float u, float v,
        float nx, float ny, float nz, float alpha, float brightness) {
        builder.addVertex(pose.pose(), x, y, z)
            .setColor(brightness, brightness, brightness, alpha)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(pose, nx, ny, nz);
    }

    public static final class RobotRenderState extends EntityRenderState {
        @Nullable
        Identifier texture;
        float yaw;
        boolean asleep;
        int energy;
    }
}
