package buildcraft.lib.platform.client;
import net.neoforged.neoforge.client.event.ModelEvent;
//? if >=1.21.11 {
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import net.minecraft.client.resources.model.QuadCollection;
//? }

/** Converts model events; no model implementation or registration catalogue belongs here. */
public final class PlatformClientModels {
    private PlatformClientModels() {}
    //? if >=1.21.11 {
    public static ClientModelBaking.Additional additional(ModelEvent.RegisterStandalone event) {
        return model -> {
            StandaloneModelKey<QuadCollection> key = new StandaloneModelKey<>(() -> "BuildCraft static model " + model.location());
            event.register(key, SimpleUnbakedStandaloneModel.quadCollection(model.location()));
            model.bind(manager -> manager.getStandaloneModel(key));
        };
    }
    public static ClientModelBaking.Completed completed(ModelEvent.BakingCompleted event) { return new ClientModelBaking.Completed(event.getModelManager()); }
    public static ClientModelBaking.Models models(ModelEvent.ModifyBakingResult event) {
        return new ClientModelBaking.Models(event.getBakingResult().blockStateModels(), event.getBakingResult().itemStackModels());
    }
    //? } else {
    public static ClientModelBaking.Additional additional(ModelEvent.RegisterAdditional event) { return event::register; }
    public static ClientModelBaking.Completed completed(ModelEvent.BakingCompleted event) { return new ClientModelBaking.Completed(event.getModels()); }
    public static ClientModelBaking.Models models(ModelEvent.ModifyBakingResult event) { return new ClientModelBaking.Models(event.getModels()); }
    //? }
}
