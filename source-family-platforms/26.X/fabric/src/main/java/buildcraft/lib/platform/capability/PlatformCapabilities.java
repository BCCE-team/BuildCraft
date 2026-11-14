package buildcraft.lib.platform.capability;

import javax.annotation.Nullable;
import buildcraft.api.v2.machine.MachineHostProvider;
import buildcraft.lib.internal.tiles.IHasWork;
import buildcraft.lib.internal.tiles.TilesAPI;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Loader-owned BuildCraft hasWork query. Do not force-load a chunk or
 * execute a machine tick to discover its status.
 */
public final class PlatformCapabilities {
    private PlatformCapabilities() {}

    @Nullable
    public static IHasWork hasWork(@Nullable BlockEntity tile, @Nullable Direction face) {
        if (tile == null || tile.getLevel() == null || tile.isRemoved()) return null;
        var level = tile.getLevel();
        if (!level.hasChunkAt(tile.getBlockPos())) return null;

        IHasWork nativeWork = TilesAPI.fabricLookup(TilesAPI.CAP_HAS_WORK).find(
            level, tile.getBlockPos(), tile.getBlockState(), tile, face
        );
        if (nativeWork != null) return nativeWork;
        // A tile not yet registered with Fabric can still publish its internal BCCE token.
        IHasWork owned = buildcraft.lib.misc.CapUtil.getCapability(
            level, tile.getBlockPos(), TilesAPI.CAP_HAS_WORK, face);
        if (owned != null) return owned;
        if (tile instanceof MachineHostProvider provider) {
            return () -> {
                try {
                    return provider.machineHost() != null && provider.machineHost().hasWork();
                } catch (RuntimeException ignored) {
                    return false;
                }
            };
        }
        return null;
    }
}
