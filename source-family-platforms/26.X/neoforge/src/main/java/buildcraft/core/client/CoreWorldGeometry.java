//? source if >=26.2
package buildcraft.core.client;

import java.util.List;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import buildcraft.lib.client.render.DetachedRenderer;
import buildcraft.lib.client.render.compat.BCWorldGeometry;
import buildcraft.lib.client.render.compat.CapturedBlockEntityRenderer;
import buildcraft.lib.client.render.laser.LaserRenderer_BC8;
import net.minecraft.client.Minecraft;
import net.minecraft.util.context.ContextKey;
import net.minecraft.resources.Identifier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/** Captures the complete core world overlay before deferred feature rendering. */
public final class CoreWorldGeometry {
    private static final ContextKey<List<CapturedBlockEntityRenderer.Layer>> GEOMETRY =
        new ContextKey<>(Identifier.fromNamespaceAndPath("buildcraftcore", "world_geometry"));

    private CoreWorldGeometry() {}

    public static void extract(ExtractLevelRenderStateEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) {
            event.getRenderState().setRenderData(GEOMETRY, List.of());
            return;
        }
        float partialTicks = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        List<CapturedBlockEntityRenderer.Layer> layers = BCWorldGeometry.capture(() -> {
            PoseStack pose = new PoseStack();
            Matrix4f matrix = new Matrix4f();
            LaserRenderer_BC8.setupLaserRenderState();
            RenderTickListener.captureWorld(pose, matrix, partialTicks);
            MarkerSubmitRenderer121111.capture(level, player, pose, matrix);
            DetachedRenderer.INSTANCE.renderWorldLastEvent(pose, matrix, player, partialTicks);
            LaserRenderer_BC8.flushStaticLasers();
        });
        event.getRenderState().setRenderData(GEOMETRY, layers);
    }

    public static void submit(SubmitCustomGeometryEvent event) {
        List<CapturedBlockEntityRenderer.Layer> layers = event.getLevelRenderState().getRenderData(GEOMETRY);
        if (layers != null && !layers.isEmpty()) {
            BCWorldGeometry.submit(layers, new PoseStack(), event.getSubmitNodeCollector());
        }
    }
}
