package buildcraft.lib.platform.events;
import java.util.function.Consumer;
import buildcraft.lib.net.BCNetworkSide;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;

/** Loader event subscription boundary. Phase selection is explicit, never silently coalesced. */
public final class PlatformEvents {
    private PlatformEvents() {}
    public static boolean isClient() { return FMLEnvironment.dist == Dist.CLIENT; }
    public static void serverTick(BCEvents.Phase selected, Consumer<BCEvents.ServerTick> handler) {
        if (selected == BCEvents.Phase.START) NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre event) -> handler.accept(new BCEvents.ServerTick(selected)));
        else NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> handler.accept(new BCEvents.ServerTick(selected)));
    }
    private static BCNetworkSide side(net.minecraft.world.level.Level level) {
        //? if >=1.21.11 {
        return level.isClientSide() ? BCNetworkSide.CLIENT : BCNetworkSide.SERVER;
        //? } else {
        return level.isClientSide ? BCNetworkSide.CLIENT : BCNetworkSide.SERVER;
        //? }
    }
    public static void levelTick(BCEvents.Phase selected, Consumer<BCEvents.LevelTick> handler) {
        if (selected == BCEvents.Phase.START) NeoForge.EVENT_BUS.addListener((LevelTickEvent.Pre event) -> handler.accept(new BCEvents.LevelTick(event.getLevel(), selected, side(event.getLevel()))));
        else NeoForge.EVENT_BUS.addListener((LevelTickEvent.Post event) -> handler.accept(new BCEvents.LevelTick(event.getLevel(), selected, side(event.getLevel()))));
    }
    public static void playerTick(BCEvents.Phase selected, Consumer<BCEvents.PlayerTick> handler) {
        if (selected == BCEvents.Phase.START) NeoForge.EVENT_BUS.addListener((PlayerTickEvent.Pre event) -> handler.accept(new BCEvents.PlayerTick(event.getEntity(), selected)));
        else NeoForge.EVENT_BUS.addListener((PlayerTickEvent.Post event) -> handler.accept(new BCEvents.PlayerTick(event.getEntity(), selected)));
    }
    public static void chunkWatch(Consumer<BCEvents.ChunkWatch> handler) {
        NeoForge.EVENT_BUS.addListener((ChunkWatchEvent.Watch event) -> handler.accept(new BCEvents.ChunkWatch(event.getPlayer(), event.getLevel())));
    }
    public static void entityJoin(Consumer<BCEvents.EntityJoin> handler) { NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent event) -> handler.accept(new BCEvents.EntityJoin(event.getEntity()))); }
    public static void levelUnload(Consumer<BCEvents.LevelUnload> handler) { NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> handler.accept(new BCEvents.LevelUnload(event.getLevel()))); }
    public static void chunkUnload(Consumer<BCEvents.ChunkUnload> handler) { NeoForge.EVENT_BUS.addListener((ChunkEvent.Unload event) -> handler.accept(new BCEvents.ChunkUnload(event.getLevel(), event.getChunk().getPos()))); }
}
