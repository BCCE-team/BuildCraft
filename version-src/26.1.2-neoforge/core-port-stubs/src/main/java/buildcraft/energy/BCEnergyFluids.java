package buildcraft.energy;

import java.util.List;

import buildcraft.lib.platform.registry.BCRegistryEntry;
import net.minecraft.world.level.block.LiquidBlock;

/** Compilation boundary until the energy module is migrated. */
public final class BCEnergyFluids {
    public static final List<BCRegistryEntry<LiquidBlock>> OIL_BLOCK = List.of();

    private BCEnergyFluids() {
    }
}
