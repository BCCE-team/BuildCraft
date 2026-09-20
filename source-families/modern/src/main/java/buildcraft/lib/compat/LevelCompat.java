//? source if >=1.21.11
package buildcraft.lib.compat;

import buildcraft.lib.compat.minecraft.render.BCCamera;
import buildcraft.lib.compat.minecraft.world.BCWorldHeight;
import net.minecraft.client.Camera;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.phys.Vec3;

/** 1.21.11 bridge for world-height and camera APIs renamed since 1.21.1. */
public final class LevelCompat {
    private LevelCompat() {
    }

    public static void profilerPush(Object owner, String section) {
    }

    public static void profilerPop(Object owner) {
    }

    public static void profilerPopPush(Object owner, String section) {
    }

    /** Equivalent to LevelHeightAccessor#getMinBuildHeight(). */
    public static int getMinBuildHeight(Object level) {
        return level instanceof LevelHeightAccessor height ? BCWorldHeight.min(height) : 0;
    }

    public static Vec3 cameraPosition(Camera camera) {
        return BCCamera.position(camera);
    }

    /** Equivalent to the exclusive LevelHeightAccessor#getMaxBuildHeight(). */
    public static int getMaxBuildHeight(Object level) {
        return level instanceof LevelHeightAccessor height ? BCWorldHeight.maxExclusive(height) : 320;
    }
}
