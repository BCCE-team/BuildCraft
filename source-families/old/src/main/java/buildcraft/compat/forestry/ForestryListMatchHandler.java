package buildcraft.compat.forestry;

import javax.annotation.Nonnull;

import buildcraft.api.v2.list.ListMatchType;
import buildcraft.lib.list.ListMatchHandlerBackend;
import forestry.api.genetics.IIndividual;
import forestry.api.genetics.capability.IIndividualHandlerItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Modern equivalent of BuildCraftCompat's ListMatchGenome.
 * MATERIAL compares species, TYPE compares species family and life stage,
 * and CLASS requires both.
 */
final class ForestryListMatchHandler extends ListMatchHandlerBackend {
    @Override
    public boolean matches(ListMatchType type, @Nonnull ItemStack compare, @Nonnull ItemStack target, boolean precise) {
        IIndividualHandlerItem compareHandler = getHandler(compare);
        IIndividualHandlerItem targetHandler = getHandler(target);
        if (compareHandler == null || targetHandler == null) {
            return false;
        }

        IIndividual compareIndividual = compareHandler.getIndividual();
        IIndividual targetIndividual = targetHandler.getIndividual();
        return switch (type) {
            case MATERIAL -> matchesMaterial(compareIndividual, targetIndividual, precise);
            case TYPE -> matchesType(compareHandler, targetHandler);
            case CLASS -> matchesMaterial(compareIndividual, targetIndividual, precise)
                    && matchesType(compareHandler, targetHandler);
        };
    }

    private static boolean matchesMaterial(IIndividual compare, IIndividual target, boolean precise) {
        if (!speciesId(compare).equals(speciesId(target))) {
            return false;
        }
        return !precise || inactiveSpeciesId(compare).equals(inactiveSpeciesId(target));
    }

    private static boolean matchesType(IIndividualHandlerItem compare, IIndividualHandlerItem target) {
        return compare.getSpeciesType().id().equals(target.getSpeciesType().id())
                && compare.getStage().getSerializedName().equals(target.getStage().getSerializedName());
    }

    private static ResourceLocation speciesId(IIndividual individual) {
        return individual.getSpecies().id();
    }

    private static ResourceLocation inactiveSpeciesId(IIndividual individual) {
        return individual.getInactiveSpecies().id();
    }

    @Override
    public boolean isValidSource(ListMatchType type, @Nonnull ItemStack stack) {
        return getHandler(stack) != null;
    }

    private static IIndividualHandlerItem getHandler(ItemStack stack) {
        try {
            return IIndividualHandlerItem.get(stack);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }
}
