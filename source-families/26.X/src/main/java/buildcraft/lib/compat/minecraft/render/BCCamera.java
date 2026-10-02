package buildcraft.lib.compat.minecraft.render;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;

/** Client-only camera API boundary; keep it out of world/server helpers. */
public final class BCCamera {
    private BCCamera() {}
    public static Vec3 position(Camera camera) { return camera == null ? Vec3.ZERO : camera.position(); }
}
