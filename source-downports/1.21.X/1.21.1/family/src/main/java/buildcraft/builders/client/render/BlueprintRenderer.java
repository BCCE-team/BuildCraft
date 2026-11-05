/* Copyright (c) 2026 BuildCraft Community Edition contributors; MPL 2.0. */
package buildcraft.builders.client.render;
import buildcraft.builders.snapshot.Snapshot;
import net.minecraft.client.gui.GuiGraphics;
/** 1.21.1 uses the pre-PiP direct 3D GUI renderer. */
public final class BlueprintRenderer {
    private BlueprintRenderer() {}
    public static void renderSnapshot(GuiGraphics graphics, Snapshot snapshot, int x, int y, int width, int height) {
        BlueprintGuiRenderer.render(graphics, snapshot, x, y, width, height);
    }
}
