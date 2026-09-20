package buildcraft.energy.client.render;

import buildcraft.core.client.render.RenderEngine_BC8;
import buildcraft.energy.tile.TileDynamoMJ;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * World renderer for the BC8 MJ Dynamo.
 *
 * The dynamo is not a normal BuildCraft engine: the moving head is 12x12 rather
 * than 16x16. The original 1.12.2 model used a dedicated variable model for
 * exactly this geometry, so using RenderEngine_BC8 makes the installed block
 * visibly wrong. The static base/trunk remain supplied by ModelEngine; this
 * renderer reproduces the original moving head, chamber and stage lights.
 */
public class RenderDynamoMJ implements BlockEntityRenderer<TileDynamoMJ> {
    public RenderDynamoMJ(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(TileDynamoMJ tile, float partialTicks, PoseStack matrix, MultiBufferSource buffer, int light, int overlay) {
        TextureAtlasSprite chamber = RenderEngine_BC8.getChamberSprite();
        TextureAtlasSprite trunkLight = RenderEngine_BC8.getTrunkLightSprite();
        TextureAtlasSprite front = RenderEngine_BC8.DYNAMO_FRONT;
        if (front == null || chamber == null || trunkLight == null) return;

        matrix.pushPose();
        matrix.translate(0.5F, 0.5F, 0.5F);
        rotateToFacing(matrix, tile.getCurrentFacing());

        PoseStack.Pose pose = matrix.last();
        VertexConsumer builder = buffer.getBuffer(RenderType.solid());
        float offset = tile.getRenderProgress(partialTicks) * 8.0F / 16.0F;
        int texOffset = stageTextureOffset(tile);

        renderMovingHead(front, pose, builder, light, offset, overlay);
        for (int i = 0; i < 4; i++) {
            renderLight(trunkLight, pose, builder, 15728880, offset, overlay, texOffset);
            renderChamber(chamber, pose, builder, light, offset, overlay);
            matrix.mulPose(Axis.YP.rotationDegrees(90));
        }
        matrix.popPose();
    }

    private static void rotateToFacing(PoseStack matrix, net.minecraft.core.Direction facing) {
        if (facing == null) return;
        switch (facing) {
            case DOWN -> matrix.mulPose(Axis.XP.rotationDegrees(180));
            case EAST -> {
                matrix.mulPose(Axis.XP.rotationDegrees(90));
                matrix.mulPose(Axis.ZN.rotationDegrees(90));
            }
            case NORTH -> matrix.mulPose(Axis.XN.rotationDegrees(90));
            case SOUTH -> matrix.mulPose(Axis.XP.rotationDegrees(90));
            case WEST -> {
                matrix.mulPose(Axis.XP.rotationDegrees(90));
                matrix.mulPose(Axis.ZP.rotationDegrees(90));
            }
            case UP -> { }
        }
    }

    private static int stageTextureOffset(TileDynamoMJ tile) {
        return switch (tile.getPowerStage()) {
            case GREEN -> 2;
            case YELLOW -> 4;
            case RED -> 6;
            case OVERHEAT -> 8;
            default -> 0;
        };
    }

    /** Original BC8 base_moving element: [2,4+p,2] -> [14,8+p,14]. */
    private static void renderMovingHead(TextureAtlasSprite sprite, PoseStack.Pose pose,
        VertexConsumer builder, int light, float offset, int overlay) {
        float x0 = -6 / 16.0F, x1 = 6 / 16.0F;
        float z0 = -6 / 16.0F, z1 = 6 / 16.0F;
        float y0 = -4 / 16.0F + offset, y1 = offset;
        float u0 = sprite.getU(0), u12 = sprite.getU(12 / 16.0F);
        float v0 = sprite.getV(0), v12 = sprite.getV(12 / 16.0F), v16 = sprite.getV(1.0F);

        // down / up: uv 0,0 -> 12,12. Keep the winding consistent with the outward normal.
        // RenderType.solid() culls back faces, so reversed cap winding makes the moving-head lid disappear.
        vertex(builder, pose, x0, y0, z0, u0, v0, light, overlay, 0, -1, 0);
        vertex(builder, pose, x1, y0, z0, u12, v0, light, overlay, 0, -1, 0);
        vertex(builder, pose, x1, y0, z1, u12, v12, light, overlay, 0, -1, 0);
        vertex(builder, pose, x0, y0, z1, u0, v12, light, overlay, 0, -1, 0);

        vertex(builder, pose, x0, y1, z0, u0, v0, light, overlay, 0, 1, 0);
        vertex(builder, pose, x0, y1, z1, u0, v12, light, overlay, 0, 1, 0);
        vertex(builder, pose, x1, y1, z1, u12, v12, light, overlay, 0, 1, 0);
        vertex(builder, pose, x1, y1, z0, u12, v0, light, overlay, 0, 1, 0);

        // Four sides use the bottom 4 px of the original front texture: uv 0,12 -> 12,16.
        side(builder, pose, x0, y1, z0, x1, y1, z0, x1, y0, z0, x0, y0, z0,
            u0, u12, v12, v16, light, overlay, 0, 0, -1);
        side(builder, pose, x1, y1, z0, x1, y1, z1, x1, y0, z1, x1, y0, z0,
            u0, u12, v12, v16, light, overlay, 1, 0, 0);
        side(builder, pose, x1, y1, z1, x0, y1, z1, x0, y0, z1, x1, y0, z1,
            u0, u12, v12, v16, light, overlay, 0, 0, 1);
        side(builder, pose, x0, y1, z1, x0, y1, z0, x0, y0, z0, x0, y0, z1,
            u0, u12, v12, v16, light, overlay, -1, 0, 0);
    }

    private static void side(VertexConsumer builder, PoseStack.Pose pose,
        float ax, float ay, float az, float bx, float by, float bz,
        float cx, float cy, float cz, float dx, float dy, float dz,
        float u0, float u1, float v0, float v1, int light, int overlay, float nx, float ny, float nz) {
        vertex(builder, pose, ax, ay, az, u0, v0, light, overlay, nx, ny, nz);
        vertex(builder, pose, bx, by, bz, u1, v0, light, overlay, nx, ny, nz);
        vertex(builder, pose, cx, cy, cz, u1, v1, light, overlay, nx, ny, nz);
        vertex(builder, pose, dx, dy, dz, u0, v1, light, overlay, nx, ny, nz);
    }

    private static void renderLight(TextureAtlasSprite sprite, PoseStack.Pose pose,
        VertexConsumer builder, int light, float offset, int overlay, int texOffset) {
        float width = 8 / 16.0F;
        float minU = sprite.getU(texOffset / 16.0F);
        float maxU = sprite.getU((2 + texOffset) / 16.0F);
        float minV = sprite.getV(4 / 16.0F);
        float maxV = sprite.getV((10 - offset * 12) / 16.0F);
        float lightOffset = offset * 6 / 8;
        vertex(builder, pose, -4/16F, 6/16F+0.001F, -width/2-0.001F, minU, minV, light, overlay, 0,0,-1);
        vertex(builder, pose, -2/16F, 6/16F+0.001F, -width/2-0.001F, maxU, minV, light, overlay, 0,0,-1);
        vertex(builder, pose, -2/16F, lightOffset, -width/2-0.001F, maxU, maxV, light, overlay, 0,0,-1);
        vertex(builder, pose, -4/16F, lightOffset, -width/2-0.001F, minU, maxV, light, overlay, 0,0,-1);
        vertex(builder, pose, 2/16F, 6/16F+0.001F, -width/2-0.001F, minU, minV, light, overlay, 0,0,-1);
        vertex(builder, pose, 4/16F, 6/16F+0.001F, -width/2-0.001F, maxU, minV, light, overlay, 0,0,-1);
        vertex(builder, pose, 4/16F, lightOffset, -width/2-0.001F, maxU, maxV, light, overlay, 0,0,-1);
        vertex(builder, pose, 2/16F, lightOffset, -width/2-0.001F, minU, maxV, light, overlay, 0,0,-1);
    }

    /** Original BC8 chamber element: [3,4,3] -> [13,4+p,13], side faces only. */
    private static void renderChamber(TextureAtlasSprite sprite, PoseStack.Pose pose,
        VertexConsumer builder, int light, float offset, int overlay) {
        if (offset <= 0.0001F) return;
        float width = 10 / 16.0F;
        float minU = sprite.getU(3 / 16.0F);
        float maxU = sprite.getU(13 / 16.0F);
        float minV = sprite.getV(0);
        float maxV = sprite.getV(offset);
        vertex(builder, pose, -5/16F, -4/16F+offset, -width/2, minU, maxV, light, overlay, 0,0,-1);
        vertex(builder, pose,  5/16F, -4/16F+offset, -width/2, maxU, maxV, light, overlay, 0,0,-1);
        vertex(builder, pose,  5/16F, -4/16F,        -width/2, maxU, minV, light, overlay, 0,0,-1);
        vertex(builder, pose, -5/16F, -4/16F,        -width/2, minU, minV, light, overlay, 0,0,-1);
    }

    private static void vertex(VertexConsumer builder, PoseStack.Pose pose,
        float x, float y, float z, float u, float v, int light, int overlay, float nx, float ny, float nz) {
        builder.addVertex(pose.pose(), x, y, z).setColor(1F, 1F, 1F, 1F).setUv(u, v).setOverlay(overlay).setLight(light)
            .setNormal(pose, nx, ny, nz);
    }
}
