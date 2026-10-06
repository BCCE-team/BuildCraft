/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.gui;

import buildcraft.lib.compat.minecraft.gui.BCGraphics;
import buildcraft.lib.compat.minecraft.gui.BCContainerScreen;
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
import com.mojang.blaze3d.vertex.BufferBuilder;
import buildcraft.lib.compat.mc2612.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import java.util.List;
import java.util.function.Function;
import buildcraft.lib.compat.RenderCompat;

/** Base screen for BuildCraft menus. */
public abstract class GuiBC8<C extends MenuBC_Neptune> extends BCContainerScreen<C> {
    public final BuildCraftGui mainGui;
    public final C container;
    private GuiGraphicsExtractor activeGraphics;
    private int persistentElementCount = -1;

    public GuiBC8(C container, Inventory inventory, Component title) {
        this(container, gui -> new BuildCraftGui(gui, BuildCraftGui.createWindowedArea(gui)), inventory, title);
    }

    public GuiBC8(C container, Function<GuiBC8<?>, BuildCraftGui> constructor, Inventory inventory, Component title) {
        this(container, constructor, inventory, title, 176, 166);
    }

    protected GuiBC8(C container, Inventory inventory, Component title, int imageWidth, int imageHeight) {
        this(container, gui -> new BuildCraftGui(gui, BuildCraftGui.createWindowedArea(gui)), inventory, title,
            imageWidth, imageHeight);
    }

    protected GuiBC8(C container, Function<GuiBC8<?>, BuildCraftGui> constructor, Inventory inventory, Component title,
        int imageWidth, int imageHeight) {
        super(container, inventory, title, imageWidth, imageHeight);
        this.container = container;
        this.mainGui = constructor.apply(this);
        standardLedgerInit();
    }

    public GuiBC8(C container, Identifier jsonGuiDef, Inventory inventory, Component title) {
        this(container, jsonGuiDef, inventory, title, 10, 10);
    }

    /**
     * Sized form of the legacy JSON GUI constructor. Minecraft 26.1 resolves container geometry from the dimensions
     * passed to the superclass constructor, so addons with non-standard JSON GUIs need a constructor-time size just
     * like the native Filler/Gate screens. The four-argument overload keeps the historical 10x10 sentinel semantics.
     */
    protected GuiBC8(C container, Identifier jsonGuiDef, Inventory inventory, Component title,
        int imageWidth, int imageHeight) {
        super(container, inventory, title, imageWidth, imageHeight);
        this.container = container;
        BuildCraftJsonGui jsonGui = new BuildCraftJsonGui(this, BuildCraftGui.createWindowedArea(this), jsonGuiDef);
        jsonGui.properties.put("player.inventory", new InventorySlotHolder(container, container.playerInventory));
        this.mainGui = jsonGui;
        standardLedgerInit();
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

    public void init() {
        if (persistentElementCount < 0) {
            persistentElementCount = mainGui.shownElements.size();
        } else if (mainGui.shownElements.size() > persistentElementCount) {
            mainGui.shownElements.subList(persistentElementCount, mainGui.shownElements.size()).clear();
        }
        super.init();
    }

    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTicks) {
        activeGraphics = guiGraphics;
        try {
            super.extractRenderState(guiGraphics, mouseX, mouseY, partialTicks);
            // GuiGraphicsExtractor is 2D, so tooltips render after slots/items and advance the render stratum
            // instead of relying on a PoseStack Z translation.
            BCGraphics.nextLayer(guiGraphics);
            mainGui.drawTooltips(guiGraphics);
        } finally {
            activeGraphics = null;
        }
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
        // AbstractContainerScreen now extracts its slot tooltip inside super.extractRenderState(). A statement popup
        // that fully overrides the GUI must suppress that vanilla tooltip before BuildCraft submits its own popup tip.
        if (mainGui.currentMenu == null || !mainGui.currentMenu.shouldFullyOverride()) {
            super.extractTooltip(guiGraphics, mouseX, mouseY);
        }
    }

    public void extractContents(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTicks) {
        activeGraphics = guiGraphics;
        renderBg(guiGraphics, partialTicks, mouseX, mouseY);
        super.extractContents(guiGraphics, mouseX, mouseY, partialTicks);
    }

    protected boolean shouldAddOwnerLedger() {
        return true;
    }

    protected boolean shouldAddHelpLedger() {
        return true;
    }

    public void drawGradientRect(GuiGraphicsExtractor guiGraphics, IGuiArea area, int startColor, int endColor) {
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

        Identifier texture = RenderCompat.getShaderTexture();
        if (texture != null) {
            BCGraphics.blit(requireGraphics(), texture, x, y, u, v, w, h);
            return;
        }

        Matrix4f matrix = pose.last().pose();
        float u0 = u / 256.0F;
        float u1 = (u + w) / 256.0F;
        float v0 = v / 256.0F;
        float v1 = (v + h) / 256.0F;

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.addVertex(matrix, x, y + h, 0).setUv(u0, v1);
        builder.addVertex(matrix, x + w, y + h, 0).setUv(u1, v1);
        builder.addVertex(matrix, x + w, y, 0).setUv(u1, v0);
        builder.addVertex(matrix, x, y, 0).setUv(u0, v0);
        BufferUploader.drawWithShader(builder.buildOrThrow());
    }

    public void drawString(GuiGraphicsExtractor guiGraphics, Font fontRenderer, String text, double x, double y, int colour) {
        drawString(guiGraphics, fontRenderer, text, x, y, colour, true);
    }

