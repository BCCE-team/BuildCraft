//? source if >=1.21.11
package buildcraft.lib.gui.elem;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;

import buildcraft.lib.gui.IGuiElement;
import buildcraft.lib.gui.json.BuildCraftJsonGui;
import buildcraft.lib.gui.pos.IGuiArea;
import buildcraft.lib.misc.GuiUtil;
import buildcraft.lib.misc.GuiUtil.AutoGlScissor;
import buildcraft.lib.compat.RenderCompat;

/** A type of {@link GuiElementContainer2} that restricts the visible size of elements contained within. */
public class GuiElementContainerScissor extends GuiElementContainer2 {

    public final IGuiArea area;

    public GuiElementContainerScissor(BuildCraftJsonGui gui, IGuiArea area) {
        super(gui);
        this.area = area;
    }

    public double getX() {
        return area.getX();
    }

    public double getY() {
        return area.getY();
    }

    public double getWidth() {
        return area.getWidth();
    }

    public double getHeight() {
        return area.getHeight();
    }

    public void drawBackground(GuiGraphics guiGraphics, float partialTicks) {
        try (AutoGlScissor s = GuiUtil.scissor(area)) {
            for (IGuiElement elem : getChildElements()) {
                elem.drawBackground(guiGraphics, partialTicks);
            }
        }
    }

    public void drawForeground(GuiGraphics guiGraphics, float partialTicks) {
        try (AutoGlScissor s = GuiUtil.scissor(area)) {
            for (IGuiElement elem : getChildElements()) {
                elem.drawForeground(guiGraphics, partialTicks);
            }
        }
    }
}
