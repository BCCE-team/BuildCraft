/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.render.laser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import buildcraft.lib.client.render.laser.LaserData_BC8.LaserType;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
//? if <1.20 {
import com.mojang.math.Matrix3f;
import com.mojang.math.Matrix4f;
//?} else {
/*?
import org.joml.Matrix3f;
import org.joml.Matrix4f;
?*/
//?}

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

public class LaserRenderer_BC8 {
    private static final LoadingCache<LaserData_BC8, LaserCompiledBuffer> COMPILED_STATIC_LASERS;
    private static final Map<LaserType, CompiledLaserType> COMPILED_LASER_TYPES = new HashMap<>();
    private static final LoadingCache<LaserData_BC8, LaserCompiledBuffer> COMPILED_DYNAMIC_LASERS;
    private static final LoadingCache<List<LaserData_BC8>, LaserCompiledBuffer> COMPILED_DYNAMIC_BOXES;

    // Match the BLOCK-format terrain shaders used for laser geometry in the working 26.2 target.
    // The entity shader applies minecraft_mix_light to the vertex normal, making legacy markers
    // appreciably darker than the original BuildCraft block-shader/VBO pipeline.
    private static final RenderType WORLD_LASER_TYPE = RenderType.cutout();
    private static final RenderType WORLD_PREVIEW_TYPE = RenderType.translucent();
    private static final MultiBufferSource.BufferSource WORLD_LASER_BUFFER =
        MultiBufferSource.immediate(new BufferBuilder(2048));
    private static final MultiBufferSource.BufferSource WORLD_PREVIEW_BUFFER =
        MultiBufferSource.immediate(new BufferBuilder(2048));

    public static final VertexFormat FORMAT_LESS, FORMAT_ALL;

    static {
        COMPILED_STATIC_LASERS = CacheBuilder.newBuilder()//
            .expireAfterWrite(5, TimeUnit.SECONDS)//
            .build(CacheLoader.from(LaserRenderer_BC8::makeStaticLaser));

        COMPILED_DYNAMIC_LASERS = CacheBuilder.newBuilder()//
            .expireAfterWrite(5, TimeUnit.SECONDS)//
            .build(CacheLoader.from(LaserRenderer_BC8::makeDynamicLaser));

        COMPILED_DYNAMIC_BOXES = CacheBuilder.newBuilder()//
            .maximumSize(256)//
            .expireAfterAccess(10, TimeUnit.SECONDS)//
            .build(CacheLoader.from(LaserRenderer_BC8::makeDynamicLaserBox));

        FORMAT_LESS = new VertexFormat(ImmutableMap.<String, VertexFormatElement>builder()
                .put("Position", DefaultVertexFormat.ELEMENT_POSITION)
                .put("UV", DefaultVertexFormat.ELEMENT_UV)
                .put("UV1", DefaultVertexFormat.ELEMENT_UV1)
                .put("UV2", DefaultVertexFormat.ELEMENT_UV2)
                .build());

        FORMAT_ALL = new VertexFormat(ImmutableMap.<String, VertexFormatElement>builder()
                .put("Position", DefaultVertexFormat.ELEMENT_POSITION)
                .put("UV", DefaultVertexFormat.ELEMENT_UV)
                .put("UV1", DefaultVertexFormat.ELEMENT_UV1)
                .put("Color", DefaultVertexFormat.ELEMENT_COLOR)
                .put("UV2", DefaultVertexFormat.ELEMENT_UV2)
                .build());
    }

    /** Compiled UVs belong to one block-atlas generation; discard them after every reload. */
    public static void clearModels() {
        COMPILED_LASER_TYPES.clear();

        // Static and dynamic lasers both hold CPU-side atlas UVs.
        COMPILED_STATIC_LASERS.invalidateAll();
        COMPILED_STATIC_LASERS.cleanUp();
        COMPILED_DYNAMIC_LASERS.invalidateAll();
        COMPILED_DYNAMIC_LASERS.cleanUp();
        COMPILED_DYNAMIC_BOXES.invalidateAll();
        COMPILED_DYNAMIC_BOXES.cleanUp();
    }

