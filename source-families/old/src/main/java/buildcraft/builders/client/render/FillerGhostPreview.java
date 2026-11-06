/* Copyright (c) 2026 BuildCraft Community Edition contributors; MPL-2.0.
 * Legacy Forge world projection follows the same textured quads as Filler Planner.
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

/** World-space translucent ghosts, independent from the real construction queue. */
public final class FillerGhostPreview {
    private static final int MAX_CUBES = 1024;
    private static final Map<TileFiller, FillPreviewCache> CACHE = new WeakHashMap<>();

    private FillerGhostPreview() {}

    public static void render(TileFiller tile, PoseStack pose, MultiBufferSource buffer) {
        if (tile == null || tile.getLevel() == null) return;
        FillPreviewCache cache = CACHE.computeIfAbsent(tile, key -> new FillPreviewCache(MAX_CUBES));
        if (!tile.isPreviewEnabled()) {
            cache.clear();
            return;
        }
        Template.BuildingInfo info = tile.getPreviewBuildingInfo();
        if (info == null || info.getSnapshot() == null) {
            cache.clear();
            return;
        }
        SnapshotBuilder<?> builder = tile.getBuilder();
        boolean working = builder != null && (!builder.clientBreakTasks.isEmpty()
            || !builder.clientPlaceTasks.isEmpty());
        if (!cache.isIdle(tile.getLevel().getGameTime(), working)) return;
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        List<BlockPos> cells = cache.cellsToDraw(tile.getLevel(), info, camera);
        if (cells.isEmpty()) return;
        TextureAtlasSprite sprite = BCBuildersSprites.FILLER_PREVIEW_WHITE.getSprite();
        if (sprite == null) return;

        VertexConsumer vertices = buffer.getBuffer(RenderType.translucent());
        pose.pushPose();
        try {
            BlockPos origin = tile.getBlockPos();
            pose.translate(-origin.getX(), -origin.getY(), -origin.getZ());
            for (BlockPos cell : cells) {
                AddonQuadRenderer.box(vertices, pose, new AABB(cell).inflate(-0.1), sprite, 127);
            }
        } finally {
            pose.popPose();
        }
    }
}
