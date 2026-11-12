package buildcraft.compat.forestry.pipe;

import buildcraft.lib.net.MessageManager;
import forestry.api.core.ILocationProvider;
import forestry.api.core.genetics.ISpecies;
import forestry.api.core.genetics.filter.IFilterLogic;
import forestry.api.core.genetics.filter.IFilterRuleType;
import forestry.core.content.sorting.FilterLogic;
import net.minecraft.core.Direction;
import javax.annotation.Nullable;

/** Forestry filter rules with authenticated BuildCraft menu network updates. */
public final class PropolisFilterLogic extends FilterLogic {
    private final ILocationProvider location;

    public PropolisFilterLogic(ILocationProvider location, IFilterLogic.INetworkHandler handler) {
        super(location, handler);
        this.location = location;
    }

    @Override
    public void sendToServer(Direction face, int slot, boolean active, @Nullable ISpecies<?> species) {
        MessageManager.sendToServer(new MessagePropolisGenomeChange(
            location.getBlockPos(), face, slot, active, species == null ? null : species.id()));
    }

    @Override
    public void sendToServer(Direction face, IFilterRuleType rule) {
        MessageManager.sendToServer(new MessagePropolisRuleChange(
            location.getBlockPos(), face, rule.getId()));
    }
}
