package buildcraft.lib.misc;

import net.minecraft.core.Direction;

/** Direction lookups whose Minecraft API differs between versions. */
public final class DirectionCompat {
    private DirectionCompat() {}

    /** @return The direction closest to the given vector. */
    public static Direction nearest(float x, float y, float z) {
        return Direction.getNearest(x, y, z);
    }
}
