//? source if >=1.21.11
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.core.client.render;

import javax.annotation.Nullable;

import buildcraft.lib.compat.RenderCompat;
import buildcraft.lib.engine.TileEngineBase_BC8;
import buildcraft.lib.internal.enums.EnumPowerStage;
import buildcraft.lib.misc.SpriteUtil;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Native 1.21.11 renderer for every BC8 engine family except the MJ Dynamo's
 * type-specific wrapper (which delegates to this renderer).
 *
 * <p>On 1.21.11 the block model is only a particle/model placeholder; this BER owns the complete world geometry,
 * including the base, trunk, moving head, chamber and stage lights.</p>
 */
public class RenderEngine_BC8 implements BlockEntityRenderer<TileEngineBase_BC8, RenderEngine_BC8.EngineRenderState> {
    private static final float PX = 1.0F / 16.0F;
    private static final float EPS = 0.001F;
    private static final int FULL_BRIGHT = 0x00F000F0;

    private static final Identifier ID_TRUNK = Identifier.parse("buildcraftcore:blocks/engine/trunk");
    private static final Identifier ID_LIGHT = Identifier.parse("buildcraftcore:blocks/engine/trunk_light");
    private static final Identifier ID_CHAMBER = Identifier.parse("buildcraftlib:blocks/engine/chamber_base");

    private static final Identifier ID_REDSTONE_BACK = Identifier.parse("buildcraftcore:blocks/engine/wood/back");
    private static final Identifier ID_REDSTONE_SIDE = Identifier.parse("buildcraftcore:blocks/engine/wood/side");
    private static final Identifier ID_CREATIVE_BACK = Identifier.parse("buildcraftcore:blocks/engine/creative/back");
    private static final Identifier ID_CREATIVE_SIDE = Identifier.parse("buildcraftcore:blocks/engine/creative/side");
    private static final Identifier ID_STONE_BACK = Identifier.parse("buildcraftenergy:blocks/engine/stone/back");
    private static final Identifier ID_STONE_SIDE = Identifier.parse("buildcraftenergy:blocks/engine/stone/side");
    private static final Identifier ID_IRON_BACK = Identifier.parse("buildcraftenergy:blocks/engine/iron/back");
    private static final Identifier ID_IRON_SIDE = Identifier.parse("buildcraftenergy:blocks/engine/iron/side");
    private static final Identifier ID_FE_BACK = Identifier.parse("buildcraftenergy:blocks/engine/fe/back");
    private static final Identifier ID_FE_SIDE = Identifier.parse("buildcraftenergy:blocks/engine/fe/side");
    private static final Identifier ID_DYNAMO_BACK = Identifier.parse("buildcraftenergy:blocks/mj_dynamo/back");
    private static final Identifier ID_DYNAMO_FRONT = Identifier.parse("buildcraftenergy:blocks/mj_dynamo/front");
    private static final Identifier ID_DYNAMO_SIDE = Identifier.parse("buildcraftenergy:blocks/mj_dynamo/side");

    private static final Identifier TEX_TRUNK = texture("buildcraftcore", "blocks/engine/trunk");
    private static final Identifier TEX_LIGHT = texture("buildcraftcore", "blocks/engine/trunk_light");
    private static final Identifier TEX_CHAMBER = texture("buildcraftlib", "blocks/engine/chamber_base");

