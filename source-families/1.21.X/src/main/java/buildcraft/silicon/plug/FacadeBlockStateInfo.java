package buildcraft.silicon.plug;

import java.util.Objects;

import com.google.common.collect.ImmutableSet;

import buildcraft.lib.world.SingleBlockAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.core.registries.BuiltInRegistries;

public class FacadeBlockStateInfo  {
    public final BlockState state;
    public final ItemStack requiredStack;
    public final ImmutableSet<Property<?>> varyingProperties;
    public final boolean isTransparent;
    public final boolean isVisible;
    public final boolean[] isSideSolid = new boolean[6];

    public FacadeBlockStateInfo(BlockState state, ItemStack requiredStack,
        ImmutableSet<Property<?>> varyingProperties) {
        this.state = Objects.requireNonNull(state, "state must not be null!");
        Objects.requireNonNull(state.getBlock(), "state.getBlock must not be null!");
        Objects.requireNonNull(BuiltInRegistries.BLOCK.getKey(state.getBlock()),
            "BuiltInRegistries.BLOCK.getKey(state.getBlock() must not be null!");
        BlockGetter access = new SingleBlockAccess(state);
        this.requiredStack = requiredStack;
        this.varyingProperties = varyingProperties;
        this.isTransparent = !state.propagatesSkylightDown(access, BlockPos.ZERO);
        this.isVisible = !requiredStack.isEmpty();
        for (Direction side : Direction.values()) {
            isSideSolid[side.ordinal()] = state.isFaceSturdy(access, BlockPos.ZERO, side);
        }
    }

    // Helper methods

    public FacadePhasedState createPhased(DyeColor activeColour) {
        return new FacadePhasedState(this, activeColour);
    }

    @Override
    public String toString() {
        return "StateInfo [id=" + System.identityHashCode(this) + ", block = " + state.getBlock() + ", state =  "
            + state.toString() + "]";
    }


    public BlockState getBlockState() {
        return state;
    }

    public boolean isTransparent() {
        return isTransparent;
    }

    public ItemStack getRequiredStack() {
        return requiredStack;
    }
}
