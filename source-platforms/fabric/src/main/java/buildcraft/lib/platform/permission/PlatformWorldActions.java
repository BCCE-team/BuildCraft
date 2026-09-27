package buildcraft.lib.platform.permission;
// Loader boundary owner: net.fabricmc (implementation may delegate to vanilla/common contracts).

import javax.annotation.Nullable;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fabric world-action boundary used by machine and automation gameplay.
 *
 * <p>The caller supplies the real player or BuildCraft's fake player. It is deliberately not an
 * owner-only ACL: protection integrations must see the same actor that performed the work. The
 * raw {@link Level#setBlock(BlockPos, BlockState, int)} fallback used during the early Fabric
 * foundation bypassed Fabric's standard break hook, which made a future Mining Well, Quarry and
 * Builder disagree with normal player protection.</p>
 */
public final class PlatformWorldActions {
    private PlatformWorldActions() { }

    public static boolean canBreakBlock(ServerLevel world, BlockPos pos, Player actor) {
        if (actor == null || world.getBlockState(pos).isAir()) return false;
        if (!actor.mayBuild() || !actor.mayUseItemAt(pos, Direction.UP, ItemStack.EMPTY)
            || !world.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }

        // This is Fabric's server-side protection extension point. Calling it before any machine
        // power is spent keeps simulate/execute parity with the Forge and NeoForge implementation.
        return PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(
            world, actor, pos, world.getBlockState(pos), world.getBlockEntity(pos)
        );
    }

    public static boolean placeBlock(Level level, BlockPos pos, BlockState state, @Nullable Player actor,
                                     Direction placedAgainst, int flags) {
        if (actor != null && (!actor.mayBuild() || !actor.mayUseItemAt(pos, placedAgainst, ItemStack.EMPTY))) {
            return false;
        }
        if (level instanceof ServerLevel serverLevel && !serverLevel.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }
        // setBlock is intentionally retained for state-only construction actions. The common
        // automation layer has already evaluated the placement permission with the supplied actor;
        // this boundary additionally preserves vanilla survival rules so a Fabric machine cannot
        // create an invalid block state merely because it does not use a BlockItem.
        if (!state.canSurvive(level, pos)) {
            return false;
        }
        return level.setBlock(pos, state, flags);
    }
}
