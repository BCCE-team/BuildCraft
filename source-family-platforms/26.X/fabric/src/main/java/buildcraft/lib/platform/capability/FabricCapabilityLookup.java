package buildcraft.lib.platform.capability;

import buildcraft.lib.internal.inventory.IItemTransactor;
import buildcraft.transport.internal.IInjectable;
import buildcraft.lib.misc.CapUtil;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/** BuildCraft-only capability queries without depending on NeoForge world extensions. */
public final class FabricCapabilityLookup {
    private FabricCapabilityLookup() {}

    @Nullable
    public static IInjectable injectable(Level level, BlockPos pos, Direction side) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) return null;
        var tile = level.getBlockEntity(pos);
        if (tile instanceof IInjectable injectable) return injectable;
        // Legacy pipes may be composition based. The pipe module supplies
        // a full Fabric BlockApiLookup during its own port.
        return null;
    }
}
