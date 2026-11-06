/* Copyright (c) the BuildCraft team. SPDX-License-Identifier: MPL-2.0 */
package buildcraft.transport.client.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import buildcraft.transport.client.model.ModelPipe;
import buildcraft.transport.tile.TilePipeHolder;
import buildcraft.transport.pipe.Pipe;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Legacy Forge: fractional vertex alpha cannot be relied upon in the terrain mesh.
 * The corresponding BakedQuads are removed from ModelPipe's chunk output and
 * queued here by RenderPipeHolder. They MUST NOT render inside the block-entity
 * pass: translucent entity RenderTypes can write depth before signs, banners,
 * beds and other block entities have been drawn, making those objects disappear.
 *
 * The Forge world-stage subscriber flushes this queue after entities/particles
 * have rendered, with depth testing enabled but depth WRITES disabled.
 */
public final class LegacyPipeAlphaRenderer {
    private static final Map<BlockPos, PendingPipe> PENDING = new LinkedHashMap<>();
    private static Level pendingLevel;
    // Back-facing surfaces of a stained-glass facade must be culled, exactly as
    // in the vanilla translucent terrain path. Other pipe/lens materials retain
    // their legacy two-sided pass.
    private static final RenderType GLASS_LAYER = AlphaRenderType.make(true);
    private static final RenderType OTHER_LAYER = AlphaRenderType.make(false);

    /** User-calibrated stained-glass alpha (out of 255). Its texture alpha is still applied. */
    private static final int STAINED_GLASS_ALPHA = 255;
    /** Original pipe-paint/lens alpha; independent of stained glass. */
    private static final int PAINT_AND_LENS_ALPHA = 144;

    private LegacyPipeAlphaRenderer() { }

    /** Invoked from the normal pipe BER; no alpha draw is permitted here. */
    public static void render(TilePipeHolder tile, PoseStack pose, MultiBufferSource buffers,
            int light, int overlay) {
        // The renderer used by GUI previews has a detached FakeWorld. Do not
        // enqueue its pipes for the real world's late alpha pass: the queue is
        // intentionally world-scoped and would discard them instead of drawing.
        if (tile == null || tile.getLevel() != Minecraft.getInstance().level
            || !tile.getLevel().isClientSide()) return;
        if (pendingLevel != tile.getLevel()) {
            PENDING.clear();
            pendingLevel = tile.getLevel();
        }
        PENDING.put(tile.getBlockPos().immutable(), new PendingPipe(tile, light, overlay));
    }

    /**
     * Draw the alpha quads omitted from the pipe's baked block model in GUI
     * previews. This path deliberately bypasses world AO and light sampling:
     * FakeWorld returns zero directional shade and has no stable light engine.
     * A full-bright item-style model pass preserves actual texture alpha without
     * hiding the facades in both tooltip and Replacer previews.
     * The caller has already translated the pose to the preview pipe's cell.
     */
    public static void renderPreview(TilePipeHolder tile, PoseStack pose,
            MultiBufferSource.BufferSource buffer, int overlay) {
        if (tile == null || tile.getPipe() == Pipe.EMPTY || tile.getLevel() == null) return;
        Map<AlphaGroup, List<BakedQuad>> groups = groupAlphaQuads(ModelPipe.getAlphaQuads(tile));
        if (groups.isEmpty()) return;
        ModelBlockRenderer renderer = Minecraft.getInstance().getBlockRenderer().getModelRenderer();
        for (Map.Entry<AlphaGroup, List<BakedQuad>> group : groups.entrySet()) {
            AlphaQuadsModel model = new AlphaQuadsModel(group.getValue());
            boolean glassFacade = group.getKey().glassFacade();
            VertexConsumer consumer = new FixedAlphaConsumer(buffer.getBuffer(
                glassFacade ? GLASS_LAYER : OTHER_LAYER), group.getKey().alpha(), tile, false);
            // Stable across Forge 1.19.2 and 1.20.1: the legacy model rendering
            // overload handles all faces of AlphaQuadsModel without world AO.
            renderer.renderModel(pose.last(), consumer, tile.getBlockState(), model,
                1.0F, 1.0F, 1.0F, 15728880, overlay);
        }
    }

