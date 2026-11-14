package buildcraft.lib.platform.events;

import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Client-side event binding, only loaded by the Fabric client initializer.
 */
public final class PlatformClientEvents {
    private PlatformClientEvents() {}

    public static void tick(BCEvents.Phase phase, Consumer<BCEvents.ClientTick> handler) {
        if (phase == BCEvents.Phase.START) {
            ClientTickEvents.START_CLIENT_TICK.register(client ->
                handler.accept(new BCEvents.ClientTick(phase)));
        } else {
            ClientTickEvents.END_CLIENT_TICK.register(client ->
                handler.accept(new BCEvents.ClientTick(phase)));
        }
    }

    public static void login(Runnable handler) {
        ClientPlayConnectionEvents.JOIN.register((networkHandler, packetSender, client) -> handler.run());
    }

    public static void logout(Runnable handler) {
        ClientPlayConnectionEvents.DISCONNECT.register((networkHandler, client) -> handler.run());
    }
}
