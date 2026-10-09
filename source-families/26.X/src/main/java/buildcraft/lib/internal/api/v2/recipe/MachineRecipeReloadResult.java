package buildcraft.lib.internal.api.v2.recipe;

import buildcraft.api.v2.reload.ReloadDiagnostic;
import buildcraft.api.v2.reload.ReloadGeneration;
import java.util.List;

public record MachineRecipeReloadResult(
    boolean published, ReloadGeneration generation, List<ReloadDiagnostic> diagnostics
) {
    public MachineRecipeReloadResult { diagnostics = List.copyOf(diagnostics); }
}
