package buildcraft.lib.compat.minecraft.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.sdl.SDLMouse;
import buildcraft.lib.compat.RenderCompat;

/** Typed input adapter: version-specific event objects never need reflection. */
public final class BCGuiInput {
    private BCGuiInput() {}

    /** 26.3 native mouse events use SDL 1/3/2, while BC8 widgets use legacy 0/1/2. */
    public static int nativeButton(int legacyButton) {
        return switch (legacyButton) {
            case 0 -> SDLMouse.SDL_BUTTON_LEFT;
            case 1 -> SDLMouse.SDL_BUTTON_RIGHT;
            case 2 -> SDLMouse.SDL_BUTTON_MIDDLE;
            default -> legacyButton + 1;
        };
    }

    public static int legacyButton(int nativeButton) {
        return switch (nativeButton) {
            case SDLMouse.SDL_BUTTON_LEFT -> 0;
            case SDLMouse.SDL_BUTTON_RIGHT -> 1;
            case SDLMouse.SDL_BUTTON_MIDDLE -> 2;
            default -> nativeButton - 1;
        };
    }

    public static InputConstants.Key key(int key, int scanCode) {
        return InputConstants.getKey(new KeyEvent(key, scanCode, 0));
    }
    public static boolean click(Object target, double x, double y, int button) {
        if (target instanceof BCWidgetInput panel) return panel.mouseClicked(x, y, button);
        return target instanceof GuiEventListener widget
            && widget.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(nativeButton(button), 0)), false);
    }
    public static boolean key(Object target, int key, int scanCode, int modifiers) {
        if (target instanceof BCWidgetInput panel) return panel.keyPressed(key, scanCode, modifiers);
        return target instanceof GuiEventListener widget && widget.keyPressed(new KeyEvent(key, scanCode, modifiers));
    }
    public static boolean character(Object target, int codePoint, int modifiers) {
        if (target instanceof BCWidgetInput panel) return codePoint <= Character.MAX_VALUE && panel.charTyped((char) codePoint, modifiers);
        return target instanceof GuiEventListener widget && widget.charTyped(new CharacterEvent(codePoint));
    }
}
