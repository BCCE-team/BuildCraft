package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.template.TemplateHandler;
import buildcraft.api.v2.template.TemplateRegistration;
import buildcraft.api.v2.template.TemplateService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;

public final class TemplateServiceImpl implements TemplateService {
    private final Map<Identifier, TemplateRegistration> registrations = new LinkedHashMap<>();
    private volatile List<TemplateRegistration> snapshot = List.of();

    public synchronized void register(Identifier id, int priority, TemplateHandler handler) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handler, "handler");
        if (registrations.containsKey(id)) throw new IllegalStateException("Duplicate template handler id: " + id);
        registrations.put(id, new TemplateRegistration(id, priority, handler));
        rebuild();
    }

    public List<TemplateRegistration> handlers() { return snapshot; }

    private void rebuild() {
        List<TemplateRegistration> ordered = new ArrayList<>(registrations.values());
        ordered.sort(Comparator.comparingInt(TemplateRegistration::priority).reversed().thenComparing(e -> e.id().toString()));
        snapshot = List.copyOf(ordered);
    }
}
