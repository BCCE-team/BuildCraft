package buildcraft.lib.internal.api.v2.reload;

import buildcraft.api.v2.reload.DefinitionEntry;
import buildcraft.api.v2.reload.ReloadDiagnostic;

import java.util.List;

@FunctionalInterface
public interface DefinitionValidator<V> {
    List<ReloadDiagnostic> validate(DefinitionEntry<V> definition);
}
