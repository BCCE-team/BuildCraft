package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.machine.EngineComponent;
import buildcraft.api.v2.machine.EngineFactory;
import buildcraft.api.v2.machine.EngineHeatSettings;
import buildcraft.api.v2.machine.EngineHost;
import buildcraft.api.v2.machine.EngineHostOwner;
import buildcraft.api.v2.machine.EngineHostProvider;
import buildcraft.api.v2.machine.EngineService;
import buildcraft.api.v2.machine.EngineType;
import buildcraft.api.v2.machine.EngineView;
import buildcraft.api.v2.persistence.OpaqueData;
import buildcraft.api.v2.persistence.PersistentType;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

final class EngineServiceImpl implements EngineService {
    @Override public <E extends EngineComponent> EngineHost createHost(EngineType type, EngineHostOwner owner,
        EngineFactory<E> factory, PersistentType<E, OpaqueData> persistence, EngineHeatSettings heat) {
        return new EngineRuntimeHost(type, owner, factory, persistence, heat);
    }
    @Override public Optional<EngineView> engine(Level level, BlockPos pos) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) return Optional.empty();
        BlockEntity block = level.getBlockEntity(pos);
        if (block instanceof EngineHostProvider host) return Optional.ofNullable(host.engineHost());
        return block instanceof EngineView view ? Optional.of(view) : Optional.empty();
    }
}
