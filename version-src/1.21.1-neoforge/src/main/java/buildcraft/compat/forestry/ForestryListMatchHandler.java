package buildcraft.compat.forestry;

import buildcraft.api.v2.list.ListMatchType;
import buildcraft.lib.list.ListMatchHandlerBackend;
import forestry.api.core.genetics.IIndividual;
import forestry.api.core.genetics.capability.IIndividualHandlerItem;
import net.minecraft.world.item.ItemStack;
import javax.annotation.Nonnull;

/** Matches Forestry individuals by species, species type, and life stage. */
final class ForestryListMatchHandler extends ListMatchHandlerBackend {
    @Override
    public boolean matches(ListMatchType type, @Nonnull ItemStack compare,
                           @Nonnull ItemStack target, boolean precise) {
        try {
            IIndividual a = IIndividualHandlerItem.getIndividual(compare);
            IIndividual b = IIndividualHandlerItem.getIndividual(target);
            if (a == null || b == null) return false;
            boolean material = a.getSpecies() != null && b.getSpecies() != null
                && a.getSpecies().id().equals(b.getSpecies().id())
                && (!precise || (a.getInactiveSpecies() != null && b.getInactiveSpecies() != null
                    && a.getInactiveSpecies().id().equals(b.getInactiveSpecies().id())));
            var aType = IIndividualHandlerItem.getSpeciesType(compare);
            var bType = IIndividualHandlerItem.getSpeciesType(target);
            var aStage = IIndividualHandlerItem.getLifeStage(compare);
            var bStage = IIndividualHandlerItem.getLifeStage(target);
            boolean kind = aType != null && bType != null && aStage != null && bStage != null
                && aType.id().equals(bType.id())
                && aStage.getSerializedName().equals(bStage.getSerializedName());
            return switch (type) {
                case MATERIAL -> material;
                case TYPE -> kind;
                case CLASS -> material && kind;
            };
        } catch (RuntimeException | LinkageError invalidGenome) {
            return false;
        }
    }

    @Override
    public boolean isValidSource(ListMatchType type, @Nonnull ItemStack stack) {
        try {
            return IIndividualHandlerItem.getIndividual(stack) != null
                && IIndividualHandlerItem.getSpeciesType(stack) != null;
        } catch (RuntimeException | LinkageError invalidGenome) {
            return false;
        }
    }
}