    private static final Identifier TEX_REDSTONE_BACK = texture("buildcraftcore", "blocks/engine/wood/back");
    private static final Identifier TEX_REDSTONE_SIDE = texture("buildcraftcore", "blocks/engine/wood/side");
    private static final Identifier TEX_CREATIVE_BACK = texture("buildcraftcore", "blocks/engine/creative/back");
    private static final Identifier TEX_CREATIVE_SIDE = texture("buildcraftcore", "blocks/engine/creative/side");
    private static final Identifier TEX_STONE_BACK = texture("buildcraftenergy", "blocks/engine/stone/back");
    private static final Identifier TEX_STONE_SIDE = texture("buildcraftenergy", "blocks/engine/stone/side");
    private static final Identifier TEX_IRON_BACK = texture("buildcraftenergy", "blocks/engine/iron/back");
    private static final Identifier TEX_IRON_SIDE = texture("buildcraftenergy", "blocks/engine/iron/side");
    private static final Identifier TEX_FE_BACK = texture("buildcraftenergy", "blocks/engine/fe/back");
    private static final Identifier TEX_FE_SIDE = texture("buildcraftenergy", "blocks/engine/fe/side");
    private static final Identifier TEX_DYNAMO_BACK = texture("buildcraftenergy", "blocks/mj_dynamo/back");
    private static final Identifier TEX_DYNAMO_FRONT = texture("buildcraftenergy", "blocks/mj_dynamo/front");
    private static final Identifier TEX_DYNAMO_SIDE = texture("buildcraftenergy", "blocks/mj_dynamo/side");

    /*
     * Public sprite fields are part of the shared client compatibility surface. The native 1.21.11 world renderer
     * binds engine PNGs directly and does not depend on this cache.
     */
    public static TextureAtlasSprite REDSTONE_BACK;
    public static TextureAtlasSprite REDSTONE_SIDE;
    public static TextureAtlasSprite CREATIVE_BACK;
    public static TextureAtlasSprite CREATIVE_SIDE;
    public static TextureAtlasSprite IRON_BACK;
    public static TextureAtlasSprite IRON_SIDE;
    public static TextureAtlasSprite STONE_BACK;
    public static TextureAtlasSprite STONE_SIDE;
    public static TextureAtlasSprite FE_BACK;
    public static TextureAtlasSprite FE_SIDE;
    public static TextureAtlasSprite DYNAMO_BACK;
    public static TextureAtlasSprite DYNAMO_FRONT;
    public static TextureAtlasSprite DYNAMO_SIDE;
    private static TextureAtlasSprite TRUNK;
    private static TextureAtlasSprite LIGHT;
    private static TextureAtlasSprite CHAMBER;

    public RenderEngine_BC8(BlockEntityRendererProvider.Context context) {
    }

    public EngineRenderState createRenderState() {
        return new EngineRenderState();
    }

    public void extractRenderState(TileEngineBase_BC8 tile, EngineRenderState state, float partialTick,
        Vec3 cameraPosition, @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        extractEngineState(tile, state, partialTick, crumblingOverlay);
    }

    /** Shared with the 1.21.11 MJ Dynamo wrapper. */
    public static void extractEngineState(TileEngineBase_BC8 tile, EngineRenderState state, float partialTick,
        @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderState.extractBase(tile, state, crumblingOverlay);
        state.visualType = tile.getVisualType();
        state.facing = tile.getCurrentFacing();
        state.powerStage = tile.getPowerStage();
        state.offset = tile.getRenderProgress(partialTick) * 8.0F * PX;
    }

    public void submit(EngineRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
        CameraRenderState cameraState) {
        submitEngine(state, poseStack, collector);
    }

