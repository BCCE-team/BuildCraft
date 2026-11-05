/* Copyright (c) 2026 BuildCraft Community Edition contributors
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * Projection geometry follows the working Filler Planner + Volume Box renderer.
 */
package buildcraft.builders.client.render;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import buildcraft.builders.BCBuildersSprites;
import buildcraft.builders.filler.FillPreviewCache;
import buildcraft.builders.snapshot.SnapshotBuilder;
import buildcraft.builders.snapshot.Template;
import buildcraft.builders.tile.TileFiller;
import buildcraft.core.marker.volume.AddonQuadRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 1.21.1 world ghost: the same white half-transparent cubes as the Volume Box Filler Planner. */
public final class FillerGhostPreview {
    private static final int MAX_CUBES = 1024;
    private static final Map<TileFiller, FillPreviewCache> CACHE = new WeakHashMap<>();

    private FillerGhostPreview() {}

    public static void render(TileFiller tile, PoseStack matrix, MultiBufferSource buffer) {
        if (tile == null || tile.getLevel() == null) return;
        FillPreviewCache cache = CACHE.computeIfAbsent(tile, ignored -> new FillPreviewCache(MAX_CUBES));
        if (!tile.isPreviewEnabled()) {
            cache.clear();
            return;
        }
        // TileFiller.buildingInfo is a SERVER-side job snapshot, never synced to the client.
        // The tile derives a stable visual-only template from the synced pattern and area.
        Template.BuildingInfo info = tile.getPreviewBuildingInfo();
        if (info == null || info.getSnapshot() == null) {
            cache.clear();
            return;
        }
        SnapshotBuilder<?> active = tile.getBuilder();
        boolean working = active != null && (!active.clientBreakTasks.isEmpty()
            || !active.clientPlaceTasks.isEmpty());
        if (!cache.isIdle(tile.getLevel().getGameTime(), working)) return;
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        List<BlockPos> cells = cache.cellsToDraw(tile.getLevel(), info, camera);
        if (cells.isEmpty()) return;
        TextureAtlasSprite sprite = BCBuildersSprites.FILLER_PREVIEW_WHITE.getSprite();
        if (sprite == null) return;

        // Match the Planner's atlas UVs, transparency, inset and diffuse shading.
        VertexConsumer vertices = buffer.getBuffer(RenderType.translucent());
        matrix.pushPose();
        BlockPos origin = tile.getBlockPos();
        matrix.translate(-origin.getX(), -origin.getY(), -origin.getZ());
        for (BlockPos cell : cells) {
            AddonQuadRenderer.box(vertices, matrix, new AABB(cell).inflate(-0.1), sprite, 127);
        }
        matrix.popPose();
    }
}
