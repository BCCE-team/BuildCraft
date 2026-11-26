package buildcraft.transport.client.render;

import buildcraft.lib.internal.config.ClientPresentation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Client-only visibility checks; does not affect network state or transport simulation. */
final class PipeContentRendering {
    private PipeContentRendering() {}
    static boolean visible(BlockPos pos, Vec3 camera) {
        double range = ClientPresentation.pipeDistance();
        return camera.distanceToSqr(pos.getX()+0.5, pos.getY()+0.5, pos.getZ()+0.5) <= range*range;
    }
    static int viewDistance() { return Math.max(64, ClientPresentation.pipeDistance()); }
}
