package dev.bcce.apitest;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class LoaderEntry implements ModInitializer {
    @Override public void onInitialize() {
        ApiSmokeProbe.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> ApiSmokeProbe.verify(server.overworld()));
    }
}
