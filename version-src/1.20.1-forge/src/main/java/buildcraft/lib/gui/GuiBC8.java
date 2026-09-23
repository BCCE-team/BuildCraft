/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.gui;

import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.lib.gui.json.BuildCraftJsonGui;
import buildcraft.lib.gui.json.InventorySlotHolder;
import buildcraft.lib.gui.ledger.LedgerHelp;
import buildcraft.lib.gui.ledger.LedgerOwnership;
import buildcraft.lib.gui.ledger.Ledger_Neptune;
import buildcraft.lib.gui.pos.GuiRectangle;
import buildcraft.lib.gui.pos.IGuiArea;
import buildcraft.lib.gui.statement.GuiElementStatementParam;
import buildcraft.lib.misc.GuiUtil;
import buildcraft.lib.tile.TileBC_Neptune;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import java.util.List;
import java.util.function.Function;

/** Base screen for BuildCraft menus. */
public abstract class GuiBC8<C extends MenuBC_Neptune> extends AbstractContainerScreen<C> {
    public final BuildCraftGui mainGui;
    public final C container;
    private GuiGraphics activeGraphics;
    private int persistentElementCount = -1;

    public GuiBC8(C container, Inventory inventory, Component title) {
        this(container, gui -> new BuildCraftGui(gui, BuildCraftGui.createWindowedArea(gui)), inventory, title);
    }

    public GuiBC8(C container, Function<GuiBC8<?>, BuildCraftGui> constructor, Inventory inventory, Component title) {
        super(container, inventory, title);
        this.container = container;
        this.mainGui = constructor.apply(this);
        standardLedgerInit();
    }

    public GuiBC8(C container, ResourceLocation jsonGuiDef, Inventory inventory, Component title) {
        super(container, inventory, title);
        this.container = container;
        BuildCraftJsonGui jsonGui = new BuildCraftJsonGui(this, BuildCraftGui.createWindowedArea(this), jsonGuiDef);
        jsonGui.properties.put("player.inventory", new InventorySlotHolder(container, container.playerInventory));
        this.mainGui = jsonGui;
        standardLedgerInit();
        imageWidth = 10;
        imageHeight = 10;
    }

    private void standardLedgerInit() {
        if (shouldAddOwnerLedger() && container instanceof IMenuBCTile tileMenu) {
            TileBC_Neptune tile = tileMenu.getBCTile();
            if (tile != null) {
                mainGui.shownElements.add(new LedgerOwnership(mainGui, tile, true));
            }
        }
        if (shouldAddHelpLedger()) {
            mainGui.shownElements.add(new LedgerHelp(mainGui, false));
        }
    }

