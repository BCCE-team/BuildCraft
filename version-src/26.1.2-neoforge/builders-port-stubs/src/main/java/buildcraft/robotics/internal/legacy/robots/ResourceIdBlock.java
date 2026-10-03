package buildcraft.robotics.internal.legacy.robots;

import net.minecraft.core.BlockPos;

/** Compile-only block reservation key. */
public final class ResourceIdBlock extends ResourceId {
    private final BlockPos pos;

    public ResourceIdBlock(BlockPos pos) {
        this.pos = pos.immutable();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ResourceIdBlock other && pos.equals(other.pos);
    }

    @Override
    public int hashCode() {
        return pos.hashCode();
    }
}
