//? source if >=26.1.2
/*
 * Copyright (c) 2026 the BuildCraftUnofficial contributors
 * Copyright (c) 2026 the BuildCraft Community Edition contributors
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * Adapted from Redux's rotating offscreen BlueprintPipRenderer. Game mechanics are untouched.
 */
package buildcraft.builders.client.render.pip;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.transport.pipe.SchematicBlockPipe;
import buildcraft.transport.pipe.PipeRegistry;
import buildcraft.transport.client.render.PipeWireRenderer;
import buildcraft.transport.item.ItemPipeHolder;
import buildcraft.lib.misc.NBTUtilBC;
import net.minecraft.world.item.DyeColor;
import buildcraft.transport.internal.pipe.PipeDefinition;
import buildcraft.transport.internal.pipe.IItemPipe;
import buildcraft.lib.compat.NbtCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.snapshot.Template;
import buildcraft.builders.client.render.ClientArchitectPreview;
import buildcraft.core.BCCoreItems;
import buildcraft.core.block.BlockEngine_BC8;
import buildcraft.lib.engine.BlockEngineBase_BC8;
import buildcraft.transport.block.BlockPipeHolder;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import buildcraft.lib.compat.RenderCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
//? if >=26.2 {
import net.minecraft.client.renderer.SubmitNodeCollector;
//?} else {
/*import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;*/
//?}
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.Vec3;

/** Rotating 3D preview with real block-item meshes and translucent, face-culled template shells. */
public final class BlueprintPipRenderer extends PictureInPictureRenderer<BlueprintPipRenderState> {
    private static final int FULL_BRIGHT = 15728880;
    private static final int MAX_CELLS = 8192;
    private static final Identifier SCAN = Identifier.parse("buildcraftbuilders:textures/blocks/scan.png");

    // PiP is redrawn for every yaw-animation frame. Rebuilding baked quads,
    // item render states and pipe meshes inside renderToTexture() caused heavy
    // frame-time spikes on 26.2. Retain immutable render inputs until the source
    // snapshot, client level or baked-model set changes (including /reload).
    private Snapshot cachedSnapshot;
    private Object cachedModelSet;
    private Object cachedClientLevel;
    private Map<ISchematicBlock, TrackingItemStackRenderState> cachedItems = new IdentityHashMap<>();
    private Map<PipePreviewKey, PreviewPipeModel.Mesh> cachedPipes = new HashMap<>();
    private PreviewPipeTopology cachedTopology;
    private PreviewBlockModelRenderer.CellCache cachedBlockModels = new PreviewBlockModelRenderer.CellCache();
    //? if >=26.2 {
    private PreviewBlockModelRenderer.Batch cachedBlockBatch = new PreviewBlockModelRenderer.Batch();
    private boolean cachedBlockBatchComplete;
    private static final boolean PROFILE = Boolean.getBoolean("buildcraft.builders.preview.debug");
    private int profiledRenders;
    private long profiledReuses;
    private long profiledExtractNanos;
    private long profiledMaxExtractNanos;
    //?}

    // 26.2+ is a fully submitted GUI render pipeline: preparing the offscreen
    // scene every display frame can monopolize the frame, even when the baked
    // block-model cache is warm. Cache the finished PIP texture for a short
    // interval; the world is never modified by this preview and only yaw changes.
    //? if >=26.2 {
    // An earlier 125 ms texture budget made the animation visibly stutter at
    // eight updates per second regardless of the game's actual frame rate.
    // Default to a smooth 60 Hz PiP redraw. Allow 0 (uncapped) or another
    // positive FPS through -Dbuildcraft.builders.preview.maxFps=<number>.
    // Geometry/model caches below still prevent expensive model re-bakes.
    private static final int PREVIEW_MAX_FPS = Math.max(0,
        Integer.getInteger("buildcraft.builders.preview.maxFps", 60));
    private final PreviewFrameBudget frameBudget = PreviewFrameBudget.forMaxFps(PREVIEW_MAX_FPS);

    @Override
    protected boolean textureIsReadyToBlit(BlueprintPipRenderState state) {
        Minecraft client = Minecraft.getInstance();
        boolean reuse = frameBudget.canReuse(state, client.level,
            client.getModelManager().getBlockStateModelSet(), System.nanoTime());
        if (reuse && PROFILE) profiledReuses++;
        return reuse;
    }
    //?}

