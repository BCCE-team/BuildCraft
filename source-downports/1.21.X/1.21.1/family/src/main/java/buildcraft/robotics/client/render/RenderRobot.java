package buildcraft.robotics.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import buildcraft.robotics.entity.EntityRobot;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Renders a placed BuildCraft robot as the original 7.1.x renderer did: a textured 8x8x8 cube centered on the robot
 * position. Do not render the robot entity via the item renderer here; the item model has inventory transforms, which
 * makes placed robots look oversized and can also hide the per-profession entity texture when the client has not baked
 * the correct item override yet.
 */
public class RenderRobot extends EntityRenderer<EntityRobot> {
    private static final ResourceLocation OVERLAY_RED = ResourceLocation.fromNamespaceAndPath("buildcraftrobotics", "textures/entities/overlay_side.png");
    private static final ResourceLocation OVERLAY_CYAN = ResourceLocation.fromNamespaceAndPath("buildcraftrobotics", "textures/entities/overlay_bottom.png");
    private static final float MIN = -4.0F / 16.0F;
    private static final float MAX = 4.0F / 16.0F;
    private static final float TEX_SIZE = 32.0F;

    public RenderRobot(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.15F;
    }

    @Override
    public void render(EntityRobot robot, float yaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();

        // Same idea as the 1.7.10 renderer: rotate the cube by the robot yaw, then draw the cube around the entity
        // origin. The renderer-provided yaw is interpolated between ticks; using robot.getYRot() here made
        // turning visibly snap while the robot was moving.
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));

        PoseStack.Pose pose = poseStack.last();
        renderRobotCube(buffer.getBuffer(RenderType.entityCutoutNoCull(getTextureLocation(robot))),
                pose, packedLight, 1.0F, 1.0F);

        // The active overlay is always visible for inventory items; placed robots show it only while doing work.
        if (!robot.isAsleepForRendering()) {
            float storagePercent = Math.max(0.0F, Math.min(1.0F,
                    robot.getEnergyForRendering() / (float) EntityRobot.MAX_ENERGY));
            renderRobotCube(buffer.getBuffer(RenderType.entityTranslucent(OVERLAY_RED)),
                    pose, LightTexture.FULL_BRIGHT, 1.0F, storagePercent);
            renderRobotCube(buffer.getBuffer(RenderType.entityTranslucent(OVERLAY_CYAN)),
                    pose, LightTexture.FULL_BRIGHT, 1.0F, 1.0F);
        }

        poseStack.popPose();
        super.render(robot, yaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(EntityRobot robot) {
        return robot.getTexture();
    }

    private static void renderRobotCube(VertexConsumer builder, PoseStack.Pose pose, int light,
                                        float alpha, float brightness) {
        // Robot textures use the classic 32x32 entity-head layout: two 8x8 caps on the first row
        // and four 8x8 side faces on the second row. Matching 8x8 UV regions avoids transparent texels.
        quad(builder, pose, light,
                MIN, MAX, MIN, MAX, MAX, MIN, MAX, MAX, MAX, MIN, MAX, MAX,
                16, 0, 24, 8, 0, 1, 0, alpha, brightness); // up
        quad(builder, pose, light,
                MIN, MIN, MAX, MAX, MIN, MAX, MAX, MIN, MIN, MIN, MIN, MIN,
                8, 0, 16, 8, 0, -1, 0, alpha, brightness); // down
        quad(builder, pose, light,
                MIN, MIN, MIN, MAX, MIN, MIN, MAX, MAX, MIN, MIN, MAX, MIN,
                8, 8, 16, 16, 0, 0, -1, alpha, brightness); // north/front
        quad(builder, pose, light,
                MAX, MIN, MAX, MIN, MIN, MAX, MIN, MAX, MAX, MAX, MAX, MAX,
                24, 8, 32, 16, 0, 0, 1, alpha, brightness); // south/back
        quad(builder, pose, light,
                MIN, MIN, MAX, MIN, MIN, MIN, MIN, MAX, MIN, MIN, MAX, MAX,
                0, 8, 8, 16, -1, 0, 0, alpha, brightness); // west/right side in the legacy texture layout
        quad(builder, pose, light,
                MAX, MIN, MIN, MAX, MIN, MAX, MAX, MAX, MAX, MAX, MAX, MIN,
                16, 8, 24, 16, 1, 0, 0, alpha, brightness); // east/left side in the legacy texture layout
    }

    private static void quad(VertexConsumer builder, PoseStack.Pose pose, int light,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float x4, float y4, float z4,
                             float u1, float v1, float u2, float v2,
                             float nx, float ny, float nz, float alpha, float brightness) {
        vertex(builder, pose, light, x1, y1, z1, u1 / TEX_SIZE, v2 / TEX_SIZE, nx, ny, nz, alpha, brightness);
        vertex(builder, pose, light, x2, y2, z2, u2 / TEX_SIZE, v2 / TEX_SIZE, nx, ny, nz, alpha, brightness);
        vertex(builder, pose, light, x3, y3, z3, u2 / TEX_SIZE, v1 / TEX_SIZE, nx, ny, nz, alpha, brightness);
        vertex(builder, pose, light, x4, y4, z4, u1 / TEX_SIZE, v1 / TEX_SIZE, nx, ny, nz, alpha, brightness);
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
}
