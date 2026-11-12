/*
 * Original Forestry genetic filter layout adapted for the BCCE Apiarist's Pipe.
 * The Forestry artwork and genetic API are used at runtime; the GUI shell is
 * native Minecraft because Forestry CE alpha9 does not expose ForestryScreen
 * and TitledForestryScreen on its published compile classpath.
 * Layout/behavior adapted from Forestry Community Edition (LGPL-3.0).
 */
package buildcraft.compat.forestry.pipe.client;

import buildcraft.compat.forestry.pipe.ContainerPropolisPipe;
import buildcraft.compat.forestry.pipe.SlotPropolisPipe;
import buildcraft.compat.forestry.pipe.client.widget.PropolisRuleWidget;
import buildcraft.compat.forestry.pipe.client.widget.PropolisSelectionWidget;
import buildcraft.compat.forestry.pipe.client.widget.PropolisSpeciesWidget;
import forestry.api.ForestryConstants;
import forestry.api.core.genetics.filter.IFilterLogic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Pixel-for-pixel placement of Forestry's genetic filter: 212x222, six rows
 * of rule/species slots, and the 11x4 selector at (0,134). This uses the
 * original Forestry textures but no unavailable Forestry client base classes.
 */
public final class GuiPropolisPipe extends AbstractContainerScreen<ContainerPropolisPipe> {
    private static final ResourceLocation BACKGROUND = ForestryConstants.forestry("textures/gui/filter.png");

    private final List<PropolisRuleWidget> rules = new ArrayList<>(6);
    private final List<PropolisSpeciesWidget> species = new ArrayList<>(36);
    private final PropolisSelectionWidget selection = new PropolisSelectionWidget(0, 134);
    @Nullable
    private EditBox searchField;

    public GuiPropolisPipe(ContainerPropolisPipe container, Inventory inventory, Component title) {
        super(container, inventory, title);
        imageHeight = 222;
        imageWidth = 212;
        inventoryLabelY = 129;

        List<forestry.api.core.genetics.ISpecies<?>> discoveredSpecies = PropolisSpeciesWidget.getDiscoveredSpecies();
        for (int i = 0; i < 6; i++) {
            Direction facing = Direction.from3DDataValue(i);
            rules.add(new PropolisRuleWidget(44, 18 + i * 18, facing, this));
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 2; k++) {
                    species.add(new PropolisSpeciesWidget(80 + j * 45 + k * 18,
                        18 + i * 18, facing, j, k == 0, this, discoveredSpecies));
                }
            }
        }
    }

    @Override
    protected void init() {
        super.init();
        String oldSearch = searchField != null ? searchField.getValue() : "";
        searchField = new EditBox(font, leftPos + selection.getX() + 125,
            topPos + selection.getY() + 4, 80, font.lineHeight, Component.empty());
        searchField.setMaxLength(50);
        searchField.setBordered(false);
        searchField.setTextColor(0xFFFFFF);
        searchField.setValue(oldSearch);
        boolean choosing = selection.isOpen();
        searchField.setEditable(choosing);
        searchField.setVisible(choosing);
        searchField.setFocused(false);
        setPlayerSlotsEnabled(!choosing);
        if (choosing) {
            selection.filterEntries(oldSearch);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
        graphics.blit(BACKGROUND, leftPos, topPos, 0, 0, imageWidth, imageHeight);
        for (PropolisRuleWidget widget : rules) {
            widget.draw(graphics, leftPos, topPos, selection.isSame(widget));
        }
        for (PropolisSpeciesWidget widget : species) {
            widget.draw(graphics, leftPos, topPos, selection.isSame(widget));
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, (imageWidth - font.width(title)) / 2, 6, 0x404040, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.render(graphics, mouseX, mouseY, partialTicks);
        if (selection.isOpen()) {
            // Draw after vanilla slots to guarantee the dropdown is in front.
            selection.draw(graphics, leftPos, topPos, font);
            if (searchField != null && searchField.visible) {
                searchField.render(graphics, mouseX, mouseY, partialTicks);
            }
        }
        // Preserve vanilla player-inventory tooltips, like ForestryScreen does.
        renderTooltip(graphics, mouseX, mouseY);
        Component tooltip = getSelectionTooltip(mouseX - leftPos, mouseY - topPos);
        if (tooltip != null && menu.getCarried().isEmpty()) {
            graphics.renderTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    @Nullable
    private Component getSelectionTooltip(double x, double y) {
        if (selection.isOpen() && selection.contains(x, y)) {
            return selection.getTooltip(x, y);
        }
        for (PropolisRuleWidget widget : rules) {
            if (widget.contains(x, y)) return widget.getTooltip();
        }
        for (PropolisSpeciesWidget widget : species) {
            if (widget.contains(x, y)) return widget.getTooltip();
        }
        return null;
    }

    public void onModuleClick(PropolisSelectionWidget.Provider<?> provider) {
        if (selection.isSame(provider)) {
            closeSelection();
        } else {
            selection.setProvider(provider);
            if (searchField != null) {
                searchField.setEditable(true);
                searchField.setVisible(true);
                searchField.setFocused(false);
            }
            selection.filterEntries(searchField == null ? "" : searchField.getValue());
            setPlayerSlotsEnabled(false);
        }
    }

    public void closeSelection() {
        selection.setProvider(null);
        if (searchField != null) {
            searchField.setEditable(false);
            searchField.setVisible(false);
            searchField.setFocused(false);
        }
        setPlayerSlotsEnabled(true);
    }

    private void setPlayerSlotsEnabled(boolean enabled) {
        for (Slot slot : menu.slots) {
            if (slot instanceof SlotPropolisPipe pipeSlot) pipeSlot.setEnabled(enabled);
        }
    }

    public void playClick() {
        Minecraft.getInstance().getSoundManager().play(
            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private void refreshSearch() {
        selection.filterEntries(searchField == null ? "" : searchField.getValue());
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (selection.isOpen() && key == 256) {
            closeSelection();
            return true;
        }
        if (selection.isOpen() && searchField != null && searchField.isFocused()
                && searchField.keyPressed(key, scanCode, modifiers)) {
            refreshSearch();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (selection.isOpen() && searchField != null && searchField.isFocused()
                && searchField.charTyped(codePoint, modifiers)) {
            refreshSearch();
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double localX = mouseX - leftPos;
        double localY = mouseY - topPos;
        if (selection.isOpen() && searchField != null && searchField.visible) {
            if (searchField.mouseClicked(mouseX, mouseY, button)) return true;
            searchField.setFocused(false);
        }
        if (selection.isOpen() && selection.contains(localX, localY)) {
            selection.handleClick(localX, localY, button);
            return true; // Do not click the inventory underneath the selector.
        }
        for (PropolisRuleWidget widget : rules) {
            if (widget.contains(localX, localY)) {
                widget.handleClick(button);
                return true;
            }
        }
        for (PropolisSpeciesWidget widget : species) {
            if (widget.contains(localX, localY)) {
                widget.handleClick(button);
                return true;
            }
        }
        if (selection.isOpen()) {
            closeSelection();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (selection.releaseScrollbar(button)) return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (selection.dragScrollbar(mouseX - leftPos, mouseY - topPos, button)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (selection.isOpen() && selection.contains(mouseX - leftPos, mouseY - topPos)) {
            if (scrollY != 0) selection.scroll(scrollY > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    public ContainerPropolisPipe getPipeMenu() {
        return menu;
    }

    public IFilterLogic getLogic() {
        return menu.getLogic();
    }
}
