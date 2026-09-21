//? source if >=1.21.1
package buildcraft.lib.gui.recipe;

import java.util.List;

import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

/** Pagination state for the 1.21.11 phantom crafting recipe panel. */
public final class RecipeBookPagePhantom {
    public static final int ENTRIES_PER_PAGE = 20;

    private List<RecipeDisplayEntry> entries = List.of();
    private int page;

    public void setEntries(List<RecipeDisplayEntry> entries) {
        this.entries = List.copyOf(entries);
        page = Math.min(page, Math.max(0, pageCount() - 1));
    }

    public List<RecipeDisplayEntry> visibleEntries() {
        int start = page * ENTRIES_PER_PAGE;
        if (start >= entries.size()) {
            return List.of();
        }
        return entries.subList(start, Math.min(entries.size(), start + ENTRIES_PER_PAGE));
    }

    public int page() {
        return page;
    }

    public int pageCount() {
        return Math.max(1, (entries.size() + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
    }

    public void reset() {
        page = 0;
    }

    public boolean previous() {
        if (page <= 0) return false;
        page--;
        return true;
    }

    public boolean next() {
        if (page + 1 >= pageCount()) return false;
        page++;
        return true;
    }
}
