package buildcraft.api.v2.pipe;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable, bounded, opaque addon routing data attached to a travelling item. */
public final class ItemTransitPayload {
    public static final ItemTransitPayload EMPTY = new ItemTransitPayload(Map.of());
    public static final int MAX_ENTRY_BYTES = 4096;
    public static final int MAX_TOTAL_BYTES = 16384;
    private final Map<String, byte[]> entries;

    public ItemTransitPayload(Map<String, byte[]> data) {
        Objects.requireNonNull(data, "data");
        Map<String, byte[]> copy = new LinkedHashMap<>();
        int total = 0;
        for (var entry : data.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "key");
            validateKey(key);
            byte[] bytes = Objects.requireNonNull(entry.getValue(), "payload").clone();
            if (bytes.length > MAX_ENTRY_BYTES) throw new IllegalArgumentException("Oversized transit payload: " + key);
            total += bytes.length + key.length();
            if (total > MAX_TOTAL_BYTES) throw new IllegalArgumentException("Oversized aggregate transit payload");
            copy.put(key, bytes);
        }
        entries = Collections.unmodifiableMap(copy);
    }

    private static void validateKey(String key) {
        if (key.length() > 128 || !key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid namespaced transit payload key: " + key);
        }
    }

    public Optional<byte[]> get(String key) {
        byte[] value = entries.get(key);
        return value == null ? Optional.empty() : Optional.of(value.clone());
    }

    public Map<String, byte[]> entries() {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        entries.forEach((key, value) -> copy.put(key, value.clone()));
        return Collections.unmodifiableMap(copy);
    }

    public ItemTransitPayload with(String key, byte[] value) {
        validateKey(key);
        Map<String, byte[]> updated = new LinkedHashMap<>(entries());
        updated.put(key, Objects.requireNonNull(value, "value").clone());
        return new ItemTransitPayload(updated);
    }

    public ItemTransitPayload without(String key) {
        if (!entries.containsKey(key)) return this;
        Map<String, byte[]> updated = new LinkedHashMap<>(entries());
        updated.remove(key);
        return new ItemTransitPayload(updated);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ItemTransitPayload payload) || entries.size() != payload.entries.size()) return false;
        for (var entry : entries.entrySet()) {
            if (!Arrays.equals(entry.getValue(), payload.entries.get(entry.getKey()))) return false;
        }
        return true;
    }

    @Override public int hashCode() {
        int result = 1;
        for (var entry : entries.entrySet()) result += entry.getKey().hashCode() ^ Arrays.hashCode(entry.getValue());
        return result;
    }
}
