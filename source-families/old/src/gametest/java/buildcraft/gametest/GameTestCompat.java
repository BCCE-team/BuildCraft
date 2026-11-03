package buildcraft.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Compatibility helpers for legacy Forge GameTests. */
public final class GameTestCompat {
    private GameTestCompat() {
    }

    public static BlockEntity getBlockEntity(GameTestHelper helper, BlockPos relativePos) {
        return helper.getLevel().getBlockEntity(helper.absolutePos(relativePos));
    }

    public static int readInt(CompoundTag tag, String key) {
        return tag.getInt(key);
    }
}
