/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.transport.client.model;

import net.minecraft.client.Minecraft;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.client.model.MutableVertex;
import buildcraft.lib.misc.SpriteUtil;
import buildcraft.transport.BCTransportSprites;
import buildcraft.transport.client.model.PipeModelCacheBase.PipeBaseCutoutKey;
import buildcraft.transport.client.model.PipeModelCacheBase.PipeBaseTranslucentKey;
import buildcraft.transport.client.model.key.PipeModelKey;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.tile.TilePipeHolder;

import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import java.util.IdentityHashMap;
import java.util.HashMap;
import buildcraft.silicon.plug.PluggableFacade;
import buildcraft.silicon.plug.FacadePhasedState;
import net.minecraft.client.color.block.BlockTintSource;
import java.util.function.Predicate;

/**
 * Native 26.1.2 world model for BuildCraft pipes.
 *
 * <p>The pipe body must be terrain geometry, not BER/custom geometry. Rendering it through a block-entity renderer
 * bypasses chunk depth/terrain passes and makes pipes show through nearby blocks and disappear from shader terrain
 * reflection passes. This model converts the legacy BuildCraft pipe quads to the native baked-quad format and feeds
 * them to the normal chunk mesher.</p>
 */
public final class ModelPipeNative2612 implements BlockStateModel {
    public static final ModelPipeNative2612 INSTANCE = new ModelPipeNative2612();

    /** Marker stored in the high byte of a tint index. The low 24 bits hold the already-computed pipe RGB. */
    public static final int PIPE_TINT_MARKER = 0x5A000000;
    private static final int PIPE_TINT_MARKER_MASK = 0xFF000000;
    private static final int PIPE_TINT_RGB_MASK = 0x00FFFFFF;

    private ModelPipeNative2612() {
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

        List<buildcraft.lib.compat.mc2612.client.renderer.block.model.BakedQuad> legacyBaseCutout;
        List<buildcraft.lib.compat.mc2612.client.renderer.block.model.BakedQuad> legacyBaseTranslucent;
        List<buildcraft.lib.compat.mc2612.client.renderer.block.model.BakedQuad> legacyPlugCutout;
        List<buildcraft.lib.compat.mc2612.client.renderer.block.model.BakedQuad> legacyPlugTranslucent;

        PipeModelCachePluggable.PluggableKey plugCutoutKey =
            new PipeModelCachePluggable.PluggableKey(buildcraft.lib.compat.RenderCompat.cutout(), tile,
                ModelPipeNative2612::isNativeStaticPluggable);
        PipeModelCachePluggable.PluggableKey plugTranslucentKey =
            new PipeModelCachePluggable.PluggableKey(buildcraft.lib.compat.RenderCompat.translucent(), tile,
                ModelPipeNative2612::isNativeStaticPluggable);

        // PipeBaseModelGenStandard temporarily stretches shared template quads while it bakes long connections. Keep
        // both the pipe body and the static pluggable bakers behind the same lock. The immutable native quads produced
        // below are then safe for asynchronous chunk-meshing workers.
        synchronized (PipeModelCacheBase.class) {
            var apiModel = buildcraft.lib.client.render.ApiPresentationModels.pipeBaseModel(pipe);
            if (apiModel != null && apiModel.hasBakedQuads()) {
                boolean dynamic = buildcraft.lib.client.render.ApiPresentationModels.animatedPipe(pipe);
                legacyBaseCutout = dynamic ? List.of()
                    : java.util.Arrays.stream(apiModel.getCutoutQuads())
                        .map(buildcraft.lib.client.model.MutableQuad::toBakedBlock).toList();
                legacyBaseTranslucent = dynamic ? List.of()
                    : java.util.Arrays.stream(apiModel.getTranslucentQuads())
                        .map(buildcraft.lib.client.model.MutableQuad::toBakedBlock).toList();
            } else {
                legacyBaseCutout = PipeModelCacheBase.cacheCutout.bake(new PipeBaseCutoutKey(key));
                legacyBaseTranslucent = PipeModelCacheBase.cacheTranslucent.bake(new PipeBaseTranslucentKey(key));
            }
            legacyPlugCutout = PipeModelCachePluggable.cacheCutoutAll.bake(plugCutoutKey);
            legacyPlugTranslucent = PipeModelCachePluggable.cacheTranslucentAll.bake(plugTranslucentKey);
        }

        Map<BakedQuad, int[]> vertexColours = new IdentityHashMap<>();
        Map<Integer, Integer> facadeTintColours = findFacadeColours(tile);
        List<BakedQuad> cutout = new ArrayList<>(legacyBaseCutout.size() + legacyPlugCutout.size());
        cutout.addAll(convertPipeBody(legacyBaseCutout, false, vertexColours));
        cutout.addAll(convertPluggables(legacyPlugCutout, false, vertexColours, facadeTintColours));

        // Glass is the only static pluggable with boundary faces on the translucent pass. Preserve
        // its boundary/cull-face classification instead of putting every face in getQuads(null).
        // The null bucket never consults the neighbouring block, so glass placed against opaque
        // terrain continued drawing covered faces (unlike a vanilla stained-glass block).
        List<BakedQuad> translucentBody = convertPipeBody(legacyBaseTranslucent, true, vertexColours);
        List<BakedQuad> translucentPlugs = convertPluggables(legacyPlugTranslucent, true, vertexColours, facadeTintColours);
        List<BakedQuad> translucent = new ArrayList<>(translucentBody.size() + translucentPlugs.size());
        translucent.addAll(translucentBody);
        translucent.addAll(translucentPlugs);

        TextureAtlasSprite particle = firstSprite(cutout);
        if (particle == null) {
            particle = firstSprite(translucent);
        }
        if (particle == null) {
            particle = fallbackParticle();
        }

        BlockStateModelPart cutoutPart = cutout.isEmpty()
            ? null
            : new PipeModelPart(List.copyOf(cutout), List.copyOf(cutout), Map.of(),
                particle, ChunkSectionLayer.CUTOUT);
        BlockStateModelPart translucentPart = translucent.isEmpty()
            ? null
            : translucentPart(translucentBody, translucentPlugs, translucent, particle);
        PipeGeometryKey geometryKey = new PipeGeometryKey(key, plugCutoutKey, plugTranslucentKey);
        return new PipeRenderData(geometryKey, cutoutPart, translucentPart, particle, vertexColours);
    }

