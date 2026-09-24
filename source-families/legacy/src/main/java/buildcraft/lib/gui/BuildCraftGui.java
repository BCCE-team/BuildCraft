package buildcraft.lib.gui;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.platform.InputConstants.Key;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import buildcraft.lib.BCLibSprites;
import buildcraft.lib.expression.api.IVariableNode.IVariableNodeBoolean;
import buildcraft.lib.gui.config.GuiConfigManager;
import buildcraft.lib.gui.elem.ToolTip;
import buildcraft.lib.gui.pos.GuiRectangle;
import buildcraft.lib.gui.pos.IGuiArea;
import buildcraft.lib.gui.pos.IGuiPosition;
import buildcraft.lib.gui.pos.MousePosition;
import buildcraft.lib.misc.GuiUtil;
import buildcraft.lib.misc.RenderUtil;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;

/** A gui element that allows for easy implementation of an actual {@link Screen} class.
 * <p>
 * This isn't final, although you should generally only subclass this for additional library functionality, not to
 * render out a particular gui.
 * <p>
 * Classes extending {@link Screen} (either directly or indirectly) need to call the following methods:
 * <ul>
 * <li>{@link #tick()} once per tick (usually in {@link Screen#tick()}</li>
 * <li>{@link #drawBackgroundLayer(GuiGraphics, float, int, int, Runnable)} before drawing anything else, except for your own
 * backgrounds</li>
 * <li>{@link #drawElementBackgrounds(GuiGraphics)} after {@link #drawBackgroundLayer(GuiGraphics, float, int, int, Runnable)},but before
 * sub-display backgrounds</li>
 * <li>{@link #drawElementForegrounds(Runnable, GuiGraphics)} after drawing everything else.</li>
 * <li>{@link #preDrawForeground(PoseStack)} if your base gui class offsets the call to drawing the foreground by the gui's
 * position, for example, {@link AbstractContainerScreen}.</li>
 * <li>{@link #postDrawForeground(PoseStack)} after {@link #preDrawForeground(PoseStack)} (and the same rules apply). These two calls
 * should wrap around and calls to this that occur while the gl state is translated.
 * <li>{@link #onMouseClicked(int, int, int)} whenever the mouse is clicked. If this returns true you shouldn't do any
 * other mouse click handling.</li>
 * <li>{@link #onMouseReleased(int, int, int)} whenever the mouse is released.</li>
 * <li>{@link #onMouseDragged(int, int, int, long)} whenever the mouse is dragged.</li>
 * </ul>
 * For both {@link #drawBackgroundLayer(GuiGraphics, float, int, int, Runnable)} and {@link #drawElementForegrounds(Runnable, GuiGraphics)} the
 * {@link Runnable} passed will only be called once, and it's call time will differ based on the
 * {@link #currentMenu}. */
public class BuildCraftGui {

    /** Used to control if this gui should show debugging lines, and other oddities that help development. */
    public static final IVariableNodeBoolean isDebuggingEnabled;

    /** If true then the debug icon will be shown. */
    public static final IVariableNodeBoolean isDebuggingShown;

    static {
        ResourceLocation debugDef = new ResourceLocation("buildcraftlib", "base");
        isDebuggingShown = GuiConfigManager.getOrAddBoolean(debugDef, "debugging_is_shown", false);
        isDebuggingEnabled = GuiConfigManager.getOrAddBoolean(debugDef, "debugging_is_enabled", false);
    }

    public static final GuiSpriteScaled SPRITE_DEBUG = new GuiSpriteScaled(BCLibSprites.DEBUG, 16, 16);

    public final Minecraft mc = Minecraft.getInstance();
    public final Screen gui;
    public final MousePosition mouse = new MousePosition();

    /** The area that encompasses the entire screen. */
    public final IGuiArea screenElement;

    /** The area that most of the GUI elements should be in. For most container-based gui's this will be a rectangle
     * smaller than the entire screen. For gui's that display outside of a world this will probably be the entire
     * screen, and then this will equal the {@link #screenElement}. */
    public final IGuiArea rootElement;

    /** All of the {@link IGuiElement} which will be drawn by this gui. */
    public final List<IGuiElement> shownElements = new ArrayList<>();
    public IMenuElement currentMenu;

    /** Ledger-style elements. */
    public IGuiPosition lowerLeftLedgerPos, lowerRightLedgerPos;
    private float lastPartialTicks;

    public BuildCraftGui(Screen gui, IGuiArea rootElement) {
        this.gui = gui;
        this.screenElement = GuiUtil.AREA_WHOLE_SCREEN;
        this.rootElement = rootElement;

        lowerLeftLedgerPos = rootElement.offset(0, 5);
        lowerRightLedgerPos = rootElement.getPosition(1, -1).offset(0, 5);
    }

    /** Creates a new {@link BuildCraftGui} that uses the entire screen for display. Ledgers are displayed on the
     * opposite side (so that they expand properly). */
    public BuildCraftGui(Screen gui) {
        this.gui = gui;
        this.screenElement = GuiUtil.AREA_WHOLE_SCREEN;
        this.rootElement = screenElement;

        lowerLeftLedgerPos = screenElement.getPosition(1, -1).offset(-5, 5);
        lowerRightLedgerPos = screenElement.offset(5, 5);
    }

