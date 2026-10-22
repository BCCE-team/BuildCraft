package buildcraft.lib.compat.minecraft.gui;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** One tooltip submission boundary for immediate and deferred GUI rendering. */
public final class BCGuiTooltip {
    private BCGuiTooltip() {}
    public static void item(GuiGraphicsExtractor graphics, Font font, ItemStack stack, int x, int y) {
        if (graphics != null && stack != null) graphics.setTooltipForNextFrame(font, stack, x, y);
    }
    public static void text(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y) {
        if (graphics != null && text != null) graphics.setTooltipForNextFrame(font, text, x, y);
    }
    public static void components(GuiGraphicsExtractor graphics, Font font, List<Component> text, int x, int y) {
        if (graphics != null && text != null && !text.isEmpty()) graphics.setComponentTooltipForNextFrame(font, text, x, y);
    }
}
