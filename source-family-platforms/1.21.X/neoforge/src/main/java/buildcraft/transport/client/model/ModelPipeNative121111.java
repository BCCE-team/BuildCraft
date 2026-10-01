//? source if >=1.21.11
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.transport.client.model;

import net.minecraft.client.Minecraft;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.joml.Vector3f;

import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.client.model.MutableVertex;
import buildcraft.lib.misc.SpriteUtil;
import buildcraft.transport.BCTransportSprites;
import buildcraft.transport.client.model.PipeModelCacheBase.PipeBaseCutoutKey;
import buildcraft.transport.client.model.PipeModelCacheBase.PipeBaseTranslucentKey;
import buildcraft.transport.client.model.key.PipeModelKey;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.tile.TilePipeHolder;
import buildcraft.silicon.plug.PluggableFacade;

import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;
import net.neoforged.neoforge.model.data.ModelData;
import net.neoforged.neoforge.model.data.ModelProperty;
import net.neoforged.neoforge.client.model.quad.BakedColors;
import net.neoforged.neoforge.client.model.quad.BakedNormals;

/**
 * Native 1.21.11 world model for BuildCraft pipes.
 *
 * <p>The pipe body must be terrain geometry, not BER/custom geometry. Rendering it through a block-entity renderer
 * bypasses chunk depth/terrain passes and makes pipes show through nearby blocks and disappear from shader terrain
 * reflection passes. This model converts the legacy BuildCraft pipe quads to the 1.21.11 baked-quad format and feeds
 * them to the normal chunk mesher.</p>
 */
public final class ModelPipeNative121111 implements DynamicBlockStateModel {
    public static final ModelPipeNative121111 INSTANCE = new ModelPipeNative121111();
    public static final ModelProperty<PipeRenderData> MODEL_DATA = new ModelProperty<>();

    /** Marker stored in the high byte of a tint index. The low 24 bits hold the already-computed pipe RGB. */
    public static final int PIPE_TINT_MARKER = 0x5A000000;
    private static final int PIPE_TINT_MARKER_MASK = 0xFF000000;
    private static final int PIPE_TINT_RGB_MASK = 0x00FFFFFF;

    private ModelPipeNative121111() {
    }

    public static boolean isPipeTint(int tintIndex) {
        return (tintIndex & PIPE_TINT_MARKER_MASK) == PIPE_TINT_MARKER;
    }

    public static int pipeTintColour(int tintIndex) {
        return 0xFF000000 | (tintIndex & PIPE_TINT_RGB_MASK);
    }

    /**
     * Captures all data required by the chunk mesher while still on the block-entity/model-data side.
     * The meshing worker never dereferences the live TilePipeHolder.
     */
    @Nullable
    public static PipeRenderData buildModelData(TilePipeHolder tile) {
        Pipe pipe = tile.getPipe();
        if (pipe == null || pipe == Pipe.EMPTY) {
            return null;
        }

        PipeModelKey key = pipe.getModel();
        if (key == null || key.definition == null) {
            return null;
        }

        List<buildcraft.lib.compat.mc121111.client.renderer.block.model.BakedQuad> legacyBaseCutout;
        List<buildcraft.lib.compat.mc121111.client.renderer.block.model.BakedQuad> legacyBaseTranslucent;
        List<buildcraft.lib.compat.mc121111.client.renderer.block.model.BakedQuad> legacyPlugCutout;
        List<buildcraft.lib.compat.mc121111.client.renderer.block.model.BakedQuad> legacyPlugTranslucent;

        PipeModelCachePluggable.PluggableKey plugCutoutKey =
            new PipeModelCachePluggable.PluggableKey(buildcraft.lib.compat.RenderCompat.cutout(), tile,
                ModelPipeNative121111::isNativeStaticPluggable);
        PipeModelCachePluggable.PluggableKey plugTranslucentKey =
            new PipeModelCachePluggable.PluggableKey(buildcraft.lib.compat.RenderCompat.translucent(), tile,
                ModelPipeNative121111::isNativeStaticPluggable);

        // PipeBaseModelGenStandard temporarily stretches shared template quads while it bakes long connections. Keep
        // both the pipe body and the static pluggable bakers behind the same lock. The immutable native quads produced
        // below are then safe for the 1.21.11 chunk-meshing workers.
        synchronized (PipeModelCacheBase.class) {
            legacyBaseCutout = PipeModelCacheBase.cacheCutout.bake(new PipeBaseCutoutKey(key));
            legacyBaseTranslucent = PipeModelCacheBase.cacheTranslucent.bake(new PipeBaseTranslucentKey(key));
            legacyPlugCutout = PipeModelCachePluggable.cacheCutoutAll.bake(plugCutoutKey);
            legacyPlugTranslucent = PipeModelCachePluggable.cacheTranslucentAll.bake(plugTranslucentKey);
        }

        List<BakedQuad> cutout = new ArrayList<>(legacyBaseCutout.size() + legacyPlugCutout.size());
        cutout.addAll(convertPipeBody(legacyBaseCutout, false));
        cutout.addAll(convertPluggables(legacyPlugCutout));

        List<BakedQuad> translucent = new ArrayList<>(legacyBaseTranslucent.size() + legacyPlugTranslucent.size());
        translucent.addAll(convertPipeBody(legacyBaseTranslucent, true));
        translucent.addAll(convertPluggables(legacyPlugTranslucent));

        TextureAtlasSprite particle = firstSprite(cutout);
        if (particle == null) {
            particle = firstSprite(translucent);
        }
        if (particle == null) {
            particle = fallbackParticle();
        }

        BlockModelPart cutoutPart = cutout.isEmpty()
            ? null
            : new PipeModelPart(List.copyOf(cutout), particle, ChunkSectionLayer.CUTOUT);
        BlockModelPart translucentPart = translucent.isEmpty()
            ? null
            : new PipeModelPart(List.copyOf(translucent), particle, ChunkSectionLayer.TRANSLUCENT);
        PipeGeometryKey geometryKey = new PipeGeometryKey(key, plugCutoutKey, plugTranslucentKey);
        return new PipeRenderData(geometryKey, cutoutPart, translucentPart, particle);
    }