    /** Creates a new {@link BuildCraftGui} that takes it's {@link #rootElement} from the {@link AbstractContainerScreen}'s
     * size. */
    public static IGuiArea createWindowedArea(AbstractContainerScreen<?> gui) {
        return IGuiArea.create(gui::getGuiLeft, gui::getGuiTop, gui::getXSize, gui::getYSize);
    }

    /** @return The current partial ticks value. */
    public final float getLastPartialTicks() {
        return lastPartialTicks;
    }

    public void tick() {
        if (currentMenu != null) {
            currentMenu.tick();
        }
        for (IGuiElement element : shownElements) {
            element.tick();
        }
    }

    public List<IGuiElement> getElementsAt(double x, double y) {
        List<IGuiElement> elements = new ArrayList<>();
        IMenuElement m = currentMenu;
        if (m != null) {
            elements.addAll(m.getThisAndChildrenAt(x, y));
            if (m.shouldFullyOverride()) {
                return elements;
            }
        }
        for (IGuiElement elem : shownElements) {
            elements.addAll(elem.getThisAndChildrenAt(x, y));
        }
        return elements;
    }

    private List<ToolTip> getAllTooltips() {
        List<ToolTip> tooltips = new ArrayList<>();

        IMenuElement m = currentMenu;
        if (m != null) {
            m.addToolTips(tooltips);
            if (m.shouldFullyOverride()) {
                return tooltips;
            }
        }

        if (gui instanceof ITooltipElement) {
            ((ITooltipElement) gui).addToolTips(tooltips);
        }
        for (IGuiElement elem : shownElements) {
            elem.addToolTips(tooltips);
        }
        return tooltips;
    }

    private int drawTooltip(ToolTip tooltip, GuiGraphics guiGraphics, double x, double y) {
        int drawX = (int) Math.round(x);
        int drawY = (int) Math.round(y);
        int width = (int) Math.round(screenElement.getWidth());
        int height = (int) Math.round(screenElement.getHeight());
        return 4 + GuiUtil.drawHoveringText(guiGraphics, tooltip, drawX, drawY, width, height, -1, mc.font);
    }

    public void drawBackgroundLayer(GuiGraphics guiGraphics, float partialTicks, int mouseX, int mouseY,
        Runnable menuBackgroundRenderer) {
        this.lastPartialTicks = partialTicks;
        mouse.setMousePosition(mouseX, mouseY);
        if (currentMenu == null || !currentMenu.shouldFullyOverride()) {
            menuBackgroundRenderer.run();
        }

        RenderSystem.setShaderColor(1, 1, 1, 1);
        if (isDebuggingShown.evaluate()) {
            SPRITE_DEBUG.drawAt(guiGraphics, 0, 0);
            if (isDebuggingEnabled.evaluate()) {
                guiGraphics.fill(0, 0, 16, 16, 0x33_FF_FF_FF);

                if (rootElement != screenElement) {
                    int width = 320;
                    int height = 240;
                    int startX = (int) ((rootElement.getWidth() - width) / 2) - 1;
                    int startY = (int) ((rootElement.getHeight() - height) / 2) - 1;
                    int endX = startX + width + 2;
                    int endY = startY + height + 2;
                    guiGraphics.fill(startX, startY, endX + 1, startY + 1, -1);
                    guiGraphics.fill(startX, endY, endX + 1, endY + 1, -1);
                    guiGraphics.fill(startX, startY, startX + 1, endY + 1, -1);
                    guiGraphics.fill(endX, startY, endX + 1, endY + 1, -1);
                }
            }
        }
    }

    public void drawElementBackgrounds(GuiGraphics guiGraphics) {
        for (IGuiElement element : shownElements) {
            if (element != currentMenu) {
                element.drawBackground(guiGraphics, lastPartialTicks);
            }
        }
    }

    public void preDrawForeground(PoseStack pose) {
        pose.pushPose();
        pose.translate(-rootElement.getX(), -rootElement.getY(), 0);
    }

    public void postDrawForeground(PoseStack pose) {
        pose.popPose();
    }

