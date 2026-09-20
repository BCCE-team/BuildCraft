package buildcraft.lib.platform.permission;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import buildcraft.lib.misc.FakePlayerProvider;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.level.BlockEvent.BreakEvent;

/** Native protection events and canceled-placement snapshot rollback. No internal owner-only restriction. */
public final class PlatformWorldActions {
    private PlatformWorldActions() {}
    public static boolean canBreakBlock(ServerLevel world, BlockPos pos, Player actor) {
        if (actor == null || world.getBlockState(pos).isAir()) {
            return false;
        }
        BreakEvent breakEvent = new BreakEvent(world, pos, world.getBlockState(pos), actor);
        return !MinecraftForge.EVENT_BUS.post(breakEvent);
    }
    public static boolean placeBlock(Level level, BlockPos pos, BlockState state, @Nullable Player actor,
                                     Direction placedAgainst, int flags) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return level.setBlock(pos, state, flags);
        }
        Player placementActor = actor != null
                ? actor
                : buildcraft.lib.misc.FakePlayerProvider.INSTANCE.getFakePlayer(serverLevel, FakePlayerProvider.NULL_PROFILE, pos);
        BlockSnapshot snapshot = BlockSnapshot.create(serverLevel.dimension(), serverLevel, pos);
        if (!serverLevel.setBlock(pos, state, flags)) {
            return false;
        }
        if (ForgeEventFactory.onBlockPlace(placementActor, snapshot, placedAgainst)) {
            snapshot.restore(true);
            return false;
        }
        return true;
    }
}
