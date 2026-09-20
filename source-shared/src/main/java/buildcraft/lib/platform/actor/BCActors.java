package buildcraft.lib.platform.actor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Function;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import buildcraft.lib.internal.debug.BCLog;

/** Execution identity, not an ownership ACL. The profile UUID and fallback identity are save-compatible. */
public final class BCActors {
    public static final GameProfile SYSTEM_PROFILE = new GameProfile(
        UUID.nameUUIDFromBytes("buildcraft.core".getBytes(StandardCharsets.UTF_8)), "[BuildCraft]");
    private static final ActorCache<ServerLevel, GameProfile, ServerPlayer> PLAYERS = new ActorCache<>();
    private BCActors() {}

    public static ServerPlayer at(ServerLevel level, GameProfile owner, BlockPos position) {
        if (owner == null) {
            BCLog.logger.warn("[lib.fake] Null GameProfile! This is a bug!", new IllegalArgumentException());
            owner = SYSTEM_PROFILE;
        }
        ServerPlayer actor = PLAYERS.get(level, owner, PlatformActors::create);
        actor.setPos(position.getX(), position.getY(), position.getZ());
        // Vanilla shares PlayerAdvancements with an online player of this UUID.
        PlatformActors.afterAcquire(level, owner, actor);
        return actor;
    }

    /** Scoped action for callers that supply a tool; restores actor context even after nested actions fail.
     * The supplied stack itself is NOT copied: legitimate durability/count changes still reach the caller. */
    public static <T> T withTool(ServerLevel level, GameProfile owner, BlockPos position, ItemStack tool,
                                 Function<ServerPlayer, T> action) {
        GameProfile key = owner == null ? SYSTEM_PROFILE : owner;
        ServerPlayer actor = PLAYERS.get(level, key, PlatformActors::create);
        double x = actor.getX(), y = actor.getY(), z = actor.getZ();
        ItemStack previous = actor.getItemInHand(InteractionHand.MAIN_HAND);
        try {
            actor.setPos(position.getX(), position.getY(), position.getZ());
            PlatformActors.afterAcquire(level, key, actor);
            actor.setItemInHand(InteractionHand.MAIN_HAND, tool);
            return action.apply(actor);
        } finally {
            actor.setItemInHand(InteractionHand.MAIN_HAND, previous);
            actor.setPos(x, y, z);
        }
    }
    public static void unloadWorld(ServerLevel level) { PLAYERS.unload(level); }
    public static void stopServer() { PLAYERS.clear(); }
}