    /** Shared with the 1.21.11 MJ Dynamo wrapper. */
    public static void submitEngine(EngineRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        EngineTextures textures = resolveTextures(state.visualType);

        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        rotateToFacing(poseStack, state.facing);

        int light = state.lightCoords;
        int overlay = OverlayTexture.NO_OVERLAY;

        /*
         * Do not sample the block atlas here. On 1.21.11, textures referenced only by a BER are not guaranteed to
         * be stitched into minecraft:blocks, so entity render types bind the engine PNGs directly. Keep face culling
         * enabled: rendering the back side of a base face that is flush with the ground can otherwise overwrite the
         * neighbouring block's top face and make it look transparent.
         */
        collector.submitCustomGeometry(poseStack, RenderCompat.entityCutout(textures.back),
            (pose, consumer) -> renderBaseCaps(pose, consumer, light, overlay));
        collector.submitCustomGeometry(poseStack, RenderCompat.entityCutout(textures.side),
            (pose, consumer) -> renderBaseSides(pose, consumer, light, overlay));
        collector.submitCustomGeometry(poseStack, RenderCompat.entityCutout(TEX_TRUNK),
            (pose, consumer) -> renderTrunk(pose, consumer, light, overlay));

        if (state.visualType == TileEngineBase_BC8.EngineVisualType.MJ_DYNAMO) {
            collector.submitCustomGeometry(poseStack, RenderCompat.entityCutout(textures.front),
                (pose, consumer) -> renderDynamoMovingHead(pose, consumer, light, state.offset, overlay));
        } else {
            collector.submitCustomGeometry(poseStack, RenderCompat.entityCutout(textures.front),
                (pose, consumer) -> renderMovingHeadCaps(pose, consumer, light, state.offset, overlay));
            collector.submitCustomGeometry(poseStack, RenderCompat.entityCutout(textures.side),
                (pose, consumer) -> renderMovingHeadSides(pose, consumer, light, state.offset, overlay));
        }

        if (state.offset > 0.0001F) {
            collector.submitCustomGeometry(poseStack, RenderCompat.entityCutout(TEX_CHAMBER),
                (pose, consumer) -> renderChamber(pose, consumer, light, state.offset, overlay));
        }
        collector.submitCustomGeometry(poseStack, RenderCompat.entityCutout(TEX_LIGHT),
            (pose, consumer) -> renderStageLights(pose, consumer, state.offset,
                stageTextureOffset(state.powerStage), overlay));

        poseStack.popPose();
    }

    /** Static 16x4x16 engine base caps. */
    private static void renderBaseCaps(PoseStack.Pose pose, VertexConsumer out, int light, int overlay) {
        float x0 = -8 * PX, x1 = 8 * PX;
        // Keep the back cap slightly inside the engine block. At the exact block boundary it is
        // coplanar with the neighbouring block face and 1.21.11 can depth-fight it away.
        float y0 = -8 * PX + EPS, y1 = -4 * PX;
        float z0 = -8 * PX, z1 = 8 * PX;

        quad(out, pose, x0,y0,z0, x1,y0,z0, x1,y0,z1, x0,y0,z1,
            uv(0),uv(0), uv(16),uv(16), light,overlay, 0,-1,0, 0.80F);
        quad(out, pose, x0,y1,z0, x0,y1,z1, x1,y1,z1, x1,y1,z0,
            uv(0),uv(0), uv(16),uv(16), light,overlay, 0,1,0, 1.00F);
    }

    /** Static 16x4x16 engine base sides. */
    private static void renderBaseSides(PoseStack.Pose pose, VertexConsumer out, int light, int overlay) {
        float x0 = -8 * PX, x1 = 8 * PX;
        float y0 = -8 * PX + EPS, y1 = -4 * PX;
        float z0 = -8 * PX, z1 = 8 * PX;
        float u0 = uv(0), u1 = uv(16), v0 = uv(0), v1 = uv(4);

        quad(out, pose, x1,y0,z0, x0,y0,z0, x0,y1,z0, x1,y1,z0,
            u0,v1, u1,v0, light,overlay, 0,0,-1, 0.80F);
        quad(out, pose, x0,y0,z1, x1,y0,z1, x1,y1,z1, x0,y1,z1,
            u0,v1, u1,v0, light,overlay, 0,0,1, 0.80F);
        quad(out, pose, x0,y0,z0, x0,y0,z1, x0,y1,z1, x0,y1,z0,
            u0,v1, u1,v0, light,overlay, -1,0,0, 0.65F);
        quad(out, pose, x1,y0,z1, x1,y0,z0, x1,y1,z0, x1,y1,z1,
            u0,v1, u1,v0, light,overlay, 1,0,0, 0.65F);
    }

