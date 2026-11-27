package dev.bcce.apitest;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(ApiSmokeProbe.MOD_ID)
public final class LoaderEntry {
    private ServerLevel loadedLevel;
    public LoaderEntry() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        MinecraftForge.EVENT_BUS.addListener(this::levelLoaded);
        MinecraftForge.EVENT_BUS.addListener(this::started);
    }
    private void setup(FMLCommonSetupEvent event) { event.enqueueWork(ApiSmokeProbe::register); }
    private void levelLoaded(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) loadedLevel = level;
    }
    private void started(ServerStartedEvent event) {
        if (loadedLevel == null) throw new IllegalStateException("No loaded server world for API test");
        ApiSmokeProbe.verify(loadedLevel);
        loadedLevel = null;
    }
}
