/*
 * Genetic-filter GUI behaviour adapted from Forestry Community Edition.
 * Forestry is distributed under the GNU Lesser General Public License v3.0.
 */
package buildcraft.compat.forestry.pipe.client.widget;

import java.util.IdentityHashMap;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;

import buildcraft.compat.forestry.pipe.client.GuiPropolisPipe;
import forestry.api.IForestryApi;
import forestry.api.core.tooltips.ToolTip;
import forestry.api.genetics.IBreedingTracker;
import forestry.api.genetics.IIndividual;
import forestry.api.genetics.ISpecies;
import forestry.api.genetics.ISpeciesType;
import forestry.api.genetics.capability.IIndividualHandlerItem;
import forestry.api.genetics.filter.IFilterLogic;
import forestry.core.gui.GuiForestry;
import forestry.core.gui.GuiUtil;
import forestry.core.gui.widgets.Widget;
import forestry.core.gui.widgets.WidgetManager;
import forestry.core.utils.GeneticsUtil;
import forestry.core.utils.SoundUtil;
import forestry.sorting.gui.ISelectableProvider;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public final class PropolisSpeciesWidget extends Widget implements ISelectableProvider<ISpecies<?>> {
    private static final IdentityHashMap<ISpecies<?>, ItemStack> ITEMS = createItemEntries();

    private final ImmutableSet<ISpecies<?>> entries;
    private final Direction facing;
    private final int index;
    private final boolean active;
    private final GuiPropolisPipe gui;

    public PropolisSpeciesWidget(WidgetManager manager, int x, int y, Direction facing, int index,
            boolean active, GuiPropolisPipe gui) {
        super(manager, x, y);
        this.facing = facing;
        this.index = index;
        this.active = active;
        this.gui = gui;

        ImmutableSet.Builder<ISpecies<?>> builder = ImmutableSet.builder();
        if (manager.minecraft.level != null && manager.minecraft.player != null) {
            for (ISpeciesType<?, ?> type : IForestryApi.INSTANCE.getGeneticManager().getSpeciesTypes()) {
                IBreedingTracker tracker = type.getBreedingTracker(manager.minecraft.level,
                    manager.minecraft.player.getGameProfile());
                for (ResourceLocation id : tracker.getDiscoveredSpecies()) {
                    ISpecies<?> species = type.getSpeciesSafe(id);
                    if (species != null) {
                        builder.add(species);
                    }
                }
            }
        }
        this.entries = builder.build();
    }

    @Override
    public void draw(GuiGraphics graphics, int startX, int startY) {
        int x = xPos + startX;
        int y = yPos + startY;
        ISpecies<?> species = gui.getLogic().getGenomeFilter(facing, index, active);
        if (species != null) {
            GuiUtil.drawItemStack(graphics, manager.gui, ITEMS.getOrDefault(species, ItemStack.EMPTY), x, y);
        }
        if (gui.selection.isSame(this)) {
            graphics.blit(PropolisSelectionWidget.TEXTURE, x - 1, y - 1, 212, 0, 18, 18);
        }
    }

    @Override
    public ImmutableSet<ISpecies<?>> getEntries() {
        return entries;
    }

    @Override
    public void onSelect(@Nullable ISpecies<?> selectable) {
        IFilterLogic logic = gui.getLogic();
        if (logic.setGenomeFilter(facing, index, active, selectable)) {
            logic.sendToServer(facing, (short) index, active, selectable);
        }
        if (gui.selection.isSame(this)) {
            gui.onModuleClick(this);
        }
        SoundUtil.playButtonClick();
    }

    @Override
    public void draw(GuiForestry<?> gui, ISpecies<?> selectable, GuiGraphics graphics, int y, int x) {
        GuiUtil.drawItemStack(graphics, gui, ITEMS.getOrDefault(selectable, ItemStack.EMPTY), x, y);
    }

    @Override
    public Component getName(ISpecies<?> selectable) {
        return selectable.getDisplayName();
    }

    @Nullable
    @Override
    public ToolTip getToolTip(int mouseX, int mouseY) {
        ISpecies<?> species = gui.getLogic().getGenomeFilter(facing, index, active);
        if (species == null) {
            return null;
        }
        ToolTip tooltip = new ToolTip();
        tooltip.add(getName(species));
        return tooltip;
    }

    @Override
    public void handleMouseClick(double mouseX, double mouseY, int mouseButton) {
        if (gui.getMinecraft().player != null) {
            ItemStack carried = gui.getMinecraft().player.inventoryMenu.getCarried();
            if (!carried.isEmpty()) {
                IIndividual individual = IIndividualHandlerItem.getIndividual(carried);
                if (individual != null) {
                    onSelect(mouseButton == 0 ? individual.getSpecies() : individual.getInactiveSpecies());
                    return;
                }
            }
        }
        if (mouseButton == 1) {
            onSelect(null);
        } else {
            SoundUtil.playButtonClick();
            gui.onModuleClick(this);
        }
    }

    private static IdentityHashMap<ISpecies<?>, ItemStack> createItemEntries() {
        IdentityHashMap<ISpecies<?>, ItemStack> entries = new IdentityHashMap<>();
        for (ISpeciesType<?, ?> type : IForestryApi.INSTANCE.getGeneticManager().getSpeciesTypes()) {
            GeneticsUtil.getIconStacks(entries, type.getDefaultStage(), type);
        }
        return entries;
    }
}
