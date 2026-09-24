/*
 * Genetic-filter GUI behaviour adapted from Forestry Community Edition.
 * Forestry is distributed under the GNU Lesser General Public License v3.0.
 */
package buildcraft.compat.forestry.pipe.client.widget;

import java.util.Collection;

import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import buildcraft.compat.forestry.pipe.client.GuiPropolisPipe;
import forestry.api.IForestryApi;
import forestry.api.client.ForestrySprites;
import forestry.api.client.IForestryClientApi;
import forestry.api.core.tooltips.ToolTip;
import forestry.api.genetics.filter.IFilterLogic;
import forestry.api.genetics.filter.IFilterRuleType;
import forestry.core.gui.GuiForestry;
import forestry.core.gui.widgets.Widget;
import forestry.core.gui.widgets.WidgetManager;
import forestry.core.utils.SoundUtil;
import forestry.sorting.gui.ISelectableProvider;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

public final class PropolisRuleWidget extends Widget implements ISelectableProvider<IFilterRuleType> {
    private static final ImmutableSet<IFilterRuleType> ENTRIES = createEntries();

    private final Direction facing;
    private final GuiPropolisPipe gui;

    public PropolisRuleWidget(WidgetManager manager, int x, int y, Direction facing, GuiPropolisPipe gui) {
        super(manager, x, y);
        this.facing = facing;
        this.gui = gui;
    }

    @Override
    public void draw(PoseStack transform, int startX, int startY) {
        int x = xPos + startX;
        int y = yPos + startY;
        IFilterRuleType rule = gui.getLogic().getRule(facing);
        draw(manager.gui, rule, transform, y, x);
        if (gui.selection.isSame(this)) {
            RenderSystem.setShaderTexture(0, PropolisSelectionWidget.TEXTURE);
            gui.blit(transform, x - 1, y - 1, 212, 0, 18, 18);
        }
    }

    @Override
    public Collection<IFilterRuleType> getEntries() {
        return ENTRIES;
    }

    @Override
    public void draw(GuiForestry gui, IFilterRuleType selectable, PoseStack transform, int y, int x) {
        RenderSystem.setShaderTexture(0, ForestrySprites.TEXTURE_ATLAS);
        TextureAtlasSprite sprite = IForestryClientApi.INSTANCE.getTextureManager().getSprite(selectable.getSprite());
        GuiComponent.blit(transform, x, y, gui.getBlitOffset(), 16, 16, sprite);
    }

    @Override
    public Component getName(IFilterRuleType selectable) {
        return Component.translatable("for.gui.filter." + selectable.getId());
    }

    @Override
    public void onSelect(IFilterRuleType selectable) {
        IFilterLogic logic = gui.getLogic();
        if (logic.setRule(facing, selectable)) {
            logic.sendToServer(facing, selectable);
        }
        if (gui.selection.isSame(this)) {
            gui.onModuleClick(this);
        }
        SoundUtil.playButtonClick();
    }

    @Override
    public void handleMouseClick(double mouseX, double mouseY, int mouseButton) {
        if (mouseButton == 1) {
            onSelect(IForestryApi.INSTANCE.getFilterManager().getDefaultRule());
        } else {
            SoundUtil.playButtonClick();
            gui.onModuleClick(this);
        }
    }

    @Override
    public ToolTip getToolTip(int mouseX, int mouseY) {
        ToolTip tooltip = new ToolTip();
        tooltip.add(getName(gui.getLogic().getRule(facing)));
        return tooltip;
    }

    private static ImmutableSet<IFilterRuleType> createEntries() {
        return ImmutableSet.copyOf(IForestryApi.INSTANCE.getFilterManager().getRules());
    }
}
