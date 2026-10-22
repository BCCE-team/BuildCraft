package buildcraft.api.v2.reload;

import java.util.List;
import java.util.Objects;

/** A failed reload never changes the previously published snapshot. */
public record ReloadPublishResult(boolean published, ReloadGeneration generation, List<ReloadDiagnostic> diagnostics) {
    public ReloadPublishResult {
        Objects.requireNonNull(generation, "generation");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }
}