    /** Static 8x12x8 trunk. */
    private static void renderTrunk(PoseStack.Pose pose, VertexConsumer out, int light, int overlay) {
        float x0 = -4 * PX, x1 = 4 * PX;
        float y0 = -4 * PX, y1 = 8 * PX;
        float z0 = -4 * PX, z1 = 4 * PX;

        float cu0 = uv(0), cu1 = uv(8), cv0 = uv(0), cv1 = uv(8);
        float su0 = uv(8), su1 = uv(16), sv0 = uv(0), sv1 = uv(12);

        quad(out, pose, x0,y0,z0, x1,y0,z0, x1,y0,z1, x0,y0,z1,
            cu0,cv0, cu1,cv1, light,overlay, 0,-1,0, 0.80F);
        quad(out, pose, x0,y1,z0, x0,y1,z1, x1,y1,z1, x1,y1,z0,
            cu0,cv0, cu1,cv1, light,overlay, 0,1,0, 1.00F);
        quad(out, pose, x1,y0,z0, x0,y0,z0, x0,y1,z0, x1,y1,z0,
            su0,sv1, su1,sv0, light,overlay, 0,0,-1, 0.80F);
        quad(out, pose, x0,y0,z1, x1,y0,z1, x1,y1,z1, x0,y1,z1,
            su0,sv1, su1,sv0, light,overlay, 0,0,1, 0.80F);
        quad(out, pose, x0,y0,z0, x0,y0,z1, x0,y1,z1, x0,y1,z0,
            su0,sv1, su1,sv0, light,overlay, -1,0,0, 0.65F);
        quad(out, pose, x1,y0,z1, x1,y0,z0, x1,y1,z0, x1,y1,z1,
            su0,sv1, su1,sv0, light,overlay, 1,0,0, 0.65F);
    }

    /** Normal BC engine moving-head top and bottom, using the back/front texture. */
    private static void renderMovingHeadCaps(PoseStack.Pose pose, VertexConsumer out, int light, float offset, int overlay) {
        float x0 = -8 * PX, x1 = 8 * PX;
        float y0 = -4 * PX + offset, y1 = offset;
        float z0 = -8 * PX, z1 = 8 * PX;

        quad(out, pose, x0,y0,z0, x1,y0,z0, x1,y0,z1, x0,y0,z1,
            uv(0),uv(0), uv(16),uv(16), light,overlay, 0,-1,0, 0.80F);
        quad(out, pose, x0,y1,z0, x0,y1,z1, x1,y1,z1, x1,y1,z0,
            uv(0),uv(0), uv(16),uv(16), light,overlay, 0,1,0, 0.90F);
    }

    /** Normal BC engine moving-head sides, using the four-pixel side strip. */
    private static void renderMovingHeadSides(PoseStack.Pose pose, VertexConsumer out, int light, float offset, int overlay) {
        float x0 = -8 * PX, x1 = 8 * PX;
        float y0 = -4 * PX + offset, y1 = offset;
        float z0 = -8 * PX, z1 = 8 * PX;
        float u0 = uv(0), u1 = uv(16), v0 = uv(0), v1 = uv(4);

        quad(out, pose, x1,y0,z0, x0,y0,z0, x0,y1,z0, x1,y1,z0,
            u0,v1, u1,v0, light,overlay, 0,0,-1, 0.80F);
        quad(out, pose, x0,y0,z1, x1,y0,z1, x1,y1,z1, x0,y1,z1,
            u0,v1, u1,v0, light,overlay, 0,0,1, 0.80F);
        quad(out, pose, x0,y0,z0, x0,y0,z1, x0,y1,z1, x0,y1,z0,
            u0,v1, u1,v0, light,overlay, -1,0,0, 0.70F);
        quad(out, pose, x1,y0,z1, x1,y0,z0, x1,y1,z0, x1,y1,z1,
            u0,v1, u1,v0, light,overlay, 1,0,0, 0.70F);
    }

    /** MJ Dynamo moving head is 12x4x12 and keeps all UVs on its front texture. */
    private static void renderDynamoMovingHead(PoseStack.Pose pose, VertexConsumer out, int light, float offset, int overlay) {
        float x0 = -6 * PX, x1 = 6 * PX;
        float y0 = -4 * PX + offset, y1 = offset;
        float z0 = -6 * PX, z1 = 6 * PX;

        renderHeadBox(out, pose, x0,x1,y0,y1,z0,z1,
            uv(0),uv(12),uv(0),uv(12),
            uv(0),uv(12),uv(12),uv(16), light,overlay);
    }

