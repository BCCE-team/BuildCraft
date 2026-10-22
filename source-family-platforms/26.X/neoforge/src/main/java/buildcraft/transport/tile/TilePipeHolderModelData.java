package buildcraft.transport.tile;

import buildcraft.transport.client.model.ModelPipeNative2612;
import net.neoforged.neoforge.client.model.data.ModelData;

/** Supplies immutable pipe geometry to the 26.1.2 terrain model. */
public final class TilePipeHolderModelData {
    private TilePipeHolderModelData() {}

    public static ModelData build(TilePipeHolder tile) {
        ModelPipeNative2612.PipeRenderData renderData = ModelPipeNative2612.buildModelData(tile);
        return renderData == null
            ? ModelData.EMPTY
            : ModelData.builder().with(ModelPipeNative2612.MODEL_DATA, renderData).build();
    }
}
