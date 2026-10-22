package buildcraft.lib.platform.actor;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Loader factory. Do not cache players globally or move them between worlds. */
public final class PlatformActors {
    private PlatformActors() {}
    public static ServerPlayer create(ServerLevel level, GameProfile profile) {
        FakePlayer actor = new buildcraft.lib.fake.FakePlayerBC(level, profile);
        return actor;
    }
    public static void afterAcquire(ServerLevel level, GameProfile profile, ServerPlayer actor) {
        if (profile.getId() != null) {
            ServerPlayer online = level.getServer().getPlayerList().getPlayer(profile.getId());
            if (online != null && online != actor) online.getAdvancements().setPlayer(online);
        }
    }
}
