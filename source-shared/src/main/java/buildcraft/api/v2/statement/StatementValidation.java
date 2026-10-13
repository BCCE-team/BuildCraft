package buildcraft.api.v2.statement;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Validates the declared parameter slots and types before running addon code. */
public final class StatementValidation {
    private StatementValidation() {}

    public static boolean valid(ParameterSchema schema, StatementParameters parameters) {
        if (schema == null || parameters == null) return false;
        Map<ResourceLocation, ParameterValue<?>> values = parameters.values();
        Set<ResourceLocation> declared = new HashSet<>();
        for (ParameterSpec spec : schema.parameters()) {
            if (!declared.add(spec.slotId())) return false;
            ParameterValue<?> value = values.get(spec.slotId());
            if (value == null) {
                if (spec.required()) return false;
            } else if (!spec.typeId().equals(value.type().id())) {
                return false;
            }
        }
        return declared.containsAll(values.keySet());
    }
}
