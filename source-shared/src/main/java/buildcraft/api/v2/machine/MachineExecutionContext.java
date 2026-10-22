package buildcraft.api.v2.machine;

import buildcraft.api.v2.fuels.EnergyFluidService;
import buildcraft.api.v2.recipe.MachineRecipeService;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/** Live, level-thread-confined machine environment. Queries do not force chunk loading. */
public interface MachineExecutionContext extends MachineView {
    MachineType definition();
    boolean isServerSide();
    boolean isActive();
    MachineControlMode mode();
    /** Authoritative status for gates and client menus. */
    void setWorkStatus(WorkStatus status);
    /** Marks component persistence and presentation dirty after any in-place state change. */
    void markChanged();
    default <T> T property(MachineProperty<T> key, T fallback) {
        return definition().propertyOrDefault(key, fallback);
    }
    Optional<BlockState> blockState(BlockPos pos);
    boolean isBlockLoaded(BlockPos pos);
    /** Never loads a chunk; the owner's world-access implementation enforces permissions. */
    boolean setBlock(BlockPos pos, BlockState state);
    MachineRecipeService recipes();
    EnergyFluidService energyFluids();
    /** Client-safe immutable state provided by a registered presentation codec. */
    <T> Optional<T> presentation(ResourceLocation componentId, Class<T> type);
}
