package buildcraft.lib.internal.area;

import buildcraft.api.v2.area.AreaProvider;
import buildcraft.api.v2.area.Path;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/** Internal world-backed path provider. Public addon code consumes API2 {@link AreaProvider}. */
public interface IPathProvider extends AreaProvider {
    List<BlockPos> getPath();
    void removeFromWorld(@Nullable Player player);

    default Optional<Path> path() {
        List<BlockPos> snapshot = List.copyOf(getPath());
        return Optional.of(() -> snapshot);
    }
}
