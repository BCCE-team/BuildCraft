//? source if >=1.21.1
package buildcraft.lib.compat.minecraft.gui;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
//? if >=1.21.11 {
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//? }

/** Native input lifecycle boundary shared by BC8 menus and component-based screens. */
public abstract class BCContainerScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {
    protected BCContainerScreen(T menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

//? if >=1.21.11 {
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        try (BCInputState.Scope ignored = BCInputState.pushShift(event.hasShiftDown())) {
            return mouseClicked(event.x(), event.y(), event.button()) || super.mouseClicked(event, doubleClick);
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        try (BCInputState.Scope ignored = BCInputState.pushShift(event.hasShiftDown())) {
            return mouseDragged(event.x(), event.y(), event.button(), dragX, dragY)
                || super.mouseDragged(event, dragX, dragY);
        }
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        try (BCInputState.Scope ignored = BCInputState.pushShift(event.hasShiftDown())) {
            return mouseReleased(event.x(), event.y(), event.button()) || super.mouseReleased(event);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        try (BCInputState.Scope ignored = BCInputState.pushShift(event.hasShiftDown())) {
            return keyPressed(event.key(), event.scancode(), event.modifiers()) || super.keyPressed(event);
        }
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return (event.codepoint() <= Character.MAX_VALUE
            && charTyped((char) event.codepoint(), event.modifiers())) || super.charTyped(event);
    }

    // Stable internal callbacks. Only this boundary constructs or consumes native event records.
    public boolean mouseClicked(double x, double y, int button) { return false; }
    public boolean mouseDragged(double x, double y, int button, double dragX, double dragY) { return false; }
    public boolean mouseReleased(double x, double y, int button) { return false; }
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) { return false; }
    public boolean charTyped(char codePoint, int modifiers) { return false; }
//? }
}
