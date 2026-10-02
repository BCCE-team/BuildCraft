package buildcraft.lib.internal.capabilities;

import javax.annotation.Nullable;

import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.BlockCapability;

/**
 * Internal BuildCraft capability provider used by composed objects such as pipe flows,
 * pipe behaviours and block entities.
 *
 * <p>NeoForge 1.21.1 registers block capability providers through
 * {@code RegisterCapabilitiesEvent}; this interface only provides the common lookup
 * contract used by BuildCraft's internal delegation.</p>
 */
public interface IBCCapabilityProvider {
    @Nullable
    <T> T getCapability(BlockCapability<T, Direction> capability, @Nullable Direction side);
}
