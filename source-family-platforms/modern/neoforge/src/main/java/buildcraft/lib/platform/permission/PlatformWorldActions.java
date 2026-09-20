//? source if >=1.21.1
package buildcraft.lib.platform.permission;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import buildcraft.lib.misc.FakePlayerProvider;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.level.BlockEvent.BreakEvent;

/** Native protection events and canceled-placement snapshot rollback. No internal owner-only restriction. */
public final class PlatformWorldActions {
    private PlatformWorldActions() {}
    public static boolean canBreakBlock(ServerLevel world, BlockPos pos, Player actor) {
        if (actor == null || world.getBlockState(pos).isAir()) {
            return false;
        }
        BreakEvent breakEvent = new BreakEvent(world, pos, world.getBlockState(pos), actor);
        NeoForge.EVENT_BUS.post(breakEvent);
        return !breakEvent.isCanceled();
    }
    public static boolean placeBlock(Level level, BlockPos pos, BlockState state, @Nullable Player actor,
                                     Direction placedAgainst, int flags) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return level.setBlock(pos, state, flags);
        }
        Player placementActor = actor != null
            ? actor
            : buildcraft.lib.misc.FakePlayerProvider.INSTANCE.getFakePlayer(serverLevel, FakePlayerProvider.NULL_PROFILE, pos);
        BlockSnapshot snapshot = BlockSnapshot.create(serverLevel.dimension(), serverLevel, pos, flags);
        if (!serverLevel.setBlock(pos, state, flags)) {
            return false;
        }
        if (EventHooks.onBlockPlace(placementActor, snapshot, placedAgainst)) {
            snapshot.restore();
            return false;
        }
        return true;
    }
}