    private static List<BakedQuad> convertPipeBody(
        List<buildcraft.lib.compat.mc121111.client.renderer.block.model.BakedQuad> legacy,
        boolean translucentLayer
    ) {
        return convert(legacy, translucentLayer, true);
    }

    private static boolean isNativeStaticPluggable(buildcraft.transport.internal.pluggable.PipePluggable pluggable) {
        // The native terrain quad path drops the glass alpha. Render only glass facades in RenderPipeHolder's
        // translucent dynamic pass; all other pluggables stay in the terrain model.
        if (!(pluggable instanceof PluggableFacade facade)) {
            return true;
        }
        int phase = facade.activeState;
        return phase < 0 || phase >= facade.states.phasedStates.length
            || !PluggableFacade.isGlass(facade.states.phasedStates[phase].stateInfo.state);
    }

    private static List<BakedQuad> convertPluggables(
        List<buildcraft.lib.compat.mc121111.client.renderer.block.model.BakedQuad> legacy
    ) {
        return convert(legacy, false, false);
    }

    private static List<BakedQuad> convert(
        List<buildcraft.lib.compat.mc121111.client.renderer.block.model.BakedQuad> legacy,
        boolean translucentLayer,
        boolean pipeBody
    ) {
        if (legacy == null || legacy.isEmpty()) {
            return List.of();
        }
        List<BakedQuad> result = new ArrayList<>(legacy.size());
        for (buildcraft.lib.compat.mc121111.client.renderer.block.model.BakedQuad baked : legacy) {
            if (baked == null || baked.getVertices().length < 32) {
                continue;
            }
            MutableQuad quad = new MutableQuad(baked);
            TextureAtlasSprite sprite = quad.getSprite();
            if (sprite == null) {
                continue;
            }

            Direction face = actualFace(quad);
            // Pipe-body colour arrives through per-vertex data and must be recovered into the native block-colour
            // path. Pluggables are different: their tint index encodes the pipe side and the
            // pluggable-specific colour slot, while some of them also rely on per-vertex alpha / colour data.
            if (pipeBody) {
                int tint = encodedTint(quad, translucentLayer);
                result.add(new BakedQuad(
                    position(quad.vertex_0),
                    position(quad.vertex_1),
                    position(quad.vertex_2),
                    position(quad.vertex_3),
                    UVPair.pack(quad.vertex_0.tex_u, quad.vertex_0.tex_v),
                    UVPair.pack(quad.vertex_1.tex_u, quad.vertex_1.tex_v),
                    UVPair.pack(quad.vertex_2.tex_u, quad.vertex_2.tex_v),
                    UVPair.pack(quad.vertex_3.tex_u, quad.vertex_3.tex_v),
                    tint,
                    face,
                    sprite,
                    true,
                    0,
                    BakedNormals.of(
                        BakedNormals.pack(quad.vertex_0.normal_x, quad.vertex_0.normal_y, quad.vertex_0.normal_z),
                        BakedNormals.pack(quad.vertex_1.normal_x, quad.vertex_1.normal_y, quad.vertex_1.normal_z),
                        BakedNormals.pack(quad.vertex_2.normal_x, quad.vertex_2.normal_y, quad.vertex_2.normal_z),
                        BakedNormals.pack(quad.vertex_3.normal_x, quad.vertex_3.normal_y, quad.vertex_3.normal_z)
                    ),
                    // Pipe RGB comes from the BlockColor tint above. Native terrain quads still need an explicit
                    // opaque vertex colour; the short constructor defaults this layer to a weak alpha.
                    BakedColors.of(0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF),
                    false
                ));
            } else {
                result.add(new BakedQuad(
                    position(quad.vertex_0),
                    position(quad.vertex_1),
                    position(quad.vertex_2),
                    position(quad.vertex_3),
                    UVPair.pack(quad.vertex_0.tex_u, quad.vertex_0.tex_v),
                    UVPair.pack(quad.vertex_1.tex_u, quad.vertex_1.tex_v),
                    UVPair.pack(quad.vertex_2.tex_u, quad.vertex_2.tex_v),
                    UVPair.pack(quad.vertex_3.tex_u, quad.vertex_3.tex_v),
                    quad.getTint(),
                    face,
                    sprite,
                    quad.isShade(),
                    lightEmission(quad),
                    BakedNormals.of(
                        BakedNormals.pack(quad.vertex_0.normal_x, quad.vertex_0.normal_y, quad.vertex_0.normal_z),
                        BakedNormals.pack(quad.vertex_1.normal_x, quad.vertex_1.normal_y, quad.vertex_1.normal_z),
                        BakedNormals.pack(quad.vertex_2.normal_x, quad.vertex_2.normal_y, quad.vertex_2.normal_z),
                        BakedNormals.pack(quad.vertex_3.normal_x, quad.vertex_3.normal_y, quad.vertex_3.normal_z)
                    ),
                    BakedColors.of(argb(quad.vertex_0), argb(quad.vertex_1), argb(quad.vertex_2), argb(quad.vertex_3)),
                    false
                ));
            }
        }
        return result;
    }

