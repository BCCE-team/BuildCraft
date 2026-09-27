package buildcraft.lib.platform.actor;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric execution-actor factory.
 *
 * <p>Fabric does not provide Forge's {@code FakePlayer}; a detached vanilla {@link ServerPlayer}
 * is the compatible execution identity for BuildCraft automation. It is intentionally not added
 * to the server player list: ownership, advancement state and network lifecycle stay owned by the
 * real player, while protection callbacks receive the requested owner profile.</p>
 */
public final class PlatformActors {
    private PlatformActors() { }

    public static ServerPlayer create(ServerLevel level, GameProfile profile) {
        return new ServerPlayer(level.getServer(), level, profile);
    }

    /** A detached Fabric actor has no player-list or connection state to reconcile. */
    public static void afterAcquire(ServerLevel level, GameProfile profile, ServerPlayer actor) {
    }
}
