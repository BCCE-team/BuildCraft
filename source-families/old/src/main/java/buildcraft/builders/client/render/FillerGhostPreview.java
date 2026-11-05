/* Copyright (c) 2026 BuildCraft Community Edition contributors
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * Adapted from BuildCraft Redux FillPreview/FillPreviewCache/FillPreviewRenderer.
 */
package buildcraft.builders.client.render;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import buildcraft.builders.filler.FillPreviewCache;
import buildcraft.builders.snapshot.SnapshotBuilder;
import buildcraft.builders.snapshot.Template;
import buildcraft.builders.tile.TileFiller;
import buildcraft.core.client.BuildCraftLaserManager;
import buildcraft.lib.client.render.laser.LaserBoxRenderer;
import buildcraft.lib.misc.data.Box;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;
//? if <1.20 {
import com.mojang.math.Matrix3f;
import com.mojang.math.Matrix4f;
//?} else {
/*?
import org.joml.Matrix3f;
import org.joml.Matrix4f;
?*/
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;

/** Client-only cached preview of unfilled Filler cells, independent from server machine state. */
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
        Template.BuildingInfo info = tile.getTemplateBuildingInfo();
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
        VertexConsumer vertices = buffer.getBuffer(RenderType.cutout());
        matrix.pushPose();
        BlockPos origin = tile.getBlockPos();
        matrix.translate(-origin.getX(), -origin.getY(), -origin.getZ());
        Matrix4f pose = matrix.last().pose();
        Matrix3f normal = matrix.last().normal();
        for (BlockPos cell : cells) {
            LaserBoxRenderer.renderLaserBoxDynamic(new Box(cell, cell),
                BuildCraftLaserManager.POWER_HIGH, pose, normal, vertices, false);
        }
        matrix.popPose();
    }
}
