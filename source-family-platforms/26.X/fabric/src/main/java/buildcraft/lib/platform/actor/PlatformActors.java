package buildcraft.lib.platform.actor;

import buildcraft.lib.compat.GameProfileCompat;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric fake-player binding for the existing BCActors per-world/UUID cache.
 * Do not replace the owner's UUID or use a real player's ServerPlayer instance.
 */
public final class PlatformActors {
    private PlatformActors() {}

    public static ServerPlayer create(ServerLevel level, GameProfile profile) {
        return FakePlayer.get(level, profile);
    }

    /**
     * Preserve the NeoForge advancement-sharing repair when an owner's UUID
     * also belongs to a currently connected player.
     */
    public static void afterAcquire(ServerLevel level, GameProfile profile, ServerPlayer actor) {
        if (GameProfileCompat.id(profile) == null) return;
        ServerPlayer online = level.getServer().getPlayerList().getPlayer(GameProfileCompat.id(profile));
        if (online != null && online != actor) {
            online.getAdvancements().setPlayer(online);
        }
    }
}
