//? source if >=1.21.11
package buildcraft.builders.gui;

import java.util.Objects;

import buildcraft.builders.client.render.BlueprintRenderer;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.lib.gui.BuildCraftGui;
import buildcraft.lib.gui.help.DummyHelpElement;
import buildcraft.lib.gui.help.ElementHelpInfo;
import buildcraft.lib.gui.ledger.LedgerHelp;
import buildcraft.lib.gui.ledger.LedgerOwnership;
import buildcraft.lib.gui.pos.GuiRectangle;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.Identifier;
import buildcraft.lib.compat.RenderCompat;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/** BCU-compatible 176x241 Replacer screen: 3D what-if preview and explicit Replace/Remove. */
public final class ScreenReplacer extends AbstractContainerScreen<MenuReplacer> {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("buildcraftbuilders", "textures/gui/replacer.png");
    private static final int PREVIEW_X = 8, PREVIEW_Y = 9, PREVIEW_W = 160, PREVIEW_H = 100;
    private final BuildCraftGui mainGui;
    private final ReplacerGuiPreview preview = new ReplacerGuiPreview();
    private EditBox nameField;
    private Button replaceButton;
    private Snapshot.Key seededKey;
    private String seededName;

    public ScreenReplacer(MenuReplacer menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 241;
        this.inventoryLabelY = 147;
        this.mainGui = new BuildCraftGui(this, BuildCraftGui.createWindowedArea(this));
        if (menu.tile != null) {
            this.mainGui.shownElements.add(new LedgerOwnership(this.mainGui, menu.tile, true));
        }
        addHelpElements();
        this.mainGui.shownElements.add(new LedgerHelp(this.mainGui, false));
    }

    private void addHelpElements() {
        this.mainGui.shownElements.add(new DummyHelpElement(
            new GuiRectangle(8, 9, 160, 100).offset(this.mainGui.rootElement),
            new ElementHelpInfo("buildcraft.help.replacer.preview.title", 0xFF88CCFF,
                "buildcraft.help.replacer.preview.desc1", "buildcraft.help.replacer.preview.desc2")));
        this.mainGui.shownElements.add(new DummyHelpElement(
            new GuiRectangle(8, 115, 16, 16).offset(this.mainGui.rootElement),
            new ElementHelpInfo("buildcraft.help.replacer.snapshot.title", 0xFF88CC88,
                "buildcraft.help.replacer.snapshot.desc")));
        this.mainGui.shownElements.add(new DummyHelpElement(
            new GuiRectangle(30, 117, 138, 12).offset(this.mainGui.rootElement),
            new ElementHelpInfo("buildcraft.help.replacer.name.title", 0xFFE1C92F,
                "buildcraft.help.replacer.name.desc")));
        this.mainGui.shownElements.add(new DummyHelpElement(
            new GuiRectangle(8, 137, 16, 16).offset(this.mainGui.rootElement),
            new ElementHelpInfo("buildcraft.help.replacer.from.title", 0xFFFF8888,
                "buildcraft.help.replacer.from.desc1", "buildcraft.help.replacer.from.desc2")));
        this.mainGui.shownElements.add(new DummyHelpElement(
            new GuiRectangle(56, 137, 16, 16).offset(this.mainGui.rootElement),
            new ElementHelpInfo("buildcraft.help.replacer.to.title", 0xFF88FF88,
                "buildcraft.help.replacer.to.desc")));
        this.mainGui.shownElements.add(new DummyHelpElement(
            new GuiRectangle(80, 135, 60, 20).offset(this.mainGui.rootElement),
            new ElementHelpInfo("buildcraft.help.replacer.replace.title", 0xFFCCAA88,
                "buildcraft.help.replacer.replace.desc1", "buildcraft.help.replacer.replace.desc2")));
    }

