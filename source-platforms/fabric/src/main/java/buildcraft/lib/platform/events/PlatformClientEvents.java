package buildcraft.lib.platform.events;

import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/** Client event bridge kept minimal until the dedicated client/render stage. */
public final class PlatformClientEvents {
    private PlatformClientEvents() { }
    public static void tick(BCEvents.Phase selected, Consumer<BCEvents.ClientTick> handler) {
        if (selected == BCEvents.Phase.START) {
            ClientTickEvents.START_CLIENT_TICK.register(client -> handler.accept(new BCEvents.ClientTick(selected)));
        } else {
            ClientTickEvents.END_CLIENT_TICK.register(client -> handler.accept(new BCEvents.ClientTick(selected)));
        }
    }
}