    private static Vector3f position(MutableVertex vertex) {
        return new Vector3f(vertex.position_x, vertex.position_y, vertex.position_z);
    }

    private static int argb(MutableVertex vertex) {
        return ((vertex.colour_a & 0xFF) << 24)
            | ((vertex.colour_r & 0xFF) << 16)
            | ((vertex.colour_g & 0xFF) << 8)
            | (vertex.colour_b & 0xFF);
    }

    private static int lightEmission(MutableQuad quad) {
        int max = 0;
        for (MutableVertex vertex : quad.vertexs) {
            max = Math.max(max, vertex.light_block & 0xFFFF);
        }
        return Math.min(15, max);
    }

    /** Use the real vertex normal. Inner pipe faces deliberately point opposite to the nominal generated face. */
    private static Direction actualFace(MutableQuad quad) {
        MutableVertex v = quad.vertex_0;
        float ax = Math.abs(v.normal_x);
        float ay = Math.abs(v.normal_y);
        float az = Math.abs(v.normal_z);
        if (ay >= ax && ay >= az) {
            return v.normal_y >= 0 ? Direction.UP : Direction.DOWN;
        }
        if (ax >= az) {
            return v.normal_x >= 0 ? Direction.EAST : Direction.WEST;
        }
        return v.normal_z >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * Legacy pipe quads bake material colour and directional shading into vertex colour. Native 1.21.11 block quads
     * no longer carry per-vertex colour, so recover the material RGB through BlockColor and let terrain lighting/AO
     * provide the single lighting pass.
     */
    private static int encodedTint(MutableQuad quad, boolean translucentLayer) {
        MutableVertex v = quad.vertex_0;
        boolean rawColour = translucentLayer || isBorderSprite(quad.getSprite());
        float divisor = 1.0F;
        if (!rawColour) {
            divisor = MutableQuad.diffuseLight(v.normal_x, v.normal_y, v.normal_z);
            Direction nominal = quad.getFace();
            if (nominal != null) {
                float dot = v.normal_x * nominal.getStepX()
                    + v.normal_y * nominal.getStepY()
                    + v.normal_z * nominal.getStepZ();
                if (dot < -0.5F) {
                    // dupDarker() applies this after recalculating the inward-facing diffuse value.
                    divisor *= 0.75F;
                }
            }
            if (!Float.isFinite(divisor) || divisor < 0.01F) {
                divisor = 1.0F;
            }
        }

        int r = clamp(Math.round(v.colour_r / divisor));
        int g = clamp(Math.round(v.colour_g / divisor));
        int b = clamp(Math.round(v.colour_b / divisor));
        if (r >= 250 && g >= 250 && b >= 250) {
            return -1;
        }
        return PIPE_TINT_MARKER | (r << 16) | (g << 8) | b;
    }

    private static boolean isBorderSprite(TextureAtlasSprite sprite) {
        return sprite != null && (sprite == BCTransportSprites.PIPE_COLOUR_BORDER_OUTER.getSprite()
            || sprite == BCTransportSprites.PIPE_COLOUR_BORDER_INNER.getSprite());
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    @Nullable
    private static TextureAtlasSprite firstSprite(List<BakedQuad> quads) {
        return quads.isEmpty() ? null : quads.get(0).sprite();
    }

    private static PipeRenderData modelData(BlockAndTintGetter level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        ModelData data = level.getModelData(pos);
        return data == null ? null : data.get(MODEL_DATA);
    }

    @Override
    public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        PipeRenderData data = modelData(level, pos);
        return data == null ? PipeModelKey.DEFAULT_KEY : data.geometryKey();
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
        List<BlockModelPart> parts) {
        PipeRenderData data = modelData(level, pos);
        if (data == null) {
            return;
        }
        if (data.cutout() != null) {
            parts.add(data.cutout());
        }
        if (data.translucent() != null) {
            parts.add(data.translucent());
        }
    }

    @Override
    public TextureAtlasSprite particleIcon() {
        return fallbackParticle();
    }

    @Override
    public TextureAtlasSprite particleIcon(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        PipeRenderData data = modelData(level, pos);
        return data == null || data.particle() == null ? fallbackParticle() : data.particle();
    }

    private static TextureAtlasSprite fallbackParticle() {
        // TerrainParticle dereferences the block-state model particle sprite in its constructor,
        // before BuildCraft gets a chance to replace it with the pipe-specific sprite.
        // Returning null here therefore crashes immediately when a pipe is broken.
        var modelManager = Minecraft.getInstance().getModelManager();
        if (modelManager != null) {
            var missingModel = modelManager.getMissingBlockStateModel();
            if (missingModel != null) {
                TextureAtlasSprite sprite = missingModel.particleIcon();
                if (sprite != null) {
                    return sprite;
                }
            }
        }
        TextureAtlasSprite sprite = SpriteUtil.missingSprite();
        if (sprite == null) {
            throw new IllegalStateException("Minecraft returned a null fallback particle sprite for the pipe holder");
        }
        return sprite;
    }

    /**
     * The dynamic model cache key must include pluggables as well as the pipe body. Otherwise 1.21.11 is allowed to
     * reuse terrain geometry from an identical pipe that has a completely different set of plugs installed.
     */
    public record PipeGeometryKey(PipeModelKey pipe, PipeModelCachePluggable.PluggableKey cutoutPluggables,
        PipeModelCachePluggable.PluggableKey translucentPluggables) {
    }

    public record PipeRenderData(PipeGeometryKey geometryKey, @Nullable BlockModelPart cutout,
        @Nullable BlockModelPart translucent, TextureAtlasSprite particle) {
    }

    private record PipeModelPart(List<BakedQuad> quads, TextureAtlasSprite particle,
        ChunkSectionLayer layer) implements BlockModelPart {
        @Override
        public List<BakedQuad> getQuads(@Nullable Direction side) {
            // Pipe faces never cover a complete voxel face and must not be culled by a neighbouring full block.
            return side == null ? quads : List.of();
        }

        @Override
        public boolean useAmbientOcclusion() {
            return true;
        }

        @Override
        public TextureAtlasSprite particleIcon() {
            return particle;
        }

        @Override
        public ChunkSectionLayer getRenderType(BlockState state) {
            return layer;
        }
    }
}
