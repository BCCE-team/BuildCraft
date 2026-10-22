/** Internal compatibility view for classic BuildCraft area providers. */
package buildcraft.lib.internal.area;

import buildcraft.api.v2.area.AreaProvider;
import buildcraft.api.v2.area.BlockBox;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/**
 * Classic mutable/world-backed area provider used by the built-in marker runtime.
 * Public addon code should consume {@link AreaProvider} instead.
 */
public interface IAreaProvider extends AreaProvider {
    BlockPos min();
    BlockPos max();

    /** Remove from the world all objects used to define the area. */
    void removeFromWorld(@Nullable Player player);

    default Optional<BlockBox> box() {
        return Optional.of(new BlockBox(min(), max()));
    }
}
