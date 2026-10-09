package buildcraft.lib.misc;

import net.minecraft.core.Direction;

/** Direction lookups across Minecraft versions with different method names. */
public final class DirectionCompat {
    private DirectionCompat() {}

    public static Direction nearest(float x, float y, float z) {
        return Direction.getApproximateNearest(x, y, z);
    }
}
