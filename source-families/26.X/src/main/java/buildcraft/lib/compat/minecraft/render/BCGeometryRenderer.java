package buildcraft.lib.compat.minecraft.render;

import com.mojang.blaze3d.vertex.PoseStack;
//? if neoforge && (>=26.2) {
import buildcraft.lib.compat.minecraft.render.BCVertexBuffers;
//? } else {
import net.minecraft.client.renderer.MultiBufferSource;
//?}
import net.minecraft.world.level.block.entity.BlockEntity;
import buildcraft.lib.client.render.compat.CapturedBlockEntityRenderer;

/**
 * Geometry-only machine rendering contract. The module supplies geometry; the backend
 * decides whether to render immediately or capture an immutable submission snapshot.
 * Native item/entity/laser submitters intentionally do not opt into this interface.
 */
public interface BCGeometryRenderer<T extends BlockEntity> extends CapturedBlockEntityRenderer<T> {
    default boolean shouldRenderOffScreen() { return renderOffScreen(); }
//? if neoforge && (>=26.2) {
    default void renderLegacy(T tile, float partialTick, PoseStack pose, BCVertexBuffers buffers, int light, int overlay) {
//? } else {
    default void renderLegacy(T tile, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
//?}
        renderContents(tile, partialTick, pose, buffers, light, overlay);
    }
    default boolean renderOffScreen() { return false; }
//? if neoforge && (>=26.2) {
    void renderContents(T tile, float partialTick, PoseStack pose, BCVertexBuffers buffers, int light, int overlay);
//? } else {
    void renderContents(T tile, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay);
//?}
}
