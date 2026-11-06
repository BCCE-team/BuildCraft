/* Copyright (c) 2026 BuildCraft Community Edition contributors; MPL 2.0. */
package buildcraft.builders.client.render;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
//? if <1.20 {
import com.mojang.math.Vector3f;
import net.minecraft.client.renderer.block.model.ItemTransforms.TransformType;
//?} else {
/*?
import com.mojang.math.Axis;
import net.minecraft.world.item.ItemDisplayContext;
?*/
//?}
import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.FakeWorld;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.snapshot.Template;
import buildcraft.lib.engine.TileEngineBase_BC8;
import buildcraft.transport.tile.TilePipeHolder;
import buildcraft.transport.client.render.LegacyPipeAlphaRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Rotating Replacer GUI viewport for old Forge. Never called from Builder or Architect GUI. */
public final class LegacyBlueprintGuiRenderer {
    private static final int MAX_CELLS = 8192;
    private static final int FULL_BRIGHT = 15728880;
    private LegacyBlueprintGuiRenderer() { }

    public static void render(Snapshot snapshot, PoseStack pose, int x, int y, int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        if (snapshot == null || snapshot.size == null || minecraft.level == null) return;
        int sx = Math.max(1, snapshot.size.getX());
        int sy = Math.max(1, snapshot.size.getY());
        int sz = Math.max(1, snapshot.size.getZ());
        float diag = (float) Math.sqrt((double) sx * sx + (double) sy * sy + (double) sz * sz);
        float scale = Math.min(width, height) / (diag * 1.05f);
        int total = snapshot.getDataSize();
        int step = Math.max(1, (total + MAX_CELLS - 1) / MAX_CELLS);
        Blueprint blueprint = snapshot instanceof Blueprint bp ? bp : null;
        LegacyBlueprintPreviewScene scene = blueprint == null ? null
            : LegacyBlueprintPreviewScene.of(blueprint, minecraft.level, step);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        ItemRenderer items = minecraft.getItemRenderer();
        // Clip both Forge versions. A tooltip can coexist with other 3D GUI content,
        // so never clear the depth buffer outside our viewport.
        double guiScale = minecraft.getWindow().getGuiScale();
        int scissorX = (int) Math.floor(x * guiScale);
        int scissorY = minecraft.getWindow().getHeight() - (int) Math.ceil((y + height) * guiScale);
        int scissorW = (int) Math.ceil(width * guiScale);
        int scissorH = (int) Math.ceil(height * guiScale);
        RenderSystem.enableScissor(scissorX, scissorY, scissorW, scissorH);
        // Background slots/items have already written depth when tooltip Pre fires.
        // Clearing only the scissored preview rectangle prevents the entire model
        // from being hidden behind stale inventory depth.
        RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        pose.pushPose();
        try {
            pose.translate(x + width / 2.0f, y + height / 2.0f, 200);
            pose.scale(scale, -scale, scale);
            //? if <1.20 {
            pose.mulPose(Vector3f.XP.rotationDegrees(20.0f));
            pose.mulPose(Vector3f.YP.rotationDegrees((System.currentTimeMillis() % 3600L) / 10.0f));
            //?} else {
            /*?
            pose.mulPose(Axis.XP.rotationDegrees(20.0f));
            pose.mulPose(Axis.YP.rotationDegrees((System.currentTimeMillis() % 3600L) / 10.0f));
            ?*/
            //?}
            pose.translate(-sx / 2.0f, -sy / 2.0f, -sz / 2.0f);
            RenderSystem.enableDepthTest();
            Lighting.setupForEntityInInventory();
            for (int index = 0; index < total; index += step) {
                BlockPos pos = snapshot.indexToPos(index);
                if (snapshot instanceof Template template) {
                    if (template.data != null && template.data.get(index)) {
                        pose.pushPose();
                        try {
                            pose.translate(pos.getX(), pos.getY(), pos.getZ());
                            minecraft.getBlockRenderer().renderSingleBlock(
                                net.minecraft.world.level.block.Blocks.LIME_STAINED_GLASS.defaultBlockState(),
                                pose, buffers, FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                        } finally { pose.popPose(); }
                    }
                    continue;
                }
                if (blueprint == null || blueprint.data == null || index >= blueprint.data.length) continue;
                int palette = blueprint.data[index];
                if (palette < 0 || palette >= blueprint.palette.size()) continue;
                ISchematicBlock schematic = blueprint.palette.get(palette);
                if (schematic == null || schematic.isAir()) continue;
                BlockState state = null;
                try { state = scene == null ? schematic.getBlockStateForRender()
                    : scene.blockStateFor(schematic); }
                catch (RuntimeException badState) { /* item fallback below */ }
                boolean rendered = false;
                BlockEntity be = scene == null ? null : scene.blockEntityAt(pos);
                if (state != null && state.getRenderShape() == RenderShape.MODEL) {
                    pose.pushPose();
                    try {
                        pose.translate(pos.getX(), pos.getY(), pos.getZ());
                        if (be instanceof TilePipeHolder pipe) {
                            // The pipe's dynamic model uses BE model data and two separate
                            // render layers. A single vanilla layer loses pipes and facades.
                            var data = pipe.getModelData();
                            minecraft.getBlockRenderer().renderSingleBlock(state, pose, buffers,
                                FULL_BRIGHT, OverlayTexture.NO_OVERLAY, data, RenderType.cutout());
                            minecraft.getBlockRenderer().renderSingleBlock(state, pose, buffers,
                                FULL_BRIGHT, OverlayTexture.NO_OVERLAY, data, RenderType.translucent());
                        } else if (be instanceof TileEngineBase_BC8 engine) {
                            // Engine type and facing live in the captured BlockEntity.
                            minecraft.getBlockRenderer().renderSingleBlock(state, pose, buffers,
                                FULL_BRIGHT, OverlayTexture.NO_OVERLAY, engine.getModelData(), null);
                        } else {
                            minecraft.getBlockRenderer().renderSingleBlock(state, pose, buffers,
                                FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                        }
                        rendered = true;
                    } catch (RuntimeException | LinkageError brokenModel) {
                        // An addon may be missing; keep the rest of the scene visible.
                    } finally { pose.popPose(); }
                }
                if (be != null) {
                    pose.pushPose();
                    try {
                        pose.translate(pos.getX(), pos.getY(), pos.getZ());
                        // Do NOT use BlockEntityRenderDispatcher.render(be,...): its
                        // visibility test is against the real Minecraft camera, not
                        // the detached FakeWorld. It drops chests, signs, beds,
                        // engines and pipe attachments from GUI previews.
                        rendered |= renderBlockEntityDirect(be, pose, buffers);
                    } catch (RuntimeException | LinkageError badBlockEntityRenderer) {
                        // Only this cell falls back; a broken BER must not blank the scene.
                    } finally { pose.popPose(); }
                }
                if (be instanceof TilePipeHolder pipe) {
                    pose.pushPose();
                    try {
                        pose.translate(pos.getX(), pos.getY(), pos.getZ());
                        // The real-world late alpha stage is never invoked in the
                        // inventory tooltip or Replacer. Emit omitted translucent
                        // facade quads directly, even if the dynamic BER failed.
                        LegacyPipeAlphaRenderer.renderPreview(pipe, pose, buffers,
                            OverlayTexture.NO_OVERLAY);
                    } catch (RuntimeException | LinkageError badAlphaModel) {
                        // Never hide the rest of the blueprint on a bad facade.
                    } finally { pose.popPose(); }
                }
                if (!rendered && scene != null) {
                    ItemStack item = scene.fallbackItem(schematic, minecraft.level);
                    if (!item.isEmpty()) {
                        pose.pushPose();
                        try {
                            pose.translate(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                            pose.scale(0.65f, 0.65f, 0.65f);
                            items.renderStatic(item,
                                //? if <1.20 {
                                TransformType.FIXED,
                                //?} else {
                                /*?
                                ItemDisplayContext.FIXED,
                                ?*/
                                //?}
                                FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, buffers,
                                //? if >=1.20 {
                                /*?
                                minecraft.level,
                                ?*/
                                //?}
                                index);
                        } catch (RuntimeException badFallback) { /* skip only this item */ }
                        finally { pose.popPose(); }
                    }
                }
            }
            if (scene != null) {
                for (Entity entity : scene.entities) {
                    pose.pushPose();
                    try {
                        net.minecraft.world.phys.Vec3 local = entity.position().subtract(
                            net.minecraft.world.phys.Vec3.atLowerCornerOf(FakeWorld.BLUEPRINT_OFFSET));
                        minecraft.getEntityRenderDispatcher().render(entity, local.x, local.y, local.z,
                            entity.getYRot(), 0.0f, pose, buffers, FULL_BRIGHT);
                    } catch (RuntimeException | LinkageError badDecoration) { /* skip just this entity */ }
                    finally { pose.popPose(); }
                }
            }
            buffers.endBatch();
        } finally {
            pose.popPose();
            RenderSystem.disableScissor();
            Lighting.setupFor3DItems();
        }
    }
    private static <T extends BlockEntity> boolean renderBlockEntityDirect(
        T blockEntity, PoseStack pose, MultiBufferSource buffers) {
        BlockEntityRenderer<T> renderer = Minecraft.getInstance()
            .getBlockEntityRenderDispatcher().getRenderer(blockEntity);
        if (renderer == null) return false;
        renderer.render(blockEntity, 0.0f, pose, buffers,
            FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        return true;
    }

}
