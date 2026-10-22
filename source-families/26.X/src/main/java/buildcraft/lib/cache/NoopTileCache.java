package buildcraft.lib.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public enum NoopTileCache implements ITileCache {
    INSTANCE;

    public void invalidate() {}

    public TileCacheRet getTile(BlockPos pos) {
        return null;
    }

    public TileCacheRet getTile(Direction offset) {
        return null;
    }
}
