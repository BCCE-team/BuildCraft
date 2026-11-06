package buildcraft.builders.gui;

import java.util.Objects;


import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.item.ItemSchematicSingle;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.ClientSnapshots;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.lib.gui.BuildCraftGui;
import buildcraft.lib.gui.help.DummyHelpElement;
import buildcraft.lib.gui.help.ElementHelpInfo;
import buildcraft.lib.gui.ledger.LedgerHelp;
import buildcraft.lib.gui.ledger.LedgerOwnership;
import buildcraft.lib.gui.pos.GuiRectangle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class ScreenReplacer extends AbstractContainerScreen<MenuReplacer> {

    private static final ResourceLocation TEXTURE_BASE = new ResourceLocation("buildcraftbuilders", "textures/gui/replacer.png");
    private static final int SIZE_X = 176;
    private static final int SIZE_Y = 241;
    private static final int PREVIEW_X = 8;
    private static final int PREVIEW_Y = 9;
    private static final int PREVIEW_W = 160;
    private static final int PREVIEW_H = 100;
    private static final int TEXT_COLOUR = 4210752;
    private static final int ERROR_COLOUR = 0xAA0000;
    private static final int OK_COLOUR = 0x2F6F2F;

    private final BuildCraftGui mainGui;
    private final ReplacerGuiPreview preview = new ReplacerGuiPreview();
    private EditBox nameField;
    private Button replaceButton;
    private Snapshot.Key seededKey;
    private String seededName;

    public ScreenReplacer(MenuReplacer menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.mainGui = new BuildCraftGui(this, BuildCraftGui.createWindowedArea(this));
        if (menu.tile != null) {
            this.mainGui.shownElements.add(new LedgerOwnership(this.mainGui, menu.tile, true));
        }
        this.mainGui.shownElements.add(new DummyHelpElement(
            new GuiRectangle(PREVIEW_X, PREVIEW_Y, PREVIEW_W, PREVIEW_H).offset(this.mainGui.rootElement).expand(2),
            new ElementHelpInfo(
                "buildcraft.help.replacer.preview.title",
                0xFF_CC_AA_88,
                "buildcraft.help.replacer.preview.desc"
            )
        ));
        this.mainGui.shownElements.add(new DummyHelpElement(
            new GuiRectangle(8, 115, 18, 18).offset(this.mainGui.rootElement).expand(2),
            new ElementHelpInfo(
                "buildcraft.help.replacer.blueprint.title",
                0xFF_88_AA_CC,
                "buildcraft.help.replacer.blueprint.desc"
            )
        ));
        this.mainGui.shownElements.add(new DummyHelpElement(
            new GuiRectangle(8, 137, 18, 18).offset(this.mainGui.rootElement).expand(2),
            new ElementHelpInfo(
                "buildcraft.help.replacer.from.title",
                0xFF_CC_88_88,
                "buildcraft.help.replacer.from.desc"
            )
        ));
        this.mainGui.shownElements.add(new DummyHelpElement(
            new GuiRectangle(56, 137, 18, 18).offset(this.mainGui.rootElement).expand(2),
            new ElementHelpInfo(
                "buildcraft.help.replacer.to.title",
                0xFF_88_CC_88,
                "buildcraft.help.replacer.to.desc"
            )
        ));
        this.mainGui.shownElements.add(new DummyHelpElement(
            new GuiRectangle(30, 117, 138, 12).offset(this.mainGui.rootElement),
            new ElementHelpInfo("buildcraft.help.replacer.name.title", 0xFF_E1_C9_2F,
                "buildcraft.help.replacer.name.desc")
        ));
        this.mainGui.shownElements.add(new DummyHelpElement(
            new GuiRectangle(80, 135, 60, 20).offset(this.mainGui.rootElement),
            new ElementHelpInfo("buildcraft.help.replacer.replace.title", 0xFF_CC_AA_88,
                "buildcraft.help.replacer.replace.desc1", "buildcraft.help.replacer.replace.desc2")
        ));
        this.mainGui.shownElements.add(new LedgerHelp(this.mainGui, false));
        this.imageWidth = SIZE_X;
        this.imageHeight = SIZE_Y;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 148;
    }

    @Override
    protected void init() {
        String existingName = nameField == null
            ? (currentHeader() == null ? "" : currentHeader().name) : nameField.getValue();
        boolean keepFocus = nameField != null && nameField.isFocused();
        super.init();
        nameField = new EditBox(font, leftPos + 30, topPos + 117, 138, 12, Component.empty());
        nameField.setMaxLength(MenuReplacer.MAX_BLUEPRINT_NAME_LENGTH);
        nameField.setValue(existingName);
        // A newly constructed EditBox is unfocused. Its setFocused(boolean)
        // method is protected in Minecraft 1.19.2, so do not call it here.
        addRenderableWidget(nameField);
        if (keepFocus) setFocused(nameField);
        replaceButton = new Button(leftPos + 80, topPos + 135, 60, 20,
            Component.translatable("gui.buildcraftbuilders.replacer.replace"),
            button -> menu.sendReplaceRequest(nameField.getValue()));
        addRenderableWidget(replaceButton);
        Snapshot.Header header = currentHeader();
        seededKey = header == null ? null : header.key;
        seededName = header == null ? "" : header.name;
        updateControls();
    }

    @Override
    public void containerTick() {
        super.containerTick();
        this.mainGui.tick();
        updateControls();
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
            if (nameField != null && !nameField.isFocused()) nameField.setValue(name);
            seededKey = key;
            seededName = name;
        }
        if (replaceButton != null) {
            replaceButton.active = ReplacerGuiPreview.canApply(menu.getSlot(0).getItem(),
                menu.getSlot(1).getItem(), menu.getSlot(2).getItem());
            replaceButton.setMessage(Component.translatable(isRemoving()
                ? "gui.buildcraftbuilders.replacer.remove" : "gui.buildcraftbuilders.replacer.replace"));
        }
    }

    private Blueprint preview() {
        return preview.get(menu.getSlot(0).getItem(), menu.getSlot(1).getItem(), menu.getSlot(2).getItem());
    }

    private String summaryText() {
        if (!preview.hasSummary()) return null;
        String text = preview.summary(isRemoving()).getString();
        int maxWidth = PREVIEW_W - 6;
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "...";
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width(ellipsis))) + ellipsis;
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(pose);
        super.render(pose, mouseX, mouseY, partialTick);
        this.renderTooltip(pose, mouseX, mouseY);
    }

    @Override
    protected void renderBg(PoseStack pose, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderTexture(0, TEXTURE_BASE);
        this.blit(pose, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);
        renderBlueprintPreview(pose);
        this.mainGui.drawBackgroundLayer(pose, partialTick, mouseX, mouseY, () -> {});
        this.mainGui.drawElementBackgrounds(pose);
    }

    @Override
    protected void renderLabels(PoseStack pose, int mouseX, int mouseY) {
        // Keep the GUI visually clean: the only text we render is the centred preview message inside the preview panel.
        this.mainGui.preDrawForeground(pose);
        this.mainGui.drawElementForegrounds(pose, () -> {});
        this.mainGui.postDrawForeground(pose);
        String summary = summaryText();
        if (summary != null) {
            pose.pushPose();
            pose.translate(0, 0, 180);
            fill(pose, PREVIEW_X, PREVIEW_Y + PREVIEW_H - 11,
                PREVIEW_X + PREVIEW_W, PREVIEW_Y + PREVIEW_H, 0x99000000);
            this.font.draw(pose, summary, PREVIEW_X + 3, PREVIEW_Y + PREVIEW_H - 9, 0xFFFFFFFF);
            pose.popPose();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (nameField != null && nameField.isFocused() && !nameField.isMouseOver(mouseX, mouseY)) {
            setFocused(null);
        }
        boolean result = super.mouseClicked(mouseX, mouseY, button);
        this.mainGui.onMouseClicked(mouseX, mouseY, button);
        return result;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean result = super.mouseReleased(mouseX, mouseY, button);
        this.mainGui.onMouseReleased(mouseX, mouseY, button);
        return result;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        boolean result = super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        this.mainGui.onMouseDragged(mouseX, mouseY, button, dragX, dragY);
        return result;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (nameField != null && nameField.isFocused()) {
            if (keyCode == 257 || keyCode == 335) { setFocused(null); return true; }
            if (keyCode != 256 && (nameField.keyPressed(keyCode, scanCode, modifiers)
                || nameField.canConsumeInput())) return true;
        }
        if (this.mainGui.onKeyTyped(modifiers, InputConstants.getKey(keyCode, scanCode))) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void renderBlueprintPreview(PoseStack pose) {
        Snapshot snapshot = getInsertedSnapshot();
        if (snapshot == null) {
            renderPreviewMessage(pose, getPreviewMessage(), TEXT_COLOUR);
            return;
        }
        if (!(snapshot instanceof Blueprint blueprint)) {
            renderPreviewMessage(pose, Component.translatable("gui.buildcraftbuilders.replacer.preview.not_blueprint"), ERROR_COLOUR);
            return;
        }

        Blueprint candidate = preview();
        if (candidate == null) return;
        buildcraft.builders.client.render.LegacyBlueprintGuiRenderer.render(
            candidate, pose, this.leftPos + PREVIEW_X, this.topPos + PREVIEW_Y, PREVIEW_W, PREVIEW_H);

    }

    private void renderReplacementStatus(PoseStack pose) {
        StatusLine status = getReplacementStatus();
        int maxWidth = PREVIEW_W - 8;
        String text = this.font.plainSubstrByWidth(status.message.getString(), maxWidth);
        int x = this.leftPos + PREVIEW_X + 4;
        int y = this.topPos + PREVIEW_Y + PREVIEW_H - 12;
        this.font.draw(pose, text, x, y, status.colour);
    }

    private Snapshot getInsertedSnapshot() {
        ItemStack stack = this.menu.getSlot(0).getItem();
        Snapshot.Header header = ItemSnapshot.getHeader(stack);
        if (header == null) {
            return null;
        }
        return ClientSnapshots.INSTANCE.getSnapshot(header.key);
    }

    private Component getPreviewMessage() {
        ItemStack stack = this.menu.getSlot(0).getItem();
        Snapshot.Header header = ItemSnapshot.getHeader(stack);
        return header == null
            ? Component.translatable("gui.buildcraftbuilders.replacer.status.no_blueprint")
            : Component.translatable("gui.buildcraftbuilders.replacer.status.loading");
    }

    private StatusLine getReplacementStatus() {
        ItemStack blueprintStack = this.menu.getSlot(0).getItem();
        Snapshot.Header header = ItemSnapshot.getHeader(blueprintStack);
        if (header == null) {
            return new StatusLine(Component.translatable("gui.buildcraftbuilders.replacer.status.no_blueprint"), TEXT_COLOUR);
        }
        ItemStack fromStack = this.menu.getSlot(1).getItem();
        if (fromStack.isEmpty()) {
            return new StatusLine(Component.translatable("gui.buildcraftbuilders.replacer.status.missing_from"), TEXT_COLOUR);
        }
        ItemStack toStack = this.menu.getSlot(2).getItem();
        if (toStack.isEmpty()) {
            return new StatusLine(Component.translatable("gui.buildcraftbuilders.replacer.status.missing_to"), TEXT_COLOUR);
        }
        Snapshot snapshot = ClientSnapshots.INSTANCE.getSnapshot(header.key);
        if (!(snapshot instanceof Blueprint blueprint)) {
            return new StatusLine(Component.translatable("gui.buildcraftbuilders.replacer.status.loading"), TEXT_COLOUR);
        }
        try {
            ISchematicBlock from = ItemSchematicSingle.getSchematic(fromStack);
            ISchematicBlock to = ItemSchematicSingle.getSchematic(toStack);
            if (from == null || to == null) {
                return new StatusLine(Component.translatable("gui.buildcraftbuilders.replacer.status.invalid_schematic"), ERROR_COLOUR);
            }
            if (Blueprint.schematicMatchesForReplacement(from, to)) {
                return new StatusLine(Component.translatable("gui.buildcraftbuilders.replacer.status.same_schematic"), TEXT_COLOUR);
            }
            int matches = blueprint.countMatchingSchematic(from);
            return matches > 0
                ? new StatusLine(Component.translatable("gui.buildcraftbuilders.replacer.status.ready", matches), OK_COLOUR)
                : new StatusLine(Component.translatable("gui.buildcraftbuilders.replacer.status.no_match"), ERROR_COLOUR);
        } catch (InvalidInputDataException e) {
            return new StatusLine(Component.translatable("gui.buildcraftbuilders.replacer.status.invalid_schematic"), ERROR_COLOUR);
        }
    }

    private void renderPreviewMessage(PoseStack pose, Component message, int colour) {
        int x = this.leftPos + PREVIEW_X + (PREVIEW_W - this.font.width(message.getString())) / 2;
        int y = this.topPos + PREVIEW_Y + PREVIEW_H / 2 - 4;
        this.font.draw(pose, message, x, y, colour);
    }

    private static final class StatusLine {
        private final Component message;
        private final int colour;

        private StatusLine(Component message, int colour) {
            this.message = message;
            this.colour = colour;
        }
    }
}
