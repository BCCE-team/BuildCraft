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
    @Override
    protected boolean storesMachineDataAtRoot() { return true; }
    @Override
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
        if (level == null || level.isClientSide) {
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

    @Override
    protected void writeData(BCValueOutput bcData) {
        if (owner != null) {
            bcData.put(NBT_OWNER, NbtUtils.writeBlockPos(owner));
        }
    }

    @Override
    protected void readData(BCValueInput bcData) {
        owner = bcData.has(NBT_OWNER) ? BuildersNbtUtil.readBlockPos(bcData.tag(), NBT_OWNER) : null;
    }
}
