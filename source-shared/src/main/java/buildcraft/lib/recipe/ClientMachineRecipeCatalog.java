package buildcraft.lib.recipe;

import buildcraft.api.v2.recipe.RecipeDefinition;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-only *display* snapshot. Never publish remote recipes into MachineRecipeService:
 * the integrated server and its client share the same JVM and service instance.
 * Machines continue to consult only the server-authoritative registry.
 */
public final class ClientMachineRecipeCatalog {
    private static final Logger LOG = LoggerFactory.getLogger("BCCE/RecipeSync");
    private static volatile Snapshot current = Snapshot.empty();
    private static Pending pending;
    private static Runnable listener;

    public record Entry(String machine, ResourceLocation id, RecipeDefinition definition) {}
    public record Snapshot(long generation, boolean authoritative, List<Entry> recipes,
                           Map<String, Set<ResourceLocation>> denied) {
        static Snapshot empty() { return new Snapshot(-1, false, List.of(), Map.of()); }
        public Snapshot {
            recipes = List.copyOf(recipes);
            Map<String, Set<ResourceLocation>> frozen = new LinkedHashMap<>();
            denied.forEach((machine, ids) -> frozen.put(machine, Set.copyOf(ids)));
            denied = Collections.unmodifiableMap(frozen);
        }
        public boolean isDenied(String machine, ResourceLocation id) {
            return denied.getOrDefault(machine, Set.of()).contains(id);
        }
    }
    private static final class Pending {
        final long generation;
        final int count;
        final Map<Integer, buildcraft.lib.net.MessageMachineRecipes> parts = new LinkedHashMap<>();
        int entries;
        int bytes;
        Pending(long generation, int count) { this.generation = generation; this.count = count; }
    }

    private ClientMachineRecipeCatalog() {}
    public static Snapshot snapshot() { return current; }

    /** JEI installs this while its runtime exists. Registering late immediately reconciles it. */
    public static synchronized void setListener(Runnable callback) {
        listener = callback;
        if (callback != null) callback.run();
    }

    public static synchronized void clear() {
        current = Snapshot.empty();
        pending = null;
        if (listener != null) listener.run();
    }

    /** Invoked exclusively on the client main thread by the message handler. */
    public static synchronized void accept(buildcraft.lib.net.MessageMachineRecipes part) {
        if (part.generation() < current.generation()) return;
        if (part.generation() == current.generation() && current.authoritative()) return;
        if (pending != null && part.generation() < pending.generation) return;
        if (pending == null || pending.generation != part.generation() || pending.count != part.partCount()) {
            pending = new Pending(part.generation(), part.partCount());
        }
        if (!pending.parts.containsKey(part.partIndex())) {
            int entries = part.recipes().size() + part.bans().size();
            long bytes = 0;
            for (var recipe : part.recipes()) bytes += recipe.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + recipe.machine().length() + recipe.id().toString().length() + 32L;
            for (var ban : part.bans()) bytes += ban.machine().length() + ban.id().toString().length() + 32L;
            if (pending.entries + entries > buildcraft.lib.net.MessageMachineRecipes.MAX_TOTAL_ENTRIES
                || pending.bytes + bytes > buildcraft.lib.net.MessageMachineRecipes.MAX_TOTAL_BYTES) {
                LOG.warn("Rejecting oversized remote machine recipe index");
                pending = null;
                return;
            }
            pending.entries += entries;
            pending.bytes += (int) bytes;
            pending.parts.put(part.partIndex(), part);
        }
        if (pending.parts.size() != pending.count) return;
        List<Entry> all = new ArrayList<>();
        Map<String, Set<ResourceLocation>> denied = new LinkedHashMap<>();
        try {
            // Publish only a complete, validated set. A malicious or truncated packet
            // cannot partially change the displayed catalog or relax a denial.
            for (int index = 0; index < pending.count; index++) {
                var chunk = pending.parts.get(index);
                if (chunk == null) throw new IllegalArgumentException("Missing chunk " + index);
                for (var encoded : chunk.recipes()) {
                    RecipeDefinition definition = MachineRecipeDatapackReloadListener.decode(
                        encoded.machine(), JsonParser.parseString(encoded.json()).getAsJsonObject());
                    all.add(new Entry(encoded.machine(), encoded.id(), definition));
                    if (all.size() > buildcraft.lib.net.MessageMachineRecipes.MAX_TOTAL_ENTRIES) {
                        throw new IllegalArgumentException("Recipe display limit exceeded");
                    }
                }
                for (var ban : chunk.bans()) {
                    denied.computeIfAbsent(ban.machine(), ignored -> new LinkedHashSet<>()).add(ban.id());
                }
            }
            current = new Snapshot(part.generation(), true, all, denied);
            LOG.info("Received {} authoritative machine recipe displays and {} deny rules",
                all.size(), denied.values().stream().mapToInt(Set::size).sum());
            if (listener != null) listener.run();
        } catch (RuntimeException invalid) {
            LOG.warn("Rejected invalid recipe display snapshot: {}", invalid.toString());
        } finally {
            pending = null;
        }
    }

    /** Authoritative remote definitions if available; otherwise local single-player/bundled fallback. */
    public static <T extends RecipeDefinition> List<T> visibleRecipes(Class<T> type) {
        Snapshot snapshot = current;
        if (snapshot.authoritative()) {
            List<T> output = new ArrayList<>();
            for (Entry entry : snapshot.recipes()) {
                if (type.isInstance(entry.definition()) && !snapshot.isDenied(entry.machine(), entry.id())) {
                    output.add(type.cast(entry.definition()));
                }
            }
            return List.copyOf(output);
        }
        return buildcraft.api.v2.BuildCraftApi.service(buildcraft.api.v2.BuildCraftServices.MACHINE_RECIPES)
            .recipes(type).stream().map(match -> match.recipe()).toList();
    }

    /** Exact server-authoritative display definition, with deny-wins filtering. */
    public static RecipeDefinition visibleDefinition(String machine, ResourceLocation id) {
        Snapshot snapshot = current;
        if (id == null || snapshot.isDenied(machine, id)) return null;
        if (snapshot.authoritative()) {
            for (Entry entry : snapshot.recipes()) {
                if (entry.machine().equals(machine) && entry.id().equals(id)) return entry.definition();
            }
            return null;
        }
        return buildcraft.api.v2.BuildCraftApi.service(buildcraft.api.v2.BuildCraftServices.MACHINE_RECIPES)
            .snapshot().resolved(id).map(result -> result.value()).orElse(null);
    }

    public static boolean contains(String machine, ResourceLocation id) {
        return visibleDefinition(machine, id) != null;
    }

    public static boolean isDenied(String machine, ResourceLocation id) {
        Snapshot snapshot = current;
        return snapshot.authoritative() ? snapshot.isDenied(machine, id) : RecipeBlacklist.isDenied(machine, id);
    }
}
