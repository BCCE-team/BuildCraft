/* Copyright (c) 2026 BuildCraft Community Edition contributors
 */
package buildcraft.builders.client.render;

import java.util.HashMap;
import java.util.Map;
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
import net.minecraft.world.level.block.state.BlockState;

/** Pre-PiP implementation: rotate a real 3D model in GuiGraphics' PoseStack. */
public final class BlueprintGuiRenderer {
    private static final int MAX_CELLS = 8192;
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
        for (int i = 0; i < total; i += step) {
            BlockPos at = snapshot.indexToPos(i);
            ItemStack visual;
            if (snapshot instanceof Template template) {
                if (template.data == null || !template.data.get(i)) continue;
                visual = new ItemStack(Items.LIME_STAINED_GLASS);
            } else if (snapshot instanceof Blueprint blueprint) {
                if (blueprint.data == null || i >= blueprint.data.length) continue;
                int p = blueprint.data[i];
                if (p < 0 || p >= blueprint.palette.size()) continue;
                ISchematicBlock cell = blueprint.palette.get(p);
                if (cell == null || cell.isAir()) continue;
                visual = models.computeIfAbsent(cell, block -> {
                    BlockState state = block.getBlockStateForRender();
                    if (state != null && !state.isAir()) {
                        ItemStack stack = new ItemStack(state.getBlock());
                        if (!stack.isEmpty()) return stack;
                    }
                    for (ItemStack stack : block.computeRequiredItems(mc.level)) {
                        if (stack != null && !stack.isEmpty()) return stack.copyWithCount(1);
                    }
                    return ItemStack.EMPTY;
                });
            } else continue;
            if (visual.isEmpty()) continue;
            pose.pushPose();
            pose.translate(at.getX()+0.5,at.getY()+0.5,at.getZ()+0.5);
            renderer.renderStatic(visual, ItemDisplayContext.NONE, 15728880,
                OverlayTexture.NO_OVERLAY, pose, buffers, mc.level, 0);
            pose.popPose();
        }
        buffers.endBatch();
        pose.popPose();
        Lighting.setupFor3DItems();
        graphics.disableScissor();
    }
}