    /** Retained for callers that still render with the legacy immediate block shader. */
    public static void setupLaserRenderState() {
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        //? if <1.20 {
        RenderSystem.setShader(GameRenderer::getBlockShader);
        //?} else {
        /*?
        RenderSystem.setShader(GameRenderer::getRendertypeSolidShader);
        ?*/
        //?}
        RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static CompiledLaserType compileType(LaserType laserType) {
        if (!COMPILED_LASER_TYPES.containsKey(laserType)) {
            COMPILED_LASER_TYPES.put(laserType, new CompiledLaserType(laserType));
        }
        return COMPILED_LASER_TYPES.get(laserType);
    }

    private static LaserCompiledBuffer makeStaticLaser(LaserData_BC8 data) {
        // Terrain shaders do not add entity directional light; retain the original
        // per-face diffuse colour exactly as in the 26.2 laser implementation.
        LaserCompiledBuffer.Builder renderer = new LaserCompiledBuffer.Builder(data.enableDiffuse);
        makeLaser(data, renderer, true);
        return renderer.build();
    }

    private static LaserCompiledBuffer makeDynamicLaser(LaserData_BC8 data) {
        // Legacy dynamic/BER laser callers are unchanged; they may use BLOCK format
        // and still require the original CPU diffuse colour.
        LaserCompiledBuffer.Builder renderer = new LaserCompiledBuffer.Builder(data.enableDiffuse);
        makeLaser(data, renderer, data.isOrgin);//default False
        return renderer.build();
    }

    private static void makeLaser(LaserData_BC8 data, ILaserRenderer renderer, boolean isStatic) {
        LaserContext ctx = new LaserContext(renderer, data, data.enableDiffuse, data.doubleFace, isStatic);
        CompiledLaserType type = compileType(data.laserType);
        type.bakeFor(ctx);
    }

    public static int computeLightmap(double x, double y, double z, int minBlockLight) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return 0;
        int blockLight =
            minBlockLight >= 15 ? 15 : Math.max(minBlockLight, getLightFor(level, LightLayer.BLOCK, x, y, z));
        int skyLight = getLightFor(level, LightLayer.SKY, x, y, z);
        return skyLight << 20 | blockLight << 4;
    }

    private static int getLightFor(Level level, LightLayer type, double x, double y, double z) {
        int max = 0;
        int count = 0;
        int sum = 0;
        boolean ao = Minecraft.useAmbientOcclusion();

        double xn = (x % 1 + 1) % 1;
        double yn = (y % 1 + 1) % 1;
        double zn = (z % 1 + 1) % 1;

        final double lowerBound = 0.3;
        final double upperBound = 1 - lowerBound;

        int xl = ao ? (xn < lowerBound ? -1 : 0) : -1;
        int yl = ao ? (yn < lowerBound ? -1 : 0) : -1;
        int zl = ao ? (zn < lowerBound ? -1 : 0) : -1;
        int xu = ao ? (xn > upperBound ? 1 : 0) : 1;
        int yu = ao ? (yn > upperBound ? 1 : 0) : 1;
        int zu = ao ? (zn > upperBound ? 1 : 0) : 1;

        for (int xp = xl; xp <= xu; xp++) {
            for (int yp = yl; yp <= yu; yp++) {
                for (int zp = zl; zp <= zu; zp++) {
                    //? if <1.20 {
                    int light = level.getBrightness(type, new BlockPos(x + xp, y + yp, z + zp));
                    //?} else {
                    /*?
                    int light = level.getBrightness(type, BlockPos.containing(x + xp, y + yp, z + zp));
                    ?*/
                    //?}
                    if (light > 0) {
                        sum += light;
                        count++;
                    }
                    max = Math.max(max, light);
                }
            }
        }

        if (ao) {
            return count == 0 ? 0 : sum / count;
        } else {
            return max;
        }
    }

