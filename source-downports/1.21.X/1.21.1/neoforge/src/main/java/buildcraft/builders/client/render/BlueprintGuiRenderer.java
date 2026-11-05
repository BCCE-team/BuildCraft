/* Copyright (c) 2026 BuildCraft Community Edition contributors
 * Adapted from BuildCraft Redux direct 1.21.1 blueprint viewport (MPL 2.0).
 * NeoForge-specific block-state model rendering, preserving the 1.21.1 GUI path.
 */
package buildcraft.builders.client.render;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import buildcraft.builders.client.render.pip.PreviewPipeModel;
import buildcraft.transport.pipe.SchematicBlockPipe;
import buildcraft.lib.engine.BlockEngineBase_BC8;
import buildcraft.core.EngineItemVariants;
import buildcraft.core.block.BlockEngine_BC8;
import buildcraft.lib.engine.TileEngineBase_BC8;
import buildcraft.lib.internal.properties.BuildCraftProperties;
import buildcraft.transport.block.BlockPipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.snapshot.Template;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * 1.21.1 NeoForge: draw each cell's placed BlockState model, not the inventory item.
 * Block models, block-entity models and the structure context are independent
 * stages: installed vanilla beds, double chests and signs must render from the
 * saved state and (when present) saved BlockEntity data, not item models.
 * BuildCraft engines use the same native BER as the placed engine; item fallback
 * is reserved for a missing/broken renderer and preserves the exact engine type.
 */
public final class BlueprintGuiRenderer {
    private static final int MAX_CELLS = 8192;
    // One warning per BE type, not one stack trace per frame.
    private static final Set<BlockEntityType<?>> FAILED_BER_TYPES = new HashSet<>();
    private BlueprintGuiRenderer() {}

