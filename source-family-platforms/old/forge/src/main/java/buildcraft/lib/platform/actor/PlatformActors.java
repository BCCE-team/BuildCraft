package buildcraft.lib.platform.actor;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;

/** Loader factory. Do not cache players globally or move them between worlds. */
public final class PlatformActors {
    private PlatformActors() {}
    public static ServerPlayer create(ServerLevel level, GameProfile profile) { return new FakePlayer(level, profile); }
    public static void afterAcquire(ServerLevel level, GameProfile profile, ServerPlayer actor) {
    }
}