    /**
     * Keep only the exterior boundary of a translucent facade in the directional quad buckets.
     * Minecraft calls Block.shouldRenderFace for those buckets, and therefore drops a glass face
     * when the adjacent terrain completely covers it. Pipe and inset facade geometry must stay
     * unculled: a pipe arm is not a full block face and would disappear beside solid neighbours.
     *
     * <p>PluggableFacade puts glass (and stained glass) on the translucent layer. Non-glass basic
     * facades belong to the cutout layer and never take this path.</p>
     */
    private static PipeModelPart translucentPart(List<BakedQuad> pipeQuads, List<BakedQuad> plugQuads,
        List<BakedQuad> allQuads, TextureAtlasSprite particle) {
        List<BakedQuad> unculled = new ArrayList<>(allQuads.size());
        unculled.addAll(pipeQuads);
        Map<Direction, List<BakedQuad>> culled = new EnumMap<>(Direction.class);
        for (BakedQuad quad : plugQuads) {
            Direction face = quad.direction();
            if (isBoundaryFace(quad, face)) {
                culled.computeIfAbsent(face, ignored -> new ArrayList<>()).add(quad);
            } else {
                unculled.add(quad);
            }
        }
        Map<Direction, List<BakedQuad>> immutableCulled = new EnumMap<>(Direction.class);
        culled.forEach((face, quads) -> immutableCulled.put(face, List.copyOf(quads)));
        return new PipeModelPart(List.copyOf(unculled), List.copyOf(allQuads),
            Map.copyOf(immutableCulled), particle, ChunkSectionLayer.TRANSLUCENT);
    }

    private static boolean isBoundaryFace(BakedQuad quad, Direction face) {
        if (face == null) {
            return false;
        }
        float boundary = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0F : 0.0F;
        for (int i = 0; i < 4; i++) {
            Vector3fc pos = quad.position(i);
            float coordinate = switch (face.getAxis()) {
                case X -> pos.x();
                case Y -> pos.y();
                case Z -> pos.z();
            };
            if (Math.abs(coordinate - boundary) > 1.0E-4F) {
                return false;
            }
        }
        return true;
    }

    private static List<BakedQuad> convertPipeBody(
        List<buildcraft.lib.compat.mc2612.client.renderer.block.model.BakedQuad> legacy,
        boolean translucentLayer, Map<BakedQuad, int[]> vertexColours
    ) {
        return convert(legacy, translucentLayer, true, vertexColours, Map.of());
    }

