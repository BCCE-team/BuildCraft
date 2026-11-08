/* Copyright (c) 2026 BuildCraft Community Edition contributors. MPL-2.0. */
package buildcraft.builders.client.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Vector3f;

import buildcraft.builders.client.ClientArchitectTables;
import buildcraft.lib.client.model.ModelUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** World-space digitizing cubes. Called in a translucent world pass, after camera translation. */
public final class ArchitectScanGeometry {

    private static final int MAX_DRAWN_CELLS = 4096;
    private static final double MAX_DISTANCE_SQR = 192.0 * 192.0;
    private static final Vector3f CENTER = new Vector3f(.5f, .5f, .5f);
    private static final Vector3f RADIUS = new Vector3f(.5f, .5f, .5f);
    private static final ModelUtil.UvFaceData FULL_UV = new ModelUtil.UvFaceData(0, 0, 1, 1);

    private ArchitectScanGeometry() {}

    public static void draw(PoseStack pose, VertexConsumer consumer, Vec3 camera) {
        if (ClientArchitectTables.SCANNED_BLOCKS.isEmpty()) return;
        // Keep closest positions within view range; draw far-to-near for translucency.
        List<Map.Entry<BlockPos, Integer>> visible = new ArrayList<>();
        for (Map.Entry<BlockPos, Integer> entry : ClientArchitectTables.SCANNED_BLOCKS.entrySet()) {
            BlockPos pos = entry.getKey();
            if (entry.getValue() > 0 && Vec3.atCenterOf(pos).distanceToSqr(camera) <= MAX_DISTANCE_SQR) {
                visible.add(entry);
            }
        }
        visible.sort(Comparator.comparingDouble(
            entry -> Vec3.atCenterOf(entry.getKey()).distanceToSqr(camera)));
        int first = Math.min(visible.size(), MAX_DRAWN_CELLS) - 1;
        for (int i = first; i >= 0; i--) {
            Map.Entry<BlockPos, Integer> entry = visible.get(i);
            int alpha = Math.max(0, Math.min(50, entry.getValue()));
            BlockPos pos = entry.getKey();
            pose.pushPose();
            pose.translate(pos.getX(), pos.getY(), pos.getZ());
            for (Direction face : Direction.values()) {
                ModelUtil.createFace(face, CENTER, RADIUS, FULL_UV)
                    .lighti(15, 15)
                    .colouri(255, 255, 255, alpha)
                    .render(pose.last().pose(), pose.last().normal(), consumer);
            }
            pose.popPose();
        }
    }
}
