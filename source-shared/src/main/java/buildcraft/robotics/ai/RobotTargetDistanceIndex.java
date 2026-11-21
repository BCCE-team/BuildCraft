package buildcraft.robotics.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Immutable exact Manhattan-distance index for one queued navigation search.
 * Each A* expansion used to compare up to 64 targets for EVERY air neighbour.
 * A balanced 3D bounding-box tree rejects entire groups of distant targets.
 *
 * The returned lower bound is distance to an adjacent cell of any solid target,
 * so it remains admissible for a six-neighbour grid with unit traversal cost.
 */
public final class RobotTargetDistanceIndex {
    private static final int LEAF_SIZE = 6;

    private static final class Node {
        final int minX, maxX, minY, maxY, minZ, maxZ;
        final Node first, second;
        final List<BlockPos> positions;

        Node(List<BlockPos> positions, Node first, Node second) {
            this.positions = first == null ? positions : List.of();
            this.first = first;
            this.second = second;
            int loX = Integer.MAX_VALUE, hiX = Integer.MIN_VALUE;
            int loY = Integer.MAX_VALUE, hiY = Integer.MIN_VALUE;
            int loZ = Integer.MAX_VALUE, hiZ = Integer.MIN_VALUE;
            for (BlockPos pos : positions) {
                loX = Math.min(loX, pos.getX());
                hiX = Math.max(hiX, pos.getX());
                loY = Math.min(loY, pos.getY());
                hiY = Math.max(hiY, pos.getY());
                loZ = Math.min(loZ, pos.getZ());
                hiZ = Math.max(hiZ, pos.getZ());
            }
            minX = loX; maxX = hiX;
            minY = loY; maxY = hiY;
            minZ = loZ; maxZ = hiZ;
        }

        long minimumDistance(BlockPos p) {
            return axisDistance(p.getX(), minX, maxX)
                    + axisDistance(p.getY(), minY, maxY)
                    + axisDistance(p.getZ(), minZ, maxZ);
        }
    }

    private final Node root;

    public RobotTargetDistanceIndex(Collection<BlockPos> targets) {
        root = targets == null || targets.isEmpty() ? null : build(new ArrayList<>(targets));
    }

    private static long axisDistance(int value, int min, int max) {
        if (value < min) return (long) min - value;
        if (value > max) return (long) value - max;
        return 0L;
    }

    private static Node build(List<BlockPos> points) {
        if (points.size() <= LEAF_SIZE) return new Node(points, null, null);
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : points) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        long dx = (long) maxX - minX, dy = (long) maxY - minY, dz = (long) maxZ - minZ;
        Comparator<BlockPos> order = dx >= dy && dx >= dz
                ? Comparator.comparingInt(BlockPos::getX)
                : dy >= dz ? Comparator.comparingInt(BlockPos::getY)
                           : Comparator.comparingInt(BlockPos::getZ);
        points.sort(order);
        int mid = points.size() / 2;
        return new Node(points, build(new ArrayList<>(points.subList(0, mid))),
                build(new ArrayList<>(points.subList(mid, points.size()))));
    }

    private static long distance(BlockPos a, BlockPos b) {
        return Math.abs((long) a.getX() - b.getX())
                + Math.abs((long) a.getY() - b.getY())
                + Math.abs((long) a.getZ() - b.getZ());
    }

    private static long nearest(Node node, BlockPos position, long best) {
        if (node == null || node.minimumDistance(position) >= best) return best;
        if (node.first == null) {
            for (BlockPos point : node.positions) best = Math.min(best, distance(position, point));
            return best;
        }
        long a = node.first.minimumDistance(position);
        long b = node.second.minimumDistance(position);
        if (a <= b) {
            best = nearest(node.first, position, best);
            return nearest(node.second, position, best);
        }
        best = nearest(node.second, position, best);
        return nearest(node.first, position, best);
    }

    /** Exact h(n) = max(0, nearestManhattanDistance - 1). */
    public int distanceToAdjacent(BlockPos position) {
        if (root == null) return 0;
        long nearest = nearest(root, position, Long.MAX_VALUE);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, nearest - 1));
    }
}
