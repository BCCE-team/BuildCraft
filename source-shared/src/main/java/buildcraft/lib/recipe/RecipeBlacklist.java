package buildcraft.lib.recipe;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-authoritative, deny-wins recipe policy. A blacklist entry in ANY loaded datapack
 * disables the recipe for the named machine; neither a recipe override nor a more highly
 * prioritized pack can re-enable it. Only the datapack reload coordinator can publish it.
 */
public final class RecipeBlacklist {
    private static volatile Snapshot current = new Snapshot(0, Map.of());

    private RecipeBlacklist() {}

    public record Snapshot(long generation, Map<String, Set<ResourceLocation>> denied) {
        public Snapshot {
            Map<String, Set<ResourceLocation>> copy = new LinkedHashMap<>();
            denied.forEach((machine, ids) -> copy.put(machine, Collections.unmodifiableSet(new LinkedHashSet<>(ids))));
            denied = Collections.unmodifiableMap(copy);
        }

        public boolean denies(String machine, ResourceLocation recipe) {
            return denied.getOrDefault(machine, Set.of()).contains(recipe);
        }
    }

    public static Snapshot snapshot() { return current; }

    public static long generation() { return current.generation(); }

    public static boolean isDenied(String machine, ResourceLocation recipe) {
        return recipe != null && current.denies(Objects.requireNonNull(machine, "machine"), recipe);
    }

    public static String machineFor(buildcraft.api.v2.recipe.RecipeDefinition.Kind kind) {
        return switch (kind) {
            case DISTILLATION -> "distiller";
            case HEATING, COOLING -> "heat_exchanger";
            case ASSEMBLY -> "assembly_table";
            case INTEGRATION -> "integration_table";
            case PROGRAMMING -> "programming_table";
        };
    }

    /** On an otherwise failed reload, keep all existing bans AND apply every newly
     * discovered ban. A broken recipe or higher-priority bad JSON must never lift a ban. */
    static synchronized void mergeFailClosed(Map<String, Set<ResourceLocation>> additional) {
        Map<String, Set<ResourceLocation>> union = new LinkedHashMap<>();
        current.denied().forEach((machine, values) -> union.put(machine, new LinkedHashSet<>(values)));
        additional.forEach((machine, values) -> union.computeIfAbsent(machine, ignored -> new LinkedHashSet<>())
            .addAll(values));
        current = new Snapshot(current.generation() + 1, union);
    }

    /** Apply only after the new machine recipe registry was published successfully. */
    static synchronized void publish(Map<String, Set<ResourceLocation>> denied) {
        current = new Snapshot(current.generation() + 1, denied);
    }
}
