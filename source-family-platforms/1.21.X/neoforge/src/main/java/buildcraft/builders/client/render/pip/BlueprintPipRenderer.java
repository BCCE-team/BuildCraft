//? source if ==1.21.11
/*
 * Copyright (c) 2026 the BuildCraftUnofficial contributors
 * Copyright (c) 2026 the BuildCraft Community Edition contributors
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * 1.21.11 NeoForge overlay: restore native block-entity / decorative-entity preview parity in PiP.
 */
package buildcraft.builders.client.render.pip;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import buildcraft.builders.client.render.ClientArchitectPreview;
import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.snapshot.Template;
import buildcraft.lib.engine.BlockEngineBase_BC8;
import buildcraft.core.EngineItemVariants;
import buildcraft.core.block.BlockEngine_BC8;
import buildcraft.lib.compat.NbtCompat;
import buildcraft.lib.compat.RenderCompat;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.transport.block.BlockPipeHolder;
import buildcraft.transport.client.render.PipeWireRenderer;
import buildcraft.transport.internal.pipe.IItemPipe;
import buildcraft.transport.internal.pipe.PipeDefinition;
import buildcraft.transport.item.ItemPipeHolder;
import buildcraft.transport.pipe.PipeRegistry;
import buildcraft.transport.pipe.SchematicBlockPipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Rotating 3D preview with native block models, block entities and decorative entity states. */
public final class BlueprintPipRenderer extends PictureInPictureRenderer<BlueprintPipRenderState> {
    private static final int FULL_BRIGHT = 15728880;
    private static final int MAX_CELLS = 8192;
    private static final Identifier SCAN = Identifier.parse("buildcraftbuilders:textures/blocks/scan.png");

