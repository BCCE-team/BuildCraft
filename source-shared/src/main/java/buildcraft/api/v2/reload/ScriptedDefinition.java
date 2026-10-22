package buildcraft.api.v2.reload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** An input from a data pack, an addon or a script; does not depend on Gson or a mod loader. */
public record ScriptedDefinition(ResourceLocation id, Kind kind, Map<String, Object> data,
                                 ResourceLocation target, String reason, DefinitionProvenance provenance) {
    public enum Kind { VALUE, ALIAS, DISABLED }

    public ScriptedDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(provenance, "provenance");
        data = immutableMap(data, 0);
        switch (kind) {
            case VALUE -> {
                if (target != null || reason != null) throw new IllegalArgumentException("Invalid value entry");
            }
            case ALIAS -> {
                Objects.requireNonNull(target, "target");
                if (id.equals(target) || !data.isEmpty() || reason != null) {
                    throw new IllegalArgumentException("Invalid alias entry");
                }
            }
            case DISABLED -> {
                if (reason == null || reason.isBlank() || target != null || !data.isEmpty()) {
                    throw new IllegalArgumentException("Disabled definition requires a reason");
                }
            }
        }
    }

    public static ScriptedDefinition value(ResourceLocation id, Map<String, Object> data,
                                             DefinitionProvenance provenance) {
        return new ScriptedDefinition(id, Kind.VALUE, data, null, null, provenance);
    }

    public static ScriptedDefinition alias(ResourceLocation id, ResourceLocation target,
                                             DefinitionProvenance provenance) {
        return new ScriptedDefinition(id, Kind.ALIAS, Map.of(), target, null, provenance);
    }

    public static ScriptedDefinition disabled(ResourceLocation id, String reason,
                                                DefinitionProvenance provenance) {
        return new ScriptedDefinition(id, Kind.DISABLED, Map.of(), null, reason, provenance);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source, int depth) {
        if (depth > 48) throw new IllegalArgumentException("Definition nesting exceeds 48");
        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "definition key");
            copied.put(key, immutableValue(entry.getValue(), depth + 1));
        }
        return Collections.unmodifiableMap(copied);
    }

    private static Object immutableValue(Object value, int depth) {
        if (depth > 48) throw new IllegalArgumentException("Definition nesting exceeds 48");
        if (value == null) return null; // Valid JSON null in a nested value.
        if (value instanceof String || value instanceof Boolean || value instanceof Byte || value instanceof Short
            || value instanceof Integer || value instanceof Long || value instanceof Float || value instanceof Double
            || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal) return value;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> checked = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Definition map keys must be strings");
                }
                checked.put(key, immutableValue(entry.getValue(), depth + 1));
            }
            return Collections.unmodifiableMap(checked);
        }
        if (value instanceof List<?> list) {
            List<Object> checked = new ArrayList<>(list.size());
            for (Object element : list) checked.add(immutableValue(element, depth + 1));
            return Collections.unmodifiableList(checked);
        }
        throw new IllegalArgumentException("Unsupported definition value: " + value.getClass().getName());
    }
}
