package buildcraft.energy.client.gui;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import buildcraft.lib.internal.mj.MjCapabilities;
import buildcraft.core.BCCoreItems;
import buildcraft.energy.menu.ContainerDynamoMJ;
import buildcraft.energy.tile.TileDynamoMJ;
import buildcraft.lib.gui.GuiBC8;
import buildcraft.lib.gui.GuiElementSimple;
import buildcraft.lib.gui.GuiIcon;
import buildcraft.lib.gui.elem.ToolTip;
import buildcraft.lib.gui.help.DummyHelpElement;
import buildcraft.lib.gui.help.ElementHelpInfo;
import buildcraft.lib.gui.help.ElementHelpInfo.HelpPosition;
import buildcraft.lib.gui.pos.GuiRectangle;
import buildcraft.lib.misc.LocaleUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** MJ -> FE Dynamo screen, preserving the original BuildCraft 8 layout and help elements. */
public class GuiDynamoMJ extends GuiBC8<ContainerDynamoMJ> {
    private static final ResourceLocation TEXTURE = new ResourceLocation("buildcraftenergy:textures/gui/mj_dynamo_gui.png");
    private static final GuiIcon GUI = new GuiIcon(TEXTURE, 0, 0, 176, 177);
    private static final GuiIcon FE = new GuiIcon(TEXTURE, 176, 0, 16, 60);
    private static final GuiIcon OVERLAY = new GuiIcon(TEXTURE, 39, 18, 80, 23);
    private static final GuiRectangle RECT_UPGRADES = new GuiRectangle(42, 42, 74, 20);
    private static final GuiRectangle RECT_UPGRADE_TYPES = new GuiRectangle(42, 20, 74, 20);
    private static final GuiRectangle RECT_FE_BATTERY = new GuiRectangle(138, 17, 8, 62);

    public GuiDynamoMJ(ContainerDynamoMJ container, Inventory inventory, Component title) {
        super(container, inventory, title);
        imageWidth = 176;
        imageHeight = 177;
        inventoryLabelX = 8;
        inventoryLabelY = 81;
        mainGui.shownElements.add(new LedgerDynamoMJ(mainGui, container.tile, true));
        mainGui.shownElements.add(new DummyHelpElement(
            RECT_UPGRADES.offset(mainGui.rootElement),
            new ElementHelpInfo("buildcraft.help.fe_engine.upgrades.title", 0xFF_FF_FF_FF,
                "buildcraft.help.fe_engine.upgrades.desc")
        ));
        mainGui.shownElements.add(new GuiElementSimple(mainGui, RECT_UPGRADE_TYPES.offset(mainGui.rootElement)) {
            @Override
            public void addToolTips(List<ToolTip> tooltips) {
                if (!contains(mainGui.mouse)) return;
                TileDynamoMJ.getMjPerTick(container.upgrades);
                List<Component> lines = new ArrayList<>();
                lines.add(Component.literal(LocaleUtil.localize("buildcraft.gui.fe_engine.upgrade_types")));
                for (Map.Entry<Item, Long> entry : TileDynamoMJ.FE_UPGRADES.entrySet()) {
                    String itemName = new ItemStack(entry.getKey()).getHoverName().getString();
                    long fe = BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().microMjToWholeFe(entry.getValue());
                    lines.add(Component.literal(itemName + " = +").append(LocaleUtil.localizeFeFlow(fe)));
                }
                tooltips.add(new ToolTip(lines));
            }
        });
        mainGui.shownElements.add(new GuiElementSimple(mainGui, RECT_FE_BATTERY.offset(mainGui.rootElement)) {
            @Override
            public void addHelpElements(List<HelpPosition> elements) {
                String mj = LocaleUtil.localizeMj(TileDynamoMJ.getMjPerTick(container.upgrades)).getString();
                String fe = LocaleUtil.localizeFeFlow(TileDynamoMJ.getFeGenerationRate(container.upgrades)).getString();
                String conversion = LocaleUtil.localize("buildcraft.help.mj_dynamo.fe_battery.desc") + "\n" + mj + " \u2192 " + fe;
                elements.add(ElementHelpInfo.preTranslated(
                    LocaleUtil.localize("buildcraft.help.mj_dynamo.fe_battery.title"),
                    0xFF_FF_FF_FF, conversion).target(this));
            }

            @Override
            public void addToolTips(List<ToolTip> tooltips) {
                if (contains(mainGui.mouse)) {
                    tooltips.add(new ToolTip(Component.literal(
                        LocaleUtil.localizeFe(container.tile.getCurrentFe()).getString() + " / "
                            + LocaleUtil.localizeFe(TileDynamoMJ.MAX_FE).getString())));
                }
            }
        });
    }

    @Override
    protected void drawForegroundLayer(PoseStack pose, int mouseX, int mouseY) {
        // BuildCraftGui temporarily removes AbstractContainerScreen's GUI translation before this hook.
        // Draw labels in the same absolute root coordinates used by the original BC8 GUI.
        double titleX = mainGui.rootElement.getCenterX() - font.width(title) / 2.0;
        double titleY = mainGui.rootElement.getY() + 6;
        font.draw(pose, title, (int) titleX, (int) titleY, 0x404040);
        font.draw(pose, playerInventoryTitle,
            (int) mainGui.rootElement.getX() + inventoryLabelX,
            (int) mainGui.rootElement.getY() + inventoryLabelY, 0x404040);
    }

    @Override
    protected void drawBackgroundLayer(PoseStack pose, int mouseX, int mouseY, float partialTicks) {
        GUI.drawAt(pose, mainGui.rootElement);
        if (container.tile != null) {
            double height = 60.0 * container.tile.getCurrentFe() / TileDynamoMJ.MAX_FE;
            double scale = minecraft.getWindow().getGuiScale();
            height = Math.round(height * scale) / scale;
            FE.drawCutInside(pose, new GuiRectangle(139, 78 - height, 6, height).offset(mainGui.rootElement));
        }

        // Original BC8 draw order: base GUI -> gear icons -> translucent slot overlay.
        // Keeping this in the screen background layer avoids the item icons being lost behind GUI depth state.
        RenderSystem.enableDepthTest();
        itemRenderer.renderAndDecorateItem(new ItemStack(BCCoreItems.GEAR_IRON.get()), leftPos + 60, topPos + 22);
        itemRenderer.renderAndDecorateItem(new ItemStack(BCCoreItems.GEAR_GOLD.get()), leftPos + 83, topPos + 22);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShaderColor(1, 1, 1, 0.65f);
        OVERLAY.drawAt(pose, mainGui.rootElement.offset(39, 18));
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();

    }
}
