//? source if >=1.21.1
package buildcraft.lib.compat.minecraft.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.block.entity.BlockEntity;
//? if >=1.21.11 {
import buildcraft.lib.client.render.compat.CapturedBlockEntityRenderer;
//? } else {
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
//? }

/**
 * Geometry-only machine rendering contract. The module supplies geometry; the backend
 * decides whether to render immediately or capture an immutable submission snapshot.
 * Native item/entity/laser submitters intentionally do not opt into this interface.
 */
//? if >=1.21.11 {
public interface BCGeometryRenderer<T extends BlockEntity> extends CapturedBlockEntityRenderer<T> {
    @Override
    default boolean shouldRenderOffScreen() { return renderOffScreen(); }
    @Override
    default void renderLegacy(T tile, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        renderContents(tile, partialTick, pose, buffers, light, overlay);
    }
//? } else {
public interface BCGeometryRenderer<T extends BlockEntity> extends BlockEntityRenderer<T> {
    @Override
    default boolean shouldRenderOffScreen(T tile) { return renderOffScreen(); }
    @Override
    default void render(T tile, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        renderContents(tile, partialTick, pose, buffers, light, overlay);
    }
//? }
    default boolean renderOffScreen() { return false; }
    void renderContents(T tile, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay);
}