    /** Called once per view, after the regular world, entity and translucent passes. */
    public static void flush(PoseStack pose, Vec3 camera) {
        Level current = Minecraft.getInstance().level;
        if (current == null || pendingLevel != current) {
            PENDING.clear();
            pendingLevel = null;
            return;
        }
        if (PENDING.isEmpty()) return;

        // Snapshot and clear before rendering, so a thrown render exception cannot
        // leave old pipes queued into another frame/dimension.
        List<PendingPipe> pending = new ArrayList<>(PENDING.values());
        PENDING.clear();
        // Sort entire pipes far-to-near; the RenderType also sorts individual quads.
        pending.sort(Comparator.comparingDouble((PendingPipe p) ->
            p.tile.getBlockPos().distToCenterSqr(camera.x, camera.y, camera.z)).reversed());

        MultiBufferSource.BufferSource batch = MultiBufferSource.immediate(new BufferBuilder(8192));
        pose.pushPose();
        try {
            pose.translate(-camera.x, -camera.y, -camera.z);
            for (PendingPipe item : pending) {
                TilePipeHolder tile = item.tile;
                if (tile.isRemoved() || tile.getLevel() != current) continue;
                List<BakedQuad> quads = ModelPipe.getAlphaQuads(tile);
                if (quads.isEmpty()) continue;

                BlockPos position = tile.getBlockPos();
                pose.pushPose();
                try {
                    pose.translate(position.getX(), position.getY(), position.getZ());
                    // Unlike BER's single packedLight, vanilla's block tessellator
                    // samples neighbors and each quad corner for smooth AO. This is
                    // essential near opaque blocks: the legacy standalone vertex
                    // writer cannot darken a facade when its surroundings change.
                    renderLitQuads(tile, quads, item.overlay, pose, batch);
                } finally {
                    pose.popPose();
                }
            }
        } finally {
            pose.popPose();
            // Both custom RenderTypes have COLOR_WRITE, not COLOR_DEPTH_WRITE.
            // Flush even if a particular pipe's model fails to avoid leaking a batch.
            batch.endBatch();
        }
    }

    /** Reuse Minecraft/Forge's actual world-block lighting (including neighboring-block AO). */
    private static void renderLitQuads(TilePipeHolder tile, List<BakedQuad> source,
            int overlay, PoseStack pose, MultiBufferSource.BufferSource batch) {
        Map<AlphaGroup, List<BakedQuad>> byAlpha = groupAlphaQuads(source);
        if (byAlpha.isEmpty()) return;

        Minecraft client = Minecraft.getInstance();
        ModelBlockRenderer blockRenderer = client.getBlockRenderer().getModelRenderer();
        BlockPos pos = tile.getBlockPos();
        BlockState state = tile.getBlockState();
        // AO is a user graphics setting; ordinary blocks also switch to flat
        // lighting when it is disabled.
        boolean smooth = Minecraft.useAmbientOcclusion();
        for (Map.Entry<AlphaGroup, List<BakedQuad>> layer : byAlpha.entrySet()) {
            AlphaQuadsModel model = new AlphaQuadsModel(layer.getValue());
            // Alpha 160 identifies baked stained-glass facade quads (source alpha 51).
            // The native terrain glass path culls reverse windings. The old
            // NO_CULL render type exposed the thin facade's interior and rear
            // bevels through its front plane, especially after AO darkened them.
            boolean glassFacade = layer.getKey().glassFacade();
            VertexConsumer alphaConsumer = new FixedAlphaConsumer(
                batch.getBuffer(glassFacade ? GLASS_LAYER : OTHER_LAYER), layer.getKey().alpha(), tile);
            RandomSource random = RandomSource.create(42L);
            if (smooth) {
                blockRenderer.tesselateWithAO(tile.getLevel(), model, state, pos, pose,
                    alphaConsumer, false, random, 42L, overlay, ModelData.EMPTY, RenderType.translucent());
            } else {
                blockRenderer.tesselateWithoutAO(tile.getLevel(), model, state, pos, pose,
                    alphaConsumer, false, random, 42L, overlay, ModelData.EMPTY, RenderType.translucent());
            }
        }
    }

