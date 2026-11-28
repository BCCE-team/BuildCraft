/*
 * Copyright (c) 2026 BuildCraft Community Edition contributors
 * Based on the BuildCraft Redux blueprint block-state preview renderer (MPL-2.0).
 * Render-only implementation: snapshots and build decisions are unchanged.
 */
package buildcraft.builders.client.render.pip;

import com.mojang.blaze3d.vertex.PoseStack;
import buildcraft.lib.engine.BlockEngineBase_BC8;
import buildcraft.lib.internal.properties.BuildCraftProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.MultiBufferSource;

/** The actual block-state model (rather than the inventory-item model) used for Blueprint cells. */
public final class PreviewBlockModelRenderer {
    private PreviewBlockModelRenderer() {}

    /** Scoped to one preview pass so weighted block variants are decoded once per distinct state. */
    public static final class CellCache {
    }

    public static boolean renderBlock(BlockState state, PoseStack pose, MultiBufferSource.BufferSource sink,
        CellCache cache, int light, int overlay) {
        // Engine body geometry is implemented by its BER, not the blockstate model.
        if (state.getBlock() instanceof BlockEngineBase_BC8) return false;
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, pose, sink, light, overlay);
        return true;
    }




    /** Engines use 3D item models, aligned to the orientation captured in the blueprint. */
    public static float[] engineItemRotation(BlockState state) {
        if (!(state.getBlock() instanceof BlockEngineBase_BC8)
            || !state.hasProperty(BuildCraftProperties.BLOCK_FACING_6)) return null;
        return switch (state.getValue(BuildCraftProperties.BLOCK_FACING_6)) {
            case DOWN -> new float[] { 180f, 0f };
            case NORTH -> new float[] { 90f, 180f };
            case SOUTH -> new float[] { 90f, 0f };
            case WEST -> new float[] { 90f, 90f };
            case EAST -> new float[] { 90f, 270f };
            default -> new float[] { 0f, 0f };
        };
    }
}
