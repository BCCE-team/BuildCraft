//? source if >=1.21.1
package buildcraft.builders.tile;

import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import buildcraft.lib.compat.minecraft.persistence.BCBlockEntity;
import buildcraft.builders.BCBuildersBlocks;
import buildcraft.builders.BuildersNbtUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class TileQuarryDrillCollision extends BCBlockEntity {
    protected boolean storesMachineDataAtRoot() { return true; }
    protected boolean requiresPersistenceRegistries() { return false; }

    private static final String NBT_OWNER = "owner";

    private BlockPos owner;

    public TileQuarryDrillCollision(BlockPos pos, BlockState state) {
        super(BCBuildersBlocks.QUARRY_DRILL_COLLISION_TILE_BC8.get(), pos, state);
    }

    public void setOwner(BlockPos owner) {
        this.owner = owner.immutable();
        setChanged();
    }

    public BlockPos getOwner() {
        return owner;
    }

    public void update() {
        if (level == null || level.isClientSide()) {
            return;
        }
        if (owner == null) {
            removeSelf();
            return;
        }
        BlockEntity ownerTile = level.getBlockEntity(owner);
        if (!(ownerTile instanceof TileQuarry quarry) || !quarry.shouldKeepCollisionBlock(worldPosition)) {
            removeSelf();
        }
    }

    private void removeSelf() {
        if (level != null && level.getBlockState(worldPosition).getBlock() == BCBuildersBlocks.QUARRY_DRILL_COLLISION.get()) {
            level.setBlock(worldPosition, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    protected void writeData(BCValueOutput bcData) {
        if (owner != null) {
            bcData.writeIntArray(NBT_OWNER, new int[] {owner.getX(), owner.getY(), owner.getZ()});
        }
    }

    protected void readData(BCValueInput bcData) {
        owner = bcData.decode(NBT_OWNER, BlockPos.CODEC).map(BlockPos::immutable).orElse(null);
    }
}
