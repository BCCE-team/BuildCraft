package buildcraft.transport.block;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Multipart geometry in block-local coordinates. No player/world transforms belong here. */
final class PipeHitGeometry {
    private static final double EPSILON = 1.0E-5;
    private static final int FIRST_ATTACHMENT = 7;

    private PipeHitGeometry() { }

    static VoxelShape combine(VoxelShape[] parts, VoxelShape fallback) {
        VoxelShape combined = null;
        for (VoxelShape part : parts) {
            if (part == null || part.isEmpty()) continue;
            combined = combined == null ? part : Shapes.or(combined, part);
        }
        return combined == null ? fallback : combined;
    }

    /**
     * The level has already picked the first visible surface. In particular a
     * physics mod may return plot-space coordinates for a hit on a moving ship.
     * Resolve only present shapes at that point, never extrapolate from the eye.
     * For coincident surfaces prefer an attachment, as pick-block does.
     */
    static int findPart(VoxelShape[] parts, BlockPos pos, @Nullable BlockHitResult hit) {
        if (hit == null || hit.getType() != HitResult.Type.BLOCK || !pos.equals(hit.getBlockPos())) return -1;
        Vec3 local = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
        if (!Double.isFinite(local.x) || !Double.isFinite(local.y) || !Double.isFinite(local.z)) return -1;
        int best = -1;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int i = 0; i < parts.length; i++) {
            VoxelShape shape = parts[i];
            if (shape == null || shape.isEmpty()) continue;
            for (AABB box : shape.toAabbs()) {
                if (!contains(box, local)) continue;
                double score = hit.isInside() ? 0.0 : faceDistance(box, local, hit.getDirection());
                // A box merely containing the point is less specific than its
                // actual hit face (including floating-point error at plot coords).
                if (score <= EPSILON) score = 0.0;
                if (score < bestScore || (score == bestScore && i >= FIRST_ATTACHMENT && best < FIRST_ATTACHMENT)) {
                    bestScore = score;
                    best = i;
                }
            }
        }
        return best;
    }

    private static boolean contains(AABB box, Vec3 p) {
        return p.x >= box.minX - EPSILON && p.x <= box.maxX + EPSILON
            && p.y >= box.minY - EPSILON && p.y <= box.maxY + EPSILON
            && p.z >= box.minZ - EPSILON && p.z <= box.maxZ + EPSILON;
    }

    private static double faceDistance(AABB box, Vec3 p, Direction face) {
        return switch (face) {
            case DOWN -> Math.abs(p.y - box.minY);
            case UP -> Math.abs(p.y - box.maxY);
            case NORTH -> Math.abs(p.z - box.minZ);
            case SOUTH -> Math.abs(p.z - box.maxZ);
            case WEST -> Math.abs(p.x - box.minX);
            case EAST -> Math.abs(p.x - box.maxX);
        };
    }
}
