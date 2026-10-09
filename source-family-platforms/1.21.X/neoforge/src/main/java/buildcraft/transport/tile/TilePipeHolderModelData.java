//? source if >=1.21.11
package buildcraft.transport.tile;

import buildcraft.transport.client.model.ModelPipeNative121111;
import net.neoforged.neoforge.model.data.ModelData;

public class TilePipeHolderModelData {
    public static ModelData build(TilePipeHolder tile) {
        ModelPipeNative121111.PipeRenderData data = ModelPipeNative121111.buildModelData(tile);
        if (data == null) {
            return ModelData.EMPTY;
        }
        return ModelData.builder().with(ModelPipeNative121111.MODEL_DATA, data).build();
    }
}
