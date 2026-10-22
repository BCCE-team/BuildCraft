package buildcraft.lib.compat.minecraft.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import buildcraft.lib.client.render.compat.CapturedBlockEntityRenderer;

/**
 * Geometry-only machine rendering contract. The module supplies geometry; the backend
 * decides whether to render immediately or capture an immutable submission snapshot.
 * Native item/entity/laser submitters intentionally do not opt into this interface.
 */
public interface BCGeometryRenderer<T extends BlockEntity> extends CapturedBlockEntityRenderer<T> {
    default boolean shouldRenderOffScreen() { return renderOffScreen(); }
    default void renderLegacy(T tile, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        renderContents(tile, partialTick, pose, buffers, light, overlay);
    }
    default boolean renderOffScreen() { return false; }
    void renderContents(T tile, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay);
}
