//? source if >=1.21.11
package buildcraft.lib.gui.button;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;

import buildcraft.lib.gui.BuildCraftGui;
import buildcraft.lib.gui.ISimpleDrawable;
import buildcraft.lib.gui.pos.IGuiArea;
import buildcraft.lib.gui.pos.IGuiPosition;
import buildcraft.lib.compat.RenderCompat;

public final class GuiButtonDrawable extends GuiAbstractButton {
    private final ISimpleDrawable drEnabled, drActive, drHovered, drActiveHovered, drDisabled, drDisabledActive;

    public static class Builder {
        public final IGuiArea rect;
        public final ISimpleDrawable enabled;
        public ISimpleDrawable active;
        public ISimpleDrawable hovered;
        public ISimpleDrawable activeHovered;
        public ISimpleDrawable disabled;
        public ISimpleDrawable disabledActive;

        public Builder(IGuiArea rect, ISimpleDrawable enabled) {
            this.rect = rect;
            this.enabled = enabled;
        }
    }

    public GuiButtonDrawable(BuildCraftGui gui, String id, IGuiPosition parent, Builder args) {
        super(gui, id, args.rect.offset(parent));
        this.drEnabled = args.enabled;
        this.drActive = getFirstNonnull(args.active, args.enabled);
        this.drHovered = getFirstNonnull(args.hovered, args.enabled);
        this.drActiveHovered = getFirstNonnull(args.activeHovered, args.hovered, args.active, args.enabled);
        this.drDisabled = getFirstNonnull(args.disabled, args.enabled);
        this.drDisabledActive = getFirstNonnull(args.disabledActive, args.disabled, args.enabled);
    }

    private static ISimpleDrawable getFirstNonnull(ISimpleDrawable... of) {
        for (ISimpleDrawable d : of) {
            if (d != null) {
                return d;
            }
        }
        throw new NullPointerException("No non-null elements found!");
    }

    public void drawBackground(GuiGraphics guiGraphics, float partialTicks) {
        if (!visible) {
            return;
        }

        RenderCompat.setShaderColor(1, 1, 1, 1);
        RenderCompat.disableBlend();

        if (enabled) {
            boolean hovered = isMouseOver();
            if (active) {
                if (hovered) {
                    drActiveHovered.drawAt(guiGraphics, this);
                } else {
                    drActive.drawAt(guiGraphics, this);
                }
            } else if (hovered) {
                drHovered.drawAt(guiGraphics, this);
            } else {
                drEnabled.drawAt(guiGraphics, this);
            }
        } else if (active) {
            drDisabledActive.drawAt(guiGraphics, this);
        } else {
            drDisabled.drawAt(guiGraphics, this);
        }
    }
}
