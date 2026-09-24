/*
 * Genetic-filter GUI behaviour adapted from Forestry Community Edition.
 * Forestry is distributed under the GNU Lesser General Public License v3.0.
 */
package buildcraft.compat.forestry.pipe.client.widget;

import javax.annotation.Nullable;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import buildcraft.compat.forestry.pipe.client.GuiPropolisPipe;
import forestry.api.ForestryConstants;
import forestry.api.core.tooltips.ToolTip;
import forestry.core.config.Constants;
import forestry.core.gui.widgets.Widget;
import forestry.core.gui.widgets.WidgetManager;
import forestry.core.gui.widgets.WidgetScrollBar;
import forestry.sorting.gui.ISelectableProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class PropolisSelectionWidget extends Widget {
    public static final ResourceLocation TEXTURE = ForestryConstants.forestry(
        Constants.TEXTURE_PATH_GUI + "/filter_selection.png");

    final WidgetScrollBar scrollBar;
    final GuiPropolisPipe gui;
    @Nullable
    private PropolisSelectionLogic<?> logic;

    public PropolisSelectionWidget(WidgetManager manager, int x, int y, WidgetScrollBar scrollBar,
            GuiPropolisPipe gui) {
        super(manager, x, y);
        this.width = 212;
        this.height = 88;
        this.scrollBar = scrollBar;
        this.gui = gui;
    }

    public <S> void setProvider(@Nullable ISelectableProvider<S> provider) {
        this.logic = provider == null ? null : new PropolisSelectionLogic<>(this, provider);
    }

    public boolean isSame(ISelectableProvider<?> provider) {
        return logic != null && logic.isSame(provider);
    }

    @Nullable
    public PropolisSelectionLogic<?> getLogic() {
        return logic;
    }

    @Override
    public void draw(PoseStack transform, int startX, int startY) {
        if (logic == null) {
            return;
        }
        RenderSystem.setShaderTexture(0, TEXTURE);
        manager.gui.blit(transform, startX + xPos, startY + yPos, 0, 0, width, height);
        logic.draw(transform);
        manager.minecraft.font.draw(transform, Component.translatable("for.gui.filter.seletion"),
            startX + xPos + 12, startY + yPos + 4, manager.gui.getFontColor().get("gui.title"));
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return logic != null && super.isMouseOver(mouseX, mouseY);
    }

    @Nullable
    @Override
    public ToolTip getToolTip(int mouseX, int mouseY) {
        return logic == null ? null : logic.getToolTip(mouseX, mouseY);
    }

    @Override
    public void handleMouseClick(double mouseX, double mouseY, int mouseButton) {
        if (logic != null) {
            logic.select(mouseX, mouseY);
        }
    }

    public void filterEntries(String filter) {
        if (logic != null) {
            logic.filterEntries(filter);
        }
    }
}
