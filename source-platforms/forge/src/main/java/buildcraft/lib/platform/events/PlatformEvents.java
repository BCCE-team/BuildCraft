package buildcraft.lib.platform.events;
import java.util.function.Consumer;
import buildcraft.lib.net.BCNetworkSide;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.api.distmarker.Dist;

/** Loader event subscription boundary. Phase selection is explicit, never silently coalesced. */
public final class PlatformEvents {
    private PlatformEvents() {}
    public static boolean isClient() { return FMLEnvironment.dist == Dist.CLIENT; }
    private static BCEvents.Phase phase(TickEvent.Phase phase) { return phase == TickEvent.Phase.END ? BCEvents.Phase.END : BCEvents.Phase.START; }
    public static void serverTick(BCEvents.Phase selected, Consumer<BCEvents.ServerTick> handler) {
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent event) -> { if (phase(event.phase) == selected) handler.accept(new BCEvents.ServerTick(selected)); });
    }
    public static void levelTick(BCEvents.Phase selected, Consumer<BCEvents.LevelTick> handler) {
        MinecraftForge.EVENT_BUS.addListener((TickEvent.LevelTickEvent event) -> { if (phase(event.phase) == selected) handler.accept(new BCEvents.LevelTick(event.level, selected, event.side == LogicalSide.SERVER ? BCNetworkSide.SERVER : BCNetworkSide.CLIENT)); });
    }
    public static void playerTick(BCEvents.Phase selected, Consumer<BCEvents.PlayerTick> handler) {
        MinecraftForge.EVENT_BUS.addListener((TickEvent.PlayerTickEvent event) -> { if (phase(event.phase) == selected) handler.accept(new BCEvents.PlayerTick(event.player, selected)); });
    }
    public static void chunkWatch(Consumer<BCEvents.ChunkWatch> handler) {
        //? if <1.20 {
        MinecraftForge.EVENT_BUS.addListener((ChunkWatchEvent event) -> handler.accept(new BCEvents.ChunkWatch(event.getPlayer(), event.getPlayer().getLevel())));
        //? } else {
        MinecraftForge.EVENT_BUS.addListener((ChunkWatchEvent.Watch event) -> handler.accept(new BCEvents.ChunkWatch(event.getPlayer(), event.getLevel())));
        //? }
    }
    public static void entityJoin(Consumer<BCEvents.EntityJoin> handler) { MinecraftForge.EVENT_BUS.addListener((EntityJoinLevelEvent event) -> handler.accept(new BCEvents.EntityJoin(event.getEntity()))); }
    public static void levelUnload(Consumer<BCEvents.LevelUnload> handler) { MinecraftForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> handler.accept(new BCEvents.LevelUnload(event.getLevel()))); }
    public static void chunkUnload(Consumer<BCEvents.ChunkUnload> handler) { MinecraftForge.EVENT_BUS.addListener((ChunkEvent.Unload event) -> handler.accept(new BCEvents.ChunkUnload(event.getLevel(), event.getChunk().getPos()))); }
}
