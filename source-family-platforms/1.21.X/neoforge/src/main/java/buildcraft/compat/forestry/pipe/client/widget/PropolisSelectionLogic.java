//? source if <1.21.11
/*
 * Filtering and layout of Forestry's 11x4 genetic selector. This class has no
 * dependency on Forestry's non-public GUI classes (not shipped by alpha9).
 * Layout/behavior adapted from Forestry Community Edition (LGPL-3.0).
 */
package buildcraft.compat.forestry.pipe.client.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Pattern;

final class PropolisSelectionLogic<S> {
    static final int SELECTABLE_PER_ROW = 11;
    static final int VISIBLE_ROWS = 4;

    private final PropolisSelectionWidget.Provider<S> provider;
    private final Collection<S> entries;
    private final ArrayList<S> sorted = new ArrayList<>();

    PropolisSelectionLogic(PropolisSelectionWidget.Provider<S> provider) {
        this.provider = provider;
        this.entries = provider.getEntries();
        filterEntries("");
    }

    boolean isSame(PropolisSelectionWidget.Provider<?> other) {
        return provider == other;
    }

    void filterEntries(String query) {
        sorted.clear();
        Pattern pattern;
        try {
            pattern = Pattern.compile(query, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (RuntimeException invalidPattern) {
            pattern = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }
        for (S entry : entries) {
            String name = provider.getName(entry).getString();
            if (pattern.matcher(name).find()) sorted.add(entry);
        }
        sorted.sort(Comparator.comparing(s -> provider.getName(s).getString(),
            String.CASE_INSENSITIVE_ORDER));
    }

    int getMaxScroll() {
        return Math.max(0, (sorted.size() + SELECTABLE_PER_ROW - 1) / SELECTABLE_PER_ROW - VISIBLE_ROWS);
    }

    void draw(GuiGraphics graphics, int screenX, int screenY, int scroll) {
        int startIndex = scroll * SELECTABLE_PER_ROW;
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            for (int col = 0; col < SELECTABLE_PER_ROW; col++) {
                int index = startIndex + row * SELECTABLE_PER_ROW + col;
                if (index >= sorted.size()) return;
                provider.drawEntry(graphics, sorted.get(index),
                    screenX + 12 + 16 * col, screenY + 16 + 16 * row);
            }
        }
    }

    @Nullable
    Component tooltip(double relX, double relY, int scroll) {
        int index = indexAt(relX, relY, scroll);
        return index >= 0 ? provider.getName(sorted.get(index)) : null;
    }

    boolean select(double relX, double relY, int scroll) {
        int index = indexAt(relX, relY, scroll);
        if (index < 0) return false;
        provider.onSelect(sorted.get(index));
        return true;
    }

    private int indexAt(double x, double y, int scroll) {
        double gridX = x - 12;
        double gridY = y - 16;
        if (gridX < 0 || gridX >= SELECTABLE_PER_ROW * 16
                || gridY < 0 || gridY >= VISIBLE_ROWS * 16) return -1;
        int col = (int) gridX / 16;
        int row = (int) gridY / 16;
        int index = scroll * SELECTABLE_PER_ROW + row * SELECTABLE_PER_ROW + col;
        return index < sorted.size() ? index : -1;
    }
}
