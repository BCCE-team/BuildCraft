package buildcraft.lib.platform.client;
import net.minecraftforge.client.event.ModelEvent;

/** Callbacks remain on the native model-baking thread and keep the original result-map identity. */
public final class PlatformClientModels {
    private PlatformClientModels() {}
    public static ClientModelBaking.Additional additional(ModelEvent.RegisterAdditional event) { return event::register; }
    public static ClientModelBaking.Completed completed(ModelEvent.BakingCompleted event) { return new ClientModelBaking.Completed(event.getModels()); }
    //? if >=1.20 {
    public static ClientModelBaking.Models models(ModelEvent.ModifyBakingResult event) { return new ClientModelBaking.Models(event.getModels()); }
    //? }
}
