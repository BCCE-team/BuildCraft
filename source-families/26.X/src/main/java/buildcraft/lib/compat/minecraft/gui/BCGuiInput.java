package buildcraft.lib.compat.minecraft.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
//? if neoforge && (>=26.3) {
import org.lwjgl.sdl.SDLMouse;
//? } else {
import buildcraft.lib.compat.RenderCompat;
//?}

/** Typed input adapter: version-specific event objects never need reflection. */
public final class BCGuiInput {
    private BCGuiInput() {}
//? if neoforge && (>=26.3) {
    public static int nativeButton(int button) {
        return switch (button) {
            case 0 -> SDLMouse.SDL_BUTTON_LEFT;
            case 1 -> SDLMouse.SDL_BUTTON_RIGHT;
            case 2 -> SDLMouse.SDL_BUTTON_MIDDLE;
            default -> button + 1;
        };
    }

    public static int legacyButton(int button) {
        return switch (button) {
            case SDLMouse.SDL_BUTTON_LEFT -> 0;
            case SDLMouse.SDL_BUTTON_RIGHT -> 1;
            case SDLMouse.SDL_BUTTON_MIDDLE -> 2;
            default -> button - 1;
        };
    }

//? } else {
//?}
    public static InputConstants.Key key(int key, int scanCode) {
        return InputConstants.getKey(new KeyEvent(key, scanCode, 0));
    }
    public static boolean click(Object target, double x, double y, int button) {
//? if neoforge && (>=26.3) {
        if (target instanceof BCWidgetInput panel) return panel.mouseClicked(x, y, button);
        return target instanceof GuiEventListener widget
            && widget.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(nativeButton(button), 0)), false);
//? } else {
        if (target instanceof BCWidgetInput panel) return RenderCompat.mouseClicked(panel, x, y, button);
        return target instanceof GuiEventListener widget && RenderCompat.mouseClicked(widget, new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)), false);
//?}
    }
    public static boolean key(Object target, int key, int scanCode, int modifiers) {
//? if neoforge && (>=26.3) {
        if (target instanceof BCWidgetInput panel) return panel.keyPressed(key, scanCode, modifiers);
        return target instanceof GuiEventListener widget && widget.keyPressed(new KeyEvent(key, scanCode, modifiers));
//? } else {
        if (target instanceof BCWidgetInput panel) return RenderCompat.keyPressed(panel, key, scanCode, modifiers);
        return target instanceof GuiEventListener widget && RenderCompat.keyPressed(widget, new KeyEvent(key, scanCode, modifiers));
//?}
    }
    public static boolean character(Object target, int codePoint, int modifiers) {
        if (target instanceof BCWidgetInput panel) return codePoint <= Character.MAX_VALUE && panel.charTyped((char) codePoint, modifiers);
//? if neoforge && (>=26.3) {
        return target instanceof GuiEventListener widget && widget.charTyped(new CharacterEvent(codePoint));
//? } else {
        return target instanceof GuiEventListener widget && widget.charTyped(new CharacterEvent(codePoint, modifiers));
//?}
    }
}
