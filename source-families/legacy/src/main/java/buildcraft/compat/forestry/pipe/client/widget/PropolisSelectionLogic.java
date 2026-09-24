/*
 * Genetic-filter GUI behaviour adapted from Forestry Community Edition.
 * Forestry is distributed under the GNU Lesser General Public License v3.0.
 */
package buildcraft.compat.forestry.pipe.client.widget;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;


import forestry.api.core.tooltips.ToolTip;
import forestry.core.gui.GuiForestry;
import forestry.core.gui.widgets.IScrollable;
import forestry.sorting.gui.ISelectableProvider;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

final class PropolisSelectionLogic<S> implements IScrollable {
    private static final int SELECTABLE_PER_ROW = 11;

    private final ISelectableProvider<S> provider;
    private final Comparator<S> comparator;
    private final PropolisSelectionWidget widget;
    private final Collection<S> entries;
    private final ArrayList<S> sorted = new ArrayList<>();
    private final Set<SelectableWidget> visible = new HashSet<>();

    PropolisSelectionLogic(PropolisSelectionWidget widget, ISelectableProvider<S> provider) {
        this.widget = widget;
        this.provider = provider;
        this.entries = provider.getEntries();
        this.comparator = Comparator.comparing(value -> provider.getName(value).getString(),
            String.CASE_INSENSITIVE_ORDER);
    }

    boolean isSame(ISelectableProvider<?> provider) {
        return this.provider == provider;
    }

    @Override
    public void onScroll(int value) {
        visible.clear();
        int startIndex = value * SELECTABLE_PER_ROW;
        outer:
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < SELECTABLE_PER_ROW; x++) {
                int index = startIndex + y * SELECTABLE_PER_ROW + x;
                if (index >= sorted.size()) {
                    break outer;
                }
                visible.add(new SelectableWidget(sorted.get(index),
                    widget.getX() + 12 + x * 16,
                    widget.getY() + 16 + y * 16));
            }
        }
    }

    void filterEntries(String searchText) {
        sorted.clear();
        sorted.ensureCapacity(entries.size());

        Pattern pattern;
        try {
            pattern = Pattern.compile(searchText.toLowerCase(Locale.ENGLISH), Pattern.CASE_INSENSITIVE);
        } catch (RuntimeException invalidPattern) {
            pattern = Pattern.compile(Pattern.quote(searchText.toLowerCase(Locale.ENGLISH)),
                Pattern.CASE_INSENSITIVE);
        }

        for (S entry : entries) {
            Component name = provider.getName(entry);
            if (pattern.matcher(name.getString().toLowerCase(Locale.ENGLISH)).find()) {
                sorted.add(entry);
            }
        }
        sorted.sort(comparator);

        int rowsPastVisibleArea = (sorted.size() + SELECTABLE_PER_ROW - 1) / SELECTABLE_PER_ROW - 4;
        if (rowsPastVisibleArea > 0) {
            widget.scrollBar.setParameters(this, 0, rowsPastVisibleArea, 1);
        } else {
            onScroll(0);
        }
        widget.scrollBar.setVisible(rowsPastVisibleArea > 0);
    }

    @Override
    public boolean isFocused(int mouseX, int mouseY) {
        return widget.isMouseOver(mouseX, mouseY);
    }

    void draw(GuiGraphics graphics) {
        for (SelectableWidget selectable : visible) {
            selectable.draw(widget.gui, graphics);
        }
    }

    @Nullable
    ToolTip getToolTip(int mouseX, int mouseY) {
        for (SelectableWidget selectable : visible) {
            if (selectable.isMouseOver(mouseX, mouseY)) {
                return selectable.getToolTip();
            }
        }
        return null;
    }

    void select(double mouseX, double mouseY) {
        mouseX -= widget.gui.getGuiLeft();
        mouseY -= widget.gui.getGuiTop();
        for (SelectableWidget selectable : visible) {
            if (selectable.isMouseOver(mouseX, mouseY)) {
                provider.onSelect(selectable.selectable);
                break;
            }
        }
    }

    private final class SelectableWidget {
        private final S selectable;
        private final int x;
        private final int y;

        private SelectableWidget(S selectable, int x, int y) {
            this.selectable = selectable;
            this.x = x;
            this.y = y;
        }

        private void draw(GuiForestry<?> gui, GuiGraphics graphics) {
            provider.draw(gui, selectable, graphics, y, x);
        }

        private boolean isMouseOver(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= x + 16 && mouseY >= y && mouseY <= y + 16;
        }

        private ToolTip getToolTip() {
            ToolTip tooltip = new ToolTip();
            tooltip.add(provider.getName(selectable));
            return tooltip;
        }
    }
}
