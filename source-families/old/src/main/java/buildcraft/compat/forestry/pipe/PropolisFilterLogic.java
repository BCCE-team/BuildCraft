package buildcraft.compat.forestry.pipe;

import javax.annotation.Nullable;

import forestry.api.core.ILocationProvider;
import forestry.api.genetics.ISpecies;
import forestry.api.genetics.filter.IFilterLogic;
import forestry.api.genetics.filter.IFilterRuleType;
import forestry.sorting.FilterLogic;
import net.minecraft.core.Direction;

/** Forestry's filter implementation with packets routed to a pipe instead of a Forestry tile. */
public final class PropolisFilterLogic extends FilterLogic {
    private final ILocationProvider location;

    public PropolisFilterLogic(ILocationProvider location, IFilterLogic.INetworkHandler networkHandler) {
        super(location, networkHandler);
        this.location = location;
    }

    @Override
    public void sendToServer(Direction facing, int index, boolean active, @Nullable ISpecies<?> species) {
        ForestryPropolisNetwork.sendGenomeChange(location.getCoordinates(), facing, index, active,
            species == null ? null : species.id());
    }

    @Override
    public void sendToServer(Direction facing, IFilterRuleType rule) {
        ForestryPropolisNetwork.sendRuleChange(location.getCoordinates(), facing, rule.getId());
    }
}
