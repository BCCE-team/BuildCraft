//? source if >=1.21.11
package buildcraft.lib.gui.recipe;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

/** Vanilla-styled recipe result cell used by {@link GuiRecipeBookPhantom}. */
public final class GuiButtonRecipePhantom {
    public static final int SIZE = 25;
    private static final Identifier SLOT_CRAFTABLE = Identifier.withDefaultNamespace("recipe_book/slot_craftable");
    private static final Identifier SLOT_UNCRAFTABLE = Identifier.withDefaultNamespace("recipe_book/slot_uncraftable");

    private final RecipeDisplayEntry entry;
    private final ItemStack result;
    private final boolean craftable;
    private final int x;
    private final int y;

    public GuiButtonRecipePhantom(RecipeDisplayEntry entry, ContextMap context, boolean craftable, int x, int y) {
        this.entry = entry;
        this.result = entry.resultItems(context).stream().findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
        this.craftable = craftable;
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
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, craftable ? SLOT_CRAFTABLE : SLOT_UNCRAFTABLE,
            x, y, SIZE, SIZE);
        if (!result.isEmpty()) {
            graphics.renderFakeItem(result, x + 4, y + 4);
        }
    }
}
