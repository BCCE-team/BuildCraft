package buildcraft.silicon.api2;

import buildcraft.api.v2.gate.GateService;
import buildcraft.api.v2.gate.GateView;
import buildcraft.silicon.plug.PluggableGate;
import buildcraft.transport.tile.TilePipeHolder;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Runtime GateService backed by the real BCCE gate pluggable. */
public final class GateServiceImpl implements GateService {
    public static final GateServiceImpl INSTANCE = new GateServiceImpl();
    private GateServiceImpl() {}

    @Override
    public Optional<GateView> gate(Level level, BlockPos pos, Direction side) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(side, "side");
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof TilePipeHolder holder)) return Optional.empty();
        if (!(holder.getPluggable(side) instanceof PluggableGate gate)) return Optional.empty();
        return Optional.of(gate.logic);
    }
}
