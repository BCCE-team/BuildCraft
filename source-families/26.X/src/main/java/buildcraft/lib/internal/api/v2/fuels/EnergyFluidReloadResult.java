package buildcraft.lib.internal.api.v2.fuels;

import buildcraft.api.v2.reload.ReloadDiagnostic;
import buildcraft.api.v2.reload.ReloadGeneration;
import java.util.List;
import java.util.Objects;

public record EnergyFluidReloadResult(boolean published, ReloadGeneration generation, List<ReloadDiagnostic> diagnostics) {
    public EnergyFluidReloadResult {
        Objects.requireNonNull(generation, "generation");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }
}
