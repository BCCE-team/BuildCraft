package buildcraft.lib.platform.events;
import java.util.function.Consumer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Only referenced from client-gated registration; dedicated servers never subscribe client event types. */
public final class PlatformClientEvents {
    private PlatformClientEvents() {}
    public static void tick(BCEvents.Phase selected, Consumer<BCEvents.ClientTick> handler) { if (selected == BCEvents.Phase.START) NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre event) -> handler.accept(new BCEvents.ClientTick(selected)));
        else NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> handler.accept(new BCEvents.ClientTick(selected))); }
    public static void login(Runnable handler) { NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> handler.run()); }
    public static void logout(Runnable handler) { NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> handler.run()); }
}