    private static void renderHeadBox(VertexConsumer out, PoseStack.Pose pose,
        float x0, float x1, float y0, float y1, float z0, float z1,
        float cu0, float cu1, float cv0, float cv1,
        float su0, float su1, float sv0, float sv1,
        int light, int overlay) {
        quad(out, pose, x0,y0,z0, x1,y0,z0, x1,y0,z1, x0,y0,z1,
            cu0,cv0, cu1,cv1, light,overlay, 0,-1,0, 0.80F);
        quad(out, pose, x0,y1,z0, x0,y1,z1, x1,y1,z1, x1,y1,z0,
            cu0,cv0, cu1,cv1, light,overlay, 0,1,0, 0.90F);
        quad(out, pose, x1,y0,z0, x0,y0,z0, x0,y1,z0, x1,y1,z0,
            su0,sv1, su1,sv0, light,overlay, 0,0,-1, 0.80F);
        quad(out, pose, x0,y0,z1, x1,y0,z1, x1,y1,z1, x0,y1,z1,
            su0,sv1, su1,sv0, light,overlay, 0,0,1, 0.80F);
        quad(out, pose, x0,y0,z0, x0,y0,z1, x0,y1,z1, x0,y1,z0,
            su0,sv1, su1,sv0, light,overlay, -1,0,0, 0.70F);
        quad(out, pose, x1,y0,z1, x1,y0,z0, x1,y1,z0, x1,y1,z1,
            su0,sv1, su1,sv0, light,overlay, 1,0,0, 0.70F);
    }

    /** Inner 10xN x10 chamber with side faces only. */
    private static void renderChamber(PoseStack.Pose pose, VertexConsumer out, int light, float offset, int overlay) {
        float x0 = -5 * PX, x1 = 5 * PX;
        float y0 = -4 * PX, y1 = -4 * PX + offset;
        float z0 = -5 * PX, z1 = 5 * PX;
        float u0 = uv(3), u1 = uv(13);
        float v0 = uv(0), v1 = uv(8);

        quad(out, pose, x1,y0,z0, x0,y0,z0, x0,y1,z0, x1,y1,z0,
            u0,v1, u1,v0, light,overlay, 0,0,-1, 1.00F);
        quad(out, pose, x0,y0,z1, x1,y0,z1, x1,y1,z1, x0,y1,z1,
            u0,v1, u1,v0, light,overlay, 0,0,1, 1.00F);
        quad(out, pose, x0,y0,z0, x0,y0,z1, x0,y1,z1, x0,y1,z0,
            u0,v1, u1,v0, light,overlay, -1,0,0, 1.00F);
        quad(out, pose, x1,y0,z1, x1,y0,z0, x1,y1,z0, x1,y1,z1,
            u0,v1, u1,v0, light,overlay, 1,0,0, 1.00F);
    }

    /** Two stage-light strips on each of the four trunk faces. */
    private static void renderStageLights(PoseStack.Pose pose, VertexConsumer out,
        float offset, int texOffset, int overlay) {
        float u0 = uv(texOffset), u1 = uv(texOffset + 2);
        float v0 = uv(4), v1 = uv(10 - offset * 12.0F);
        float yTop = 6 * PX + EPS;
        float yBottom = offset * 6.0F / 8.0F;

        if (yBottom >= yTop) {
            return;
        }

        renderLightPairZ(out, pose, -4 * PX - EPS, true, yBottom,yTop, u0,u1,v0,v1, overlay);
        renderLightPairZ(out, pose,  4 * PX + EPS, false, yBottom,yTop, u0,u1,v0,v1, overlay);
        renderLightPairX(out, pose, -4 * PX - EPS, true, yBottom,yTop, u0,u1,v0,v1, overlay);
        renderLightPairX(out, pose,  4 * PX + EPS, false, yBottom,yTop, u0,u1,v0,v1, overlay);
    }