    private static boolean isNativeStaticPluggable(buildcraft.transport.internal.pluggable.PipePluggable pluggable) {
        // Native BakedQuad keeps per-vertex BakedColors, including alpha. Keep glass facades in the
        // terrain translucent layer so they share ordering with water, ice and vanilla glass.
        return true;
    }

    private static List<BakedQuad> convertPluggables(
        List<buildcraft.lib.compat.mc2612.client.renderer.block.model.BakedQuad> legacy,
        boolean translucentLayer, Map<BakedQuad, int[]> vertexColours,
        Map<Integer, Integer> facadeTintColours
    ) {
        return convert(legacy, translucentLayer, false, vertexColours, facadeTintColours);
    }

    private static List<BakedQuad> convert(
        List<buildcraft.lib.compat.mc2612.client.renderer.block.model.BakedQuad> legacy,
        boolean translucentLayer,
        boolean pipeBody, Map<BakedQuad, int[]> vertexColours, Map<Integer, Integer> facadeTintColours
    ) {
        if (legacy == null || legacy.isEmpty()) {
            return List.of();
        }
        List<BakedQuad> result = new ArrayList<>(legacy.size());
        for (buildcraft.lib.compat.mc2612.client.renderer.block.model.BakedQuad baked : legacy) {
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
                int colour = pipeTintColour(encodedTint(quad, translucentLayer));
                BakedQuad nativeQuad = new BakedQuad(
                    position(quad.vertex_0),
                    position(quad.vertex_1),
                    position(quad.vertex_2),
                    position(quad.vertex_3),
                    UVPair.pack(quad.vertex_0.tex_u, quad.vertex_0.tex_v),
                    UVPair.pack(quad.vertex_1.tex_u, quad.vertex_1.tex_v),
                    UVPair.pack(quad.vertex_2.tex_u, quad.vertex_2.tex_v),
                    UVPair.pack(quad.vertex_3.tex_u, quad.vertex_3.tex_v),
                    face,
                    material(sprite, translucentLayer, -1, true, 0)
                );
                vertexColours.put(nativeQuad, new int[] {colour, colour, colour, colour});
                result.add(nativeQuad);
            } else {
                BakedQuad nativeQuad = new BakedQuad(
                    position(quad.vertex_0),
                    position(quad.vertex_1),
                    position(quad.vertex_2),
                    position(quad.vertex_3),
                    UVPair.pack(quad.vertex_0.tex_u, quad.vertex_0.tex_v),
                    UVPair.pack(quad.vertex_1.tex_u, quad.vertex_1.tex_v),
                    UVPair.pack(quad.vertex_2.tex_u, quad.vertex_2.tex_v),
                    UVPair.pack(quad.vertex_3.tex_u, quad.vertex_3.tex_v),
                    face,
                    // Vanilla glass receives world AO on its exposed, block-boundary faces. Disabling AO
                    // for the whole facade makes those faces *brighter* than vanilla glass near dirt/stone.
                    // Its inset/back faces are inside the pipe holder, however; applying boundary AO to
                    // those would double-darken the thin facade when opaque neighbours are present.
                    // Keep non-glass pluggables and the pipe body on their existing lighting path.
                    material(sprite, translucentLayer, -1, quad.isShade(), lightEmission(quad))
                );
                int facadeTint = facadeTintColours.getOrDefault(quad.getTint(), 0xFFFFFFFF);
                vertexColours.put(nativeQuad, new int[] {
                    multiplyColours(argb(quad.vertex_0), facadeTint),
                    multiplyColours(argb(quad.vertex_1), facadeTint),
                    multiplyColours(argb(quad.vertex_2), facadeTint),
                    multiplyColours(argb(quad.vertex_3), facadeTint)
                });
                result.add(nativeQuad);
            }
        }
        return result;
    }

    /**
     * Unlike a vanilla glass block, a thin facade also has inset back and edge faces. Only a face
     * lying on the OUTER boundary of its block should share the adjacent-block AO/light sample
     * of the corresponding vanilla glass face. Keep the test independent of facade orientation.
     */
    private static boolean isOuterGlassFace(MutableQuad quad, Direction face) {
        if (face == null) {
            return false;
        }
        float boundary = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0F : 0.0F;
        for (MutableVertex vertex : quad.vertexs) {
            float coordinate = switch (face.getAxis()) {
                case X -> vertex.position_x;
                case Y -> vertex.position_y;
                case Z -> vertex.position_z;
            };
            if (Math.abs(coordinate - boundary) > 1.0E-4F) {
                return false;
            }
        }
        return true;
    }

    private static BakedQuad.MaterialInfo material(TextureAtlasSprite sprite, boolean translucent, int tintIndex,
        boolean shade, int lightEmission) {
        return new BakedQuad.MaterialInfo(sprite,
            translucent ? ChunkSectionLayer.TRANSLUCENT : ChunkSectionLayer.CUTOUT,
            translucent ? Sheets.translucentBlockItemSheet() : Sheets.cutoutBlockItemSheet(),
            translucent ? Sheets.translucentBlockItemGlintSheet() : Sheets.cutoutBlockItemGlintSheet(),
            translucent ? Sheets.translucentBlockItemGlintSpecialSheet() : Sheets.cutoutBlockItemGlintSpecialSheet(),
            // 26.3 replaces the old shade flag with a shading-direction override:
            // null shades using the quad's actual face, while UP emulates the old shade=false.
            // Do not invert this mapping: forcing UP on normal side faces makes glass and pipes
            // brighter than vanilla blocks and the NeoForge renderer.
            tintIndex, shade ? null : Direction.UP, lightEmission);
    }

    private static Vector3f position(MutableVertex vertex) {
        return new Vector3f(vertex.position_x, vertex.position_y, vertex.position_z);
    }