    public BlueprintPipRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }

    @Override
    public Class<BlueprintPipRenderState> getRenderStateClass() {
        return BlueprintPipRenderState.class;
    }

    @Override
    protected String getTextureLabel() { return "bcce_blueprint_preview"; }

    @Override
    protected float getTranslateY(int height, int guiScale) { return height / 2.0f; }

    @Override
    protected void renderToTexture(BlueprintPipRenderState state, PoseStack poseStack) {
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
        FeatureRenderDispatcher dispatcher = minecraft.gameRenderer.getFeatureRenderDispatcher();
        SubmitNodeStorage storage = dispatcher.getSubmitNodeStorage();
        Blueprint blueprint = snapshot instanceof Blueprint bp ? bp : null;
        BlueprintPreviewScene scene = blueprint != null && minecraft.level instanceof ClientLevel client
            ? BlueprintPreviewScene.forSnapshot(blueprint, client, Math.max(1, (snapshot.getDataSize() + MAX_CELLS - 1) / MAX_CELLS))
            : null;
        CameraRenderState previewCamera = new CameraRenderState();
        previewCamera.initialized = true;
        previewCamera.entityPos = net.minecraft.world.phys.Vec3.ZERO;

        Map<ISchematicBlock, TrackingItemStackRenderState> models = new HashMap<>();
        Map<PipePreviewKey, PreviewPipeModel.Mesh> pipeModels = new HashMap<>();
        PreviewPipeTopology topology = blueprint == null ? null : new PreviewPipeTopology(blueprint);
        PreviewBlockModelRenderer.CellCache modelCache = new PreviewBlockModelRenderer.CellCache();
        int size = snapshot.getDataSize();
        int step = Math.max(1, (size + MAX_CELLS - 1) / MAX_CELLS);
        Template template = snapshot instanceof Template tp ? tp : null;
        for (int i = 0; i < size; i += step) {
            BlockPos at = snapshot.indexToPos(i);
            if (template != null) {
                if (template.data == null || !template.data.get(i)) continue;
                EnumSet<Direction> visible = step == 1
                    ? TemplateGhostGeometry.visibleFaces(template, snapshot.size,
                        at.getX(), at.getY(), at.getZ())
                    : EnumSet.allOf(Direction.class);
                if (visible.isEmpty()) continue;
                poseStack.pushPose();
                poseStack.translate(at.getX(), at.getY(), at.getZ());
                emitGhost(poseStack.last(), this.bufferSource.getBuffer(RenderCompat.entityTranslucent(SCAN)), visible);
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
                pipeModels.computeIfAbsent(key,
                    ignored -> PreviewPipeModel.reconstruct(pipeData, around.connectionMask()));
                PreviewPipeModel.Mesh mesh = pipeModels.get(key);
                poseStack.pushPose();
                poseStack.translate(at.getX(), at.getY(), at.getZ());
                if (mesh != null) PreviewPipeModel.submit(mesh, poseStack, this.bufferSource, FULL_BRIGHT);
                if (NbtCompat.getIntArray(NbtCompat.getCompound(pipeData, "wireManager"), "parts").length > 0) {
                    PipeWireRenderer.renderPreviewWires(pipeData, around.tiles(), poseStack.last(),
                        this.bufferSource.getBuffer(RenderCompat.cutout()), FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY);
                }
                poseStack.popPose();
                if (mesh != null) continue;
            }

            BlockState renderedState = block.getBlockStateForRender();
            boolean waterlogged = renderedState != null
                && renderedState.hasProperty(BlockStateProperties.WATERLOGGED)
                && renderedState.getValue(BlockStateProperties.WATERLOGGED);
            if (renderedState != null && !waterlogged && !renderedState.getFluidState().isEmpty()) {
                PreviewFluidRenderer.render(renderedState.getFluidState(), blueprint, at, step,
                    poseStack, this.bufferSource);
                continue;
            }

            boolean engine = renderedState != null
                && renderedState.getBlock() instanceof BlockEngineBase_BC8;
            // The placed forms of chests, beds and BC engines are provided by
            // their BlockEntityRenderer. A blockstate-only pass may report a
            // successful submission despite having no visible geometry.
            boolean blockEntityOnly = renderedState != null && (engine
                || renderedState.getBlock() instanceof AbstractChestBlock
                || renderedState.getBlock() instanceof BedBlock);
            float[] engineRotation = renderedState == null ? null
                : PreviewBlockModelRenderer.engineItemRotation(renderedState);
            // World-only block-entity geometry (especially engines, chests and
            // beds) must be submitted by its actual BER. An engine's JSON block
            // model is only a particle placeholder and must never suppress the BER.
            boolean submittedBlockEntity = scene != null && renderedState != null
                && renderedState.hasBlockEntity()
                && scene.submitBlockEntity(at, poseStack, storage, previewCamera);
            boolean drawnBlockModel = false;
            if (renderedState != null && !renderedState.isAir() && !blockEntityOnly
                && !(renderedState.getBlock() instanceof BlockPipeHolder)
                && (waterlogged || renderedState.getFluidState().isEmpty())
                && renderedState.getRenderShape() == RenderShape.MODEL) {
                poseStack.pushPose();
                poseStack.translate(at.getX(), at.getY(), at.getZ());
                drawnBlockModel = PreviewBlockModelRenderer.renderBlock(renderedState, poseStack,
                    this.bufferSource, modelCache, FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                poseStack.popPose();
            }
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
            item.submit(poseStack, storage, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
        if (scene != null) {
            scene.submitEntities(poseStack, storage, previewCamera);
        }
        dispatcher.renderAllFeatures();
        this.bufferSource.endBatch();
    }

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

    private static ItemStack previewStack(ISchematicBlock block, Minecraft minecraft) {
        CompoundTag pipeData = getPipePreviewTag(block);
        if (pipeData != null) {
            String pipeId = NbtCompat.getString(NbtCompat.getCompound(pipeData, "pipe"), "def");
            if (!pipeId.isEmpty()) {
                try {
                    PipeDefinition definition = PipeRegistry.INSTANCE.loadDefinition(pipeId);
                    IItemPipe pipeItem = PipeRegistry.INSTANCE.getItemForPipe(definition);
                    if (pipeItem instanceof Item item) {
                        ItemStack stack = new ItemStack(item);
                        try {
                            DyeColor dye = NBTUtilBC.readEnum(
                                NbtCompat.getCompound(pipeData, "pipe").get("col"), DyeColor.class);
                            ItemPipeHolder.setPipeColor(stack, dye);
                        } catch (RuntimeException ignored) {
                        }
                        return stack;
                    }
                } catch (buildcraft.lib.internal.core.InvalidInputDataException ignored) {
                }
            }
        }
        BlockState state = block.getBlockStateForRender();
        if (state != null && state.getBlock() instanceof BlockEngine_BC8) {
            return EngineItemVariants.fromState(state);
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
