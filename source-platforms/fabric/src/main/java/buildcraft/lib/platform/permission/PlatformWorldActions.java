package buildcraft.lib.platform.permission;
// Loader boundary owner: net.fabricmc (implementation may delegate to vanilla/common contracts).

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Fabric world-action boundary. Ownership remains execution identity, never a BuildCraft-only ACL. */
public final class PlatformWorldActions {
    private PlatformWorldActions() { }

    public static boolean canBreakBlock(ServerLevel world, BlockPos pos, Player actor) {
        if (actor == null || world.getBlockState(pos).isAir()) return false;
        return actor.mayBuild() && actor.mayUseItemAt(pos, Direction.UP, ItemStack.EMPTY)
            && world.getWorldBorder().isWithinBounds(pos);
    }

    public static boolean placeBlock(Level level, BlockPos pos, BlockState state, @Nullable Player actor,
                                     Direction placedAgainst, int flags) {
        if (actor != null && (!actor.mayBuild() || !actor.mayUseItemAt(pos, placedAgainst, ItemStack.EMPTY))) {
            return false;
        }
        if (level instanceof ServerLevel serverLevel && !serverLevel.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }
        return level.setBlock(pos, state, flags);
    }
}