    @Override
    public void init() {
        if (persistentElementCount < 0) {
            persistentElementCount = mainGui.shownElements.size();
        } else if (mainGui.shownElements.size() > persistentElementCount) {
            mainGui.shownElements.subList(persistentElementCount, mainGui.shownElements.size()).clear();
        }
        super.init();
    }

    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        activeGraphics = guiGraphics;
        try {
            super.render(guiGraphics, mouseX, mouseY, partialTicks);
            if (mainGui.currentMenu == null || !mainGui.currentMenu.shouldFullyOverride()) {
                renderTooltip(guiGraphics, mouseX, mouseY);
            }
        } finally {
            activeGraphics = null;
        }
    }

    protected boolean shouldAddOwnerLedger() {
        return true;
    }

    protected boolean shouldAddHelpLedger() {
        return true;
    }

    public void drawGradientRect(GuiGraphics guiGraphics, IGuiArea area, int startColor, int endColor) {
        guiGraphics.fillGradient((int) area.getX(), (int) area.getY(), (int) area.getEndX(),
            (int) area.getEndY(), startColor, endColor);
    }

    /** Compatibility hook for screens that consume the legacy matrix path. */
    @Deprecated
    public void drawGradientRect(PoseStack pose, IGuiArea area, int startColor, int endColor) {
        drawGradientRect(requireGraphics(), area, startColor, endColor);
    }

    public List<Renderable> getButtonList() {
        return renderables;
    }

    public Font getFontRenderer() {
        return font;
    }

    public void drawTexturedModalRect(PoseStack pose, double posX, double posY, double textureX, double textureY,
        double width, double height) {
        int x = Mth.floor(posX);
        int y = Mth.floor(posY);
        int u = Mth.floor(textureX);
        int v = Mth.floor(textureY);
        int w = Mth.floor(width);
        int h = Mth.floor(height);
        Matrix4f matrix = pose.last().pose();
        float u0 = u / 256.0F;
        float u1 = (u + w) / 256.0F;
        float v0 = v / 256.0F;
        float v1 = (v + h) / 256.0F;
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.vertex(matrix, x, y + h, 0).uv(u0, v1).endVertex();
        builder.vertex(matrix, x + w, y + h, 0).uv(u1, v1).endVertex();
        builder.vertex(matrix, x + w, y, 0).uv(u1, v0).endVertex();
        builder.vertex(matrix, x, y, 0).uv(u0, v0).endVertex();
        Tesselator.getInstance().end();
    }

    public void drawString(GuiGraphics guiGraphics, Font fontRenderer, String text, double x, double y, int colour) {
        drawString(guiGraphics, fontRenderer, text, x, y, colour, true);
    }

    public void drawString(GuiGraphics guiGraphics, Font fontRenderer, String text, double x, double y, int colour,
        boolean shadow) {
        guiGraphics.drawString(fontRenderer, text, (int) x, (int) y, colour, shadow);
    }

    @Deprecated
    public void drawString(PoseStack pose, Font fontRenderer, String text, double x, double y, int colour) {
        drawString(requireGraphics(), fontRenderer, text, x, y, colour, true);
    }

    /** @deprecated Pass the current GuiGraphics explicitly. */
    @Deprecated
    public static void drawItemStackAt(ItemStack stack, GuiGraphics guiGraphics, int x, int y) {
        GuiUtil.drawItemStackAt(stack, guiGraphics, x, y);
    }

    @Override
    public void containerTick() {
        super.containerTick();
        mainGui.tick();
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTicks, int mouseX, int mouseY) {
        activeGraphics = guiGraphics;
        mainGui.drawBackgroundLayer(guiGraphics, partialTicks, mouseX, mouseY, () -> renderBackground(guiGraphics));
        drawBackgroundLayer(guiGraphics.pose(), mouseX, mouseY, partialTicks);
        mainGui.drawElementBackgrounds(guiGraphics);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        activeGraphics = guiGraphics;
        PoseStack pose = guiGraphics.pose();
        mainGui.preDrawForeground(pose);
        drawForegroundLayer(pose, mouseX, mouseY);
        mainGui.drawElementForegrounds(() -> renderBackground(guiGraphics), guiGraphics);
        drawForegroundLayerAboveElements();
        mainGui.postDrawForeground(pose);
    }

    public void drawProgress(GuiGraphics guiGraphics, GuiRectangle rect, GuiIcon icon, double widthPercent,
        double heightPercent) {
        double width = rect.width * Math.abs(widthPercent);
        double height = rect.height * Math.abs(heightPercent);
        ISprite sprite = GuiUtil.subRelative(icon.sprite, 0, 0, widthPercent, heightPercent);
        double x = rect.x + mainGui.rootElement.getX();
        double y = rect.y + mainGui.rootElement.getY();
        GuiIcon.draw(guiGraphics, sprite, x, y, x + width, y + height);
    }

    @Deprecated
    public void drawProgress(PoseStack pose, GuiRectangle rect, GuiIcon icon, double widthPercent,
        double heightPercent) {
        drawProgress(requireGraphics(), rect, icon, widthPercent, heightPercent);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        List<IGuiElement> elements = mainGui.getElementsAt(mouseX, mouseY);
        boolean hitsStatementParameter = elements.stream().anyMatch(GuiElementStatementParam.class::isInstance);
        boolean hitsLedger = elements.stream().anyMatch(Ledger_Neptune.class::isInstance);
        if (hitsStatementParameter || hitsLedger) {
            mainGui.onMouseClicked(mouseX, mouseY, mouseButton);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, mouseButton)
            | mainGui.onMouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        boolean result = super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        mainGui.onMouseDragged(mouseX, mouseY, button, dragX, dragY);
        return result;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean result = super.mouseReleased(mouseX, mouseY, button);
        mainGui.onMouseReleased(mouseX, mouseY, button);
        return result;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!mainGui.onKeyTyped(modifiers, InputConstants.getKey(keyCode, scanCode))) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        return true;
    }

    /** Legacy drawing hook used by module GUIs through the lib compatibility path. */
    protected void drawBackgroundLayer(PoseStack pose, int mouseX, int mouseY, float partialTicks) {
    }

    /** Legacy drawing hook used by module GUIs through the lib compatibility path. */
    protected void drawForegroundLayer(PoseStack pose, int mouseX, int mouseY) {
    }

    protected void drawForegroundLayerAboveElements() {
    }

    /** Returns the active GuiGraphics while this screen is being rendered. */
    protected final GuiGraphics getActiveGraphics() {
        return requireGraphics();
    }

    private GuiGraphics requireGraphics() {
        if (activeGraphics == null) {
            throw new IllegalStateException("No active GuiGraphics outside the screen render pass");
        }
        return activeGraphics;
    }
}
