//? source if >=1.21.1
package buildcraft.lib.compat.minecraft.gui;

/** Input contract for BCCE-owned panels that are not vanilla GuiEventListeners. */
public interface BCWidgetInput {
    boolean mouseClicked(double x, double y, int button);
    boolean keyPressed(int key, int scanCode, int modifiers);
    default boolean charTyped(char codePoint, int modifiers) { return false; }
}