    @Override
    protected void init() {
        String savedName = nameField == null
            ? (currentHeader() == null ? "" : currentHeader().name) : nameField.getValue();
        boolean keepFocus = nameField != null && nameField.isFocused();
        super.init();
        nameField = new EditBox(font, leftPos + 30, topPos + 117, 138, 12, Component.empty());
        nameField.setMaxLength(MenuReplacer.MAX_BLUEPRINT_NAME_LENGTH);
        nameField.setValue(savedName);
        nameField.setFocused(false);
        replaceButton = Button.builder(Component.translatable("gui.buildcraftbuilders.replacer.replace"),
            button -> menu.sendReplaceRequest(nameField.getValue()))
            .bounds(leftPos + 80, topPos + 135, 60, 20).build();
        addRenderableWidget(replaceButton);
        addRenderableWidget(nameField);
        if (keepFocus) setFocused(nameField);
        else setFocused(null);
        Snapshot.Header h = currentHeader();
        seededKey = h == null ? null : h.key;
        seededName = h == null ? "" : h.name;
        updateControls();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        mainGui.tick();
        updateControls();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        mainGui.drawTooltips(graphics);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        RenderCompat.blit(graphics, TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight);
        Blueprint bp = preview();
        if (bp != null) {
            BlueprintRenderer.renderSnapshot(graphics, bp,
                leftPos + PREVIEW_X, topPos + PREVIEW_Y, PREVIEW_W, PREVIEW_H);
        }
        mainGui.drawBackgroundLayer(graphics, partialTick, mouseX, mouseY, () -> {});
        mainGui.drawElementBackgrounds(graphics);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        mainGui.preDrawForeground(graphics.pose());
        mainGui.drawElementForegrounds(() -> {}, graphics);
        mainGui.postDrawForeground(graphics.pose());
        String summary = summaryText();
        if (summary != null) {
            graphics.fill(PREVIEW_X, PREVIEW_Y + PREVIEW_H - 11,
                PREVIEW_X + PREVIEW_W, PREVIEW_Y + PREVIEW_H, 0x99000000);
            graphics.drawString(font, summary, PREVIEW_X + 3, PREVIEW_Y + PREVIEW_H - 9, 0xFFFFFFFF, true);
        }
    }

    private Snapshot.Header currentHeader() {
        if (menu.slots.isEmpty()) return null;
        return ItemSnapshot.getHeader(menu.getSlot(0).getItem());
    }

    private boolean isRemoving() {
        return !menu.getSlot(1).getItem().isEmpty() && menu.getSlot(2).getItem().isEmpty();
    }

    private void updateControls() {
        Snapshot.Header header = currentHeader();
        Snapshot.Key key = header == null ? null : header.key;
        String name = header == null ? "" : header.name;
        if (!Objects.equals(seededKey, key) || !Objects.equals(seededName, name)) {
            // Do not wipe a rename while the user is typing it.
            if (nameField != null && !nameField.isFocused()) nameField.setValue(name);
            seededKey = key;
            seededName = name;
        }
        if (replaceButton != null) {
            replaceButton.active = ReplacerGuiPreview.canApply(
                menu.getSlot(0).getItem(), menu.getSlot(1).getItem(), menu.getSlot(2).getItem());
            replaceButton.setMessage(Component.translatable(isRemoving()
                ? "gui.buildcraftbuilders.replacer.remove" : "gui.buildcraftbuilders.replacer.replace"));
        }
    }

    private Blueprint preview() {
        return preview.get(menu.getSlot(0).getItem(), menu.getSlot(1).getItem(), menu.getSlot(2).getItem());
    }

    /** Keep the BCU summary small enough to fit in its 160px panel. */
    private String summaryText() {
        if (!preview.hasSummary()) return null;
        String line = preview.summary(isRemoving()).getString();
        int maxWidth = 160 - 6;
        if (font.width(line) <= maxWidth) return line;
        String ellipsis = "…";
        return font.plainSubstrByWidth(line, Math.max(0, maxWidth - font.width(ellipsis))) + ellipsis;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (nameField != null && !nameField.isMouseOver(event.x(), event.y())) setFocused(null);
        boolean handled = super.mouseClicked(event, doubleClick);
        mainGui.onMouseClicked(event.x(), event.y(), event.button());
        return handled;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean handled = super.mouseReleased(event);
        mainGui.onMouseReleased(event.x(), event.y(), event.button());
        return handled;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        boolean handled = super.mouseDragged(event, dx, dy);
        mainGui.onMouseDragged(event.x(), event.y(), event.button(), dx, dy);
        return handled;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (nameField != null && nameField.isFocused()) {
            if (event.key() == 257 || event.key() == 335) { setFocused(null); return true; }
            if (event.key() != 256 && (nameField.keyPressed(event) || nameField.canConsumeInput())) return true;
        }
        if (mainGui.onKeyTyped(event.modifiers(), RenderCompat.inputKey(event.key(), event.scancode()))) return true;
        return super.keyPressed(event);
    }
}
