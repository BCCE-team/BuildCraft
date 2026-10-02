package buildcraft.lib.compat.minecraft.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import buildcraft.lib.compat.RenderCompat;

/** Typed input adapter: version-specific event objects never need reflection. */
public final class BCGuiInput {
    private BCGuiInput() {}
    public static InputConstants.Key key(int key, int scanCode) {
        return InputConstants.getKey(new KeyEvent(key, scanCode, 0));
    }
    public static boolean click(Object target, double x, double y, int button) {
        if (target instanceof BCWidgetInput panel) return RenderCompat.mouseClicked(panel, x, y, button);
        return target instanceof GuiEventListener widget && RenderCompat.mouseClicked(widget, new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)), false);
    }
    public static boolean key(Object target, int key, int scanCode, int modifiers) {
        if (target instanceof BCWidgetInput panel) return RenderCompat.keyPressed(panel, key, scanCode, modifiers);
        return target instanceof GuiEventListener widget && RenderCompat.keyPressed(widget, new KeyEvent(key, scanCode, modifiers));
    }
    public static boolean character(Object target, int codePoint, int modifiers) {
        if (target instanceof BCWidgetInput panel) return codePoint <= Character.MAX_VALUE && panel.charTyped((char) codePoint, modifiers);
        return target instanceof GuiEventListener widget && widget.charTyped(new CharacterEvent(codePoint, modifiers));
    }
}
