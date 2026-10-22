//? source if >=1.21.11
package buildcraft.lib.compat.neoforge121111.common.util;

import net.minecraft.core.HolderLookup;

/**
 * Compatibility bridge for legacy BuildCraft serializers on NeoForge 1.21.11.
 *
 * NeoForge moved new code toward registry-aware ValueIO serializers, but most
 * BCCE containers expose the serializeNBT/deserializeNBT compatibility shape.
 * This bridge preserves the serializeNBT/deserializeNBT contract while adapting
 * it to the registry-aware signatures required by this target.
 */
public interface INBTSerializable<T> {
    T serializeNBT(HolderLookup.Provider registries);

    void deserializeNBT(HolderLookup.Provider registries, T nbt);

    default T serializeNBT() {
        return serializeNBT(null);
    }

    default void deserializeNBT(T nbt) {
        deserializeNBT(null, nbt);
    }
}
