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
import java.util.function.Supplier;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.mojang.blaze3d.vertex.VertexConsumer;

import buildcraft.lib.client.model.MutableVertex;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.misc.GuiUtil;
import buildcraft.lib.misc.MathUtil;
import buildcraft.lib.misc.SpriteUtil;
import buildcraft.lib.misc.VecUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.transfer.v1.client.fluid.FluidVariantRendering;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import buildcraft.lib.fluid.FabricFluidProperties;
import buildcraft.lib.fluid.FluidStack;
import buildcraft.lib.compat.neoforge263.fluids.IFluidTank;
import net.minecraft.core.registries.BuiltInRegistries;
import buildcraft.lib.compat.RenderCompat;

/** Can render 3D fluid cuboid's, up to 1x1x1 in size. Note that they *must* be contained within the 1x1x1 block space -
 * you can't use this to render off large multiblocks. Not thread safe -- this uses static variables so you should only
 * call this from the main client thread. */
// Perhaps move this into IModelRenderer? And that way we get the buffer, force shaders to cope with fluids (?!), etc
public class FluidRenderer {

    private static final EnumMap<FluidSpriteType, Map<FluidVariant, TextureAtlasSprite>> fluidSprites =
        new EnumMap<>(FluidSpriteType.class);
    public static final MutableVertex vertex = new MutableVertex();
    private static final boolean[] DEFAULT_FACES = { true, true, true, true, true, true };

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

    private static FluidVariant variant(Fluid fluid, FluidStack stack) {
        if (fluid == null || fluid == net.minecraft.world.level.material.Fluids.EMPTY)
            return FluidVariant.blank();
        return FluidVariant.of(fluid, stack == null || stack.isEmpty()
            ? net.minecraft.core.component.DataComponentPatch.EMPTY : stack.getComponentsPatch());
    }

    private static int colour(Fluid fluid, FluidStack stack) {
        FluidVariant variant = variant(fluid, stack);
        return variant.isBlank() ? 0xFFFFFFFF : FluidVariantRendering.getColor(variant);
    }

    /** Refreshes all fluid sprites after the 1.20 block atlas has been uploaded. */
    public static void onTextureStitchPost(ClientAtlas.After event) {
        if (!net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS.equals(event.getAtlas().location())) {
            return;
        }
        clearSpriteCache();


        // Do not prewarm fluid models here: on 26.2+ the atlas uploads before the
        // ModelManager installs FluidStateModelSet. Eager lookups throw for EVERY
        // registered fluid and cache missing sprites for the entire session.
        // getFluidSprite() resolves the baked model lazily after initialization.

    }

    private static void clearSpriteCache() {
        for (FluidSpriteType type : FluidSpriteType.values()) {
            fluidSprites.get(type).clear();
        }
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
     *  With a raw fluid, the component-bearing texture variant cannot be selected
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
        if (FabricFluidProperties.isLighterThanAir(fluidType)) {
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
            if (min.x >= 1) {
                xTexDiff = Math.floor(min.x);
            } else if (min.x < 0) {
                xTexDiff = Math.floor(min.x);
            } else {
                xTexDiff = 0;
            }
            if (min.y >= 1) {
                yTexDiff = Math.floor(min.y);
            } else if (min.y < 0) {
                yTexDiff = Math.floor(min.y);
            } else {
                yTexDiff = 0;
            }
            if (min.z >= 1) {
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
        vertex.colouri(colour(fluidType, texParam));

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

    /** Component-sensitive Fabric render lookup; still/frozen and flowing sprites are distinct. */
    public static TextureAtlasSprite getFluidSprite(FluidSpriteType type, Fluid fluid, FluidStack stack) {
        FluidVariant identity = variant(fluid, stack);
        if (identity.isBlank()) return SpriteUtil.missingSprite();
        FluidSpriteType selected = type == null ? FluidSpriteType.STILL : type;
        TextureAtlasSprite existing = fluidSprites.get(selected).get(identity);
        if (existing != null) return existing;
        try {
            var model = Minecraft.getInstance().getModelManager()
                .getFluidStateModelSet().get(identity.getFluid().defaultFluidState());
            var material = selected == FluidSpriteType.FLOWING
                ? model.flowingMaterial() : model.stillMaterial();
            if (material == null || material.sprite() == null)
                return SpriteUtil.missingSprite();
            TextureAtlasSprite texture = material.sprite();
            fluidSprites.get(selected).put(identity, texture);
            return texture;
        } catch (RuntimeException failure) {
            BCLog.logger.warn("[lib.fluid.render] Failed to resolve Fabric fluid sprite for {}",
                BuiltInRegistries.FLUID.getKey(fluid), failure);
            return SpriteUtil.missingSprite();
        }
    }

    /** Helper function to add a vertex. */
    private static void vertex(double x, double y, double z) {

        vertex.positiond(x, y, z);
        texmap.apply(x - xTexDiff, y - yTexDiff, z - zTexDiff);
        vertex.renderAsBlock(pose.pose(), pose.normal(), bb);
    }

    /** Draws the component-aware sprite into native GUI extraction (no GL-side Tesselator). */
    public static void drawFluidForGui(FluidStack stack, double startX, double startY,
        double endX, double endY, GuiGraphicsExtractor graphics) {
        if (stack == null || stack.isEmpty() || graphics == null) return;
        drawFluidForGuiNative(getFluidSprite(FluidSpriteType.STILL, stack.getFluid(), stack),
            colour(stack.getFluid(), stack), startX, startY, endX, endY, graphics);
    }

    public static void drawFluidForGui(Fluid fluid, double startX, double startY,
        double endX, double endY, GuiGraphicsExtractor graphics) {
        if (fluid == null || fluid == net.minecraft.world.level.material.Fluids.EMPTY || graphics == null) return;
        drawFluidForGuiNative(getFluidSprite(FluidSpriteType.STILL, fluid, null),
            colour(fluid, null), startX, startY, endX, endY, graphics);
    }

    private static void drawFluidForGuiNative(TextureAtlasSprite fluidSprite, int tint, double startX, double startY,
        double endX, double endY, GuiGraphicsExtractor guiGraphics) {
        if (fluidSprite == null) {
            fluidSprite = RenderCompat.blockSprites().apply(MissingTextureAtlasSprite.getLocation());
        }
        int minX = (int) Math.floor(Math.min(startX, endX));
        int maxX = (int) Math.ceil(Math.max(startX, endX));
        int minY = (int) Math.floor(Math.min(startY, endY));
        int maxY = (int) Math.ceil(Math.max(startY, endY));
        if (maxX <= minX || maxY <= minY) return;

        guiGraphics.enableScissor(minX, minY, maxX, maxY);
        try {
            for (int x = minX; x < maxX; x += 16) {
                for (int y = minY; y < maxY; y += 16) {
                    guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, fluidSprite, x, y, 16, 16, tint);
                }
            }
        } finally {
            guiGraphics.disableScissor();
        }
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
            vertex.texf(sprite.getU((float) realu), sprite.getV((float) realv));

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