    /** Apply exactly the same glass/lens alpha calibration for world and GUI. */
    private static Map<AlphaGroup, List<BakedQuad>> groupAlphaQuads(List<BakedQuad> source) {
        // World tessellation can reset the baked alpha to 255; FixedAlphaConsumer
        // restores it after terrain shading or item-model quad emission.
        Map<AlphaGroup, List<BakedQuad>> byAlpha = new LinkedHashMap<>();
        for (BakedQuad quad : source) {
            int[] raw = quad.getVertices();
            int stride = raw.length / 4;
            if (stride < 4) continue;
            int bakedAlpha = (raw[3] >>> 24) & 0xFF;
            int effectiveAlpha = bakedAlpha == 51 ? STAINED_GLASS_ALPHA
                : bakedAlpha == 64 ? PAINT_AND_LENS_ALPHA : bakedAlpha;
            BakedQuad litQuad = bakedAlpha == 51 ? alignGlassNormalToGeometry(quad) : quad;
            byAlpha.computeIfAbsent(new AlphaGroup(effectiveAlpha, bakedAlpha == 51),
                unused -> new ArrayList<>()).add(litQuad);
        }
        return byAlpha;
    }

    /**
     * Port of the native 26.X facade correction: the face direction used by
     * world AO must correspond to the transformed geometry, not the vanilla
     * block face whose texture supplied the facade. This is local to the
     * translucent render path, so the shared model cache stays untouched.
     */
    private static BakedQuad alignGlassNormalToGeometry(BakedQuad original) {
        int[] data = original.getVertices();
        int stride = data.length / 4;
        if (stride < 8) return original;
        float x0 = Float.intBitsToFloat(data[0]);
        float y0 = Float.intBitsToFloat(data[1]);
        float z0 = Float.intBitsToFloat(data[2]);
        float ax = Float.intBitsToFloat(data[stride]) - x0;
        float ay = Float.intBitsToFloat(data[stride + 1]) - y0;
        float az = Float.intBitsToFloat(data[stride + 2]) - z0;
        float bx = Float.intBitsToFloat(data[stride * 2]) - x0;
        float by = Float.intBitsToFloat(data[stride * 2 + 1]) - y0;
        float bz = Float.intBitsToFloat(data[stride * 2 + 2]) - z0;
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(length > 1.0e-6F) || !Float.isFinite(length)) return original;
        nx /= length;
        ny /= length;
        nz /= length;
        Direction originalFace = original.getDirection();
        if (originalFace != null &&
            nx * originalFace.getStepX() + ny * originalFace.getStepY()
                + nz * originalFace.getStepZ() < 0.0F) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        Direction actualFace = Direction.getNearest(nx, ny, nz);
        int packedNormal = ((Math.round(nx * 127.0F) & 255))
            | ((Math.round(ny * 127.0F) & 255) << 8)
            | ((Math.round(nz * 127.0F) & 255) << 16);
        int[] corrected = data.clone();
        for (int i = 0; i < 4; i++) corrected[i * stride + 7] = packedNormal;
        return new BakedQuad(corrected, original.getTintIndex(), actualFace,
            original.getSprite(), original.isShade());
    }

    /** Separate the glass-vs-pipe rasterizer state even if their alpha happens to match. */
    private record AlphaGroup(int alpha, boolean glassFacade) { }

    /** An uncullable Minecraft model. Actual back-face culling is done by the render layer. */
    private static final class AlphaQuadsModel extends BakedModelWrapper<ModelPipe> {
        private final List<BakedQuad> quads;

        AlphaQuadsModel(List<BakedQuad> quads) {
            super(ModelPipe.INSTANCE);
            this.quads = quads;
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random,
                ModelData data, RenderType renderType) {
            // Emit all quad geometry to the block tessellator. Only the glass
            // RenderType's rasterizer applies back-face culling; doing it here
            // would also remove legitimate non-cube facade faces.
            return side == null ? quads : List.of();
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
            return side == null ? quads : List.of();
        }

        @Override
        public boolean useAmbientOcclusion() {
            return true;
        }

        @Override
        public boolean useAmbientOcclusion(BlockState state, RenderType renderType) {
            return true;
        }
    }

    /** Force only alpha; preserve vanilla AO, biome tint, face shade and per-vertex light. */
    private static final class FixedAlphaConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final int alpha;
        private final TilePipeHolder tile;
        private final boolean worldOcclusion;

        FixedAlphaConsumer(VertexConsumer delegate, int alpha, TilePipeHolder tile) {
            this(delegate, alpha, tile, true);
        }

        FixedAlphaConsumer(VertexConsumer delegate, int alpha, TilePipeHolder tile,
                boolean worldOcclusion) {
            this.delegate = delegate;
            this.alpha = alpha;
            this.tile = tile;
            this.worldOcclusion = worldOcclusion;
        }

        /**
         * On legacy Forge, running ModelBlockRenderer from a late world stage
         * can leave a translucent facade with the right face shading but no
         * *corner* shade from solid blocks just outside it. Supply the missing
         * local occlusion, without multiplying AO a second time when vanilla
         * has already produced a darker corner. The original per-corner light
         * maps, RGB tint and vertex alpha are all preserved.
         */
        private float[] addNeighborOcclusion(BakedQuad quad, float[] brightness) {
            if (!worldOcclusion || alpha != STAINED_GLASS_ALPHA || !Minecraft.useAmbientOcclusion()
                    || brightness == null || brightness.length < 4
                    || tile.getLevel() == null) {
                return brightness;
            }
            Level world = tile.getLevel();
            int[] raw = quad.getVertices();
            int stride = raw.length / 4;
            if (stride < 3) return brightness;
            Direction face = quad.getDirection();
            if (face == null) return brightness;
            // Only the exterior plane of an actual facade can receive this
            // additional per-corner shadow. An inner face or a bevel can have
            // one vertex at the block boundary, but shading that one vertex
            // paints the visible 'back corner' outline through the glass.
            if (tile.getPluggable(face) == buildcraft.transport.internal.pluggable.PipePluggable.EMPTY) {
                return brightness;
            }
            float boundary = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0F : 0.0F;
            for (int i = 0; i < 4; i++) {
                int offset = i * stride;
                float axisValue = switch (face.getAxis()) {
                    case X -> Float.intBitsToFloat(raw[offset]);
                    case Y -> Float.intBitsToFloat(raw[offset + 1]);
                    case Z -> Float.intBitsToFloat(raw[offset + 2]);
                };
                if (!Float.isFinite(axisValue) || Math.abs(axisValue - boundary) > 0.01F) {
                    return brightness;
                }
            }

            float[] shaded = brightness.clone();
            BlockPos origin = tile.getBlockPos();
            BlockPos outside = origin.relative(face);
            // The vanilla directional factor remains in the input brightness.
            // We apply only additional *neighbor* shadow when the outer facade
            // face is near the block boundary (not on buried inner bevels).
            float directional = world.getShade(face, true);
            for (int i = 0; i < 4; i++) {
                int offset = i * stride;
                float x = Float.intBitsToFloat(raw[offset]);
                float y = Float.intBitsToFloat(raw[offset + 1]);
                float z = Float.intBitsToFloat(raw[offset + 2]);
                if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) continue;
                // All four vertices were checked against the exterior plane
                // before the loop, so no interior bevel is ever darkened.

                // Two axes tangent to the visible facade face. A neighbor
                // directly below the facade (in front of its outer face) now
                // darkens precisely the bottom two corners, as in terrain AO.
                Direction a, b;
                switch (face.getAxis()) {
                    case X -> {
                        a = y < 0.5F ? Direction.DOWN : Direction.UP;
                        b = z < 0.5F ? Direction.NORTH : Direction.SOUTH;
                    }
                    case Y -> {
                        a = x < 0.5F ? Direction.WEST : Direction.EAST;
                        b = z < 0.5F ? Direction.NORTH : Direction.SOUTH;
                    }
                    default -> {
                        a = x < 0.5F ? Direction.WEST : Direction.EAST;
                        b = y < 0.5F ? Direction.DOWN : Direction.UP;
                    }
                }
                boolean edgeA = isOpaqueNeighbor(world, outside.relative(a));
                boolean edgeB = isOpaqueNeighbor(world, outside.relative(b));
                boolean diagonal = isOpaqueNeighbor(world, outside.relative(a).relative(b));
                if (!(edgeA || edgeB || diagonal)) continue;
                float shadow = 1.0F - (edgeA ? 0.18F : 0.0F)
                    - (edgeB ? 0.18F : 0.0F)
                    - (diagonal ? 0.14F : 0.0F);
                // Do not brighten a corner if vanilla AO already darkened it;
                // do not compound ambient occlusion from two independent passes.
                shaded[i] = Math.min(shaded[i], directional * shadow);
            }
            return shaded;
        }

        private static boolean isOpaqueNeighbor(Level level, BlockPos pos) {
            return level.getBlockState(pos).isSolidRender(level, pos);
        }

        /**
         * Vanilla's ModelBlockRenderer uses the nine-argument putBulkData method
         * for AO quads. Its default forwards with alpha=1.0, and some Forge
         * implementations multiply that by the original baked vertex alpha
         * (51 for stained-glass facades) before calling color(). Supplying our
         * calibrated alpha here is essential: a color() override alone cannot
         * reliably fix the bulk path across Minecraft/Forge renderers.
         *
         * The ten-argument overload exists in the Forge versions used by both
         * legacy targets. Calling the interface default retains all of the
         * vanilla per-corner AO brightness, block tint and lightmap handling.
         */
        @Override
        public void putBulkData(PoseStack.Pose entry, BakedQuad quad, float[] cornerBrightness,
                float red, float green, float blue, int[] cornerLights, int overlay,
                boolean readExistingColor) {
            VertexConsumer.super.putBulkData(entry, quad, addNeighborOcclusion(quad, cornerBrightness),
                red, green, blue, alpha / 255.0F, cornerLights, overlay, readExistingColor);
        }

        /** Also normalize callers that already use Forge's explicit-alpha overload. */
        @Override
        public void putBulkData(PoseStack.Pose entry, BakedQuad quad, float[] cornerBrightness,
                float red, float green, float blue, float ignoredAlpha,
                int[] cornerLights, int overlay, boolean readExistingColor) {
            VertexConsumer.super.putBulkData(entry, quad, addNeighborOcclusion(quad, cornerBrightness),
                red, green, blue, alpha / 255.0F, cornerLights, overlay, readExistingColor);
        }

        @Override public VertexConsumer vertex(double x, double y, double z) {
            delegate.vertex(x, y, z); return this;
        }
        @Override public VertexConsumer color(int r, int g, int b, int a) {
            delegate.color(r, g, b, alpha); return this;
        }
        @Override public VertexConsumer uv(float u, float v) {
            delegate.uv(u, v); return this;
        }
        @Override public VertexConsumer overlayCoords(int u, int v) {
            // The custom terrain layer uses BLOCK format and has no UV1/overlay.
            return this;
        }
        @Override public VertexConsumer uv2(int u, int v) {
            delegate.uv2(u, v); return this;
        }
        @Override public VertexConsumer normal(float x, float y, float z) {
            delegate.normal(x, y, z); return this;
        }
        @Override public void endVertex() {
            delegate.endVertex();
        }
        @Override public void defaultColor(int r, int g, int b, int a) {
            delegate.defaultColor(r, g, b, alpha);
        }
        @Override public void unsetDefaultColor() {
            delegate.unsetDefaultColor();
        }
    }

    private static final class PendingPipe {
        final TilePipeHolder tile;
        final int light;
        final int overlay;
        PendingPipe(TilePipeHolder tile, int light, int overlay) {
            this.tile = tile;
            this.light = light;
            this.overlay = overlay;
        }
    }

    /** Only colors are written; the depth buffer must remain untouched. */
    private static final class AlphaRenderType extends RenderType {
        private AlphaRenderType() {
            super("bcce_alpha_layer_factory", DefaultVertexFormat.BLOCK,
                VertexFormat.Mode.QUADS, 256, false, true, () -> { }, () -> { });
        }

        static RenderType make(boolean cullBackFaces) {
            return create(cullBackFaces ? "bcce_legacy_glass_alpha_culled"
                    : "bcce_legacy_pipe_alpha_two_sided",
                DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS, 2048,
                false, true, CompositeState.builder()
                    // Use the same terrain shader family as vanilla stained glass,
                    // not the entity shader's extra minecraft_mix_light pass.
                    .setShaderState(RENDERTYPE_TRANSLUCENT_SHADER)
                    .setTextureState(new TextureStateShard(InventoryMenu.BLOCK_ATLAS, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(cullBackFaces ? CULL : NO_CULL)
                    .setLightmapState(LIGHTMAP)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(true));
        }
    }
}
