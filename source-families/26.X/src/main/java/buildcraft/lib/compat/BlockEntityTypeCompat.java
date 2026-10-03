package buildcraft.lib.compat;

import java.util.Set;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Constructor facade for block entity type registration used by shared BuildCraft code. */
public final class BlockEntityTypeCompat {
    private BlockEntityTypeCompat() {
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static <T extends BlockEntity> BlockEntityType<T> create(
        BlockEntityType.BlockEntitySupplier<? extends T> factory,
        Block... blocks
    ) {
        return (BlockEntityType<T>) new BlockEntityType((BlockEntityType.BlockEntitySupplier) factory, Set.of(blocks), false);
    }
}
