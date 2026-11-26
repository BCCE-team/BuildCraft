package buildcraft.lib.client.config;

import buildcraft.api.v2.config.ConfigDefinition;
import buildcraft.api.v2.config.ConfigDefinition.Section;

import java.util.*;

/** Counts staged edits, independently of navigation, filtering and effective runtime values. */
final class ConfigurationDirtyPaths {
    private record Tab(boolean addon, Section section) {}
    private record Path(Tab tab, List<String> category) {}

    private final Set<String> dirty = new HashSet<>();
    private final Map<Boolean, Integer> views = new HashMap<>();
    private final Map<Tab, Integer> tabs = new HashMap<>();
    private final Map<Path, Integer> direct = new HashMap<>(), subtrees = new HashMap<>();

    ConfigurationDirtyPaths(Collection<ConfigDefinition> definitions, Map<String, String> original,
                            Map<String, String> edited, Map<String, String> invalidDrafts) {
        for (var definition : definitions) {
            String id = definition.id();
            if (!modified(definition, original.get(id), edited.get(id), invalidDrafts.containsKey(id))) continue;
            if (!dirty.add(id)) continue;
            boolean addon = definition.isAddon();
            var tab = new Tab(addon, definition.editorSection());
            var category = definition.category();
            views.merge(addon, 1, Integer::sum);
            tabs.merge(tab, 1, Integer::sum);
            direct.merge(new Path(tab, category), 1, Integer::sum);
            for (int depth = 1; depth <= category.size(); depth++) {
                subtrees.merge(new Path(tab, List.copyOf(category.subList(0, depth))), 1, Integer::sum);
            }
        }
    }

    private static boolean modified(ConfigDefinition definition, String saved, String next, boolean invalidDraft) {
        if (invalidDraft) return true;
        if (Objects.equals(saved, next)) return false;
        // Use the same canonical representation as the save transaction. For example,
        // typing 1.00 instead of 1.0 is not an actual decimal configuration change.
        if (next == null) return true;
        try { return !Objects.equals(saved, definition.validate(next)); }
        catch (IllegalArgumentException e) { return true; }
    }

    boolean contains(String id) { return dirty.contains(id); }
    int view(boolean addon) { return views.getOrDefault(addon, 0); }
    int section(boolean addon, Section section) { return tabs.getOrDefault(new Tab(addon, section), 0); }
    int category(boolean addon, Section section, List<String> path, boolean recursive) {
        return (recursive ? subtrees : direct).getOrDefault(new Path(new Tab(addon, section), path), 0);
    }
}
