package buildcraft.lib.internal.transfer;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/** Loader-owned native lookup reduced to BuildCraft's internal transfer endpoints. */
public interface PlatformTransferLookup {
    Optional<ItemTransferAccess> items(Level level, BlockPos pos, Direction side);
    Optional<FluidTransferAccess> fluids(Level level, BlockPos pos, Direction side);
    Optional<EnergyTransferAccess> energy(Level level, BlockPos pos, Direction side);
}
