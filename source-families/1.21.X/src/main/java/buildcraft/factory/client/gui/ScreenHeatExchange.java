//? source if >=1.21.11
package buildcraft.factory.client.gui;

import buildcraft.lib.compat.RenderCompat;

import buildcraft.factory.BCFactorySprites;
import buildcraft.lib.compat.minecraft.gui.BCGraphics;
import buildcraft.lib.gui.BuildCraftGui;
import buildcraft.lib.gui.ContainerScreenBase;
import buildcraft.lib.gui.TankContainerData;
import buildcraft.lib.gui.help.GuiHelpUtil;
import buildcraft.lib.gui.ledger.LedgerHelp;
import buildcraft.lib.gui.ledger.LedgerOwnership;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

public class ScreenHeatExchange extends ContainerScreenBase<MenuHeatExchange> {
    private static final Identifier TEXTURE_BASE = BCFactorySprites.HEAT_EXCHANGE;

    // The four widget data strides MUST match TankContainerData.LEN (14 ints per tank).
    // A standard TankComponent with capacityOffset=8 consumes only 10 ints, causing
    // ContainerScreenBase.setup() to disable every tank on this screen (40 != 56).
    // Keep the widgets screen-local: their setup() stores a reference to this screen.

    private static final int WIPE_X = 61, WIPE_Y = 11, WIPE_U = 176, WIPE_V = 71;
    private static final int WIPE_WIDTH = 54, WIPE_HEIGHT = 71;
    private int progressNow = 0;
    private int progressPrevious = 0;
    private final BuildCraftGui mainGui;

    public ScreenHeatExchange(MenuHeatExchange menu, Inventory inventory, Component name) {
        super(menu, inventory, name, 4, TEXTURE_BASE);
        // BCU's 176x171 GUI; vanilla's 176x166 otherwise clips the last five pixels.
        this.imageHeight = 171;
        progressNow = menu.progressData.get() & 127;
        progressPrevious = progressNow;
        this.mainGui = new BuildCraftGui(this, BuildCraftGui.createWindowedArea(this));
        // Network order is START.in, START.out, END.in, END.out (NOT screen order).
        // A full 14-word stride makes clicks, rich-fluid variants and tooltips line up.
        add(new HeatExchangeTankComponent(44, 64, 34, 17), true);  // cold input
        add(new HeatExchangeTankComponent(116, 43, 16, 38), true); // cooled output
        add(new HeatExchangeTankComponent(44, 12, 16, 38), true);  // hot input
        add(new HeatExchangeTankComponent(98, 12, 34, 17), true);  // heated output
        setup(menu.data);

        if (menu.tile != null) {
            mainGui.shownElements.add(new LedgerOwnership(mainGui, menu.tile, true));
        }
        GuiHelpUtil.addRoot(mainGui, 44, 12, 16, 38, "buildcraft.help.heat_exchange.hot_in.title",
            0xFF_FF_55_55, "buildcraft.help.heat_exchange.hot_in.desc");
        GuiHelpUtil.addRoot(mainGui, 44, 64, 34, 17, "buildcraft.help.heat_exchange.cold_in.title",
            0xFF_55_AA_FF, "buildcraft.help.heat_exchange.cold_in.desc");
        GuiHelpUtil.addRoot(mainGui, 98, 12, 34, 17, "buildcraft.help.heat_exchange.heated_out.title",
            0xFF_FF_AA_55, "buildcraft.help.heat_exchange.heated_out.desc");
        GuiHelpUtil.addRoot(mainGui, 116, 43, 16, 38, "buildcraft.help.heat_exchange.cooled_out.title",
            0xFF_AA_CC_FF, "buildcraft.help.heat_exchange.cooled_out.desc");
        // BCU's precise center-wipe hitbox: does not cover any fluid gauge.
        GuiHelpUtil.addRoot(mainGui, 73, 36, 30, 21, "buildcraft.help.heat_exchange.progress.title",
            0xFF_88_CC_88, "buildcraft.help.heat_exchange.progress.desc1",
            "buildcraft.help.heat_exchange.progress.desc2");
        mainGui.shownElements.add(new LedgerHelp(mainGui, false));
    }

    @Override
    public void containerTick() {
        super.containerTick();
        mainGui.tick();
        progressPrevious = progressNow;
        progressNow = menu.progressData.get() & 127;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderHeatSchematic(guiGraphics, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);
        mainGui.drawBackgroundLayer(guiGraphics, partialTick, mouseX, mouseY, () -> {});
        mainGui.drawElementBackgrounds(guiGraphics);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Keep the machine schematic unobstructed: do not draw the vanilla
        // title / Inventory labels. Help and ownership ledgers still render below.

        mainGui.preDrawForeground(guiGraphics.pose());
        mainGui.drawElementForegrounds(() -> {}, guiGraphics);
        mainGui.postDrawForeground(guiGraphics.pose());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mainGui.onMouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        mainGui.onMouseReleased(mouseX, mouseY, button);
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        mainGui.onMouseDragged(mouseX, mouseY, button, dragX, dragY);
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (mainGui.onKeyTyped(modifiers, RenderCompat.inputKey(keyCode, scanCode))) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** BCU's tank-glass overlays and the animated centre exchange, drawn above the fluid fill. */
    private void renderHeatSchematic(GuiGraphics graphics, float partialTick) {
        drawHeatSlice(graphics, 44, 12, 0, 171, 16, 38);
        drawHeatSlice(graphics, 44, 64, 17, 171, 34, 17);
        drawHeatSlice(graphics, 98, 12, 17, 171, 34, 17);
        drawHeatSlice(graphics, 116, 43, 0, 171, 16, 38);

        int packed = menu.progressData.get();
        int state = packed >>> 7;
        if (state <= 0 || state > 3) return; // OFF / malformed or uninitialised state
        float interpolated = progressPrevious + (progressNow - progressPrevious) * Math.max(0F, Math.min(1F, partialTick));
        float progress = Math.max(0F, Math.min(1F, interpolated / 120F));
        int left = state == 3 ? Math.round((1F - progress) * WIPE_WIDTH) : 0;
        int visible = state == 1 ? Math.round(progress * WIPE_WIDTH) : WIPE_WIDTH - left;
        if (visible > 0) {
            drawHeatSlice(graphics, WIPE_X + left, WIPE_Y, WIPE_U + left,
                WIPE_V, visible, WIPE_HEIGHT);
        }
    }

    private void drawHeatSlice(GuiGraphics graphics, int relX, int relY, int u, int v, int w, int h) {
        int x = leftPos + relX;
        int y = topPos + relY;
        BCGraphics.blit(graphics, TEXTURE_BASE, x, y, u, v, w, h);
    }
}