    private void preparePreviewCache(Snapshot snapshot, Minecraft minecraft) {
        Object models = minecraft.getModelManager().getBlockStateModelSet();
        if (snapshot == cachedSnapshot && minecraft.level == cachedClientLevel && models == cachedModelSet) return;
        cachedSnapshot = snapshot;
        cachedClientLevel = minecraft.level;
        cachedModelSet = models;
        cachedItems.clear();
        cachedPipes.clear();
        cachedTopology = snapshot instanceof Blueprint bp ? new PreviewPipeTopology(bp) : null;
        cachedBlockModels = new PreviewBlockModelRenderer.CellCache();
        //? if >=26.2 {
        cachedBlockBatch = new PreviewBlockModelRenderer.Batch();
        cachedBlockBatchComplete = false;
        //?}
    }

    //? if >=26.2 {
    public BlueprintPipRenderer() { super(); }
    //?} else {
    /*public BlueprintPipRenderer(MultiBufferSource.BufferSource bufferSource) { super(bufferSource); }*/
    //?}

    @Override
    public Class<BlueprintPipRenderState> getRenderStateClass() {
        return BlueprintPipRenderState.class;
    }

    @Override
    protected String getTextureLabel() { return "bcce_blueprint_preview"; }

    @Override
    protected float getTranslateY(int height, int guiScale) { return height / 2.0f; }

