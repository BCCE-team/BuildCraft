package buildcraft.lib.platform.client;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import net.minecraft.client.resources.model.QuadCollection;

/** Converts model events; no model implementation or registration catalogue belongs here. */
public final class PlatformClientModels {
    private PlatformClientModels() {}
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
}