    public static void render(GuiGraphics graphics, Snapshot snapshot, int x, int y, int width, int height) {
        if (snapshot == null || snapshot.size == null || Minecraft.getInstance().level == null) return;
        int sx = Math.max(1, snapshot.size.getX());
        int sy = Math.max(1, snapshot.size.getY());
        int sz = Math.max(1, snapshot.size.getZ());
        float diag = (float) Math.sqrt((double)sx*sx+(double)sy*sy+(double)sz*sz);
        float scale = Math.min(width, height) / (diag * 1.05f);
        Minecraft mc = Minecraft.getInstance();
        PoseStack pose = graphics.pose();
        MultiBufferSource.BufferSource buffers = graphics.bufferSource();
        graphics.enableScissor(x, y, x+width, y+height);
        // 1.21.1 renders the blueprint directly into the GUI buffer. Clear stale
        // GUI/item depth only inside the scissor (as BCU does) so BlockEntity
        // geometry is not rejected by pixels rendered earlier in this frame.
        RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        pose.pushPose();
        pose.translate(x + width / 2.0f, y + height / 2.0f, 100);
        pose.scale(scale, -scale, scale);
        pose.mulPose(Axis.XP.rotationDegrees(20));
        pose.mulPose(Axis.YP.rotationDegrees((System.currentTimeMillis() % 3600L) / 10.0f));
        pose.translate(-sx/2.0f, -sy/2.0f, -sz/2.0f);
        RenderSystem.enableDepthTest();
        Lighting.setupForEntityInInventory();
        ItemRenderer renderer = mc.getItemRenderer();
        Map<ISchematicBlock, ItemStack> models = new HashMap<>();
        int total = snapshot.getDataSize();
        int step = Math.max(1, (total + MAX_CELLS - 1)/MAX_CELLS);
        // Only the world/BE renderer knows that a bed is one head or foot, that
        // double chests have two different halves, and where a sign's text goes.
        // The client-only scene retains the *captured* block states, neighboring
        // cells and sanitized BE NBT. It is rebuilt when the what-if blueprint
        // changes, not once per frame of the spinning GUI preview.
        Blueprint blueprint = snapshot instanceof Blueprint bp ? bp : null;
        BlueprintBlockEntityScene scene = blueprint == null ? null
            : BlueprintBlockEntityScene.forSnapshot(blueprint, mc.level, step);
        BlockEntityRenderDispatcher blockEntityRenderer = mc.getBlockEntityRenderDispatcher();
        for (int i = 0; i < total; i += step) {
            BlockPos at = snapshot.indexToPos(i);
            if (snapshot instanceof Template template) {
                if (template.data == null || !template.data.get(i)) continue;
                renderFallbackItem(new ItemStack(Items.LIME_STAINED_GLASS), at,
                    pose, buffers, renderer, mc, i);
                continue;
            }
            if (blueprint == null || blueprint.data == null || i >= blueprint.data.length) continue;
            int paletteIndex = blueprint.data[i];
            if (paletteIndex < 0 || paletteIndex >= blueprint.palette.size()) continue;
            ISchematicBlock cell = blueprint.palette.get(paletteIndex);
            if (cell == null || cell.isAir()) continue;
            // Pipes are stored as SchematicBlockPipe. They have no renderable
            // BlockState and cannot be represented by the universal pipe-holder item.
            // Rebuild the actual geometry and static facades/gates from tile NBT.
            if (cell instanceof SchematicBlockPipe && scene != null) {
                PreviewPipeModel.Mesh mesh = scene.pipeMeshAt(at, cell);
                if (mesh != null) {
                    pose.pushPose();
                    try {
                        pose.translate(at.getX(), at.getY(), at.getZ());
                        PreviewPipeModel.submit(mesh, pose, buffers, 15728880);
                    } finally {
                        pose.popPose();
                    }
                }
                // A missing optional addon must not be represented as an
                // unrelated item model (pipe-holder has no unique item form).
                continue;
            }
            BlockState state;
            try {
                state = cell.getBlockStateForRender();
            } catch (RuntimeException invalidSchematic) {
                // Addon schema errors are isolated to one cell; the other
                // blocks in the preview must still be drawn.
                continue;
            }
            // Unlike pipes, engines need both the BER animation and the separately
            // baked ModelEngine base. Both are drawn below using the captured tile.
            boolean itemOnly = state != null
                && state.getBlock() instanceof BlockPipeHolder;
            boolean drewWorldGeometry = false;

            // A BlockState MODEL may coexist with an additional BlockEntity
            // renderer. Render both rather than treating the two as alternatives.
            if (!itemOnly && canRenderBlockModel(state)) {
                pose.pushPose();
                try {
                    pose.translate(at.getX(), at.getY(), at.getZ());
                    mc.getBlockRenderer().renderSingleBlock(state, pose, buffers,
                        15728880, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
                    drewWorldGeometry = true;
                } catch (RuntimeException badModel) {
                    // A broken addon model must not unbalance the GUI PoseStack.
                } finally {
                    pose.popPose();
                }
            }

            if (!itemOnly && state != null && state.hasBlockEntity() && scene != null) {
                BlockEntity be = scene.blockEntityAt(at);
                if (be != null && blockEntityRenderer.getRenderer(be) != null) {
                    pose.pushPose();
                    try {
                        pose.translate(at.getX(), at.getY(), at.getZ());
                        // renderItem returns FALSE when a BER was found and
                        // rendered. It returns TRUE when no BER was available.
                        // These are the documented 1.21.1 dispatcher semantics.
                        // Use the real BER so beds render HEAD/FOOT separately,
                        // adjacent chest halves combine, and signs show their NBT.
                        boolean noRenderer = blockEntityRenderer.renderItem(be, pose, buffers,
                            15728880, OverlayTexture.NO_OVERLAY);
                        if (!noRenderer) {
                            drewWorldGeometry = true;
                            // In 1.21.1 the engine BER draws the moving piston and chamber,
                            // while ModelEngine provides the stationary base and trunk.
                            // The engine facing lives in BlockEntity NBT, NOT in BlockState.
                            if (state.getBlock() instanceof BlockEngineBase_BC8
                                && be instanceof TileEngineBase_BC8 engine) {
                                pose.pushPose();
                                try {
                                    // Outer BER pose is already translated to this cell.
                                    // getModelData() exposes the real ENGINE_MODEL_FACING
                                    // from the saved currentDirection (not BlockState).
                                    ModelData modelData = engine.getModelData();
                                    mc.getBlockRenderer().renderSingleBlock(state, pose, buffers,
                                        15728880, OverlayTexture.NO_OVERLAY, modelData, null);
                                } catch (RuntimeException badEngineModel) {
                                    // The moving engine remains visible even if an optional model fails.
                                } finally {
                                    pose.popPose();
                                }
                            }
                        }
                    } catch (RuntimeException invalidBlockEntity) {
                        // A broken modded BER must not erase the block or stop
                        // rendering the rest of the blueprint. Fall back below.
                        if (FAILED_BER_TYPES.add(be.getType())) {
                            buildcraft.lib.internal.debug.BCLog.logger.warn(
                                "[builders.replacer] BlockEntity preview failed for " + be.getType()
                                    + "; using visible item fallback", invalidBlockEntity);
                        }
                    } finally {
                        pose.popPose();
                    }
                }
            }
            if (drewWorldGeometry) continue;
            // NEVER silently discard ENTITYBLOCK_ANIMATED blocks when the fake
            // world could not create/load their BlockEntity or a BER failed. A
            // schematic with beds/chests/signs must remain visible, even if an
            // unsupported addon requires a less faithful inventory fallback.
            // The normal path above always takes precedence over item fallback.

            // Item models are a LAST resort for unsupported state/BER formats,
            // as well as a type-aware emergency fallback for BuildCraft engines.
            ItemStack visual = models.computeIfAbsent(cell, block -> {
                if (state != null && state.getBlock() instanceof BlockEngine_BC8) {
                    // All five engines are the same block with different state values.
                    // Never use block.asItem(), which may resolve to the FE variant.
                    return EngineItemVariants.fromState(state);
                }
                if (state != null && !state.isAir()) {
                    ItemStack stack = new ItemStack(state.getBlock());
                    if (!stack.isEmpty()) return stack;
                }
                for (ItemStack stack : block.computeRequiredItems(mc.level)) {
                    if (stack != null && !stack.isEmpty()) return stack.copyWithCount(1);
                }
                return ItemStack.EMPTY;
            });
            if (visual.isEmpty()) continue;
            pose.pushPose();
            pose.translate(at.getX()+0.5, at.getY()+0.5, at.getZ()+0.5);
            if (state != null && state.getBlock() instanceof BlockEngineBase_BC8
                && state.hasProperty(BuildCraftProperties.BLOCK_FACING_6)) {
                float[] rotation = engineRotation(state);
                pose.mulPose(Axis.YP.rotationDegrees(-rotation[1]));
                pose.mulPose(Axis.XP.rotationDegrees(rotation[0]));
            }
            renderer.renderStatic(visual, ItemDisplayContext.NONE, 15728880,
                OverlayTexture.NO_OVERLAY, pose, buffers, mc.level, i);
            pose.popPose();
        }
        // Frames, paintings and armor stands are blueprint *entities*, never
        // palette blocks. Render them over the same depth buffer as the structure.
        if (scene != null) BlueprintEntityPreview.render(scene.previewEntities(), pose, buffers);
        buffers.endBatch();
        pose.popPose();
        Lighting.setupFor3DItems();
        graphics.disableScissor();
    }

    private static boolean canRenderBlockModel(BlockState state) {
        // Engine static models need tile model data for facing; draw them after
        // their BER using the captured TileEngineBase_BC8 instead of this generic path.
        return state != null && !state.isAir()
            && state.getRenderShape() == RenderShape.MODEL
            && !(state.getBlock() instanceof BlockEngineBase_BC8)
            && !(state.getBlock() instanceof BlockPipeHolder);
    }

    private static void renderFallbackItem(ItemStack item, BlockPos at, PoseStack pose,
            MultiBufferSource.BufferSource buffers, ItemRenderer renderer,
            Minecraft mc, int seed) {
        if (item.isEmpty()) return;
        pose.pushPose();
        pose.translate(at.getX()+0.5, at.getY()+0.5, at.getZ()+0.5);
        renderer.renderStatic(item, ItemDisplayContext.NONE, 15728880,
            OverlayTexture.NO_OVERLAY, pose, buffers, mc.level, seed);
        pose.popPose();
    }

    private static float[] engineRotation(BlockState state) {
        return switch (state.getValue(BuildCraftProperties.BLOCK_FACING_6)) {
            case DOWN -> new float[] {180f, 0f};
            case NORTH -> new float[] {90f, 180f};
            case SOUTH -> new float[] {90f, 0f};
            case WEST -> new float[] {90f, 90f};
            case EAST -> new float[] {90f, 270f};
            default -> new float[] {0f, 0f};
        };
    }
}
