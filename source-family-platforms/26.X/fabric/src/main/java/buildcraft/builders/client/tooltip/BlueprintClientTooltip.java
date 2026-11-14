/* Copyright (c) 2026 BuildCraft Community Edition contributors. MPL-2.0. */
package buildcraft.builders.client.tooltip;

import buildcraft.builders.client.render.BlueprintRenderer;
import buildcraft.builders.item.BlueprintTooltipData;
import buildcraft.builders.item.ItemSchematicSingle;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.snapshot.ClientSnapshots;
import buildcraft.builders.snapshot.Snapshot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.item.ItemStack;

/** Native Fabric tooltip image; reuses the Replacer/Library PiP renderer, not a second model renderer. */
public final class BlueprintClientTooltip implements ClientTooltipComponent {
    private static final int PREVIEW_SIZE = 96;
    private static final int PADDING = 4;
    private static final int SIZE = PREVIEW_SIZE + 2 * PADDING;
    private final ItemStack stack;

    public BlueprintClientTooltip(BlueprintTooltipData data) {
        this.stack = data.stack();
    }

    @Override
    public int getHeight(Font font) {
        return SIZE;
    }

    @Override
    public int getWidth(Font font) {
        return SIZE;
    }

    @Override
    public void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor graphics) {
        // x/y are the tooltip image row origin, while width is the width of
        // the *entire* tooltip (which may be wider than this component).
        // Centre the preview under its title rather than pinning it to the left.
        int previewX = x + Math.max(0, (width - SIZE) / 2) + PADDING;
        int previewY = y + PADDING;
        // Minecraft 26.3 already draws the themed tooltip background/frame.
        // A second hard-coded rectangular frame was offset and hid the theme.
        Snapshot snapshot = null;
        if (stack.getItem() instanceof ItemSnapshot) {
            Snapshot.Header header = ItemSnapshot.getHeader(stack);
            if (header != null) snapshot = ClientSnapshots.INSTANCE.getSnapshot(header.key);
        } else if (stack.getItem() instanceof ItemSchematicSingle) {
            snapshot = SingleSchematicPreviewCache.previewFor(stack);
        }
        if (snapshot != null && snapshot.size != null) {
            BlueprintRenderer.renderSnapshot(graphics, snapshot, previewX, previewY, PREVIEW_SIZE, PREVIEW_SIZE);
        }
    }
}
