/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.render.fluid;

import buildcraft.lib.platform.client.ClientAtlas;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import buildcraft.lib.client.model.MutableVertex;
import buildcraft.lib.misc.GuiUtil;
import buildcraft.lib.misc.MathUtil;
import buildcraft.lib.misc.SpriteUtil;
import buildcraft.lib.misc.VecUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidTank;
import net.minecraftforge.registries.ForgeRegistries;

/** Can render 3D fluid cuboid's, up to 1x1x1 in size. Note that they *must* be contained within the 1x1x1 block space -
 * you can't use this to render off large multiblocks. Not thread safe -- this uses static variables so you should only
 * call this from the main client thread. */
// Perhaps move this into IModelRenderer? And that way we get the buffer, force shaders to cope with fluids (?!), etc
@OnlyIn(Dist.CLIENT)
public class FluidRenderer {

    private static final EnumMap<FluidSpriteType, Map<String, TextureAtlasSprite>> fluidSprites =
        new EnumMap<>(FluidSpriteType.class);
    public static final MutableVertex vertex = new MutableVertex();
    private static final boolean[] DEFAULT_FACES = { true, true, true, true, true, true };
    private static Function<ResourceLocation, TextureAtlasSprite> blockTexMap;
    
    // Cached fields that prevent lots of arguments on most methods
    private static VertexConsumer bb;
    private static Pose pose;
    private static TextureAtlasSprite sprite;
    private static TexMap texmap;
    private static int color = 0xFFFFFFFF; //ARGB
    private static boolean invertU, invertV;
    private static double xTexDiff, yTexDiff, zTexDiff;
    

    static {
        for (FluidSpriteType type : FluidSpriteType.values()) {
            fluidSprites.put(type, new HashMap<>());
        }
    }

