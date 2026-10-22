package buildcraft.api.v2.map;

import buildcraft.api.v2.OperationMode;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;

public interface MapLocationService {
    Optional<MapLocationAdapter> adapter(ItemStack stack);

    default Optional<MapLocationView> read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        try {
            return adapter(stack).flatMap(a -> a.read(stack.copy()));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    default boolean write(ItemStack stack, MapLocationView location, OperationMode mode) {
        if (stack == null || stack.isEmpty() || location == null || mode == null) return false;
        try {
            return adapter(stack).map(a -> a.write(mode == OperationMode.SIMULATE ? stack.copy() : stack, location, mode)).orElse(false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    default boolean clear(ItemStack stack, OperationMode mode) {
        if (stack == null || stack.isEmpty() || mode == null) return false;
        try {
            return adapter(stack).map(a -> a.clear(mode == OperationMode.SIMULATE ? stack.copy() : stack, mode)).orElse(false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
