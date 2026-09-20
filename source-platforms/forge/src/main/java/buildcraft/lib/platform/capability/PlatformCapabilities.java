package buildcraft.lib.platform.capability;

import javax.annotation.Nullable;

import buildcraft.lib.internal.tiles.IHasWork;
import buildcraft.lib.internal.tiles.TilesAPI;
import buildcraft.lib.misc.CapUtil;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.util.LazyOptional;

/** Forge-owned access to BuildCraft block capabilities used by loader-neutral gameplay. */
public final class PlatformCapabilities {
    private PlatformCapabilities() {
    }

    @Nullable
    public static IHasWork hasWork(@Nullable BlockEntity tile, @Nullable Direction face) {
        if (tile == null) {
            return null;
        }
        LazyOptional<IHasWork> capability = CapUtil.getCapability(tile, TilesAPI.CAP_HAS_WORK, face);
        return capability.orElse(null);
    }
}
