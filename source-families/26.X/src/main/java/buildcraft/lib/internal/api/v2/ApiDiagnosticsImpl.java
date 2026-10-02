package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.diagnostics.ApiDiagnostic;
import buildcraft.api.v2.diagnostics.ApiDiagnostics;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Thread-safe runtime diagnostics sink exposed through the API2 diagnostics service. */
final class ApiDiagnosticsImpl implements ApiDiagnostics {
    private final List<ApiDiagnostic> entries = new ArrayList<>();

    public synchronized List<ApiDiagnostic> entries() {
        return List.copyOf(entries);
    }

    public synchronized void report(ApiDiagnostic diagnostic) {
        entries.add(Objects.requireNonNull(diagnostic, "diagnostic"));
    }
}