    private static void renderLightPairZ(VertexConsumer out, PoseStack.Pose pose, float z, boolean north,
        float y0, float y1, float u0, float u1, float v0, float v1, int overlay) {
        renderLightStripZ(out, pose, -4*PX,-2*PX, z,north, y0,y1, u0,u1,v0,v1,overlay);
        renderLightStripZ(out, pose,  2*PX, 4*PX, z,north, y0,y1, u0,u1,v0,v1,overlay);
    }

    private static void renderLightStripZ(VertexConsumer out, PoseStack.Pose pose, float x0, float x1, float z,
        boolean north, float y0, float y1, float u0, float u1, float v0, float v1, int overlay) {
        if (north) {
            quad(out, pose, x1,y0,z, x0,y0,z, x0,y1,z, x1,y1,z,
                u0,v1, u1,v0, FULL_BRIGHT,overlay, 0,0,-1, 1.00F);
        } else {
            quad(out, pose, x0,y0,z, x1,y0,z, x1,y1,z, x0,y1,z,
                u0,v1, u1,v0, FULL_BRIGHT,overlay, 0,0,1, 1.00F);
        }
    }

    private static void renderLightPairX(VertexConsumer out, PoseStack.Pose pose, float x, boolean west,
        float y0, float y1, float u0, float u1, float v0, float v1, int overlay) {
        renderLightStripX(out, pose, x, -4*PX,-2*PX, west, y0,y1, u0,u1,v0,v1,overlay);
        renderLightStripX(out, pose, x,  2*PX, 4*PX, west, y0,y1, u0,u1,v0,v1,overlay);
    }

    private static void renderLightStripX(VertexConsumer out, PoseStack.Pose pose, float x, float z0, float z1,
        boolean west, float y0, float y1, float u0, float u1, float v0, float v1, int overlay) {
        if (west) {
            quad(out, pose, x,y0,z0, x,y0,z1, x,y1,z1, x,y1,z0,
                u0,v1, u1,v0, FULL_BRIGHT,overlay, -1,0,0, 1.00F);
        } else {
            quad(out, pose, x,y0,z1, x,y0,z0, x,y1,z0, x,y1,z1,
                u0,v1, u1,v0, FULL_BRIGHT,overlay, 1,0,0, 1.00F);
        }
    }

    private static void quad(VertexConsumer out, PoseStack.Pose pose,
        float ax,float ay,float az, float bx,float by,float bz,
        float cx,float cy,float cz, float dx,float dy,float dz,
        float u0,float v0,float u1,float v1,
        int light,int overlay, float nx,float ny,float nz, float shade) {
        vertex(out, pose, ax,ay,az, u0,v0, light,overlay,nx,ny,nz,shade);
        vertex(out, pose, bx,by,bz, u1,v0, light,overlay,nx,ny,nz,shade);
        vertex(out, pose, cx,cy,cz, u1,v1, light,overlay,nx,ny,nz,shade);
        vertex(out, pose, dx,dy,dz, u0,v1, light,overlay,nx,ny,nz,shade);
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, float x,float y,float z,
        float u,float v, int light,int overlay, float nx,float ny,float nz, float shade) {
        out.addVertex(pose.pose(), x,y,z)
            .setColor(shade, shade, shade, 1.0F)
            .setUv(u,v)
            .setOverlay(overlay)
            .setLight(light)
            .setNormal(pose, nx,ny,nz);
    }

