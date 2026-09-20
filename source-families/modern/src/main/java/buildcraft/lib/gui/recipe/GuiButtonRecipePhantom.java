//? source if >=1.21.11
package buildcraft.lib.gui.recipe;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

/** Small recipe result cell used by {@link GuiRecipeBookPhantom}. */
public final class GuiButtonRecipePhantom {
    public static final int SIZE = 24;

    private final RecipeDisplayEntry entry;
    private final ItemStack result;
    private final int x;
    private final int y;

    public GuiButtonRecipePhantom(RecipeDisplayEntry entry, ContextMap context, int x, int y) {
        this.entry = entry;
        this.result = entry.resultItems(context).stream().findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
        this.x = x;
        this.y = y;
    }

    public RecipeDisplayEntry entry() {
        return entry;
    }

    public ItemStack result() {
        return result;
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + SIZE && mouseY >= y && mouseY < y + SIZE;
    }

    public void render(GuiGraphics graphics, boolean hovered) {
        graphics.fill(x, y, x + SIZE, y + SIZE, hovered ? 0xFF8A8A8A : 0xFF555555);
        graphics.fill(x + 1, y + 1, x + SIZE - 1, y + SIZE - 1, 0xFF202020);
        if (!result.isEmpty()) {
            graphics.renderFakeItem(result, x + 4, y + 4);
        }
    }
}
