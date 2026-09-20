package buildcraft.robotics.internal.api2;

import buildcraft.api.v2.request.RequestProvider;
import buildcraft.api.v2.request.RequestService;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.IRobotRegistry;
import buildcraft.robotics.internal.legacy.robots.RobotManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Live request discovery over Requester blocks and robot docking stations. */
public final class RequestServiceImpl implements RequestService {
    @Override
    public Optional<RequestProvider> provider(Level level, BlockPos pos, Direction side) {
        if (level == null || pos == null) return Optional.empty();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof RequestProvider provider) return Optional.of(provider);
        if (RobotManager.registryProvider == null) return Optional.empty();
        DockingStation station = RobotManager.registryProvider.getRegistry(level).getStation(pos, side);
        return station == null ? Optional.empty() : Optional.ofNullable(station.getRequestProvider());
    }

    @Override
    public Collection<? extends RequestProvider> providers(Level level) {
        if (level == null || RobotManager.registryProvider == null) return List.of();
        IRobotRegistry registry = RobotManager.registryProvider.getRegistry(level);
        Set<RequestProvider> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        List<RequestProvider> result = new ArrayList<>();
        for (DockingStation station : registry.getStations()) {
            RequestProvider provider = station == null ? null : station.getRequestProvider();
            if (provider != null && seen.add(provider)) result.add(provider);
        }
        return List.copyOf(result);
    }
}
