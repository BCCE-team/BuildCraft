package buildcraft.lib.gui.statement;

import javax.annotation.Nullable;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.lib.internal.statement.IGuiSlot;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.gui.BuildCraftGui;
import buildcraft.lib.gui.GuiIcon;
import buildcraft.lib.gui.IGuiElement;
import buildcraft.lib.gui.IMenuElement;
import buildcraft.lib.misc.data.IReference;
import buildcraft.lib.statement.StatementWrapper;
import buildcraft.lib.compat.RenderCompat;

public class GuiElementStatementDrag implements IMenuElement {

    public final BuildCraftGui gui;

    private boolean isDragging;
    private boolean initialReleasePending;

    @Nullable
    private IGuiSlot dragging;

    public GuiElementStatementDrag(BuildCraftGui gui) {
        this.gui = gui;
    }

    // Dragging

    public void startDragging(IGuiSlot slot) {
        isDragging = true;
        initialReleasePending = true;
        dragging = slot;
        gui.currentMenu = this;
    }

    // IGuiElement

    public double getX() {
        return 0;
    }

    public double getY() {
        return 0;
    }

    public double getWidth() {
        return 0;
    }

    public double getHeight() {
        return 0;
    }

    public void drawForeground(GuiGraphicsExtractor guiGraphics, float partialTicks) {
        if (isDragging) {
            boolean canPlace = false;
            for (IGuiElement element : gui.getElementsAt(gui.mouse.getX(), gui.mouse.getY())) {
                if (element instanceof IReference<?>) {
                    if (checkCanSet((IReference<?>) element, dragging)) {
                        canPlace = true;
                        break;
                    }
                }
            }
            if (!canPlace) {
                RenderCompat.setShaderColor(1f, 0.7f, 0.7f, 1f);
            }
            double x = gui.mouse.getX() - 9;
            double y = gui.mouse.getY() - 9;
            if (dragging instanceof IStatementParameter) {
                ParameterRenderer.draw(guiGraphics, (IStatementParameter) dragging, x, y);
            } else {
                GuiIcon background = GuiElementStatement.SLOT_COLOUR;
                if (dragging instanceof StatementWrapper) {
                    EnumPipePart part = ((StatementWrapper) dragging).getSourcePart();
                    if (part != EnumPipePart.CENTER) {
                        background = background.offset(0, (1 + part.getIndex()) * 18);
                    }
                }
                background.drawAt(guiGraphics, x, y);
                if (dragging != null) {
                    ISprite sprite = dragging.getSprite();
                    if (sprite != null) {
                        GuiIcon.drawAt(guiGraphics, sprite, x + 1, y + 1, 16);
                    }
                }
            }
            RenderCompat.setShaderColor(1, 1, 1, 1);
        }
    }

    private static <T> boolean checkCanSet(IReference<T> ref, Object value) {
        if (value == null) {
            return ref.canSet(null);
        }
        T obj = ref.convertToType(value);
        return obj != null && ref.canSet(obj);
    }

    // IInteractableElement

    public void onMouseClicked(int button) {
        if (!isDragging) return;
        if (!initialReleasePending) {
            // Support click-to-pick / click-to-place as well as the legacy drag gesture.
            if (button == 0) {
                tryPlaceAtPointer();
                clearDrag();
            } else if (button == 1) {
                clearDrag();
            }
            return;
        }
        if (button == 1) {
            // Right-click on an existing statement while dragging switches the carried value.
            for (IGuiElement element : gui.getElementsAt(gui.mouse.getX(), gui.mouse.getY())) {
                if (element instanceof IReference<?> ref) {
                    Object obj = ref.get();
                    if (obj == null || obj instanceof IGuiSlot) {
                        startDragging((IGuiSlot) obj);
                        break;
                    }
                }
            }
        }
    }

    public void onMouseReleased(int button) {
        if (!isDragging || button != 0) return;
        boolean placed = tryPlaceAtPointer();
        if (placed) {
            clearDrag();
        } else if (initialReleasePending) {
            // A click on the source has no destination. Keep its icon attached to the
            // pointer until the next left click, instead of discarding it immediately.
            initialReleasePending = false;
        } else {
            clearDrag();
        }
    }

    private boolean tryPlaceAtPointer() {
        for (IGuiElement element : gui.getElementsAt(gui.mouse.getX(), gui.mouse.getY())) {
            if (element instanceof IReference<?> ref && checkCanSet(ref, dragging)) {
                ref.setIfCan(dragging);
                return true;
            }
        }
        return false;
    }

    private void clearDrag() {
        isDragging = false;
        dragging = null;
        initialReleasePending = false;
        if (gui.currentMenu == this) gui.currentMenu = null;
    }

    // IMenuElement

    public boolean shouldFullyOverride() {
        return false;
    }
}
