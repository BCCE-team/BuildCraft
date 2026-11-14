/*
 * Copyright (c) 2026 BuildCraft Community Edition contributors
 * Fluid cell preview based on the BuildCraft Redux blueprint renderer (MPL-2.0).
 */
package buildcraft.builders.client.render.pip;

import java.util.EnumSet;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.lib.client.render.fluid.FluidRenderer;
import buildcraft.lib.client.render.fluid.FluidSpriteType;
import buildcraft.lib.compat.RenderCompat;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import buildcraft.lib.fluid.FluidStack;
import net.minecraft.client.renderer.SubmitNodeCollector;

/** Textured, tinted fluid cell previews with adjacent-fluid face culling. */
public final class PreviewFluidRenderer {
    private static final int LIGHT = 0xF000F0;
    private PreviewFluidRenderer() {}

    public static void render(FluidState fluidState, Blueprint snapshot, BlockPos cell, int step,
        PoseStack pose, SubmitNodeCollector buffer) {
        if (fluidState == null || fluidState.isEmpty()) return;
        Fluid fluid = fluidState.getType();
        TextureAtlasSprite sprite = FluidRenderer.getFluidSprite(FluidSpriteType.STILL, fluid, FluidStack.EMPTY);
        if (sprite == null) return;
        int tint = PreviewFluidTint.color(fluidState);
        int alpha = (tint >>> 24) & 255;
        if (alpha == 0) alpha = 255;
        final int red = (tint >>> 16) & 255;
        final int green = (tint >>> 8) & 255;
        final int blue = tint & 255;
        final int opacity = alpha;
        final float height = fluidState.isSource() ? 1f
            : Math.max(0.125f, Math.min(1f, fluidState.getOwnHeight()));
        EnumSet<Direction> visible = EnumSet.allOf(Direction.class);
        if (step == 1) {
            for (Direction side : Direction.values()) {
                if (sameFluid(snapshot, cell.relative(side), fluid)) visible.remove(side);
            }
        }
        if (visible.isEmpty()) return;
        pose.pushPose();
        pose.translate(cell.getX(), cell.getY(), cell.getZ());
        buffer.submitCustomGeometry(pose, RenderCompat.entityTranslucent(TextureAtlas.LOCATION_BLOCKS),
            (drawPose, consumer) -> emit(drawPose, consumer, sprite, visible,
                red, green, blue, opacity, height));
        pose.popPose();
    }

    private static boolean sameFluid(Blueprint snapshot, BlockPos cell, Fluid fluid) {
        if (snapshot == null || snapshot.data == null || snapshot.size == null) return false;
        if (cell.getX() < 0 || cell.getY() < 0 || cell.getZ() < 0
            || cell.getX() >= snapshot.size.getX() || cell.getY() >= snapshot.size.getY()
            || cell.getZ() >= snapshot.size.getZ()) return false;
        int index = Snapshot.posToIndex(snapshot.size, cell);
        if (index < 0 || index >= snapshot.data.length) return false;
        int palette = snapshot.data[index];
        if (palette < 0 || palette >= snapshot.palette.size()) return false;
        ISchematicBlock block = snapshot.palette.get(palette);
        if (block == null) return false;
        BlockState state = block.getBlockStateForRender();
        if (state == null || state.getFluidState().isEmpty()) return false;
        return state.getFluidState().getType() == fluid;
    }

    private static void emit(PoseStack.Pose pose, VertexConsumer out, TextureAtlasSprite sprite,
        EnumSet<Direction> visible, int r, int g, int b, int a, float h) {
        float u0 = sprite.getU(0), u1 = sprite.getU(1);
        float v0 = sprite.getV(0), v1 = sprite.getV(1), vh = sprite.getV(1-h);
        for (Direction dir : visible) {
            switch (dir) {
                case UP -> quad(out, pose, r,g,b,a, 0,1,0,
                    0,h,0, u0,v0, 0,h,1, u0,v1, 1,h,1, u1,v1, 1,h,0, u1,v0);
                case DOWN -> quad(out, pose, r,g,b,a, 0,-1,0,
                    0,0,0, u0,v0, 1,0,0, u1,v0, 1,0,1, u1,v1, 0,0,1, u0,v1);
                case NORTH -> quad(out, pose, r,g,b,a, 0,0,-1,
                    0,0,0, u0,v1, 0,h,0, u0,vh, 1,h,0, u1,vh, 1,0,0, u1,v1);
                case SOUTH -> quad(out, pose, r,g,b,a, 0,0,1,
                    1,0,1, u0,v1, 1,h,1, u0,vh, 0,h,1, u1,vh, 0,0,1, u1,v1);
                case WEST -> quad(out, pose, r,g,b,a, -1,0,0,
                    0,0,1, u0,v1, 0,h,1, u0,vh, 0,h,0, u1,vh, 0,0,0, u1,v1);
                case EAST -> quad(out, pose, r,g,b,a, 1,0,0,
                    1,0,0, u0,v1, 1,h,0, u0,vh, 1,h,1, u1,vh, 1,0,1, u1,v1);
            }
        }
    }

    private static void quad(VertexConsumer out, PoseStack.Pose pose, int r,int g,int b,int a,
        float nx,float ny,float nz,
        float ax,float ay,float az,float au,float av,
        float bx,float by,float bz,float bu,float bv,
        float cx,float cy,float cz,float cu,float cv,
        float dx,float dy,float dz,float du,float dv) {
        vertex(out,pose,ax,ay,az,au,av,r,g,b,a,nx,ny,nz);
        vertex(out,pose,bx,by,bz,bu,bv,r,g,b,a,nx,ny,nz);
        vertex(out,pose,cx,cy,cz,cu,cv,r,g,b,a,nx,ny,nz);
        vertex(out,pose,dx,dy,dz,du,dv,r,g,b,a,nx,ny,nz);
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose,
        float x,float y,float z,float u,float v,
        int r,int g,int b,int a,float nx,float ny,float nz) {
        out.addVertex(pose,x,y,z).setColor(r,g,b,a).setUv(u,v)
            .setLight(LIGHT).setOverlay(OverlayTexture.NO_OVERLAY)
            .setNormal(pose,nx,ny,nz);
    }
}
