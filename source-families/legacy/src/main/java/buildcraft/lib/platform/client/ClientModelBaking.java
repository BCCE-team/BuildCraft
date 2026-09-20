package buildcraft.lib.platform.client;
import java.util.Map;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;

/** Model registration/baking views. Key types follow Minecraft, never the loader event class. */
public final class ClientModelBaking {
    private ClientModelBaking() {}
    @FunctionalInterface public interface Additional { void register(ResourceLocation id); }
    public record Models(Map<ResourceLocation, BakedModel> getModels) {}
    public record Completed(Map<ResourceLocation, BakedModel> getModels) {}
}
