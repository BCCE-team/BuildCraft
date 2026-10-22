package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.permission.PermissionDecision;
import buildcraft.api.v2.permission.PermissionProvider;
import buildcraft.api.v2.permission.PermissionService;
import buildcraft.api.v2.permission.PermissionServiceRegistry;
import buildcraft.api.v2.permission.PermissionVerdict;
import buildcraft.api.v2.permission.WorldOperationContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/** Internal deterministic implementation of the public permission registry. */
public final class PermissionServiceRegistryImpl implements PermissionServiceRegistry {
    private static final Comparator<PermissionProvider> ORDER = Comparator
        .comparingInt(PermissionProvider::priority)
        .reversed()
        .thenComparing(provider -> provider.id().toString());

    private final Map<Identifier, PermissionProvider> providers = new LinkedHashMap<>();
    private volatile List<PermissionProvider> ordered = List.of();

    public synchronized void register(Identifier id, int priority, PermissionService service) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(service, "service");
        if (providers.containsKey(id)) {
            throw new IllegalStateException("Duplicate permission provider id: " + id);
        }
        providers.put(id, new PermissionProvider(id, priority, service));
        ArrayList<PermissionProvider> snapshot = new ArrayList<>(providers.values());
        snapshot.sort(ORDER);
        ordered = List.copyOf(snapshot);
    }

    public List<PermissionProvider> providers() {
        return ordered;
    }

    public PermissionDecision decide(WorldOperationContext context) {
        Objects.requireNonNull(context, "context");
        PermissionDecision allowed = null;
        for (PermissionProvider provider : ordered) {
            PermissionDecision decision = Objects.requireNonNull(
                provider.service().decide(context),
                "permission decision from " + provider.id()
            );
            if (decision.verdict() == PermissionVerdict.DENY) {
                return decision;
            }
            if (decision.verdict() == PermissionVerdict.ALLOW && allowed == null) {
                allowed = decision;
            }
        }
        return allowed == null ? PermissionDecision.pass() : allowed;
    }
}
