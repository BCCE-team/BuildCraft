package buildcraft.lib.platform.client;
import java.util.Map;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;

/** Model registration/baking views. Key types follow Minecraft, never the loader event class. */
public final class ClientModelBaking {
    private ClientModelBaking() {}
    @FunctionalInterface public interface Additional { void register(ModelResourceLocation id); }
    public record Models(Map<ModelResourceLocation, BakedModel> getModels) {}
    public record Completed(Map<ModelResourceLocation, BakedModel> getModels) {}
}
