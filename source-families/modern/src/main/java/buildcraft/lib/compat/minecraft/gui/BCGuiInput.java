//? source if >=1.21.1
package buildcraft.lib.compat.minecraft.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.events.GuiEventListener;
//? if >=1.21.11 {
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
//? }

/** Typed input adapter: version-specific event objects never need reflection. */
public final class BCGuiInput {
    private BCGuiInput() {}
//? if >=1.21.11 {
    public static InputConstants.Key key(int key, int scanCode) {
        return InputConstants.getKey(new KeyEvent(key, scanCode, 0));
    }
    public static boolean click(Object target, double x, double y, int button) {
        if (target instanceof BCWidgetInput panel) return panel.mouseClicked(x, y, button);
        return target instanceof GuiEventListener widget && widget.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)), false);
    }
    public static boolean key(Object target, int key, int scanCode, int modifiers) {
        if (target instanceof BCWidgetInput panel) return panel.keyPressed(key, scanCode, modifiers);
        return target instanceof GuiEventListener widget && widget.keyPressed(new KeyEvent(key, scanCode, modifiers));
    }
    public static boolean character(Object target, int codePoint, int modifiers) {
        if (target instanceof BCWidgetInput panel) return codePoint <= Character.MAX_VALUE && panel.charTyped((char) codePoint, modifiers);
        return target instanceof GuiEventListener widget && widget.charTyped(new CharacterEvent(codePoint, modifiers));
    }
//? } else {
    public static InputConstants.Key key(int key, int scanCode) { return InputConstants.getKey(key, scanCode); }
    public static boolean click(Object target, double x, double y, int button) {
        if (target instanceof BCWidgetInput panel) return panel.mouseClicked(x, y, button);
        return target instanceof GuiEventListener widget && widget.mouseClicked(x, y, button);
    }
    public static boolean key(Object target, int key, int scanCode, int modifiers) {
        if (target instanceof BCWidgetInput panel) return panel.keyPressed(key, scanCode, modifiers);
        return target instanceof GuiEventListener widget && widget.keyPressed(key, scanCode, modifiers);
    }
    public static boolean character(Object target, int codePoint, int modifiers) {
        if (target instanceof BCWidgetInput panel) return codePoint <= Character.MAX_VALUE && panel.charTyped((char) codePoint, modifiers);
        return codePoint <= Character.MAX_VALUE && target instanceof GuiEventListener widget && widget.charTyped((char) codePoint, modifiers);
    }
//? }
}
