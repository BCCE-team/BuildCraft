/* Copyright (c) the BuildCraft team. SPDX-License-Identifier: MPL-2.0 */
package buildcraft.core.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;

import buildcraft.core.client.BuildCraftLaserManager;
import buildcraft.core.marker.volume.Addon;
import buildcraft.core.marker.volume.ClientVolumeBoxes;
import buildcraft.core.marker.volume.IFastAddonRenderer;
import buildcraft.core.marker.volume.Lock;
import buildcraft.lib.client.render.DetachedRenderer;
import buildcraft.lib.client.render.laser.LaserBoxRenderer;
import buildcraft.lib.client.render.laser.LaserRenderer_BC8;
import buildcraft.lib.client.render.laser.LaserData_BC8.LaserType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;

/**
 * NeoForge 1.21.1 world overlays. The box outline is opaque, but addon/ghost
 * faces must use the translucent BLOCK shader with the real lightmap bound.
 * Using the previously selected solid laser shader can make filled volume
 * boxes appear black when Sodium leaves texture unit 2 unbound.
 *
 * <p>Transparent ghost cubes must be depth-sorted and back-face culled separately
 * from the double-sided marker lasers. Otherwise their rear faces overlap the
 * front faces, making the preview almost opaque or displaying the wrong face,
 * especially when a shader pack changes the translucent render state.</p>
 */
public enum RenderVolumeBoxes implements DetachedRenderer.IDetachedRenderer {
    INSTANCE;

    @Override
    public void render(PoseStack pose, Matrix4f matrix, Player player, float partialTicks) {
        Minecraft mc = Minecraft.getInstance();
        // Both BufferUploader draws use the view matrix supplied by RenderSystem;
        // the BCLib 1.21.1 world-stage fix from PR #71 handles camera rotation.
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        LaserRenderer_BC8.setupLaserRenderState();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        ClientVolumeBoxes.INSTANCE.volumeBoxes.forEach(volumeBox -> {
            LaserType type;
            if (volumeBox.isEditingBy(player)) {
                type = BuildCraftLaserManager.MARKER_VOLUME_SIGNAL;
            } else {
                type = volumeBox.getLockTargetsStream()
                    .filter(Lock.Target.TargetUsedByMachine.class::isInstance)
                    .map(Lock.Target.TargetUsedByMachine.class::cast)
                    .map(target -> target.type)
                    .map(Lock.Target.TargetUsedByMachine.EnumType::getLaserType)
                    .findFirst()
                    .orElse(BuildCraftLaserManager.MARKER_VOLUME_CONNECTED);
            }
            LaserBoxRenderer.renderVisibleLaserBoxDynamic(volumeBox.box, type,
                pose.last().pose(), pose.last().normal(), bb,
                false, mc.level, player.blockPosition());
        });
        var mesh = bb.build();
        if (mesh != null) {
            mc.gameRenderer.lightTexture().turnOnLightLayer();
            LaserRenderer_BC8.setupLaserRenderState();
            BufferUploader.drawWithShader(mesh);
        }

        // Ghost/addon faces need translucent fragment blending, not the solid
        // shader used for marker/volume edges. Explicitly rebind the lightmap
        // because optimized terrain renderers may unbind it between passes.
        var previousShader = RenderSystem.getShader();
        float[] previousColor = RenderSystem.getShaderColor().clone();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        // The laser pass uses NO_CULL because its beams are double-sided. Ghost blocks
        // are closed boxes: drawing all six faces makes their rear faces appear in front
        // of the nearest ones when the shader pack changes the translucent pass.
        RenderSystem.enableCull();
        try {
            mc.gameRenderer.lightTexture().turnOnLightLayer();
            RenderSystem.setShader(GameRenderer::getRendertypeTranslucentShader);
            RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            BufferBuilder ghost = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            ClientVolumeBoxes.INSTANCE.volumeBoxes.forEach(volumeBox -> {
                if (!isBoxFullyLoaded(volumeBox)) return;
                volumeBox.addons.values().forEach(addon ->
                    ((IFastAddonRenderer<Addon>) addon.getRenderer()).renderAddonFast(
                        addon, player, partialTicks, pose, ghost));
            });
            var ghostMesh = ghost.build();
            if (ghostMesh != null) {
                // The 1.21.1 event pose already contains the camera rotation and
                // translation (PR #71); ghost vertex positions are therefore in view
                // space. Sort all the translucent QUADS around the view-space origin,
                // across *all* boxes, not only between Filler Planner block positions.
                // MeshData owns the temporary sorted index-buffer result until draw.
                int vertexCount = ghostMesh.drawState().vertexCount();
                try (ByteBufferBuilder sortedIndices = new ByteBufferBuilder(
                        Math.max(4096, vertexCount * 6))) {
                    ghostMesh.sortQuads(sortedIndices, VertexSorting.byDistance(0.0F, 0.0F, 0.0F));
                    BufferUploader.drawWithShader(ghostMesh);
                }
            }
        } finally {
            // Restore the laser pass' no-cull state for the pending static VBO flush.
            RenderSystem.disableCull();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            RenderSystem.setShader(() -> previousShader);
            RenderSystem.setShaderColor(previousColor[0], previousColor[1],
                previousColor[2], previousColor[3]);
        }
    }

    private static boolean isBoxFullyLoaded(buildcraft.core.marker.volume.VolumeBox volumeBox) {
        ClientLevel level = Minecraft.getInstance().level;
        Player player = Minecraft.getInstance().player;
        if (level == null || player == null || volumeBox == null || volumeBox.box == null
                || volumeBox.box.min() == null || volumeBox.box.max() == null) return false;
        BlockPos min = volumeBox.box.min();
        BlockPos max = volumeBox.box.max();
        int px = player.getBlockX() >> 4;
        int pz = player.getBlockZ() >> 4;
        int[] xs = { min.getX() >> 4, max.getX() >> 4 };
        int[] zs = { min.getZ() >> 4, max.getZ() >> 4 };
        for (int x : xs) for (int z : zs) {
            if (Math.abs((long) x - px) <= 16 && Math.abs((long) z - pz) <= 16
                    && level.getChunkSource().getChunkNow(x, z) != null) return true;
        }
        return false;
    }
}
