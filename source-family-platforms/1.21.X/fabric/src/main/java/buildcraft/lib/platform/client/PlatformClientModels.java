package buildcraft.lib.platform.client;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

/** Fabric model lifecycle adapter. Never accepts a NeoForge event class. */
public final class PlatformClientModels {
    private PlatformClientModels() {}

    /** Used during Fabric's model-loading discovery phase, before atlas baking. */
    public static ClientModelBaking.Additional additional(Consumer<ClientStandaloneModel> callback) {
        Objects.requireNonNull(callback, "callback");
        return callback::accept;
    }

    /** Called after model/atlas reload to rebuild BuildCraft's geometry caches. */
    public static ClientModelBaking.Completed completed(ModelManager manager) {
        return new ClientModelBaking.Completed(manager);
    }

    public static ClientModelBaking.Models models(Map<BlockState, BlockStateModel> blocks,
        Map<Identifier, ItemModel> items) {
        return new ClientModelBaking.Models(blocks, items);
    }
}
