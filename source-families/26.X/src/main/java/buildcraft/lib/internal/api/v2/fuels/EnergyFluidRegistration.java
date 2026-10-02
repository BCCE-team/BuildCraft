package buildcraft.lib.internal.api.v2.fuels;

import buildcraft.api.v2.fuels.EnergyFluidDefinition;

import buildcraft.api.v2.reload.DefinitionProvenance;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/** One code- or data-owned fuel/coolant definition. */
public record EnergyFluidRegistration(
    Identifier id,
    EnergyFluidDefinition definition,
    DefinitionProvenance provenance
) {
    public EnergyFluidRegistration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(provenance, "provenance");
    }
}
