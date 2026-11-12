//? source if <1.21.11
/* Forestry species icons and active/inactive genetic filter slots. */
/* Forestry Community Edition layout/behavior, LGPL-3.0. */
package buildcraft.compat.forestry.pipe.client.widget;

import buildcraft.compat.forestry.pipe.client.GuiPropolisPipe;
import forestry.api.ForestryConstants;
import forestry.api.IForestryApi;
import forestry.api.core.genetics.IBreedingTracker;
import forestry.api.core.genetics.IIndividual;
import forestry.api.core.genetics.ISpecies;
import forestry.api.core.genetics.ISpeciesType;
import forestry.api.core.genetics.capability.IIndividualHandlerItem;
import forestry.api.core.genetics.filter.IFilterLogic;
import forestry.core.platform.util.GeneticsUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class PropolisSpeciesWidget implements PropolisSelectionWidget.Provider<ISpecies<?>> {
    private static final ResourceLocation SELECTION_TEXTURE = ForestryConstants.forestry(
        "textures/gui/filter_selection.png");
    private static final Map<ISpecies<?>, ItemStack> ICONS = createIconStacks();

    private final int x;
    private final int y;
    private final Direction facing;
    private final int index;
    private final boolean active;
    private final GuiPropolisPipe gui;
    private final Collection<ISpecies<?>> entries;

    public PropolisSpeciesWidget(int x, int y, Direction facing, int index, boolean active,
            GuiPropolisPipe gui, Collection<ISpecies<?>> discoveredSpecies) {
        this.x = x;
        this.y = y;
        this.facing = facing;
        this.index = index;
        this.active = active;
        this.gui = gui;
        this.entries = discoveredSpecies;
    }

    /** The same discovered-species restriction as Forestry's Genetic Filter. */
    public static List<ISpecies<?>> getDiscoveredSpecies() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return List.of();
        List<ISpecies<?>> result = new ArrayList<>();
        for (ISpeciesType<?, ?> type : IForestryApi.INSTANCE.getGeneticManager().getSpeciesTypes()) {
            IBreedingTracker tracker = type.getBreedingTracker(minecraft.level,
                minecraft.player.getGameProfile());
            for (ResourceLocation id : tracker.getDiscoveredSpecies()) {
                ISpecies<?> species = type.getSpeciesSafe(id);
                if (species != null && !result.contains(species)) result.add(species);
            }
        }
        return result;
    }

    public void draw(GuiGraphics graphics, int originX, int originY, boolean selected) {
        ISpecies<?> value = gui.getLogic().getGenomeFilter(facing, index, active);
        if (value != null) drawEntry(graphics, value, originX + x, originY + y);
        if (selected) {
            graphics.blit(SELECTION_TEXTURE, originX + x - 1, originY + y - 1,
                212, 0, 18, 18);
        }
    }

    public boolean contains(double localX, double localY) {
        return localX >= x && localX < x + 16 && localY >= y && localY < y + 16;
    }

    @Nullable
    public Component getTooltip() {
        ISpecies<?> species = gui.getLogic().getGenomeFilter(facing, index, active);
        return species == null ? null : getName(species);
    }

    public void handleClick(int mouseButton) {
        ItemStack carried = gui.getPipeMenu().getCarried();
        if (!carried.isEmpty()) {
            IIndividual individual = IIndividualHandlerItem.getIndividual(carried);
            if (individual != null) {
                onSelect(mouseButton == 0 ? individual.getSpecies() : individual.getInactiveSpecies());
                return;
            }
        }
        if (mouseButton == 1) {
            onSelect(null);
        } else if (mouseButton == 0) {
            gui.onModuleClick(this);
            gui.playClick();
        }
    }

    @Override
    public Collection<ISpecies<?>> getEntries() {
        return entries;
    }

    @Override
    public void drawEntry(GuiGraphics graphics, ISpecies<?> species, int x, int y) {
        // Forestry's public species API creates exactly the item shown in its
        // native genetic selector, including the appropriate life-stage data.
        ItemStack icon = ICONS.getOrDefault(species, ItemStack.EMPTY);
        if (!icon.isEmpty()) graphics.renderItem(icon, x, y);
    }

    @Override
    public Component getName(ISpecies<?> species) {
        return species.getDisplayName();
    }

    @Override
    public void onSelect(@Nullable ISpecies<?> species) {
        IFilterLogic filter = gui.getLogic();
        if (filter.setGenomeFilter(facing, index, active, species)) {
            filter.sendToServer(facing, index, active, species);
        }
        gui.closeSelection();
        gui.playClick();
    }

    private static Map<ISpecies<?>, ItemStack> createIconStacks() {
        Map<ISpecies<?>, ItemStack> result = new IdentityHashMap<>();
        for (ISpeciesType<?, ?> type : IForestryApi.INSTANCE.getGeneticManager().getSpeciesTypes()) {
            // Exactly the same representative item icons as Forestry's filter.
            GeneticsUtil.getIconStacks(result, type.getDefaultStage(), type);
        }
        return result;
    }

}
