package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.fuels.EnergyFluidDefinition;
import buildcraft.lib.internal.api.v2.fuels.EnergyFluidRegistration;
import buildcraft.lib.internal.api.v2.fuels.EnergyFluidReloadResult;
import buildcraft.api.v2.fuels.EnergyFluidService;
import buildcraft.api.v2.reload.DefinitionProvenance;
import buildcraft.api.v2.reload.DefinitionSnapshot;
import buildcraft.lib.internal.api.v2.reload.ReloadTransaction;
import buildcraft.lib.internal.api.v2.reload.ReloadableDefinitionRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/** Runtime implementation behind the public EnergyFluidService. */
public final class EnergyFluidRegistryImpl implements EnergyFluidService {
    private final ReloadableDefinitionRegistry<EnergyFluidDefinition> published = new ReloadableDefinitionRegistry<>();
    private final Map<Identifier, EnergyFluidRegistration> codeDefinitions = new LinkedHashMap<>();
    private List<EnergyFluidRegistration> dataDefinitions = List.of();
    private record Visible(DefinitionSnapshot<EnergyFluidDefinition> source,
        buildcraft.energy.OilIndustry.Mode mode, DefinitionSnapshot<EnergyFluidDefinition> result) {}
    private volatile Visible cachedVisible;

    public DefinitionSnapshot<EnergyFluidDefinition> snapshot() {
        var all = published.current();
        var mode = buildcraft.energy.OilIndustry.mode();
        Visible cached = cachedVisible;
        if (cached != null && cached.source() == all && cached.mode() == mode) return cached.result();
        var visible = new LinkedHashMap<>(all.asMap());
        visible.entrySet().removeIf(entry -> !buildcraft.energy.OilIndustry.fuelEnabled(mode,
            entry.getKey().getNamespace(), entry.getKey().getPath()));
        var result = DefinitionSnapshot.of(all.generation(), visible);
        cachedVisible = new Visible(all, mode, result);
        return result;
    }

    public synchronized void register(
        Identifier id,
        EnergyFluidDefinition definition,
        DefinitionProvenance provenance
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(provenance, "provenance");
        if (codeDefinitions.containsKey(id)) {
            throw new IllegalStateException("Duplicate code-owned energy fluid definition id: " + id);
        }
        EnergyFluidRegistration registration = new EnergyFluidRegistration(id, definition, provenance);
        codeDefinitions.put(id, registration);
        EnergyFluidReloadResult result = rebuild(dataDefinitions);
        if (!result.published()) {
            codeDefinitions.remove(id);
            throw new IllegalStateException(
                "Energy fluid definition " + id + " could not be published: " + result.diagnostics()
            );
        }
    }

    public synchronized EnergyFluidReloadResult reloadData(Collection<EnergyFluidRegistration> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        List<EnergyFluidRegistration> candidate = new ArrayList<>(definitions.size());
        for (EnergyFluidRegistration registration : definitions) {
            candidate.add(Objects.requireNonNull(registration, "registration"));
        }
        EnergyFluidReloadResult result = rebuild(candidate);
        if (result.published()) {
            dataDefinitions = List.copyOf(candidate);
        }
        return result;
    }

    private EnergyFluidReloadResult rebuild(Collection<EnergyFluidRegistration> data) {
        ReloadTransaction<EnergyFluidDefinition> transaction = published.beginReload();
        for (EnergyFluidRegistration registration : codeDefinitions.values()) {
            transaction.add(registration.id(), registration.definition(), registration.provenance());
        }
        for (EnergyFluidRegistration registration : data) {
            transaction.add(registration.id(), registration.definition(), registration.provenance());
        }
        boolean resolved = transaction.resolve();
        boolean publishedOk = resolved && transaction.publish();
        return new EnergyFluidReloadResult(publishedOk, transaction.generation(), transaction.diagnostics());
    }
}