/** Fabric does not provide the NeoForge dynamic tint hook. Bake each facade
 * source-state tint into the copied pluggable vertices at its real position. */
private static Map<Integer, Integer> findFacadeColours(TilePipeHolder tile) {
    Map<Integer, Integer> colours = new HashMap<>();
    if (tile.getLevel() == null) return colours;
    var blockColours = Minecraft.getInstance().getBlockColors();
    for (Direction side : Direction.values()) {
        if (!(tile.getPluggable(side) instanceof PluggableFacade facade)) continue;
        int active = facade.activeState;
        if (active < 0 || active >= facade.states.phasedStates.length) continue;
        FacadePhasedState phased = facade.states.phasedStates[active];
        BlockState source = phased.stateInfo.state;
        // 26.3 exposes a single source at a known tint index. Do not
        // require a NeoForge-style list accessor, which Fabric may not provide.
        for (int index = 0; index < 16; index++) {
            BlockTintSource tint = blockColours.getTintSource(source, index);
            if (tint != null) {
                int encoded = index * Direction.values().length + side.ordinal();
                if (tile.getLevel() instanceof BlockAndTintGetter tintLevel) {
                    colours.put(encoded, tint.colorInWorld(source, tintLevel, tile.getBlockPos()));
                } else {
                    // No biome context is available while building a detached preview.
                    colours.put(encoded, tint.color(source));
                }
            }
        }
    }
    return colours;
}

