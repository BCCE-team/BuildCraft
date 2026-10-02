package buildcraft.lib.platform.events;

import buildcraft.lib.net.BCNetworkSide;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ChunkPos;

/** Internal normalized event data. No loader Event, cancellation flags or event bus leak into gameplay. */
public final class BCEvents {
    private BCEvents() {}
    public enum Phase { START, END }
    public record ServerTick(Phase phase) { public Phase getPhase() { return phase; } }
    public record ClientTick(Phase phase) { public Phase getPhase() { return phase; } }
    public record LevelTick(Level level, Phase phase, BCNetworkSide side) { public Level getLevel() { return level; } }
    public record PlayerTick(Player player, Phase phase) { public Player getEntity() { return player; } }
    public record EntityJoin(Entity entity) { public Entity getEntity() { return entity; } }
    public record LevelUnload(LevelAccessor level) { public LevelAccessor getLevel() { return level; } }
    public record ChunkUnload(LevelAccessor level, ChunkPos pos) { public LevelAccessor getLevel() { return level; } }
    public record ChunkWatch(ServerPlayer player, ServerLevel level) {
        public ServerPlayer getPlayer() { return player; }
        public ServerLevel getLevel() { return level; }
    }
}
