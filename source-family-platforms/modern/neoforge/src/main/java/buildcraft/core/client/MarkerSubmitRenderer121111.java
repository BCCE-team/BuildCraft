//? source if >=1.21.11
package buildcraft.core.client;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

import buildcraft.lib.client.render.DetachedRenderer;
import buildcraft.lib.client.render.laser.LaserRenderer_BC8;
import buildcraft.lib.marker.MarkerCache;
import buildcraft.lib.marker.MarkerConnection;
import buildcraft.lib.marker.MarkerSubCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 1.21.11 world renderer for connected BuildCraft marker geometry.
 *
 * <p>NeoForge 21.11.x does not expose SubmitCustomGeometryEvent. Connected marker lasers therefore use the
 * supported AfterTranslucentBlocks stage together with BuildCraft's 1.21.11 CPU laser buffer path. This is the
 * same render path already used by map-location and zone overlays on this target.</p>
 */
public final class MarkerSubmitRenderer121111 {
    private MarkerSubmitRenderer121111() {
    }

    public static void submit(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        PoseStack pose = event.getPoseStack();
        if (level == null || player == null || pose == null) {
            return;
        }

        Matrix4f matrix = new Matrix4f(event.getModelViewMatrix());
        LaserRenderer_BC8.setupLaserRenderState();
        DetachedRenderer.fromWorldOriginPre(pose, matrix, 0.0F);
        try {
            for (MarkerCache<?> markerCache : MarkerCache.CACHES) {
                MarkerSubCache<?> cache = markerCache.getSubCache(level);
                for (MarkerConnection<?> connection : cache.getConnections()) {
                    if (!connection.getMarkerPositions().stream().allMatch(pos -> isClientChunkLoaded(level, pos))) {
                        continue;
                    }
                    connection.renderInWorld(pose, matrix);
                }
            }
        } finally {
            DetachedRenderer.fromWorldOriginPost(pose, matrix);
            LaserRenderer_BC8.flushStaticLasers();
        }
    }

    private static boolean isClientChunkLoaded(ClientLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }
}
