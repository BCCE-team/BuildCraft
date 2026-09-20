package buildcraft.transport.tile;

import buildcraft.transport.client.model.ModelPipe;
import net.neoforged.neoforge.client.model.data.ModelData;
public class TilePipeHolderModelData {
    public static ModelData build(TilePipeHolder tile) {
        return ModelData.builder().with(ModelPipe.PipeTypeModelKey, tile).build();
    }
}
