package buildcraft.lib.compat;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Fabric's public builder avoids the private vanilla BlockEntitySupplier in 26.1.2. */
public final class BlockEntityTypeCompat {
    private BlockEntityTypeCompat() {}

    public static <T extends BlockEntity> BlockEntityType<T> create(
        FabricBlockEntityTypeBuilder.Factory<? extends T> factory, Block... blocks
    ) {
        return FabricBlockEntityTypeBuilder.<T>create(factory, blocks).build();
    }
}
