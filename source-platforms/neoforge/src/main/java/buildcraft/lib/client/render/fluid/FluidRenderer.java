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

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
//? if mc_26_x && (>=26.2) {
//? } else {
import com.mojang.blaze3d.vertex.BufferUploader;
//?}
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
//? if mc_26_x && (>=26.2) {
//? } else {
import com.mojang.blaze3d.vertex.Tesselator;
//?}
import com.mojang.blaze3d.vertex.VertexConsumer;
//? if mc_26_x && (>=26.2) {
//? } else {
import com.mojang.blaze3d.vertex.VertexFormat;
//?}

import buildcraft.lib.client.model.MutableVertex;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.misc.GuiUtil;
import buildcraft.lib.misc.MathUtil;
import buildcraft.lib.misc.SpriteUtil;
import buildcraft.lib.misc.VecUtil;
import net.minecraft.client.Minecraft;
//? if mc_26_x && (>=26.2) {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//? } else {
//?}
import net.minecraft.client.renderer.GameRenderer;
//? if mc_26_x && (>=26.2) {
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.FluidModel;
//? } else {
//?}
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
//? if mc_26_x && (>=26.2) {
import net.minecraft.resources.Identifier;
//? } else {
import net.minecraft.resources.ResourceLocation;
//?}
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
//? if mc_26_x && (>=26.2) {
//? } else {
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
//?}
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.IFluidTank;
import net.minecraft.core.registries.BuiltInRegistries;
//? if mc_26_x && (>=26.2) {
import buildcraft.lib.compat.RenderCompat;
//? } else {
//?}

/** Can render 3D fluid cuboid's, up to 1x1x1 in size. Note that they *must* be contained within the 1x1x1 block space -
 * you can't use this to render off large multiblocks. Not thread safe -- this uses static variables so you should only
 * call this from the main client thread. */
// Perhaps move this into IModelRenderer? And that way we get the buffer, force shaders to cope with fluids (?!), etc
public class FluidRenderer {

    private static final EnumMap<FluidSpriteType, Map<String, TextureAtlasSprite>> fluidSprites =
        new EnumMap<>(FluidSpriteType.class);
    public static final MutableVertex vertex = new MutableVertex();
    private static final boolean[] DEFAULT_FACES = { true, true, true, true, true, true };
//? if mc_26_x && (>=26.2) {
    private static Function<Identifier, TextureAtlasSprite> blockTexMap;

//? } else {
    private static Function<ResourceLocation, TextureAtlasSprite> blockTexMap;
    
//?}
    // Cached fields that prevent lots of arguments on most methods
    private static VertexConsumer bb;
    private static Pose pose;
    private static TextureAtlasSprite sprite;
    private static TexMap texmap;
    private static int color = 0xFFFFFFFF; //ARGB
    private static boolean invertU, invertV;
    private static double xTexDiff, yTexDiff, zTexDiff;
//? if mc_26_x && (>=26.2) {

//? } else {
    
//?}

