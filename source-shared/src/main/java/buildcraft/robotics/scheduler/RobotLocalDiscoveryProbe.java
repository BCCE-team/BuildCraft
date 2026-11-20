package buildcraft.robotics.scheduler;

/**
 * A resumable, nearest-first 3D observation cursor around an already known
 * useful target. The cursor only describes coordinates. The caller owns the
 * world-read budget, loaded-chunk checks and per-robot eligibility checks.
 *
 * One air block between two ores puts the next ore in shell two, so nearby
 * deposits can be discovered without revisiting an entire chunk section.
 */
public final class RobotLocalDiscoveryProbe {
    public record Position(int x, int y, int z) { }

    private final int originX, originY, originZ, radius;
    private int shell = 1;
    private int dx = -1, dy = -1, dz = -1;

    public RobotLocalDiscoveryProbe(int x, int y, int z, int radius) {
        if (radius < 1 || radius > 16) throw new IllegalArgumentException("probe radius");
        originX = x;
        originY = y;
        originZ = z;
        this.radius = radius;
    }

    public boolean hasNext() { return shell <= radius; }

    public Position next() {
        while (hasNext()) {
            int currentShell = shell;
            int x = dx, y = dy, z = dz;
            advance();
            // Shells are disjoint, ensuring no repeated reads as a probe
            // advances across server ticks.
            if (Math.max(Math.max(Math.abs(x), Math.abs(y)), Math.abs(z)) == currentShell) {
                return new Position(originX + x, originY + y, originZ + z);
            }
        }
        return null;
    }

    private void advance() {
        if (++dz <= shell) return;
        dz = -shell;
        if (++dy <= shell) return;
        dy = -shell;
        if (++dx <= shell) return;
        shell++;
        dx = dy = dz = -shell;
    }
}
