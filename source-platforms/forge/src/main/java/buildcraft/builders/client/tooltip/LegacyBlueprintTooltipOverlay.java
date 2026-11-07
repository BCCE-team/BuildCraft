/* Copyright (c) 2026 BuildCraft Community Edition contributors; MPL 2.0. */
package buildcraft.builders.client.tooltip;

import java.util.List;

import buildcraft.builders.client.render.LegacyBlueprintGuiRenderer;
import buildcraft.builders.gui.BlueprintPreviewFrame;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.snapshot.ClientSnapshots;
import buildcraft.builders.snapshot.Snapshot;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderTooltipEvent;
//? if <1.20 {
import net.minecraft.client.gui.GuiComponent;
//?} else {
/*?
import org.joml.Vector2ic;
?*/
//?}

/**
 * Separate 3D panel for used Blueprint and Template items, as in BuildCraft Redux.
 * Forge 1.19.2 / 1.20.1 only expose the Pre and Color tooltip events: there is
 * no PostText event. The preview is drawn outside the main tooltip before it.
 * Never registers on dedicated servers and never changes a saved snapshot.
 */
public final class LegacyBlueprintTooltipOverlay {
    private static final int SIZE = 100;
    private static final int GAP = 10;

    private LegacyBlueprintTooltipOverlay() { }

    public static void onTooltipPre(RenderTooltipEvent.Pre event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof ItemSnapshot)) return;
        Snapshot.Header header = ItemSnapshot.getHeader(stack);
        if (header == null || header.key == null) return;
        List<ClientTooltipComponent> components = event.getComponents();
        if (components.isEmpty()) return;

        Font font = event.getFont();
        int textWidth = 0;
        int textHeight = components.size() == 1 ? -2 : 0;
        for (ClientTooltipComponent component : components) {
            textWidth = Math.max(textWidth, component.getWidth(font));
            textHeight += component.getHeight();
        }

        //? if <1.20 {
        // Forge 1.19.2 still uses the legacy (mouse +12,-12) tooltip positioning.
        // The vanilla text tooltip may flip to the left when its hash line
        // no longer fits; only its vertical placement is relevant here.
        int tooltipY = event.getY() - 12;
        if (tooltipY + textHeight + 6 > event.getScreenHeight()) tooltipY = event.getScreenHeight() - textHeight - 6;
        if (tooltipY < 4) tooltipY = 4;
        PoseStack pose = event.getPoseStack();
        //?} else {
        /*?
        // 1.20.1 uses the actual tooltip positioner, not raw mouse coordinates.
        Vector2ic location = event.getTooltipPositioner().positionTooltip(
            event.getScreenWidth(), event.getScreenHeight(), event.getX(), event.getY(), textWidth, textHeight);
        int tooltipY = location.y();
        PoseStack pose = event.getGraphics().pose();
        ?*/
        //?}
        // Anchor the framed 3D panel to the cursor, NOT to the text tooltip.
        // A long blueprint hash makes the vanilla tooltip flip horizontally,
        // sometimes to X=0 halfway across the hovered inventory slot.
        int x = BlueprintPreviewFrame.tooltipX(event.getX(), event.getScreenWidth(), SIZE);
        if (x < 0) return;
        int y = tooltipY + textHeight + GAP;
        if (y + SIZE + 4 > event.getScreenHeight()) y = tooltipY - SIZE - GAP;
        y = Math.max(4, Math.min(y, event.getScreenHeight() - SIZE - 4));

        // Show the panel immediately, even while ClientSnapshots is waiting for
        // the server response. This is deliberately independent of scene loading.
        pose.pushPose();
        try {
            pose.translate(0.0, 0.0, 400.0);
            //? if <1.20 {
            GuiComponent.fill(pose, x - 3, y - 3, x + SIZE + 3, y + SIZE + 3, 0xF0100010);
            GuiComponent.fill(pose, x - 3, y - 3, x + SIZE + 3, y - 2, 0xFF6B4F98);
            GuiComponent.fill(pose, x - 3, y + SIZE + 2, x + SIZE + 3, y + SIZE + 3, 0xFF30204A);
            GuiComponent.fill(pose, x - 3, y - 2, x - 2, y + SIZE + 2, 0xFF6B4F98);
            GuiComponent.fill(pose, x + SIZE + 2, y - 2, x + SIZE + 3, y + SIZE + 2, 0xFF30204A);
            //?} else {
            /*?
            event.getGraphics().fill(x - 3, y - 3, x + SIZE + 3, y + SIZE + 3, 0xF0100010);
            event.getGraphics().fill(x - 3, y - 3, x + SIZE + 3, y - 2, 0xFF6B4F98);
            event.getGraphics().fill(x - 3, y + SIZE + 2, x + SIZE + 3, y + SIZE + 3, 0xFF30204A);
            event.getGraphics().fill(x - 3, y - 2, x - 2, y + SIZE + 2, 0xFF6B4F98);
            event.getGraphics().fill(x + SIZE + 2, y - 2, x + SIZE + 3, y + SIZE + 2, 0xFF30204A);
            // Flush the panel before the separate world-style 3D buffer renders.
            event.getGraphics().flush();
            ?*/
            //?}
        } finally {
            pose.popPose();
        }

        Snapshot snapshot = ClientSnapshots.INSTANCE.getSnapshot(header.key);
        if (snapshot != null) LegacyBlueprintGuiRenderer.render(snapshot, pose, x, y, SIZE, SIZE);
    }
}
