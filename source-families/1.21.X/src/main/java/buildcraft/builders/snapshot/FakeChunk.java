package buildcraft.builders.snapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * LevelChunk variant used by the client-side blueprint preview world.
 * It deliberately avoids BlockEntity#onLoad because Forge model-data storage
 * only accepts the real client level.
 */
public class FakeChunk extends LevelChunk {
    private final Level level;

    public FakeChunk(Level level, ChunkPos chunkPos) {
        super(level, chunkPos);
        this.level = level;
    }

    @Override
    public void addAndRegisterBlockEntity(BlockEntity blockEntity) {
        setBlockEntity(blockEntity);
    }

    @Override
    public void setBlockEntity(BlockEntity blockEntity) {
        BlockPos pos = blockEntity.getBlockPos();
        if (getBlockState(pos).hasBlockEntity()) {
            blockEntity.setLevel(level);
            blockEntities.put(pos.immutable(), blockEntity);
        }
    }
}
