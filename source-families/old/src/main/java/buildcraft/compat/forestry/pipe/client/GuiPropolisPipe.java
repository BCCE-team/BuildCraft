/*
 * Genetic-filter GUI behaviour adapted from Forestry Community Edition.
 * Forestry is distributed under the GNU Lesser General Public License v3.0.
 */
package buildcraft.compat.forestry.pipe.client;

import javax.annotation.Nullable;

import buildcraft.compat.forestry.pipe.ContainerPropolisPipe;
import buildcraft.compat.forestry.pipe.SlotPropolisPipe;
import buildcraft.compat.forestry.pipe.client.widget.PropolisRuleWidget;
import buildcraft.compat.forestry.pipe.client.widget.PropolisSelectionWidget;
import buildcraft.compat.forestry.pipe.client.widget.PropolisSpeciesWidget;
import forestry.api.genetics.filter.IFilterLogic;
import forestry.core.config.Constants;
import forestry.core.gui.Drawable;
import forestry.core.gui.GuiForestryTitled;
import forestry.core.gui.widgets.Widget;
import forestry.core.gui.widgets.WidgetScrollBar;
import forestry.sorting.gui.ISelectableProvider;
//? if >=1.20 {
/*?
import net.minecraft.client.gui.GuiGraphics;
?*/
//?}
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import com.mojang.blaze3d.systems.RenderSystem;
//? if <1.20 {
import com.mojang.blaze3d.vertex.PoseStack;
//?}

/** Forestry's genetic-filter screen adapted to a BuildCraft pipe menu. */
public final class GuiPropolisPipe extends GuiForestryTitled<ContainerPropolisPipe> {
    private final WidgetScrollBar scrollBar;
    public final PropolisSelectionWidget selection;
    @Nullable
    private EditBox searchField;

    public GuiPropolisPipe(ContainerPropolisPipe container, Inventory inventory, Component title) {
        super(Constants.TEXTURE_PATH_GUI + "/filter.png", container, inventory, title);
        imageHeight = 222;
        imageWidth = 212;

        for (int i = 0; i < 6; i++) {
            Direction facing = Direction.from3DDataValue(i);
            widgetManager.add(new PropolisRuleWidget(widgetManager, 44, 18 + i * 18, facing, this));
        }
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 2; k++) {
                    widgetManager.add(new PropolisSpeciesWidget(widgetManager,
                        80 + j * 45 + k * 18, 18 + i * 18,
                        Direction.from3DDataValue(i), j, k == 0, this));
                }
            }
        }

        this.scrollBar = new WidgetScrollBar(widgetManager, 193, 150, 12, 64,
            new Drawable(new ResourceLocation(Constants.TEXTURE_PATH_GUI
                + "/container/creative_inventory/tabs.png"), 232, 0, 12, 15));
        widgetManager.add(this.selection = new PropolisSelectionWidget(widgetManager, 0, 134, scrollBar, this));
        widgetManager.add(scrollBar);
        scrollBar.setVisible(false);
    }

    public <S> void onModuleClick(ISelectableProvider<S> provider) {
        if (selection.isSame(provider)) {
            deselectFilter();
        } else {
            selectFilter(provider);
        }
    }

    private <S> void selectFilter(ISelectableProvider<S> provider) {
        selection.setProvider(provider);
        if (searchField != null) {
            searchField.setEditable(true);
            searchField.setVisible(true);
        }
        selection.filterEntries(searchField != null ? searchField.getValue() : "");
        setPlayerSlotsEnabled(false);
    }

    private void deselectFilter() {
        selection.setProvider(null);
        if (searchField != null) {
            searchField.setEditable(false);
            searchField.setVisible(false);
        }
        scrollBar.setVisible(false);
        setPlayerSlotsEnabled(true);
    }

    private void setPlayerSlotsEnabled(boolean enabled) {
        for (Slot slot : menu.slots) {
            if (slot instanceof SlotPropolisPipe propolisSlot) {
                propolisSlot.setEnabled(enabled);
            }
        }
    }

    @Override
    public void init() {
        super.init();
        String oldValue = searchField != null ? searchField.getValue() : "";
        this.searchField = new EditBox(minecraft.font,
            leftPos + selection.getX() + 125,
            topPos + selection.getY() + 4,
            80, minecraft.font.lineHeight, Component.empty());
        searchField.setMaxLength(50);
        searchField.setBordered(false);
        searchField.setTextColor(0xFFFFFF);
        searchField.setValue(oldValue);
        searchField.setEditable(selection.getLogic() != null);
        searchField.setVisible(selection.getLogic() != null);
    }

    @Override
    //? if <1.20 {
    protected void renderBg(PoseStack transform, float partialTicks, int mouseX, int mouseY) {
        super.renderBg(transform, partialTicks, mouseX, mouseY);
    //?} else {
    /*?
    protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
        super.renderBg(graphics, partialTicks, mouseX, mouseY);
    ?*/
    //?}
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        if (searchField != null) {
            //? if <1.20 {
            searchField.render(transform, mouseX, mouseY, partialTicks);
            //?} else {
            /*?
            searchField.render(graphics, mouseX, mouseY, partialTicks);
            ?*/
            //?}
        }
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (searchField != null && searchField.keyPressed(key, scanCode, modifiers)) {
            refreshSearch();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (searchField != null && searchField.charTyped(codePoint, modifiers)) {
            refreshSearch();
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private void refreshSearch() {
        scrollBar.setValue(0);
        selection.filterEntries(searchField != null ? searchField.getValue() : "");
    }

    @Nullable
    @Override
    protected Slot getSlotAtPosition(double mouseX, double mouseY) {
        Slot slot = super.getSlotAtPosition(mouseX, mouseY);
        if (slot instanceof SlotPropolisPipe && selection.getLogic() != null) {
            return null;
        }
        return slot;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        if (super.mouseClicked(mouseX, mouseY, mouseButton)) {
            return true;
        }
        if (searchField != null) {
            searchField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        Widget widget = widgetManager.getAtPosition(mouseX - leftPos, mouseY - topPos);
        if (widget == null) {
            deselectFilter();
        }
        return true;
    }

    @Override
    protected void addLedgers() {
        addHintLedger("filter");
    }

    public IFilterLogic getLogic() {
        return menu.getLogic();
    }
}