    private static void rotateToFacing(PoseStack matrix, @Nullable Direction facing) {
        Direction dir = facing == null ? Direction.UP : facing;
        switch (dir) {
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

    private static int stageTextureOffset(@Nullable EnumPowerStage stage) {
        if (stage == null) return 0;
        return switch (stage) {
            case GREEN -> 2;
            case YELLOW -> 4;
            case RED -> 6;
            case OVERHEAT -> 8;
            default -> 0;
        };
    }

    private static EngineTextures resolveTextures(@Nullable TileEngineBase_BC8.EngineVisualType requestedType) {
        TileEngineBase_BC8.EngineVisualType type = requestedType == null
            ? TileEngineBase_BC8.EngineVisualType.REDSTONE : requestedType;

        return switch (type) {
            case CREATIVE -> new EngineTextures(TEX_CREATIVE_BACK, TEX_CREATIVE_BACK, TEX_CREATIVE_SIDE);
            case STONE -> new EngineTextures(TEX_STONE_BACK, TEX_STONE_BACK, TEX_STONE_SIDE);
            case IRON -> new EngineTextures(TEX_IRON_BACK, TEX_IRON_BACK, TEX_IRON_SIDE);
            case FE -> new EngineTextures(TEX_FE_BACK, TEX_FE_BACK, TEX_FE_SIDE);
            case MJ_DYNAMO -> new EngineTextures(TEX_DYNAMO_BACK, TEX_DYNAMO_FRONT, TEX_DYNAMO_SIDE);
            case REDSTONE -> new EngineTextures(TEX_REDSTONE_BACK, TEX_REDSTONE_BACK, TEX_REDSTONE_SIDE);
            default -> new EngineTextures(TEX_REDSTONE_BACK, TEX_REDSTONE_BACK, TEX_REDSTONE_SIDE);
        };
    }

    private static Identifier texture(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, "textures/" + path + ".png");
    }

    private static float uv(float px) {
        return px * PX;
    }

    private static TextureAtlasSprite sprite(Identifier id) {
        try {
            TextureAtlasSprite result = RenderCompat.blockSprites().apply(id);
            if (result != null) return result;
        } catch (RuntimeException ignored) {
        }
        try {
            return SpriteUtil.missingSprite();
        } catch (RuntimeException ignored) {
            return null;
        }
    }


    /** Compatibility refresh for callers that still read the public sprite fields. */
    public static void reloadSprites() {
        TRUNK = sprite(ID_TRUNK);
        LIGHT = sprite(ID_LIGHT);
        CHAMBER = sprite(ID_CHAMBER);
        REDSTONE_BACK = sprite(ID_REDSTONE_BACK);
        REDSTONE_SIDE = sprite(ID_REDSTONE_SIDE);
        CREATIVE_BACK = sprite(ID_CREATIVE_BACK);
        CREATIVE_SIDE = sprite(ID_CREATIVE_SIDE);
        STONE_BACK = sprite(ID_STONE_BACK);
        STONE_SIDE = sprite(ID_STONE_SIDE);
        IRON_BACK = sprite(ID_IRON_BACK);
        IRON_SIDE = sprite(ID_IRON_SIDE);
        FE_BACK = sprite(ID_FE_BACK);
        FE_SIDE = sprite(ID_FE_SIDE);
        DYNAMO_BACK = sprite(ID_DYNAMO_BACK);
        DYNAMO_FRONT = sprite(ID_DYNAMO_FRONT);
        DYNAMO_SIDE = sprite(ID_DYNAMO_SIDE);
    }

    public static void reloadDynamoSprites() {
        DYNAMO_BACK = sprite(ID_DYNAMO_BACK);
        DYNAMO_FRONT = sprite(ID_DYNAMO_FRONT);
        DYNAMO_SIDE = sprite(ID_DYNAMO_SIDE);
    }

    public static TextureAtlasSprite getTrunkLightSprite() {
        LIGHT = sprite(ID_LIGHT);
        return LIGHT;
    }

    public static TextureAtlasSprite getChamberSprite() {
        CHAMBER = sprite(ID_CHAMBER);
        return CHAMBER;
    }

    private record EngineTextures(Identifier back, Identifier front, Identifier side) {
    }

    public static class EngineRenderState extends BlockEntityRenderState {
        TileEngineBase_BC8.EngineVisualType visualType = TileEngineBase_BC8.EngineVisualType.REDSTONE;
        Direction facing = Direction.UP;
        EnumPowerStage powerStage = EnumPowerStage.BLUE;
        float offset;
    }
}