    static {
        for (FluidSpriteType type : FluidSpriteType.values()) {
            fluidSprites.put(type, new HashMap<>());
        }
    }

//? if mc_26_x && (>=26.2) {
    private static Identifier getStillTextureSafe(Fluid fluid) {
//? } else {
    private static ResourceLocation getStillTextureSafe(Fluid fluid) {
//?}
        return getFluidTextureSafe(
            fluid, "still",
//? if mc_26_x && (>=26.2) {
            () -> fluidTexture(fluid, false),
//? } else {
            () -> IClientFluidTypeExtensions.of(fluid).getStillTexture(),
//?}
            MissingTextureAtlasSprite::getLocation
        );
    }

//? if mc_26_x && (>=26.2) {
    private static Identifier getFlowingTextureSafe(Fluid fluid) {
//? } else {
    private static ResourceLocation getFlowingTextureSafe(Fluid fluid) {
//?}
        return getFluidTextureSafe(
            fluid, "flowing",
//? if mc_26_x && (>=26.2) {
            () -> fluidTexture(fluid, true),
//? } else {
            () -> IClientFluidTypeExtensions.of(fluid).getFlowingTexture(),
//?}
            () -> getStillTextureSafe(fluid)
        );
    }

//? if mc_26_x && (>=26.2) {
    private static Identifier getStillTextureSafe(Fluid fluid, FluidStack stack) {
//? } else {
    private static ResourceLocation getStillTextureSafe(Fluid fluid, FluidStack stack) {
//?}
        return getFluidTextureSafe(
            fluid, "still",
//? if mc_26_x && (>=26.2) {
            () -> fluidTexture(fluid, false),
//? } else {
            () -> IClientFluidTypeExtensions.of(fluid).getStillTexture(stack),
//?}
            MissingTextureAtlasSprite::getLocation
        );
    }

//? if mc_26_x && (>=26.2) {
    private static Identifier getFlowingTextureSafe(Fluid fluid, FluidStack stack) {
//? } else {
    private static ResourceLocation getFlowingTextureSafe(Fluid fluid, FluidStack stack) {
//?}
        return getFluidTextureSafe(
            fluid, "flowing",
//? if mc_26_x && (>=26.2) {
            () -> fluidTexture(fluid, true),
//? } else {
            () -> IClientFluidTypeExtensions.of(fluid).getFlowingTexture(stack),
//?}
            () -> getStillTextureSafe(fluid, stack)
        );
    }

//? if mc_26_x && (>=26.2) {
    private static Identifier getFluidTextureSafe(
        Fluid fluid, String kind, Supplier<Identifier> getter, Supplier<Identifier> fallback
//? } else {
    private static ResourceLocation getFluidTextureSafe(
        Fluid fluid, String kind, Supplier<ResourceLocation> getter, Supplier<ResourceLocation> fallback
//?}
    ) {
        try {
//? if mc_26_x && (>=26.2) {
            Identifier texture = getter.get();
//? } else {
            ResourceLocation texture = getter.get();
//?}
            if (texture != null) {
                return texture;
            }
        } catch (RuntimeException exception) {
            BCLog.logger.warn(
                "[lib.fluid.render] Failed to resolve {} texture for fluid {}; using fallback",
                kind, BuiltInRegistries.FLUID.getKey(fluid), exception
            );
        }
        return fallback.get();
    }

//? if mc_26_x && (>=26.2) {
    /**
     * Fluid textures and tinting are now owned by the baked FluidModel rather
     * than IClientFluidTypeExtensions. Resolve the exact model selected by the
     * active resource reload so custom NeoForge fluid models remain visible.
     */
    private static FluidModel fluidModel(Fluid fluid) {
        return Minecraft.getInstance().getModelManager()
            .getFluidStateModelSet().get(fluid.defaultFluidState());
    }

    private static TextureAtlasSprite fluidSprite(Fluid fluid, boolean flowing) {
        FluidModel model = fluidModel(fluid);
        return (flowing ? model.flowingMaterial() : model.stillMaterial()).sprite();
    }

    private static Identifier fluidTexture(Fluid fluid, boolean flowing) {
        return fluidSprite(fluid, flowing).contents().name();
    }

    private static int fluidTint(Fluid fluid, FluidStack stack) {
        var tint = fluidModel(fluid).fluidTintSource();
        if (tint == null) {
            return 0xFFFFFFFF;
        }
        return stack == null ? tint.color(fluid.defaultFluidState()) : tint.colorAsStack(stack);
    }

//? } else {
//?}
    /** Refreshes all fluid sprites after the 1.20 block atlas has been uploaded. */
    public static void onTextureStitchPost(ClientAtlas.After event) {
//? if mc_26_x && (>=26.2) {
        if (!net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS.equals(event.getAtlas().location())) {
//? } else {
        if (!InventoryMenu.BLOCK_ATLAS.equals(event.getAtlas().location())) {
//?}
            return;
        }
        clearSpriteCache();
        blockTexMap = event.getAtlas()::getSprite;

//? if mc_26_x && (>=26.2) {
        // Do not prewarm fluid models here: on 26.2+ the atlas uploads before the
        // ModelManager installs FluidStateModelSet. Eager lookups throw for EVERY
        // registered fluid and cache missing sprites for the entire session.
        // getFluidSprite() resolves the baked model lazily after initialization.
//? } else {
        // The block atlas may finish before the fluid model set becomes available.
        // Resolve still/flowing/frozen sprites lazily from getFluidSprite() instead
        // of forcing every registered fluid during resource-reload callbacks.
//?}

    }

    private static void clearSpriteCache() {
        for (FluidSpriteType type : FluidSpriteType.values()) {
            fluidSprites.get(type).clear();
        }
        blockTexMap = null;
    }

    /** Renders a fluid cuboid to the given vertex buffer. The cube shouldn't cross over any {@literal 0->1} boundary
     * (so the cube must be contained within a block).
//? if mc_26_x && (>=26.2) {
     *
//? } else {
     * 
//?}
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
//? if mc_26_x && (>=26.2) {
     *
//? } else {
     * 
//?}
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
//? if mc_26_x && (>=26.2) {
     *
//? } else {
     * 
//?}
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
//? if mc_26_x && (>=26.2) {
        if (fluid == null || fluid.getFluid() == null || amount <= 0 )
//? } else {
        if (fluid == null || fluid.getFluid() == null || amount <= 0 ) 
//?}
            return;
        renderFluidInteral(type, fluid, fluid.getFluid(), amount, cap, min, max, fluidBuffer, matrix, sideRender, 0x00F000F0);
    }
//? if mc_26_x && (>=26.2) {

//? } else {
    
//?}
    /** Use only {@link Fluid} for render in order to avoid too often {@link FluidStack} creation
     *  As a price, parameter of {@link IClientFluidTypeExtensions#getFlowingTexture} will only been {@link FluidStack#EMPTY}
     *  So this method may render different effect if the Fluid need special use of the parameter.(Such as NBT data in the ItemStack)
//? if mc_26_x && (>=26.2) {
     *
//? } else {
     * 
//?}
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
//? if mc_26_x && (>=26.2) {
        if (fluidType == null || amount <= 0 )
//? } else {
        if (fluidType == null || amount <= 0 ) 
//?}
            return;
        renderFluidInteral(type, FluidStack.EMPTY, fluidType, amount, cap, min, max, fluidBuffer, matrix, sideRender, 0x00F000F0);
    }
//? if mc_26_x && (>=26.2) {

//? } else {
    
//?}
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
//? if mc_26_x && (>=26.2) {
        if (sideRender == null)
//? } else {
        if (sideRender == null) 
//?}
            sideRender = DEFAULT_FACES;
//? if mc_26_x && (>=26.2) {
        if (type == null)
//? } else {
        if (type == null) 
//?}
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
//? if mc_26_x && (>=26.2) {
        vertex.colouri(fluidTint(fluidType, texParam));
//? } else {
        vertex.colouri(IClientFluidTypeExtensions.of(fluidType).getTintColor(texParam));
//?}

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
//? if mc_26_x && (>=26.2) {

//? } else {
        
//?}

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
//? if mc_26_x && (>=26.2) {
        // 26.1 FluidModel already owns the baked atlas sprite. Re-resolving sprite.contents().name() through the
        // block atlas loses model-owned material identity for some fluids and leaves pipe fluid geometry untextured.
        try {
            return switch (type) {
                case FLOWING -> fluidSprite(fluid, true);
                case STILL, FROZEN -> fluidSprite(fluid, false);
            };
        } catch (RuntimeException exception) {
            BCLog.logger.warn(
                "[lib.fluid.render] Failed to resolve baked sprite for fluid {}; using atlas fallback",
                BuiltInRegistries.FLUID.getKey(fluid), exception
            );
            TextureAtlasSprite s = getSprite(type, fluid.getFluidType().getDescriptionId(), fluid, stack);
            return s != null ? s : SpriteUtil.missingSprite();
        }
//? } else {
        TextureAtlasSprite s = getSprite(type, fluid.getFluidType().getDescriptionId(), fluid, stack);
        return s != null ? s : SpriteUtil.missingSprite();
//?}
    }
//? if mc_26_x && (>=26.2) {

//? } else {
    
//?}
    private static TextureAtlasSprite getSprite(FluidSpriteType type, String key, Fluid fluid, FluidStack stack) {
    	TextureAtlasSprite tex = fluidSprites.get(type).get(key);
//? if mc_26_x && (>=26.2) {
    	if(blockTexMap == null) blockTexMap = RenderCompat.blockSprites();
//? } else {
    	if(blockTexMap == null) blockTexMap = Minecraft.getInstance()
               .getTextureAtlas(InventoryMenu.BLOCK_ATLAS);
//?}
    	switch(type){
		case FLOWING:
//? if mc_26_x && (>=26.2) {
			if(tex == null)
//? } else {
			if(tex == null) 
//?}
				tex = blockTexMap.apply(getFlowingTextureSafe(fluid, stack));
			fluidSprites.get(type).put(key, tex);
			break;
		case STILL:
//? if mc_26_x && (>=26.2) {
			if(tex == null)
//? } else {
			if(tex == null) 
//?}
				tex = blockTexMap.apply(getStillTextureSafe(fluid, stack));
			fluidSprites.get(type).put(key, tex);
			break;
		case FROZEN:
			if (tex == null) {
                tex = blockTexMap.apply(getStillTextureSafe(fluid, stack));
            }
			fluidSprites.get(type).put(key, tex);
			break;
    	}
    	return tex;
    }

    /** Helper function to add a vertex. */
    private static void vertex(double x, double y, double z) {
//? if mc_26_x && (>=26.2) {

//? } else {
    	
//?}
        vertex.positiond(x, y, z);
        texmap.apply(x - xTexDiff, y - yTexDiff, z - zTexDiff);
        vertex.renderAsBlock(pose.pose(), pose.normal(), bb);
    }

//? if mc_26_x && (>=26.2) {
    /** Native deferred GUI path. The GUI renderer no longer consumes PoseStack vertices,
     * so tile the atlas sprite through GuiGraphicsExtractor instead of immediate BufferBuilder rendering. */
    public static void drawFluidForGui(FluidStack fluid, double startX, double startY, double endX, double endY, GuiGraphicsExtractor guiGraphics) {
        if (fluid == null || fluid.isEmpty()) return;
        TextureAtlasSprite fluidSprite = getFluidSprite(FluidSpriteType.STILL, fluid.getFluid(), fluid);
        int tint = fluidTint(fluid.getFluid(), fluid);
        drawFluidForGuiNative(fluidSprite, tint, startX, startY, endX, endY, guiGraphics);
//? } else {
    /** Fills up the given region with the fluids texture, repeated. Ignores the value of {@link FluidStack#amount}. Use
     * {@link GuiUtil}'s fluid drawing methods in preference to this. */
    public static void drawFluidForGui(FluidStack fluid, double startX, double startY, double endX, double endY, Pose matrix) {
    	sprite = getFluidSprite(FluidSpriteType.STILL, fluid.getFluid(), fluid);
        color = IClientFluidTypeExtensions.of(fluid.getFluid()).getTintColor(fluid);
        drawFluidForGuiInteral(startX, startY, endX, endY, matrix);
    }
    
    public static void drawFluidForGui(Fluid fluid, double startX, double startY, double endX, double endY, Pose matrix) {
    	sprite = getFluidSprite(FluidSpriteType.STILL, fluid, FluidStack.EMPTY);
        color = IClientFluidTypeExtensions.of(fluid).getTintColor();
        drawFluidForGuiInteral(startX, startY, endX, endY, matrix);
//?}
    }

//? if mc_26_x && (>=26.2) {
    public static void drawFluidForGui(Fluid fluid, double startX, double startY, double endX, double endY, GuiGraphicsExtractor guiGraphics) {
        if (fluid == null) return;
        TextureAtlasSprite fluidSprite = getFluidSprite(FluidSpriteType.STILL, fluid, FluidStack.EMPTY);
        int tint = fluidTint(fluid, null);
        drawFluidForGuiNative(fluidSprite, tint, startX, startY, endX, endY, guiGraphics);
//? } else {
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
        BufferBuilder bufferbuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
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
        BufferUploader.drawWithShader(bufferbuilder.buildOrThrow());
        RenderSystem.setShaderColor(1, 1, 1, 1);
        sprite = null;
        bb = null;
        color = 0xFFFFFFFF;
        pose = null;
//?}
    }

//? if mc_26_x && (>=26.2) {
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
//? } else {
    private static void guiVertex(double x, double y, double u, double v) {
        float ru = sprite.getU((float) (u / 16.0));
        float rv = sprite.getV((float) (v / 16.0));
        bb.addVertex(pose.pose(), (float) x, (float) y, 0).setUv(ru, rv);
//?}
    }
//? if mc_26_x && (>=26.2) {
//? } else {

//?}

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
//? if mc_26_x && (>=26.2) {

//? } else {
            
//?}
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
//? if mc_26_x && (>=26.2) {
//? } else {

//?}