    public void drawString(GuiGraphicsExtractor guiGraphics, Font fontRenderer, String text, double x, double y, int colour,
        boolean shadow) {
        BCGraphics.text(guiGraphics, fontRenderer, text, (int) x, (int) y, normalizeTextColour(colour), shadow);
    }

    private static int normalizeTextColour(int colour) {
        return (colour & 0xFF000000) == 0 ? colour | 0xFF000000 : colour;
    }

    @Deprecated
    public void drawString(PoseStack pose, Font fontRenderer, String text, double x, double y, int colour) {
        drawString(requireGraphics(), fontRenderer, text, x, y, colour, true);
    }

    /** @deprecated Pass the current GuiGraphicsExtractor explicitly. */
    @Deprecated
    public static void drawItemStackAt(ItemStack stack, GuiGraphicsExtractor guiGraphics, int x, int y) {
        GuiUtil.drawItemStackAt(stack, guiGraphics, x, y);
    }

    public void containerTick() {
        super.containerTick();
        mainGui.tick();
    }

    protected void renderBg(GuiGraphicsExtractor guiGraphics, float partialTicks, int mouseX, int mouseY) {
        activeGraphics = guiGraphics;
        // AbstractContainerScreen has already rendered the vanilla background before calling renderBg.
        // Calling renderBackground from here re-enters renderBg on 1.21 and causes an infinite recursion.
        mainGui.drawBackgroundLayer(guiGraphics, partialTicks, mouseX, mouseY, () -> { });
        drawBackgroundLayer(new PoseStack(), mouseX, mouseY, partialTicks);
        mainGui.drawElementBackgrounds(guiGraphics);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
        // 1.21.1 GuiBC8 intentionally replaced AbstractContainerScreen#renderLabels without calling super,
        // so machine GUIs did not get the vanilla menu title / "Inventory" labels. In 26.1 that hook was
        // renamed to extractLabels; keep the legacy BuildCraft hook reachable and preserve the same label set.
        renderLabels(guiGraphics, mouseX, mouseY);
    }

    protected void renderLabels(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
        activeGraphics = guiGraphics;

        // Container foregrounds receive a GuiGraphicsExtractor already translated by leftPos/topPos. BuildCraft
        // elements use absolute screen coordinates through mainGui.rootElement, so cancel that translation on the
        // real GuiGraphicsExtractor matrix before drawing them.
        BCGraphics.push(guiGraphics);
        BCGraphics.translate(guiGraphics, (float) -mainGui.rootElement.getX(), (float) -mainGui.rootElement.getY());
        try {
            PoseStack legacyPose = new PoseStack();
            drawForegroundLayer(legacyPose, mouseX, mouseY);
            mainGui.drawElementForegrounds(() -> drawMenuOverlay(guiGraphics), guiGraphics);
            drawForegroundLayerAboveElements();
        } finally {
            BCGraphics.pop(guiGraphics);
        }
    }

    /** Draws the dimming layer used by BuildCraft menus. The real GUI matrix is at screen origin here. */
    private void drawMenuOverlay(GuiGraphicsExtractor guiGraphics) {
        guiGraphics.fillGradient(0, 0, width, height, 0xC0101010, 0xD0101010);
    }

    public void drawProgress(GuiGraphicsExtractor guiGraphics, GuiRectangle rect, GuiIcon icon, double widthPercent,
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

    /** Legacy-shaped input hooks used by BuildCraft machine screens; native event entry points delegate here. */
    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        List<IGuiElement> elements = mainGui.getElementsAt(mouseX, mouseY);
        boolean hitsStatementParameter = elements.stream().anyMatch(GuiElementStatementParam.class::isInstance);
        boolean hitsLedger = elements.stream().anyMatch(Ledger_Neptune.class::isInstance);
        if (hitsStatementParameter || hitsLedger) {
            mainGui.onMouseClicked(mouseX, mouseY, mouseButton);
            return true;
        }
        return false
            | mainGui.onMouseClicked(mouseX, mouseY, mouseButton);
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        boolean result = false;
        mainGui.onMouseDragged(mouseX, mouseY, button, dragX, dragY);
        return result;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean result = false;
        mainGui.onMouseReleased(mouseX, mouseY, button);
        return result;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!mainGui.onKeyTyped(modifiers, RenderCompat.inputKey(keyCode, scanCode))) {
            return false;
        }
        return true;
    }

    public boolean charTyped(char codePoint, int modifiers) {
        return super.charTyped(codePoint, modifiers);
    }

    /** Legacy drawing hook used by module GUIs through the lib compatibility path. */
    protected void drawBackgroundLayer(PoseStack pose, int mouseX, int mouseY, float partialTicks) {
    }

    /** Legacy drawing hook used by module GUIs through the lib compatibility path. */
    protected void drawForegroundLayer(PoseStack pose, int mouseX, int mouseY) {
    }

    protected void drawForegroundLayerAboveElements() {
    }

    /** Returns the active GuiGraphicsExtractor while this screen is being rendered. */
    protected final GuiGraphicsExtractor getActiveGraphics() {
        return requireGraphics();
    }

    private GuiGraphicsExtractor requireGraphics() {
        if (activeGraphics == null) {
            throw new IllegalStateException("No active GuiGraphicsExtractor outside the screen render pass");
        }
        return activeGraphics;
    }
}
