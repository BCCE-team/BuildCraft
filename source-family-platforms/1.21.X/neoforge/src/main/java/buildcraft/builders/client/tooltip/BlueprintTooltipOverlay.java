//? source if >=1.21.10
/* Copyright (c) 2026 BuildCraft Community Edition contributors
 * Adapted from the BuildCraft Redux BlueprintTooltipOverlay (MPL 2.0).
 */
package buildcraft.builders.client.tooltip;

import java.util.List;
import org.joml.Vector2ic;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import buildcraft.builders.client.render.BlueprintRenderer;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.item.ItemSchematicSingle;
import buildcraft.builders.snapshot.ClientSnapshots;
import buildcraft.builders.snapshot.Snapshot;

/** An extra vanilla-style 3D preview panel below the item tooltip, for used schematics. */
public final class BlueprintTooltipOverlay {
    private static final int SIDE = 100;
    private BlueprintTooltipOverlay() {}

    public static void onPreTooltip(RenderTooltipEvent.Pre event) {
        ItemStack stack = event.getItemStack();
        Snapshot snapshot;
        if (stack.getItem() instanceof ItemSnapshot) {
            Snapshot.Header header = ItemSnapshot.getHeader(stack);
            if (header == null || header.key == null) return;
            snapshot = ClientSnapshots.INSTANCE.getSnapshot(header.key);
        } else if (stack.getItem() instanceof ItemSchematicSingle) {
            snapshot = SingleSchematicPreviewCache.previewFor(stack);
            if (snapshot == null) return; // blank or invalid single scheme
        } else {
            return;
        }
        List<ClientTooltipComponent> components = event.getComponents();
        if (components.isEmpty()) return;
        Font font = event.getFont();
        int textWidth = 0;
        int textHeight = components.size() == 1 ? -2 : 0;
        for (ClientTooltipComponent part : components) {
            textWidth = Math.max(textWidth, part.getWidth(font));
            textHeight += part.getHeight(font);
        }
        ClientTooltipPositioner positioner = event.getTooltipPositioner();
        Vector2ic position = positioner.positionTooltip(event.getScreenWidth(), event.getScreenHeight(),
            event.getX(), event.getY(), textWidth, textHeight);
        int x = position.x();
        int y = position.y() + textHeight + 10;
        // Keep the preview on the visible side of the screen if the main tooltip is very low.
        if (y + SIDE + 8 > event.getScreenHeight()) {
            y = position.y() - SIDE - 10;
        }
        if (y < 4) y = 4;
        if (x + SIDE + 8 > event.getScreenWidth()) x = Math.max(4, event.getScreenWidth() - SIDE - 8);
        //? if >=26.1 {
        TooltipRenderUtil.extractTooltipBackground(event.getGraphics(), x, y, SIDE, SIDE, null);
        //?} else {
        /*TooltipRenderUtil.renderTooltipBackground(event.getGraphics(), x, y, SIDE, SIDE, null);*/
        //?}
        if (snapshot != null) BlueprintRenderer.renderSnapshot(event.getGraphics(), snapshot, x, y, SIDE, SIDE);
    }
}
