package buildcraft.energy.tile;

import buildcraft.lib.engine.EngineConnector;
import buildcraft.lib.engine.TileEngineBase_BC8;
import buildcraft.lib.internal.mj.IMjConnector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

abstract class AbstractCorePortEngineStub extends TileEngineBase_BC8 {
    AbstractCorePortEngineStub(BlockPos pos, BlockState state) {
        super(null, pos, state);
    }

    @Override
    protected IMjConnector createConnector() {
        return new EngineConnector(false);
    }

    @Override
    public boolean isBurning() {
        return false;
    }

    @Override
    public long getMaxPower() {
        return 0;
    }

    @Override
    public long maxPowerReceived() {
        return 0;
    }

    @Override
    public long maxPowerExtracted() {
        return 0;
    }

    @Override
    public float explosionRange() {
        return 0;
    }

    @Override
    public long getCurrentOutput() {
        return 0;
    }
}
