package buildcraft.api.v2.machine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable composition definition for a BuildCraft-style machine.
 *
 * <p>Machine variants are created by copying a registered base definition, overriding only
 * the desired properties/components, and registering the resulting definition under a new id.
 * This intentionally avoids subclassing BuildCraft block entities.
 */
public final class MachineType {
    private final ResourceLocation id;
    private final Set<ResourceLocation> components;
    private final Map<MachineProperty<?>, Object> properties;

    /** Compatibility constructor for simple component-only definitions. */
    public MachineType(ResourceLocation id, Set<ResourceLocation> components) {
        this(id, components, Map.of());
    }

    private MachineType(
        ResourceLocation id,
        Set<ResourceLocation> components,
        Map<MachineProperty<?>, Object> properties
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.components = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(components, "components")));
        this.properties = copyProperties(Objects.requireNonNull(properties, "properties"));
    }

    private static Map<MachineProperty<?>, Object> copyProperties(Map<MachineProperty<?>, Object> source) {
        Map<MachineProperty<?>, Object> copy = new LinkedHashMap<>();
        source.forEach((property, value) -> copy.put(property, copyValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    /** Copy standard mutable containers both when building a type and when exposing its properties. */
    private static Object copyValue(Object value) {
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object entry : list) copy.add(copyValue(entry));
            return copy;
        }
        if (value instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>();
            for (Object entry : set) copy.add(copyValue(entry));
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> copy.put(copyValue(key), copyValue(entry)));
            return copy;
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            Object copy = java.lang.reflect.Array.newInstance(value.getClass().getComponentType(), length);
            for (int i = 0; i < length; i++) {
                java.lang.reflect.Array.set(copy, i, copyValue(java.lang.reflect.Array.get(value, i)));
            }
            return copy;
        }
        return value;
    }

    public static Builder builder(ResourceLocation id) {
        return new Builder(id);
    }

    public static Builder variant(ResourceLocation id, MachineType base) {
        return new Builder(id).copyFrom(base);
    }

    public Builder copyAs(ResourceLocation newId) {
        return variant(newId, this);
    }

    public ResourceLocation id() {
        return id;
    }

    public Set<ResourceLocation> components() {
        return components;
    }

    public Map<MachineProperty<?>, Object> properties() {
        // A read-only outer Map does not protect nested collection-valued settings.
        return copyProperties(properties);
    }

    public <T> Optional<T> property(MachineProperty<T> property) {
        Objects.requireNonNull(property, "property");
        Object value = properties.get(property);
        return value == null ? Optional.empty() : Optional.of(property.cast(copyValue(value)));
    }

    public <T> T propertyOrDefault(MachineProperty<T> property, T fallback) {
        return property(property).orElse(fallback);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MachineType that
            && id.equals(that.id)
            && components.equals(that.components)
            && properties.equals(that.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, components, properties);
    }

    @Override
    public String toString() {
        return "MachineType[" + id + ", components=" + components + ", properties=" + properties + "]";
    }

    public static final class Builder {
        private final ResourceLocation id;
        private final Set<ResourceLocation> components = new LinkedHashSet<>();
        private final Map<MachineProperty<?>, Object> properties = new LinkedHashMap<>();

        private Builder(ResourceLocation id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        public Builder copyFrom(MachineType base) {
            Objects.requireNonNull(base, "base");
            components.clear();
            components.addAll(base.components);
            properties.clear();
            properties.putAll(base.properties);
            return this;
        }

        public Builder component(ResourceLocation componentId) {
            components.add(Objects.requireNonNull(componentId, "componentId"));
            return this;
        }

        public Builder component(MachineComponentType<?> componentType) {
            return component(Objects.requireNonNull(componentType, "componentType").id());
        }

        public Builder removeComponent(ResourceLocation componentId) {
            components.remove(Objects.requireNonNull(componentId, "componentId"));
            return this;
        }

        public Builder clearComponents() {
            components.clear();
            return this;
        }

        public <T> Builder property(MachineProperty<T> property, T value) {
            MachineProperty<T> checkedProperty = Objects.requireNonNull(property, "property");
            properties.put(checkedProperty, checkedProperty.validate(value));
            return this;
        }

        public Builder removeProperty(MachineProperty<?> property) {
            properties.remove(Objects.requireNonNull(property, "property"));
            return this;
        }

        public MachineType build() {
            return new MachineType(id, components, properties);
        }
    }
}
