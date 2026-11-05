/* Copyright (c) 2026 BuildCraft Community Edition contributors. MPL-2.0. */
package buildcraft.builders.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

import buildcraft.builders.client.ClientArchitectTables;
import buildcraft.lib.client.render.DetachedRenderer;
import buildcraft.lib.compat.RenderCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

/** 26.1.2 retains the synchronous world stage (before BCWorldGeometry). */
public enum ArchitectScanWorldRenderer implements DetachedRenderer.IDetachedRenderer {
    INSTANCE;
    private static final Identifier TEXTURE = Identifier.parse(
        "buildcraftbuilders:textures/blocks/scan.png");

    @Override
    public void render(PoseStack pose, Matrix4f matrix, Player player, float partialTicks) {
        if (ClientArchitectTables.SCANNED_BLOCKS.isEmpty()) return;
        var source = Minecraft.getInstance().renderBuffers().bufferSource();
        var layer = RenderCompat.entityTranslucent(TEXTURE);
        ArchitectScanGeometry.draw(pose, source.getBuffer(layer), player.getEyePosition(partialTicks));
        source.endBatch(layer);
    }
}
