package buildcraft.lib.misc;

import buildcraft.lib.platform.actor.BCActors;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Backwards-compatible internal facade; loader actor construction belongs to PlatformActors. */
public enum FakePlayerProvider {
    INSTANCE;
    public static final GameProfile NULL_PROFILE = BCActors.SYSTEM_PROFILE;
    public ServerPlayer getBuildCraftPlayer(ServerLevel world) { return getFakePlayer(world, NULL_PROFILE, BlockPos.ZERO); }
    public ServerPlayer getFakePlayer(ServerLevel world, GameProfile profile) { return getFakePlayer(world, profile, BlockPos.ZERO); }
    public ServerPlayer getFakePlayer(ServerLevel world, GameProfile profile, BlockPos pos) { return BCActors.at(world, profile, pos); }
    public void unloadWorld(ServerLevel world) { BCActors.unloadWorld(world); }
}
