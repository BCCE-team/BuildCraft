package buildcraft.lib.platform.events;
import java.util.function.Consumer;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;

/** Only referenced from client-gated registration; dedicated servers never subscribe client event types. */
public final class PlatformClientEvents {
    private PlatformClientEvents() {}
    public static void tick(BCEvents.Phase selected, Consumer<BCEvents.ClientTick> handler) { MinecraftForge.EVENT_BUS.addListener((ClientTickEvent event) -> {
            BCEvents.Phase phase = event.phase == net.minecraftforge.event.TickEvent.Phase.END ? BCEvents.Phase.END : BCEvents.Phase.START;
            if (phase == selected) handler.accept(new BCEvents.ClientTick(phase));
        }); }
    public static void login(Runnable handler) { MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> handler.run()); }
    public static void logout(Runnable handler) { MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> handler.run()); }
}
