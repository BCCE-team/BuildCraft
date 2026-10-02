package buildcraft.compat;

import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.BlockCapability;

/** Verification-only gameplay bridge; not included in the production JAR. */
public final class CompatCapTransfromer {
    public static final CompatCapTransfromer INSTANCE = new CompatCapTransfromer();
    private CompatCapTransfromer() {}
    public <T> Optional<T> getCap(BlockEntity entity, BlockCapability<T, Direction> capability, Direction side) {
        return Optional.empty();
    }
}
