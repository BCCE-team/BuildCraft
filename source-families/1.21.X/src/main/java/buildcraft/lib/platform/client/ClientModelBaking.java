//? source if >=1.21.11
package buildcraft.lib.platform.client;
import java.util.Map;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

/** Native Minecraft model result views, independent of the event which delivers them. */
public final class ClientModelBaking {
    private ClientModelBaking() {}
    @FunctionalInterface public interface Additional { void register(ClientStandaloneModel model); }
    public record Models(Map<BlockState, BlockStateModel> blockStateModels, Map<Identifier, ItemModel> itemStackModels) {}
    public record Completed(ModelManager getModelManager) {}
}
