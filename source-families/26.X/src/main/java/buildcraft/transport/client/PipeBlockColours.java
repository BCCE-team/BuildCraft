//? source if >=1.21.11
package buildcraft.transport.client;


import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 26.1.2 resolves terrain tints from an immutable source without a live block
 * entity. Pipe-body RGB is therefore encoded by ModelPipeNative2612; dynamic
 * pluggable colours stay in their submitted vertex data.
 */
public enum PipeBlockColours implements BlockTintSource {
    INSTANCE;

    @Override
    public int color(BlockState state) {
        return 0xFFFFFFFF;
    }
}