    public static void renderLaserStatic(PoseStack pose, Matrix4f matrix, LaserData_BC8 data) {
        // World-space lasers use the stage-owned projection and native shader target.
        COMPILED_STATIC_LASERS.getUnchecked(data).render(
            pose.last().pose(), pose.last().normal(), worldLaserBuffer());
    }

    public static VertexConsumer worldLaserBuffer() {
        return new BlockFormatConsumer(WORLD_LASER_BUFFER.getBuffer(WORLD_LASER_TYPE));
    }

    public static VertexConsumer worldPreviewBuffer() {
        return new BlockFormatConsumer(WORLD_PREVIEW_BUFFER.getBuffer(WORLD_PREVIEW_TYPE));
    }

    /**
     * BLOCK has POSITION, COLOR, UV0, UV2 and NORMAL, but no UV1/overlay slot.
     * LaserCompiledBuffer and addon preview geometry also support NEW_ENTITY format,
     * so discard only the overlay field when submitting to the terrain shader.
     * Do not discard UV2: it carries the actual world lightmap.
     */
    private static final class BlockFormatConsumer implements VertexConsumer {
        private final VertexConsumer delegate;

        BlockFormatConsumer(VertexConsumer delegate) {
            this.delegate = delegate;
        }

        @Override public VertexConsumer vertex(double x, double y, double z) {
            delegate.vertex(x, y, z);
            return this;
        }
        @Override public VertexConsumer color(int r, int g, int b, int a) {
            delegate.color(r, g, b, a);
            return this;
        }
        @Override public VertexConsumer uv(float u, float v) {
            delegate.uv(u, v);
            return this;
        }
        @Override public VertexConsumer overlayCoords(int u, int v) {
            return this; // BLOCK has no overlay attribute.
        }
        @Override public VertexConsumer uv2(int u, int v) {
            delegate.uv2(u, v);
            return this;
        }
        @Override public VertexConsumer normal(float x, float y, float z) {
            delegate.normal(x, y, z);
            return this;
        }
        @Override public void endVertex() {
            delegate.endVertex();
        }
        @Override public void defaultColor(int r, int g, int b, int a) {
            delegate.defaultColor(r, g, b, a);
        }
        @Override public void unsetDefaultColor() {
            delegate.unsetDefaultColor();
        }
    }

    /** Submit world geometry without the old long-distance fog attenuation. */
    public static void flushWorldLasers() {
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        try {
            RenderSystem.setShaderFogStart(Float.MAX_VALUE / 2.0F);
            RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
            WORLD_LASER_BUFFER.endBatch();
            WORLD_PREVIEW_BUFFER.endBatch();
        } finally {
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
        }
    }

    /** Assumes a compatible BLOCK or NEW_ENTITY consumer. */
    public static void renderLaserDynamic(Matrix4f pose, Matrix3f normal, LaserData_BC8 data, VertexConsumer buffer) {
        LaserCompiledBuffer compiled = COMPILED_DYNAMIC_LASERS.getUnchecked(data);
        compiled.render(pose, normal, buffer);
    }

    private static LaserCompiledBuffer makeDynamicLaserBox(List<LaserData_BC8> data) {
        // The terrain shader does not add directional light. Restore the original
        // face diffuse, also used for renderer-submitted BuildCraft volume boxes.
        LaserCompiledBuffer.Builder renderer = new LaserCompiledBuffer.Builder(true);
        for (LaserData_BC8 segment : data) {
            makeLaser(segment, renderer, segment.isOrgin);
        }
        return renderer.build();
    }

    /** Renders an immutable laser-box segment list as one cached CPU buffer instead of dispatching each edge. */
    public static void renderLaserBoxDynamic(Matrix4f pose, Matrix3f normal, List<LaserData_BC8> data, VertexConsumer buffer) {
        if (data.isEmpty()) return;
        LaserCompiledBuffer compiled = COMPILED_DYNAMIC_BOXES.getUnchecked(List.copyOf(data));
        compiled.render(pose, normal, buffer);
    }
}
