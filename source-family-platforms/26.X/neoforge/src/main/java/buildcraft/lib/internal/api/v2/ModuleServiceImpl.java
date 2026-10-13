package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.module.ModuleInfo;
import buildcraft.api.v2.module.ModuleService;
import buildcraft.lib.internal.core.BuildCraftAPI;
import buildcraft.lib.internal.module.BCModules;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import buildcraft.api.v2.registry.RegistrationContext;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/** API2 read-only view over the physical BuildCraft module containers. */
final class ModuleServiceImpl implements ModuleService {
    private final Map<Identifier, External> external = new LinkedHashMap<>();
    private boolean frozen;

    public synchronized Collection<ModuleInfo> modules() {
        List<ModuleInfo> out = new ArrayList<>(BCModules.VALUES.length + external.size());
        for (BCModules module : BCModules.VALUES) out.add(info(module));
        for (External entry : external.values()) out.add(entry.info());
        return List.copyOf(out);
    }

    public synchronized Optional<ModuleInfo> module(Identifier id) {
        Objects.requireNonNull(id, "id");
        External candidate = external.get(id);
        if (candidate != null) return Optional.of(candidate.info());
        if (!"buildcraft".equals(id.getNamespace())) return Optional.empty();
        for (BCModules module : BCModules.VALUES) {
            if (module.lowerCaseName.equals(id.getPath())) {
                return Optional.of(info(module));
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized void registerExternal(ModuleInfo info, Collection<Identifier> required,
                                              RegistrationContext context) {
        if (frozen) throw new IllegalStateException("Module registration is frozen");
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(required, "required");
        String owner = Objects.requireNonNull(Objects.requireNonNull(context, "context").owner(), "owner");
        if (owner.isBlank() || !owner.equals(info.id().getNamespace())) {
            throw new IllegalArgumentException("External module owner does not match id: " + info.id());
        }
        if (!info.loaded()) throw new IllegalArgumentException("Only loaded modules may be registered");
        if (info.version().isBlank()) throw new IllegalArgumentException("Module version must not be blank");
        if (module(info.id()).isPresent()) throw new IllegalStateException("Duplicate module " + info.id());
        Set<Identifier> deps = new LinkedHashSet<>();
        for (Identifier dependency : required) deps.add(Objects.requireNonNull(dependency, "dependency"));
        if (deps.contains(info.id())) throw new IllegalArgumentException("Module cannot depend on itself");
        external.put(info.id(), new External(info, Set.copyOf(deps)));
    }

    public synchronized void validateFreeze() {
        Set<Identifier> visited = new LinkedHashSet<>();
        for (Identifier id : external.keySet()) validateDependencies(id, visited, new LinkedHashSet<>());
    }

    private void validateDependencies(Identifier id, Set<Identifier> visited, Set<Identifier> stack) {
        if (visited.contains(id)) return;
        if (!stack.add(id)) throw new IllegalStateException("Cyclic BuildCraft module dependency: " + id);
        External entry = external.get(id);
        if (entry != null) {
            for (Identifier dependency : entry.required()) {
                if (!module(dependency).map(ModuleInfo::loaded).orElse(false)) {
                    throw new IllegalStateException("Missing required module " + dependency + " for " + id);
                }
                if (external.containsKey(dependency)) validateDependencies(dependency, visited, stack);
            }
        }
        stack.remove(id);
        visited.add(id);
    }

    public synchronized void freeze() {
        if (frozen) return;
        validateFreeze();
        frozen = true;
    }

    private record External(ModuleInfo info, Set<Identifier> required) { }

    private static ModuleInfo info(BCModules module) {
        Identifier id = Identifier.tryParse("buildcraft:" + module.lowerCaseName);
        if (id == null) throw new IllegalStateException("Invalid BuildCraft module id: " + module.lowerCaseName);
        boolean loaded = module.isLoaded();
        return new ModuleInfo(id, loaded ? BuildCraftAPI.getVersion() : "missing", loaded);
    }
}
