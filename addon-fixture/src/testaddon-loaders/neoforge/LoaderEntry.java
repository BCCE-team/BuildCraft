package dev.bcce.apitest;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

@Mod(ApiSmokeProbe.MOD_ID)
public final class LoaderEntry {
    public LoaderEntry(IEventBus bus) {
        bus.addListener(this::setup);
        NeoForge.EVENT_BUS.addListener(this::started);
    }
    private void setup(FMLCommonSetupEvent event) { event.enqueueWork(ApiSmokeProbe::register); }
    private void started(ServerStartedEvent event) { ApiSmokeProbe.verify(event.getServer().overworld()); }
}
