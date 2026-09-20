//? source if >=1.21.11
package buildcraft.factory.client.gui;

import buildcraft.lib.compat.RenderCompat;

import buildcraft.factory.BCFactorySprites;
import buildcraft.lib.gui.BuildCraftGui;
import buildcraft.lib.gui.ContainerScreenBase;
import buildcraft.lib.gui.TankContainerData;
import buildcraft.lib.gui.component.TankComponent;
import buildcraft.lib.gui.help.GuiHelpUtil;
import buildcraft.lib.gui.ledger.LedgerHelp;
import buildcraft.lib.gui.ledger.LedgerOwnership;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

public class ScreenHeatExchange extends ContainerScreenBase<MenuHeatExchange> {
    private static final Identifier TEXTURE_BASE = BCFactorySprites.HEAT_EXCHANGE;

    protected static final TankComponent[] tanks = {
        new TankComponent(44, 12, 16, 38, 0, -1, -1, TankContainerData.LEN - 1),
        new TankComponent(44, 64, 33, 16, 0, -1, -1, TankContainerData.LEN - 1),
        new TankComponent(98, 12, 33, 16, 0, -1, -1, TankContainerData.LEN - 1),
        new TankComponent(116, 43, 16, 38, 0, -1, -1, TankContainerData.LEN - 1),
    };

    private final BuildCraftGui mainGui;

    public ScreenHeatExchange(MenuHeatExchange menu, Inventory inventory, Component name) {
        super(menu, inventory, name, 4, TEXTURE_BASE);
        this.mainGui = new BuildCraftGui(this, BuildCraftGui.createWindowedArea(this));
        titleLabelY -= 3;
        inventoryLabelY += 8;
        for (TankComponent tank : tanks) {
            add(tank, true);
        }
        setup(menu.data);

        if (menu.tile != null) {
            mainGui.shownElements.add(new LedgerOwnership(mainGui, menu.tile, true));
        }
        GuiHelpUtil.addRoot(mainGui, 44, 12, 16, 38, "buildcraft.help.heat_exchange.input_a.title",
            0xFF_55_99_FF, "buildcraft.help.heat_exchange.input_a.desc");
        GuiHelpUtil.addRoot(mainGui, 44, 64, 33, 16, "buildcraft.help.heat_exchange.output_a.title",
            0xFF_55_CC_FF, "buildcraft.help.heat_exchange.output_a.desc");
        GuiHelpUtil.addRoot(mainGui, 98, 12, 33, 16, "buildcraft.help.heat_exchange.input_b.title",
            0xFF_FF_99_55, "buildcraft.help.heat_exchange.input_b.desc");
        GuiHelpUtil.addRoot(mainGui, 116, 43, 16, 38, "buildcraft.help.heat_exchange.output_b.title",
            0xFF_FF_CC_55, "buildcraft.help.heat_exchange.output_b.desc");
        mainGui.shownElements.add(new LedgerHelp(mainGui, false));
    }

    @Override
    public void containerTick() {
        super.containerTick();
        mainGui.tick();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
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
        super.renderLabels(guiGraphics, mouseX, mouseY);
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
}
