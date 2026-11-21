package buildcraft.robotics.scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;

/**
 * Deterministic, bounded selection of near members of a connected ore vein.
 * A vein still occupies one execution job: these samples only guide its route.
 * Squared distances are evaluated in long arithmetic for negative/large XZ.
 */
public final class RobotMiningPriority {
    private RobotMiningPriority() { }

    public static long squaredDistance(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - b.getX();
        long dy = (long) a.getY() - b.getY();
        long dz = (long) a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    public static long nearestMemberSquared(BlockPos origin, List<Long> packed) {
        if (origin == null || packed == null || packed.isEmpty()) return Long.MAX_VALUE;
        long nearest = Long.MAX_VALUE;
        for (long pos : packed) nearest = Math.min(nearest, squaredDistance(origin, BlockPos.of(pos)));
        return nearest;
    }

    private record Ranked(long position, long squared) { }

    public static List<Long> nearestMembers(BlockPos origin, List<Long> packed, int maximum) {
        if (origin == null || packed == null || packed.isEmpty() || maximum <= 0) return List.of();
        // Max-heap keeps only the requested closest members: O(n log k)
        // instead of sorting an entire 2048-member vein for every route check.
        Comparator<Ranked> closestFirst = Comparator.comparingLong(Ranked::squared)
                .thenComparingLong(Ranked::position);
        PriorityQueue<Ranked> chosen = new PriorityQueue<>(closestFirst.reversed());
        int limit = Math.min(maximum, packed.size());
        for (long position : packed) {
            Ranked candidate = new Ranked(position, squaredDistance(origin, BlockPos.of(position)));
            if (chosen.size() < limit) chosen.add(candidate);
            else if (closestFirst.compare(candidate, chosen.peek()) < 0) {
                chosen.poll();
                chosen.add(candidate);
            }
        }
        ArrayList<Ranked> sorted = new ArrayList<>(chosen);
        sorted.sort(closestFirst);
        ArrayList<Long> result = new ArrayList<>(sorted.size());
        for (Ranked entry : sorted) result.add(entry.position());
        return result;
    }

    /** Hysteresis avoids repeatedly replacing pending deposits for tiny gains. */
    public static boolean materiallyCloser(long candidateSquared, long farthestSquared) {
        if (candidateSquared < 0 || farthestSquared <= 0 || candidateSquared >= farthestSquared) return false;
        long improvement = farthestSquared - candidateSquared;
        return improvement >= Math.max(16L, farthestSquared / 5L);
    }
}
