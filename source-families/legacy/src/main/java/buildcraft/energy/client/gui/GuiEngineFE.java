package buildcraft.energy.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import buildcraft.core.BCCoreItems;
import buildcraft.energy.menu.ContainerEngineFE;
import buildcraft.energy.tile.TileEngineFE;
import buildcraft.lib.gui.GuiBC8;
import buildcraft.lib.gui.GuiElementSimple;
import buildcraft.lib.gui.GuiIcon;
import buildcraft.lib.gui.elem.ToolTip;
import buildcraft.lib.gui.help.DummyHelpElement;
import buildcraft.lib.gui.help.ElementHelpInfo;
import buildcraft.lib.gui.help.ElementHelpInfo.HelpPosition;
import buildcraft.lib.gui.ledger.LedgerEngine;
import buildcraft.lib.gui.pos.GuiRectangle;
import buildcraft.lib.misc.LocaleUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Restoration of the original BuildCraft 8 external-energy engine screen using FE terminology. */
public class GuiEngineFE extends GuiBC8<ContainerEngineFE> {
    private static final ResourceLocation TEXTURE = new ResourceLocation("buildcraftenergy:textures/gui/fe_engine_gui.png");
    private static final GuiIcon GUI = new GuiIcon(TEXTURE, 0, 0, 176, 177);
    private static final GuiIcon FE = new GuiIcon(TEXTURE, 176, 0, 16, 60);
    private static final GuiIcon OVERLAY = new GuiIcon(TEXTURE, 57, 18, 80, 23);
    private static final GuiRectangle RECT_UPGRADES = new GuiRectangle(60, 42, 74, 20);
    private static final GuiRectangle RECT_UPGRADE_TYPES = new GuiRectangle(60, 20, 74, 20);
    private static final GuiRectangle RECT_FE_BATTERY = new GuiRectangle(30, 17, 8, 62);

    public GuiEngineFE(ContainerEngineFE container, Inventory inventory, Component title) {
        super(container, inventory, title);
        imageWidth = 176;
        imageHeight = 177;
        inventoryLabelX = 8;
        inventoryLabelY = 81;
        mainGui.shownElements.add(new LedgerEngine(mainGui, container.tile, true));
        mainGui.shownElements.add(new DummyHelpElement(
            RECT_UPGRADES.offset(mainGui.rootElement),
            new ElementHelpInfo("buildcraft.help.fe_engine.upgrades.title", 0xFF_FF_FF_FF,
                "buildcraft.help.fe_engine.upgrades.desc")
        ));
        mainGui.shownElements.add(new GuiElementSimple(mainGui, RECT_UPGRADE_TYPES.offset(mainGui.rootElement)) {
            @Override
            public void addToolTips(List<ToolTip> tooltips) {
                if (!contains(mainGui.mouse)) return;
                container.tile.getMjPerTick();
                List<Component> lines = new ArrayList<>();
                lines.add(Component.literal(LocaleUtil.localize("buildcraft.gui.fe_engine.upgrade_types")));
                for (Map.Entry<Item, Long> entry : TileEngineFE.FE_UPGRADES.entrySet()) {
                    String itemName = new ItemStack(entry.getKey()).getHoverName().getString();
                    lines.add(Component.literal(itemName + " = +").append(LocaleUtil.localizeMjFlow(entry.getValue())));
                }
                tooltips.add(new ToolTip(lines));
            }
        });
        mainGui.shownElements.add(new GuiElementSimple(mainGui, RECT_FE_BATTERY.offset(mainGui.rootElement)) {
            @Override
            public void addHelpElements(List<HelpPosition> elements) {
                String fe = LocaleUtil.localizeFe(TileEngineFE.getFeConsumptionRate(container.upgrades)).getString();
                String mj = LocaleUtil.localizeMjFlow(TileEngineFE.getMjPerTick(container.upgrades)).getString();
                String conversion = LocaleUtil.localize("buildcraft.help.fe_engine.fe_battery.desc") + "\n" + fe + " \u2192 " + mj;
                elements.add(ElementHelpInfo.preTranslated(
                    LocaleUtil.localize("buildcraft.help.fe_engine.fe_battery.title"),
                    0xFF_FF_FF_FF, conversion).target(this));
            }

            @Override
            public void addToolTips(List<ToolTip> tooltips) {
                if (contains(mainGui.mouse)) {
                    tooltips.add(new ToolTip(Component.literal(
                        LocaleUtil.localizeFe(container.tile.getCurrentFe()).getString() + " / "
                            + LocaleUtil.localizeFe(TileEngineFE.MAX_FE).getString())));
                }
            }
        });
    }

    @Override
    protected void drawForegroundLayer(PoseStack pose, int mouseX, int mouseY) {
        GuiGraphics guiGraphics = getActiveGraphics();
        // BuildCraftGui temporarily removes AbstractContainerScreen's GUI translation before this hook.
        // Draw labels in the same absolute root coordinates used by the original BC8 GUI.
        double titleX = mainGui.rootElement.getCenterX() - font.width(title) / 2.0;
        double titleY = mainGui.rootElement.getY() + 6;
        guiGraphics.drawString(font, title, (int) titleX, (int) titleY, 0x404040, false);
        guiGraphics.drawString(font, playerInventoryTitle,
            (int) mainGui.rootElement.getX() + inventoryLabelX,
            (int) mainGui.rootElement.getY() + inventoryLabelY, 0x404040, false);
    }

    @Override
    protected void drawBackgroundLayer(PoseStack pose, int mouseX, int mouseY, float partialTicks) {
        GUI.drawAt(getActiveGraphics(), mainGui.rootElement);
        if (container.tile != null) {
            double height = 60.0 * container.tile.getCurrentFe() / TileEngineFE.MAX_FE;
            double scale = minecraft.getWindow().getGuiScale();
            height = Math.round(height * scale) / scale;
            FE.drawCutInside(getActiveGraphics(), new GuiRectangle(31, 78 - height, 6, height).offset(mainGui.rootElement));
        }

        // Original BC8 draw order: base GUI -> gear icons -> translucent slot overlay.
        // Draw directly in the screen layer so GuiGraphics item depth is deterministic.
        GuiGraphics guiGraphics = getActiveGraphics();
        RenderSystem.enableDepthTest();
        guiGraphics.renderItem(new ItemStack(BCCoreItems.GEAR_IRON.get()), leftPos + 78, topPos + 22);
        guiGraphics.renderItem(new ItemStack(BCCoreItems.GEAR_GOLD.get()), leftPos + 101, topPos + 22);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShaderColor(1, 1, 1, 0.65f);
        OVERLAY.drawAt(guiGraphics, mainGui.rootElement.offset(57, 18));
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();

    }
}
