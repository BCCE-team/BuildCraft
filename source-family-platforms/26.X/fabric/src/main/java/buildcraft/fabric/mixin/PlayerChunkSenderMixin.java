package buildcraft.fabric.mixin;

import buildcraft.lib.platform.events.PlatformEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Packet-sent (not world-chunk-loaded) hook: each watching player receives the
 * lib marker/cache synchronization event after the vanilla chunk data.
 */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderMixin {
    @Inject(method = "sendChunk", at = @At("TAIL"))
    private static void bcce$afterSend(ServerGamePacketListenerImpl connection,
                                        ServerLevel level, LevelChunk chunk, CallbackInfo ci) {
        ServerPlayer player = connection.player;
        if (player != null) PlatformEvents.fireChunkWatch(player, level);
    }
}