    /** Refreshes all fluid sprites after the 1.20 block atlas has been uploaded. */
    public static void onTextureStitchPost(ClientAtlas.After event) {
        if (!InventoryMenu.BLOCK_ATLAS.equals(event.getAtlas().location())) {
            return;
        }
        clearSpriteCache();
        blockTexMap = event.getAtlas()::getSprite;

        for (Fluid fluid : ForgeRegistries.FLUIDS.getValues()) {
            IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid);
            ResourceLocation still = extensions.getStillTexture();
            ResourceLocation flowing = extensions.getFlowingTexture();
            if (still == null || flowing == null) {
                continue;
            }
            String key = fluid.getFluidType().getDescriptionId();
            TextureAtlasSprite stillSprite = blockTexMap.apply(still);
            fluidSprites.get(FluidSpriteType.STILL).put(key, stillSprite);
            fluidSprites.get(FluidSpriteType.FLOWING).put(key, blockTexMap.apply(flowing));

            // Forge removed ClientAtlas.Before in 1.20, so dynamically injecting a generated frozen sprite into
            // the block atlas is no longer supported. The frozen renderer still uses its repeated UV mapping, backed
            // by the fluid's still sprite, which preserves the visual contract without private resource-manager hacks.
            fluidSprites.get(FluidSpriteType.FROZEN).put(key, stillSprite);
        }
    }

    private static void clearSpriteCache() {
        for (FluidSpriteType type : FluidSpriteType.values()) {
            fluidSprites.get(type).clear();
        }
        blockTexMap = null;
    }

    /** Renders a fluid cuboid to the given vertex buffer. The cube shouldn't cross over any {@literal 0->1} boundary
     * (so the cube must be contained within a block).
     * 
     * @param type The type of sprite to use. See {@link FluidSpriteType} for more details.
     * @param tank The fluid tank that should be rendered.
     * @param min The minimum coordinate that the tank should be rendered from
     * @param max The maximum coordinate that the tank will be rendered to.
     * @param bbIn The {@link BufferBuilder} that the fluid will be rendered into.
     * @param sideRender A size 6 boolean array that determines if the face will be rendered. If it is null then all
     *            faces will be rendered. The indexes are determined by what {@link Direction#ordinal()} returns.
     * @see #renderFluid(FluidSpriteType, FluidStack, double, double, Vec3, Vec3, BufferBuilder, boolean[]) */
    public static void renderFluid(FluidSpriteType type, IFluidTank tank, Vec3 min, Vec3 max, VertexConsumer bbIn, Pose matrix,
        boolean[] sideRender) {
        renderFluid(type, tank.getFluid(), tank.getCapacity(), min, max, bbIn, matrix, sideRender);
    }

    /** Render's a fluid cuboid to the given vertex buffer. The cube shouldn't cross over any {@literal 0->1} boundary
     * (so the cube must be contained within a block).
     * 
     * @param type The type of sprite to use. See {@link FluidSpriteType} for more details.
     * @param fluid The stack that represents the fluid to render
     * @param cap The maximum amount of fluid that could be in the stack. Usually the capacity of the tank.
     * @param min The minimum coordinate that the tank should be rendered from
     * @param max The maximum coordinate that the tank will be rendered to.
     * @param bbIn The {@link BufferBuilder} that the fluid will be rendered into.
     * @param sideRender A size 6 boolean array that determines if the face will be rendered. If it is null then all
     *            faces will be rendered. The indexes are determined by what {@link Direction#ordinal()} returns. */
    public static void renderFluid(FluidSpriteType type, FluidStack fluid, int cap, Vec3 min, Vec3 max,
    		VertexConsumer bbIn, Pose matrix, boolean[] sideRender) {
        renderFluid(type, fluid, fluid == null ? 0 : fluid.getAmount(), cap, min, max, bbIn, matrix,sideRender);
    }

    /** Render's a fluid cuboid to the given vertex buffer. The cube shouldn't cross over any {@literal 0->1} boundary
     * (so the cube must be contained within a block).
     * 
     * @param type The type of sprite to use. See {@link FluidSpriteType} for more details.
     * @param fluid The stack that represents the fluid to render. Note that the amount from the stack is NOT used.
     * @param amount The actual amount of fluid in the stack. Is a "double" rather than an "int" as then you can
     *            interpolate between frames.
     * @param cap The maximum amount of fluid that could be in the stack. Usually the capacity of the tank.
     * @param min The minimum coordinate that the tank should be rendered from
     * @param max The maximum coordinate that the tank will be rendered to.
     * @param fluidBuffer The {@link VertexConsumer} that the fluid will be rendered into.
     * @param matrix the last position for render
     * @param sideRender A size 6 boolean array that determines if the face will be rendered. If it is null then all
     *            faces will be rendered. The indexes are determined by what {@link Direction#ordinal()} returns. */
    public static void renderFluid(FluidSpriteType type, FluidStack fluid, double amount, double cap, Vec3 min,
        Vec3 max, VertexConsumer fluidBuffer, Pose matrix, boolean[] sideRender) {
        if (fluid == null || fluid.getFluid() == null || amount <= 0 ) 
            return;
        renderFluidInteral(type, fluid, fluid.getFluid(), amount, cap, min, max, fluidBuffer, matrix, sideRender, 0x00F000F0);
    }
    
    /** Use only {@link Fluid} for render in order to avoid too often {@link FluidStack} creation
     *  As a price, parameter of {@link IClientFluidTypeExtensions#getFlowingTexture} will only been {@link FluidStack#EMPTY}
     *  So this method may render different effect if the Fluid need special use of the parameter.(Such as NBT data in the ItemStack)
     * 
     * @param type The type of sprite to use. See {@link FluidSpriteType} for more details.
     * @param fluidType The fluid to render.
     * @param amount The actual amount of fluid in the stack. Is a "double" rather than an "int" as then you can
     *            interpolate between frames.
     * @param cap The maximum amount of fluid that could be in the stack. Usually the capacity of the tank.
     * @param min The minimum coordinate that the tank should be rendered from
     * @param max The maximum coordinate that the tank will be rendered to.
     * @param fluidBuffer The {@link VertexConsumer} that the fluid will be rendered into.
     * @param matrix the last position for render
     * @param sideRender A size 6 boolean array that determines if the face will be rendered. If it is null then all
     *            faces will be rendered. The indexes are determined by what {@link Direction#ordinal()} returns. */
    public static void renderFluid(FluidSpriteType type, Fluid fluidType, double amount, double cap, Vec3 min,
	        Vec3 max, VertexConsumer fluidBuffer, Pose matrix, boolean[] sideRender) {
        if (fluidType == null || amount <= 0 ) 
            return;
        renderFluidInteral(type, FluidStack.EMPTY, fluidType, amount, cap, min, max, fluidBuffer, matrix, sideRender, 0x00F000F0);
    }
    
    /** Same as the interpolated FluidStack overload, but uses the caller-provided packed block/sky light. */
    public static void renderFluid(FluidSpriteType type, FluidStack fluid, double amount, double cap, Vec3 min, Vec3 max,
        VertexConsumer fluidBuffer, Pose matrix, boolean[] sideRender, int packedLight) {
        if (fluid == null || fluid.isEmpty() || amount <= 0) return;
        renderFluidInteral(type, fluid, fluid.getFluid(), amount, cap, min, max, fluidBuffer, matrix, sideRender, packedLight);
    }

    /** Same as the raw-fluid overload, but uses the caller-provided packed block/sky light. */
    public static void renderFluid(FluidSpriteType type, Fluid fluidType, double amount, double cap, Vec3 min, Vec3 max,
        VertexConsumer fluidBuffer, Pose matrix, boolean[] sideRender, int packedLight) {
        if (fluidType == null || amount <= 0) return;
        renderFluidInteral(type, FluidStack.EMPTY, fluidType, amount, cap, min, max, fluidBuffer, matrix, sideRender, packedLight);
    }

    private static void renderFluidInteral(FluidSpriteType type, FluidStack texParam, Fluid fluidType, double amount, double cap, Vec3 min,
	        Vec3 max, VertexConsumer fluidBuffer, Pose matrix, boolean[] sideRender, int packedLight) {
        if (sideRender == null) 
            sideRender = DEFAULT_FACES;
        if (type == null) 
            type = FluidSpriteType.STILL;
        sprite = getFluidSprite(type, fluidType, texParam);

        double height = Mth.clamp(amount / cap, 0, 1);
        final Vec3 realMin, realMax;
        if (fluidType.getFluidType().getDensity()<0) {
            realMin = VecUtil.replaceValue(min, Axis.Y, MathUtil.interp(1 - height, min.y, max.y));
            realMax = max;
        } else {
            realMin = min;
            realMax = VecUtil.replaceValue(max, Axis.Y, MathUtil.interp(height, min.y, max.y));
        }

        bb = fluidBuffer;
        pose = matrix;
        vertex.lighti(packedLight);


        final double xs = realMin.x;
        final double ys = realMin.y;
        final double zs = realMin.z;

        final double xb = realMax.x;
        final double yb = realMax.y;
        final double zb = realMax.z;

        if (type == FluidSpriteType.FROZEN) {
            if (min.x > 1) {
                xTexDiff = Math.floor(min.x);
            } else if (min.x < 0) {
                xTexDiff = Math.floor(min.x);
            } else {
                xTexDiff = 0;
            }
            if (min.y > 1) {
                yTexDiff = Math.floor(min.y);
            } else if (min.y < 0) {
                yTexDiff = Math.floor(min.y);
            } else {
                yTexDiff = 0;
            }
            if (min.z > 1) {
                zTexDiff = Math.floor(min.z);
            } else if (min.z < 0) {
                zTexDiff = Math.floor(min.z);
            } else {
                zTexDiff = 0;
            }
        } else {
            xTexDiff = 0;
            yTexDiff = 0;
            zTexDiff = 0;
        }

//        vertex.colouri(RenderUtil.swapARGBforABGR(fluid.getFluid()));
        vertex.colouri(IClientFluidTypeExtensions.of(fluidType).getTintColor());

        setTexMap(TexMap.XZ, false, false);
        if (sideRender[Direction.UP.ordinal()]) {
            vertex.normalf(0, 1, 0);
            vertex(xs, yb, zb);
            vertex(xb, yb, zb);
            vertex(xb, yb, zs);
            vertex(xs, yb, zs);
        }

        setTexMap(TexMap.XZ, false, true);
        if (sideRender[Direction.DOWN.ordinal()]) {
            vertex.normalf(0, -1, 0);
            vertex(xs, ys, zs);
            vertex(xb, ys, zs);
            vertex(xb, ys, zb);
            vertex(xs, ys, zb);
        }

        setTexMap(TexMap.ZY, false, true);
        if (sideRender[Direction.WEST.ordinal()]) {
            vertex.normalf(-1, 0, 0);
            vertex(xs, ys, zs);
            vertex(xs, ys, zb);
            vertex(xs, yb, zb);
            vertex(xs, yb, zs);
        }

        setTexMap(TexMap.ZY, true, true);
        if (sideRender[Direction.EAST.ordinal()]) {
            vertex.normalf(1, 0, 0);
            vertex(xb, yb, zs);
            vertex(xb, yb, zb);
            vertex(xb, ys, zb);
            vertex(xb, ys, zs);
        }

        setTexMap(TexMap.XY, true, true);
        if (sideRender[Direction.NORTH.ordinal()]) {
            vertex.normalf(0, 0, -1);
            vertex(xs, yb, zs);
            vertex(xb, yb, zs);
            vertex(xb, ys, zs);
            vertex(xs, ys, zs);
        }

        setTexMap(TexMap.XY, false, true);
        if (sideRender[Direction.SOUTH.ordinal()]) {
            vertex.normalf(0, 0, 1);
            vertex(xs, ys, zb);
            vertex(xb, ys, zb);
            vertex(xb, yb, zb);
            vertex(xs, yb, zb);
        }
        

        sprite = null;
        texmap = null;
        bb = null;
        pose = null;
    }


    private static void setTexMap(TexMap map, boolean flipU, boolean flipV) {
        texmap = map;
        invertU = flipU;
        invertV = flipV;
    }

    public static TextureAtlasSprite getFluidSprite(FluidSpriteType type, Fluid fluid, FluidStack stack) {
        if (fluid == null) {
            return SpriteUtil.missingSprite();
        }
        TextureAtlasSprite s = getSprite(type, fluid.getFluidType().getDescriptionId(), fluid, stack);
        return s != null ? s : SpriteUtil.missingSprite();
    }
    
    private static TextureAtlasSprite getSprite(FluidSpriteType type, String key, Fluid fluid, FluidStack stack) {
    	TextureAtlasSprite tex = fluidSprites.get(type).get(key);
    	if(blockTexMap == null) blockTexMap = Minecraft.getInstance()
               .getTextureAtlas(InventoryMenu.BLOCK_ATLAS);
    	switch(type){
		case FLOWING:
			if(tex == null) 
				tex = blockTexMap.apply(IClientFluidTypeExtensions.of(fluid).getFlowingTexture(stack));
			fluidSprites.get(type).put(key, tex);
			break;
		case STILL:
			if(tex == null) 
				tex = blockTexMap.apply(IClientFluidTypeExtensions.of(fluid).getStillTexture(stack));
			fluidSprites.get(type).put(key, tex);
			break;
		case FROZEN:
			if (tex == null) {
                tex = blockTexMap.apply(IClientFluidTypeExtensions.of(fluid).getStillTexture(stack));
            }
			fluidSprites.get(type).put(key, tex);
			break;
    	}
    	return tex;
    }

    /** Helper function to add a vertex. */
    private static void vertex(double x, double y, double z) {
    	
        vertex.positiond(x, y, z);
        texmap.apply(x - xTexDiff, y - yTexDiff, z - zTexDiff);
        vertex.renderAsBlock(pose.pose(), pose.normal(), bb);
    }

    /** Fills up the given region with the fluids texture, repeated. Ignores the value of {@link FluidStack#amount}. Use
     * {@link GuiUtil}'s fluid drawing methods in preference to this. */
    public static void drawFluidForGui(FluidStack fluid, double startX, double startY, double endX, double endY, Pose matrix) {
    	sprite = getFluidSprite(FluidSpriteType.STILL, fluid.getFluid(), fluid);
        color = IClientFluidTypeExtensions.of(fluid.getFluid()).getTintColor();
        drawFluidForGuiInteral(startX, startY, endX, endY, matrix);
    }
    
    public static void drawFluidForGui(Fluid fluid, double startX, double startY, double endX, double endY, Pose matrix) {
    	sprite = getFluidSprite(FluidSpriteType.STILL, fluid, FluidStack.EMPTY);
        color = IClientFluidTypeExtensions.of(fluid).getTintColor();
        drawFluidForGuiInteral(startX, startY, endX, endY, matrix);
    }

    private static void drawFluidForGuiInteral(double startX, double startY, double endX, double endY, Pose matrix) {
        if (sprite == null) {
            sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(MissingTextureAtlasSprite.getLocation());
        }
//        Minecraft.getInstance().getBlockRenderer().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
//        RenderUtil.setGLColorFromInt(fluid.getFluid().getFluidType(fluid));

//        Tessellator tess = Tessellator.getInstance();
//        bb = tess.getBuffer();
//        bb.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);

        // draw all the full sprites
        RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(((color>>16)&0xFF)/255f, ((color>>8)&0xFF)/255f, ((color)&0xFF)/255f, ((color>>24)&0xFF)/255f);//rgba
        BufferBuilder bufferbuilder = Tesselator.getInstance().getBuilder();
        bufferbuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bb = bufferbuilder;
        pose = matrix;

        double diffX = endX - startX;
        double diffY = endY - startY;

        int stepX = diffX > 0 ? 16 : -16;
        int stepY = diffY > 0 ? 16 : -16;

        int loopCountX = (int) Math.abs(diffX / 16);
        int loopCountY = (int) Math.abs(diffY / 16);

        double x = startX;
        for (int xc = 0; xc < loopCountX; xc++) {
            double y = startY;
            for (int yc = 0; yc < loopCountY; yc++) {
                guiVertex(x, y, 0, 0);
                guiVertex(x + stepX, y, 16, 0);
                guiVertex(x + stepX, y + stepY, 16, 16);
                guiVertex(x, y + stepY, 0, 16);
                y += stepY;
            }
            x += stepX;
        }

        if (diffX % 16 != 0) {
            double additionalWidth = diffX % 16;
            x = endX - additionalWidth;
            double xTex = Math.abs(additionalWidth);
            double y = startY;
            for (int yc = 0; yc < loopCountY; yc++) {
                guiVertex(x, y, 0, 0);
                guiVertex(endX, y, xTex, 0);
                guiVertex(endX, y + stepY, xTex, 16);
                guiVertex(x, y + stepY, 0, 16);
                y += stepY;
            }
        }

        if (diffY % 16 != 0) {
            double additionalHeight = diffY % 16;
            double y = endY - additionalHeight;
            double yTex = Math.abs(additionalHeight);
            x = startX;
            for (int xc = 0; xc < loopCountX; xc++) {
                guiVertex(x, y, 0, 0);
                guiVertex(x + stepX, y, 16, 0);
                guiVertex(x + stepX, endY, 16, yTex);
                guiVertex(x, endY, 0, yTex);
                x += stepX;
            }
        }

        if (diffX % 16 != 0 && diffY % 16 != 0) {
            double w = diffX % 16;
            double h = diffY % 16;
            x = endX - w;
            double y = endY - h;
            double tx = w < 0 ? -w : w;
            double ty = h < 0 ? -h : h;
            guiVertex(x, y, 0, 0);
            guiVertex(endX, y, tx, 0);
            guiVertex(endX, endY, tx, ty);
            guiVertex(x, endY, 0, ty);
            
        }
        BufferUploader.drawWithShader(bufferbuilder.end());
        RenderSystem.setShaderColor(1, 1, 1, 1);
        sprite = null;
        bb = null;
        color = 0xFFFFFFFF;
        pose = null;
    }

    private static void guiVertex(double x, double y, double u, double v) {
        float ru = sprite.getU(u);
        float rv = sprite.getV(v);
        bb.vertex(pose.pose(),(float)x, (float)y, 0);
        bb.uv(ru, rv);
        bb.endVertex();
    }


    /** Used to keep track of what position maps to what texture co-ord.
     * <p>
     * For example XY maps X to U and Y to V, and ignores Z */
    private enum TexMap {
        XY(true, true),
        XZ(true, false),
        ZY(false, true);

        /** If true, then X maps to U. Otherwise Z maps to U. */
        private final boolean ux;
        /** If true, then Y maps to V. Otherwise Z maps to V. */
        private final boolean vy;

        TexMap(boolean ux, boolean vy) {
            this.ux = ux;
            this.vy = vy;
        }

        /** Changes the vertex's texture co-ord to be the same as the position, for that face. (Uses {@link #ux} and
         * {@link #vy} to determine how they are mapped). */
        private void apply(double x, double y, double z) {
            double realu = ux ? x : z;
            double realv = vy ? y : z;
            if (invertU) {
                realu = 1 - realu;
            }
            if (invertV) {
                realv = 1 - realv;
            }
//            realu = realu > 1 ? 1 : realu;
//            realv = realv > 1 ? 1 : realv;
/*            max = max < realv ? realv : max;
            min = min > realv ? realv : min;
            if(realu > 1)
            BCLog.logger.debug(""+realu);*/
            vertex.texf(sprite.getU(realu * 16.0), sprite.getV(realv * 16.0)); // sprite.getU/V use logical 0..16 UV space, independent of pixel resolution
            
        }
    }

    public static class TankSize {
        public final Vec3 min;
        public final Vec3 max;

        public TankSize(int sx, int sy, int sz, int ex, int ey, int ez) {
            this(new Vec3(sx, sy, sz).scale(1 / 16.0), new Vec3(ex, ey, ez).scale(1 / 16.0));
        }

        public TankSize(Vec3 min, Vec3 max) {
            this.min = min;
            this.max = max;
        }

        public TankSize shrink(double by) {
            return shrink(by, by, by);
        }

        public TankSize shrink(double x, double y, double z) {
            return new TankSize(min.add(x, y, z), max.subtract(x, y, z));
        }

        public TankSize shink(Vec3 by) {
            return shrink(by.x, by.y, by.z);
        }

        public TankSize rotateY() {
            Vec3 _min = rotateY(min);
            Vec3 _max = rotateY(max);
            return new TankSize(VecUtil.min(_min, _max), VecUtil.max(_min, _max));
        }

        private static Vec3 rotateY(Vec3 vec) {
            return new Vec3(//
                1 - vec.z, //
                vec.y, //
                vec.x//
            );
        }
    }
}