/** Retain per-vertex translucency while applying the source block's tint. */
private static int multiplyColours(int colour, int tint) {
    int a = ((colour >>> 24) & 255) * ((tint >>> 24) & 255) / 255;
    int r = ((colour >>> 16) & 255) * ((tint >>> 16) & 255) / 255;
    int g = ((colour >>> 8) & 255) * ((tint >>> 8) & 255) / 255;
    int b = (colour & 255) * (tint & 255) / 255;
    return (a << 24) | (r << 16) | (g << 8) | b;
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
     * Legacy pipe quads bake material colour and directional shading into vertex colour. Pipe-body RGB is recovered
     * through BlockColor so terrain lighting/AO can provide the single lighting pass. Pluggables keep their original
     * BakedColors separately, including translucent alpha.
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
        return quads.isEmpty() ? null : quads.get(0).materialInfo().sprite();
    }

    private static PipeRenderData modelData(BlockAndTintGetter level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        // Fabric does not provide NeoForge ModelData.  Obtain the geometry
        // from the client-side tile when the chunk mesher requests this model.
        if (level.getBlockEntity(pos) instanceof TilePipeHolder tile) {
            return buildModelData(tile);
        }
        return null;
    }

    @Override
    public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        PipeRenderData data = modelData(level, pos);
        return data == null ? PipeModelKey.DEFAULT_KEY : data.geometryKey();
    }

    // Vanilla BlockStateModel has no block-context argument. Dynamic geometry is supplied by
    // Fabric's world-aware collector below, which can access the TilePipeHolder safely.
    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
        // Without a level/position, it is incorrect to select an arbitrary pipe's geometry.
    }

    /** Optional world-aware collector for renderer integrations that request native parts.
     * Fabric's primary dynamic path is {@link #emitQuads}, not this overload. */
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
        List<BlockStateModelPart> parts) {
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

    /** Renderer API path: unlike vanilla BakedQuad this retains dyed-pipe RGB,
     * facade alpha and the original pipe-quad per-vertex colour gradients. */
    @Override
    public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos,
        BlockState state, RandomSource random, Predicate<Direction> cullTest) {
        PipeRenderData data = modelData(level, pos);
        if (data == null) return;
        for (BlockStateModelPart part : new BlockStateModelPart[] {data.cutout(), data.translucent()}) {
            if (part == null) continue;
            emitPart(part, null, emitter, data.vertexColours());
            for (Direction face : Direction.values()) {
                if (!cullTest.test(face)) emitPart(part, face, emitter, data.vertexColours());
            }
        }
    }

    private static void emitPart(BlockStateModelPart part, @Nullable Direction cullFace,
        QuadEmitter emitter, Map<BakedQuad, int[]> colours) {
        for (BakedQuad quad : part.getQuads(cullFace)) {
            emitter.fromBakedQuad(quad).cullFace(cullFace);
            int[] perVertex = colours.get(quad);
            if (perVertex != null) {
                emitter.color(perVertex[0], perVertex[1], perVertex[2], perVertex[3]);
            }
            emitter.emit();
        }
    }

    @Override
    public Material.Baked particleMaterial() {
        return new Material.Baked(fallbackParticle(), false);
    }

    @Override
    public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        PipeRenderData data = modelData(level, pos);
        return new Material.Baked(data == null || data.particle() == null ? fallbackParticle() : data.particle(), false);
    }

    @Override
    public int materialFlags() {
        return 0;
    }

    /** Native pipe geometry has no atlas-local static state to invalidate. */
    public static void clearTextureCache() {
    }

    private static TextureAtlasSprite fallbackParticle() {
        // TerrainParticle dereferences the block-state model particle sprite in its constructor,
        // before BuildCraft gets a chance to replace it with the pipe-specific sprite.
        // Returning null here therefore crashes immediately when a pipe is broken.
        var modelManager = Minecraft.getInstance().getModelManager();
        if (modelManager != null) {
            var missingModel = modelManager.getBlockStateModelSet().missingModel();
            if (missingModel != null) {
                Material.Baked material = missingModel.particleMaterial();
                if (material != null && material.sprite() != null) {
                    return material.sprite();
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
     * The dynamic model cache key must include pluggables as well as the pipe body. Otherwise the model cache can
     * reuse terrain geometry from an identical pipe that has a completely different set of plugs installed.
     */
    public record PipeGeometryKey(PipeModelKey pipe, PipeModelCachePluggable.PluggableKey cutoutPluggables,
        PipeModelCachePluggable.PluggableKey translucentPluggables) {
    }

    public record PipeRenderData(PipeGeometryKey geometryKey, @Nullable BlockStateModelPart cutout,
        @Nullable BlockStateModelPart translucent, TextureAtlasSprite particle,
        Map<BakedQuad, int[]> vertexColours) {
    }

    private record PipeModelPart(List<BakedQuad> quads, List<BakedQuad> allQuads,
        Map<Direction, List<BakedQuad>> culledQuads, TextureAtlasSprite particle,
        ChunkSectionLayer layer) implements BlockStateModelPart {
        @Override
        public List<BakedQuad> getQuads(@Nullable Direction side) {
            // Interior geometry and pipe arms remain in the unculled bucket; glass's external faces
            // participate in vanilla neighbour occlusion like the equivalent full glass block.
            return side == null ? quads : culledQuads.getOrDefault(side, List.of());
        }

        @Override
        public boolean useAmbientOcclusion() {
            return true;
        }

        @Override
        public Material.Baked particleMaterial() {
            return new Material.Baked(particle, false);
        }

        @Override
        public int materialFlags() {
            // Include directional glass faces too; NeoForge uses flags to allocate the render pass.
            return allQuads.stream().map(BakedQuad::materialInfo).mapToInt(BakedQuad.MaterialInfo::flags)
                .reduce(0, (left, right) -> left | right);
        }
    }
}
