package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.machine.BuiltInMachineProperties;
import buildcraft.api.v2.machine.MachineProperty;
import buildcraft.api.v2.machine.MachineType;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/**
 * Internal bridge from BCCE machine implementations to their authoritative API 2
 * archetype definitions. Defaults remain centralized in the public machine definitions.
 */
public final class MachineDefinitionLookup {
    private MachineDefinitionLookup() {}

    public static MachineType type(Identifier id) {
        return BuildCraftApi.registry(BuildCraftRegistries.MACHINE_TYPES).get(Objects.requireNonNull(id, "id"));
    }

    public static <T> T property(Identifier id, MachineProperty<T> property, T fallback) {
        MachineType type = type(id);
        return type == null ? fallback : type.propertyOrDefault(property, fallback);
    }

    public static long maxInputMicroMj(Identifier id, long fallback) {
        return property(id, BuiltInMachineProperties.MAX_MJ_INPUT_PER_TICK, MjAmount.ofMicro(fallback)).microMj();
    }

    public static long capacityMicroMj(Identifier id, long fallback) {
        return property(id, BuiltInMachineProperties.MJ_CAPACITY, MjAmount.ofMicro(fallback)).microMj();
    }

    public static double workSpeedMultiplier(Identifier id) {
        return property(id, BuiltInMachineProperties.WORK_SPEED_MULTIPLIER, 1.0);
    }

    public static double energyCostMultiplier(Identifier id) {
        return property(id, BuiltInMachineProperties.ENERGY_COST_MULTIPLIER, 1.0);
    }

    public static boolean chunkLoading(Identifier id, boolean fallback) {
        return property(id, BuiltInMachineProperties.CHUNK_LOADING, fallback);
    }
}
