//? source if <1.21.11
/* Forestry genetic rule sprite and selection logic, without Forestry's internal screens. */
/* Forestry Community Edition layout/behavior, LGPL-3.0. */
package buildcraft.compat.forestry.pipe.client.widget;

import buildcraft.compat.forestry.pipe.client.GuiPropolisPipe;
import forestry.api.IForestryApi;
import forestry.api.client.IForestryClientApi;
import forestry.api.ForestryConstants;
import forestry.api.core.genetics.filter.IFilterLogic;
import forestry.api.core.genetics.filter.IFilterRuleType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;

public final class PropolisRuleWidget implements PropolisSelectionWidget.Provider<IFilterRuleType> {
    private static final ResourceLocation SELECTION_TEXTURE = ForestryConstants.forestry(
        "textures/gui/filter_selection.png");
    private final int x;
    private final int y;
    private final Direction facing;
    private final GuiPropolisPipe gui;

    public PropolisRuleWidget(int x, int y, Direction facing, GuiPropolisPipe gui) {
        this.x = x;
        this.y = y;
        this.facing = facing;
        this.gui = gui;
    }

    public void draw(GuiGraphics graphics, int originX, int originY, boolean selected) {
        drawEntry(graphics, gui.getLogic().getRule(facing), originX + x, originY + y);
        if (selected) {
            graphics.blit(SELECTION_TEXTURE, originX + x - 1, originY + y - 1,
                212, 0, 18, 18);
        }
    }

    public boolean contains(double localX, double localY) {
        return localX >= x && localX < x + 16 && localY >= y && localY < y + 16;
    }

    public Component getTooltip() {
        return getName(gui.getLogic().getRule(facing));
    }

    public void handleClick(int mouseButton) {
        if (mouseButton == 1) {
            onSelect(IForestryApi.INSTANCE.getFilterManager().getDefaultRule());
        } else if (mouseButton == 0) {
            gui.onModuleClick(this);
            gui.playClick();
        }
    }

    @Override
    public Collection<IFilterRuleType> getEntries() {
        return IForestryApi.INSTANCE.getFilterManager().getRules();
    }

    @Override
    public void drawEntry(GuiGraphics graphics, IFilterRuleType rule, int x, int y) {
        if (rule == null) return;
        TextureAtlasSprite sprite = IForestryClientApi.INSTANCE.getTextureManager().getSprite(rule.getSprite());
        graphics.blit(x, y, 0, 16, 16, sprite);
    }

    @Override
    public Component getName(IFilterRuleType rule) {
        return Component.translatable("for.gui.filter." + rule.getId());
    }

    @Override
    public void onSelect(IFilterRuleType rule) {
        IFilterLogic filter = gui.getLogic();
        if (filter.setRule(facing, rule)) filter.sendToServer(facing, rule);
        gui.closeSelection();
        gui.playClick();
    }
}
