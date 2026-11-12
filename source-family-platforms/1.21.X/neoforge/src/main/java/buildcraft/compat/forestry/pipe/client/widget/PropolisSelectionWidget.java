//? source if <1.21.11
/* The Forestry genetic filter chooser, drawn with Forestry's original art. */
/* Forestry Community Edition layout/behavior, LGPL-3.0. */
package buildcraft.compat.forestry.pipe.client.widget;

import forestry.api.ForestryConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Collection;

public final class PropolisSelectionWidget {
    public interface Provider<T> {
        Collection<T> getEntries();
        Component getName(T entry);
        void drawEntry(GuiGraphics graphics, T entry, int x, int y);
        void onSelect(T entry);
    }

    private static final ResourceLocation TEXTURE = ForestryConstants.forestry("textures/gui/filter_selection.png");
    private static final ResourceLocation SCROLLBAR = ForestryConstants.forestry(
        "textures/gui/container/creative_inventory/tabs.png");
    private static final int WIDTH = 212;
    private static final int HEIGHT = 88;
    private static final int SLIDER_X = 193;
    private static final int SLIDER_Y = 16;
    private static final int SLIDER_WIDTH = 12;
    private static final int SLIDER_HEIGHT = 64;
    private static final int SLIDER_THUMB_HEIGHT = 15;

    private final int x;
    private final int y;
    @Nullable
    private PropolisSelectionLogic<?> logic;
    private int scroll;
    private boolean draggingScrollbar;

    public PropolisSelectionWidget(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public boolean isOpen() { return logic != null; }

    public <S> void setProvider(@Nullable Provider<S> provider) {
        logic = provider == null ? null : new PropolisSelectionLogic<>(provider);
        scroll = 0;
        draggingScrollbar = false;
    }

    public boolean isSame(Provider<?> provider) {
        return logic != null && logic.isSame(provider);
    }

    public void filterEntries(String text) {
        if (logic != null) {
            logic.filterEntries(text);
            scroll = 0;
        }
    }

    public boolean contains(double relX, double relY) {
        return logic != null && relX >= x && relX < x + WIDTH && relY >= y && relY < y + HEIGHT;
    }

    public void draw(GuiGraphics graphics, int left, int top, Font font) {
        if (logic == null) return;
        graphics.blit(TEXTURE, left + x, top + y, 0, 0, WIDTH, HEIGHT);
        logic.draw(graphics, left + x, top + y, scroll);
        graphics.drawString(font, Component.translatable("for.gui.filter.seletion"),
            left + x + 12, top + y + 4, 0x404040, false);
        if (logic.getMaxScroll() > 0) {
            int offset = Math.round(scroll * (SLIDER_HEIGHT - SLIDER_THUMB_HEIGHT)
                / (float) logic.getMaxScroll());
            graphics.blit(SCROLLBAR, left + x + SLIDER_X, top + y + SLIDER_Y + offset,
                232, 0, SLIDER_WIDTH, SLIDER_THUMB_HEIGHT);
        }
    }

    @Nullable
    public Component getTooltip(double relX, double relY) {
        if (!contains(relX, relY) || logic == null) return null;
        return logic.tooltip(relX - x, relY - y, scroll);
    }

    public boolean handleClick(double relX, double relY, int button) {
        if (!contains(relX, relY) || logic == null) return false;
        double localX = relX - x;
        double localY = relY - y;
        if (button == 0 && localX >= SLIDER_X && localX < SLIDER_X + SLIDER_WIDTH
                && localY >= SLIDER_Y && localY < SLIDER_Y + SLIDER_HEIGHT
                && logic.getMaxScroll() > 0) {
            draggingScrollbar = true;
            dragScrollbar(relX, relY, button);
            return true;
        }
        if (button == 0) return logic.select(localX, localY, scroll);
        return false;
    }

    public boolean dragScrollbar(double relX, double relY, int button) {
        if (!draggingScrollbar || button != 0 || logic == null) return false;
        float normalized = (float) ((relY - y - SLIDER_Y - SLIDER_THUMB_HEIGHT / 2.0)
            / (SLIDER_HEIGHT - SLIDER_THUMB_HEIGHT));
        int value = Math.round(normalized * logic.getMaxScroll());
        scroll = Math.max(0, Math.min(value, logic.getMaxScroll()));
        return true;
    }

    public boolean releaseScrollbar(int button) {
        if (button != 0 || !draggingScrollbar) return false;
        draggingScrollbar = false;
        return true;
    }

    public void scroll(int rows) {
        if (logic != null) {
            scroll = Math.max(0, Math.min(scroll + rows, logic.getMaxScroll()));
        }
    }
}