    //? if >=26.2 {
    @Override
    protected void renderToTexture(BlueprintPipRenderState state, PoseStack poseStack,
                                   SubmitNodeCollector collector) {
    //?} else {
    /*@Override
    protected void renderToTexture(BlueprintPipRenderState state, PoseStack poseStack) {*/
    //?}
        //? if >=26.2 {
        long profileStart = PROFILE ? System.nanoTime() : 0L;
        //?}
        Snapshot snapshot = state.snapshot();
        if (snapshot == null || snapshot.size == null) return;
        int sizeX = Math.max(1, snapshot.size.getX());
        int sizeY = Math.max(1, snapshot.size.getY());
        int sizeZ = Math.max(1, snapshot.size.getZ());
        poseStack.scale(1.0f, -1.0f, -1.0f);
        poseStack.mulPose(Axis.XP.rotationDegrees(20));
        float yaw = (System.currentTimeMillis() % 3600L) / 3600.0f * 360.0f;
        poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
        poseStack.translate(-sizeX / 2.0f, -sizeY / 2.0f, -sizeZ / 2.0f);

        Minecraft minecraft = Minecraft.getInstance();
        //? if <26.2 {
        /*FeatureRenderDispatcher dispatcher = minecraft.gameRenderer.getFeatureRenderDispatcher();
        SubmitNodeStorage storage = dispatcher.getSubmitNodeStorage();*/
        //?}
        Blueprint blueprint = snapshot instanceof Blueprint bp ? bp : null;
        preparePreviewCache(snapshot, minecraft);
        Map<ISchematicBlock, TrackingItemStackRenderState> models = cachedItems;
        // These are keyed by the actual immutable snapshot and model generation,
        // not by rotation angle. No mesh rebake on every draw frame.
        Map<PipePreviewKey, PreviewPipeModel.Mesh> pipeModels = cachedPipes;
        PreviewPipeTopology topology = cachedTopology;
        PreviewBlockModelRenderer.CellCache modelCache = cachedBlockModels;
        //? if >=26.2 {
        // Keep native BE and item nodes separate, but batch ordinary block
        // geometry into ONE custom node rather than thousands per frame.
        // Build the ordinary-block batch once per unchanged blueprint and
        // baked-model generation, not on every 3D rotation update.
        PreviewBlockModelRenderer.Batch blockBatch = cachedBlockBatch;
        boolean assembleBlockBatch = !cachedBlockBatchComplete;
        //?}
        int size = snapshot.getDataSize();
        int step = Math.max(1, (size + MAX_CELLS - 1) / MAX_CELLS);
        // The standalone offscreen scene contains captured neighbours, vanilla
        // block entities and decorative entities; it never touches the live world.
        BlueprintPreviewScene scene = blueprint == null || minecraft.level == null ? null
            : BlueprintPreviewScene.forSnapshot(blueprint, minecraft.level, step);
        CameraRenderState previewCamera = new CameraRenderState();
        previewCamera.initialized = true;
        previewCamera.pos = Vec3.ZERO;
        previewCamera.blockPos = BlockPos.ZERO;
        Template template = snapshot instanceof Template tp ? tp : null;
        for (int i = 0; i < size; i += step) {
            BlockPos at = snapshot.indexToPos(i);
            if (template != null) {
                if (template.data == null || !template.data.get(i)) continue;
                // Sparse LOD drawing cannot cull against cells we have deliberately skipped.
                EnumSet<Direction> visible = step == 1
                    ? TemplateGhostGeometry.visibleFaces(template, snapshot.size,
                        at.getX(), at.getY(), at.getZ())
                    : EnumSet.allOf(Direction.class);
                if (visible.isEmpty()) continue;
                poseStack.pushPose();
                poseStack.translate(at.getX(), at.getY(), at.getZ());
                //? if >=26.2 {
                collector.submitCustomGeometry(poseStack, RenderCompat.entityTranslucent(SCAN),
                    (pose, vertex) -> emitGhost(pose, vertex, visible));
                //?} else {
                /*emitGhost(poseStack.last(), this.bufferSource.getBuffer(RenderCompat.entityTranslucent(SCAN)), visible);*/
                //?}
                poseStack.popPose();
                continue;
            }
            if (blueprint == null || blueprint.data == null || i >= blueprint.data.length) continue;
            int paletteIndex = blueprint.data[i];
            if (paletteIndex < 0 || paletteIndex >= blueprint.palette.size()) continue;
            ISchematicBlock block = blueprint.palette.get(paletteIndex);
            if (block == null || block.isAir()) continue;
            CompoundTag pipeData = topology.tileData(block);
            if (pipeData != null) {
                PreviewPipeTopology.Neighbours around = topology.around(at, block, pipeData);
                PipePreviewKey key = new PipePreviewKey(block, around.connectionMask());
                if (!pipeModels.containsKey(key)) {
                    pipeModels.put(key, PreviewPipeModel.reconstruct(pipeData, around.connectionMask()));
                }
                PreviewPipeModel.Mesh mesh = pipeModels.get(key);
                // Wire geometry is independent of the pipe model baker. Keep it visible
                // even if a missing addon forces the body to fall back to an item model.
                poseStack.pushPose();
                poseStack.translate(at.getX(), at.getY(), at.getZ());
                //? if >=26.2 {
                if (mesh != null) PreviewPipeModel.submit(mesh, poseStack, collector, FULL_BRIGHT);
                if (NbtCompat.getIntArray(NbtCompat.getCompound(pipeData, "wireManager"), "parts").length > 0) {
                    collector.submitCustomGeometry(poseStack, RenderCompat.cutout(),
                        (renderPose, consumer) -> PipeWireRenderer.renderPreviewWires(
                            pipeData, around.tiles(), renderPose, consumer,
                            FULL_BRIGHT, OverlayTexture.NO_OVERLAY));
                }
                //?} else {
                /*if (mesh != null) PreviewPipeModel.submit(mesh, poseStack, this.bufferSource, FULL_BRIGHT);
                PipeWireRenderer.renderPreviewWires(pipeData, around.tiles(), poseStack.last(),
                    this.bufferSource.getBuffer(RenderCompat.cutout()), FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY);*/
                //?}
                poseStack.popPose();
                if (mesh != null) continue;
            }
            BlockState renderedState = block.getBlockStateForRender();
            // Waterlogged slabs/stairs still need their solid shape rather than a water cube.
            boolean waterlogged = renderedState != null
                && renderedState.hasProperty(BlockStateProperties.WATERLOGGED)
                && renderedState.getValue(BlockStateProperties.WATERLOGGED);
            if (renderedState != null && !waterlogged && !renderedState.getFluidState().isEmpty()) {
                //? if >=26.2 {
                PreviewFluidRenderer.render(renderedState.getFluidState(), blueprint, at, step,
                    poseStack, collector);
                //?} else {
                /*PreviewFluidRenderer.render(renderedState.getFluidState(), blueprint, at, step,
                    poseStack, this.bufferSource);*/
                //?}
                continue;
            }
            boolean engine = renderedState != null
                && renderedState.getBlock() instanceof BlockEngineBase_BC8;
            float[] engineRotation = renderedState == null ? null
                : PreviewBlockModelRenderer.engineItemRotation(renderedState);
            // BER geometry must be submitted even when the block has a MODEL
            // shape (e.g. standing signs need both the post AND saved text).
            // Engines are entirely BER-driven; their block model is a placeholder.
            //? if >=26.2 {
            boolean submittedBlockEntity = scene != null && renderedState != null
                && renderedState.hasBlockEntity()
                && scene.submitBlockEntity(at, poseStack, collector, previewCamera);
            //?} else {
            /*boolean submittedBlockEntity = scene != null && renderedState != null
                && renderedState.hasBlockEntity()
                && scene.submitBlockEntity(at, poseStack, storage, previewCamera);*/
            //?}
            boolean drawnBlockModel = false;
            if (renderedState != null && !renderedState.isAir() && !engine
                && !(renderedState.getBlock() instanceof BlockPipeHolder)
                && (waterlogged || renderedState.getFluidState().isEmpty())
                && renderedState.getRenderShape() == RenderShape.MODEL) {
                //? if >=26.2 {
                drawnBlockModel = assembleBlockBatch
                    ? blockBatch.add(renderedState, at, modelCache,
                        FULL_BRIGHT, OverlayTexture.NO_OVERLAY)
                    : blockBatch.contains(at);
                //?} else {
                /*poseStack.pushPose();
                poseStack.translate(at.getX(), at.getY(), at.getZ());
                drawnBlockModel = PreviewBlockModelRenderer.renderBlock(renderedState, poseStack,
                    this.bufferSource, modelCache, FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                poseStack.popPose();*/
                //?}
            }
            // Do NOT treat a successful block-state submission as an alternative
            // to the BE: the post and text are separate independent passes.
            if (submittedBlockEntity || drawnBlockModel) continue;
            TrackingItemStackRenderState item = models.get(block);
            if (item == null && !models.containsKey(block)) {
                ItemStack stack = previewStack(block, minecraft);
                if (!stack.isEmpty()) {
                    item = new TrackingItemStackRenderState();
                    minecraft.getItemModelResolver().updateForTopItem(item, stack,
                        ItemDisplayContext.NONE, minecraft.level, null, 0);
                }
                models.put(block, item);
            }
            if (item == null) continue;
            poseStack.pushPose();
            poseStack.translate(at.getX() + 0.5f, at.getY() + 0.5f, at.getZ() + 0.5f);
            if (engineRotation != null) {
                poseStack.mulPose(Axis.YP.rotationDegrees(-engineRotation[1]));
                poseStack.mulPose(Axis.XP.rotationDegrees(engineRotation[0]));
            }
            //? if >=26.2 {
            item.submit(poseStack, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
            //?} else {
            /*item.submit(poseStack, storage, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);*/
            //?}
            poseStack.popPose();
        }
        //? if >=26.2 {
        cachedBlockBatchComplete = true;
        blockBatch.submit(poseStack, collector);
        //?}
        // Decorative entities (frames, paintings, armour stands) do not have
        // palette entries and must be submitted after all blocks/tiles.
        if (scene != null) {
            //? if >=26.2 {
            scene.submitEntities(poseStack, collector, previewCamera);
            //?} else {
            /*scene.submitEntities(poseStack, storage, previewCamera);*/
            //?}
        }
        //? if <26.2 {
        /*dispatcher.renderAllFeatures();
        this.bufferSource.endBatch();*/
        //?}
        //? if >=26.2 {
        frameBudget.markRendered(state, minecraft.level, cachedModelSet, System.nanoTime());
        if (PROFILE) {
            long elapsed = System.nanoTime() - profileStart;
            profiledExtractNanos += elapsed;
            profiledMaxExtractNanos = Math.max(profiledMaxExtractNanos, elapsed);
            if (++profiledRenders == 16) {
                buildcraft.lib.internal.debug.BCLog.logger.info(
                    "[builders.preview] PiP 26.x: 16 redraws, cached blits="
                    + profiledReuses + ", extraction avg="
                    + (profiledExtractNanos / 16_000_000.0D) + " ms, max="
                    + (profiledMaxExtractNanos / 1_000_000.0D)
                    + " ms (GPU time not included), batch cells=" + blockBatch.size());
                profiledRenders = 0;
                profiledReuses = 0L;
                profiledExtractNanos = 0L;
                profiledMaxExtractNanos = 0L;
            }
        }
        //?}
    }

    /** One palette entry may need different arms at different positions. */
    private record PipePreviewKey(ISchematicBlock schematic, int connections) {}

    private static CompoundTag getPipePreviewTag(ISchematicBlock block) {
        if (block instanceof SchematicBlockPipe saved) {
            return NbtCompat.getCompound(saved.serializeNBT(), "tileNbt");
        }
        if (block instanceof ClientArchitectPreview.RenderBlock live) {
            return live.pipeRenderNbt();
        }
        return null;
    }

    /** Pipe schematics use their saved definition as the fallback if model baking fails. */
    private static ItemStack previewStack(ISchematicBlock block, Minecraft minecraft) {
        // Pipe schematics save their pipe definition in tile NBT. computeRequiredItems() is
        // deliberately server-only and therefore cannot serve as a client-side pipe preview.
        CompoundTag pipeData = getPipePreviewTag(block);
        if (pipeData != null) {
            String pipeId = NbtCompat.getString(NbtCompat.getCompound(pipeData, "pipe"), "def");
            if (!pipeId.isEmpty()) {
                try {
                    PipeDefinition definition = PipeRegistry.INSTANCE.loadDefinition(pipeId);
                    IItemPipe pipeItem = PipeRegistry.INSTANCE.getItemForPipe(definition);
                    if (pipeItem instanceof Item item) {
                        ItemStack stack = new ItemStack(item);
                        // Preserve dyed-pipe colour even if its offline mesh could not be rebuilt.
                        try {
                            DyeColor dye = NBTUtilBC.readEnum(
                                NbtCompat.getCompound(pipeData, "pipe").get("col"), DyeColor.class);
                            ItemPipeHolder.setPipeColor(stack, dye);
                        } catch (RuntimeException ignored) {
                            // An absent/invalid dye tag should not discard the pipe item fallback.
                        }
                        return stack;
                    }
                } catch (buildcraft.lib.internal.core.InvalidInputDataException ignored) {
                    // A missing addon pipe must not crash the entire blueprint preview.
                }
            }
        }
        BlockState state = block.getBlockStateForRender();
        if (state != null && state.getBlock() instanceof BlockEngine_BC8 engine) {
            // All five engines share a block. Block#asItem() returns only one
            // variant (often FE), so recover the saved engine subtype instead.
            var item = BCCoreItems.ENGINE_ITEM_MAP.get(state.getValue(engine.getEngineProperty()));
            if (item != null) return new ItemStack(item);
        }
        if (state != null && !state.isAir()) {
            ItemStack stack = new ItemStack(state.getBlock());
            if (!stack.isEmpty()) return stack;
            if (!state.getFluidState().isEmpty()) {
                return new ItemStack(state.getFluidState().getType().getBucket());
            }
        }
        if (minecraft.level == null) return ItemStack.EMPTY;
        for (ItemStack required : block.computeRequiredItems(minecraft.level)) {
            if (required != null && !required.isEmpty()) return required.copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    /** Emit the six sides of a scan-textured cell, omitting faces hidden by adjacent template cells. */
    private static void emitGhost(PoseStack.Pose pose, VertexConsumer vertex, EnumSet<Direction> faces) {
        for (Direction face : faces) {
            switch (face) {
                case UP -> quad(pose, vertex, 0,1,1, 1,1,1, 1,1,0, 0,1,0, 0,1,0);
                case DOWN -> quad(pose, vertex, 0,0,0, 1,0,0, 1,0,1, 0,0,1, 0,-1,0);
                case NORTH -> quad(pose, vertex, 0,1,0, 1,1,0, 1,0,0, 0,0,0, 0,0,-1);
                case SOUTH -> quad(pose, vertex, 0,0,1, 1,0,1, 1,1,1, 0,1,1, 0,0,1);
                case WEST -> quad(pose, vertex, 0,0,1, 0,1,1, 0,1,0, 0,0,0, -1,0,0);
                case EAST -> quad(pose, vertex, 1,0,0, 1,1,0, 1,1,1, 1,0,1, 1,0,0);
            }
        }
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer consumer,
                             float ax,float ay,float az, float bx,float by,float bz,
                             float cx,float cy,float cz, float dx,float dy,float dz,
                             float nx,float ny,float nz) {
        vertex(consumer,pose,ax,ay,az, 0,0,nx,ny,nz);
        vertex(consumer,pose,bx,by,bz, 1,0,nx,ny,nz);
        vertex(consumer,pose,cx,cy,cz, 1,1,nx,ny,nz);
        vertex(consumer,pose,dx,dy,dz, 0,1,nx,ny,nz);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose,
                               float x,float y,float z, float u,float v,
                               float nx,float ny,float nz) {
        consumer.addVertex(pose,x,y,z)
            .setColor(255,255,255,128).setUv(u,v)
            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT)
            .setNormal(pose,nx,ny,nz);
    }
}