    /** Draws ordinary elements, an overriding menu, tooltips, and optional GUI debug information. */
    public void drawElementForegrounds(Runnable menuBackgroundRenderer, GuiGraphics guiGraphics) {
        RenderSystem.enableDepthTest();
        for (IGuiElement element : shownElements) {
            if (element != currentMenu) {
                element.drawForeground(guiGraphics, lastPartialTicks);
            }
        }

        IMenuElement menu = currentMenu;
        if (menu != null) {
            if (menu.shouldFullyOverride() && menuBackgroundRenderer != null) {
                menuBackgroundRenderer.run();
            }
            menu.drawBackground(guiGraphics, lastPartialTicks);
            menu.drawForeground(guiGraphics, lastPartialTicks);
        }

        GuiUtil.drawVerticallyAppending(mouse, getAllTooltips(), this::drawTooltip, guiGraphics);

        if (isDebuggingEnabled.evaluate()) {
            int x = 6;
            int y = 18;
            List<String> info = new ArrayList<>();
            IntArraySet xAxisFilled = new IntArraySet();
            Font font = mc.font;
            for (IGuiElement element : getElementsAt(mouse.getX(), mouse.getY())) {
                String name = element.getDebugInfo(info);
                int startX = (int) element.getX() - 1;
                int startY = (int) element.getY() - 1;
                int endX = startX + (int) element.getWidth() + 2;
                int endY = startY + (int) element.getHeight() + 2;

                int colour = name.hashCode() | 0xFF_00_00_00;
                float[] hsb = Color.RGBtoHSB(colour & 0xFF, colour >> 8 & 0xFF, colour >> 16 & 0xFF, null);
                int dark = Color.HSBtoRGB(hsb[0], hsb[1], Math.max(hsb[2] - 0.25f, 0)) | 0xFF_00_00_00;

                guiGraphics.fill(startX, startY, endX + 1, startY + 1, colour);
                guiGraphics.fill(startX, endY, endX + 1, endY + 1, colour);
                guiGraphics.fill(startX, startY, startX + 1, endY + 1, colour);
                guiGraphics.fill(endX, startY, endX + 1, endY + 1, colour);
                guiGraphics.fill(startX - 1, startY - 1, endX + 2, startY, dark);
                guiGraphics.fill(startX - 1, endY + 1, endX + 2, endY + 2, dark);
                guiGraphics.fill(startX - 1, startY - 1, startX, endY + 2, dark);
                guiGraphics.fill(endX + 1, startY - 1, endX + 2, endY + 2, dark);

                guiGraphics.drawString(font, name, x, y, -1, true);
                int textEndX = x + font.width(name) + 3;
                int markerX = ((startX + 3) >> 2) << 2;
                for (int candidate = markerX; candidate < endX; candidate += 4) {
                    if (xAxisFilled.add(candidate)) {
                        markerX = candidate;
                        break;
                    }
                }
                GuiUtil.drawHorizontalLine(guiGraphics, textEndX, markerX, y + 4, colour);
                GuiUtil.drawVerticalLine(guiGraphics, markerX, y + 4, startY, colour);
                y += font.lineHeight + 2;

                for (String line : info) {
                    guiGraphics.drawString(font, line, x + 7, y, -1, true);
                    y += font.lineHeight + 2;
                }
                info.clear();
            }
        }
    }

    /** @return True if the {@link #currentMenu} {@link IMenuElement#shouldFullyOverride() fully overrides} other mouse
     *         clicks, false otherwise. */
    public boolean onMouseClicked(double mouseX, double mouseY, int mouseButton) {
        mouse.setMousePosition(mouseX, mouseY);

        if (isDebuggingShown.evaluate()) {
            GuiRectangle debugRect = new GuiRectangle(0, 0, 16, 16);
            if (debugRect.contains(mouse)) {
                isDebuggingEnabled.set(!isDebuggingEnabled.evaluate());
            }
        }

        IMenuElement m = currentMenu;
        if (m != null) {
            m.onMouseClicked(mouseButton);
            if (m.shouldFullyOverride()) {
                return true;
            }
        }

        for (IGuiElement element : shownElements) {
            if (element instanceof IInteractionElement) {
                ((IInteractionElement) element).onMouseClicked(mouseButton);
            }
        }
        return false;
    }

    public void onMouseDragged(double mouseX, double mouseY, int clickedMouseButton, double finalX, double finalY) {
        mouse.setMousePosition(mouseX, mouseY);

        IMenuElement m = currentMenu;
        if (m != null) {
            m.onMouseDragged(clickedMouseButton, finalX, finalY);
            if (m.shouldFullyOverride()) {
                return;
            }
        }

        for (IGuiElement element : shownElements) {
            if (element instanceof IInteractionElement) {
                ((IInteractionElement) element).onMouseDragged(clickedMouseButton, finalX, finalY);
            }
        }
    }

    public void onMouseReleased(double mouseX, double mouseY, int state) {
        mouse.setMousePosition(mouseX, mouseY);

        IMenuElement m = currentMenu;
        if (m != null) {
            m.onMouseReleased(state);
            if (m.shouldFullyOverride()) {
                return;
            }
        }

        for (IGuiElement element : shownElements) {
            if (element instanceof IInteractionElement) {
                ((IInteractionElement) element).onMouseReleased(state);
            }
        }
    }

    public boolean onKeyTyped(int p_97767_, Key key) {
        boolean action = false;
        IMenuElement m = currentMenu;
        if (m != null) {
            action = m.onKeyPress(p_97767_, key);
            if (action && m.shouldFullyOverride()) {
                return true;
            }
        }

        for (IGuiElement element : shownElements) {
            if (element instanceof IInteractionElement) {
                action |= ((IInteractionElement) element).onKeyPress(p_97767_, key);
            }
        }
        return action;
    }
}
