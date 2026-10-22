package buildcraft.api.v2.facade;

import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public interface FacadeService {
    Optional<FacadeStateResult> resolve(BlockState state);
    Optional<FacadeStateResult> resolve(ItemStack stack);

    /** Creates a native BuildCraft facade item using the existing phased-state codec. */
    default Optional<ItemStack> createPhased(List<FacadePhase> phases, boolean hollow) {
        return Optional.empty();
    }
}
