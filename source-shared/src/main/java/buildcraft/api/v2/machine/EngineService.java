package buildcraft.api.v2.machine;

import buildcraft.api.v2.persistence.OpaqueData;
import buildcraft.api.v2.persistence.PersistentType;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Engine registration remains EngineType/EngineProfile; host creation is a block-entity boundary. */
public interface EngineService {
    /**
     * The engine type must be registered before creation. The component persistence ID is the
     * engine type ID; codec migrations and unknown payload handling use the machine runtime.
     */
    default <E extends EngineComponent> EngineHost createHost(EngineType type, EngineHostOwner owner,
        EngineFactory<E> factory, PersistentType<E, OpaqueData> persistence, EngineHeatSettings heat) {
        throw new UnsupportedOperationException("Engine hosts unavailable");
    }
    /** Does not load chunks; includes the original BCCE engines implementing EngineView. */
    Optional<EngineView> engine(Level level, BlockPos pos);
}
