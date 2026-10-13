package buildcraft.api.v2.facade;

import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public interface FacadeMaterialAdapter {
    default Optional<FacadeStateResult> fromState(BlockState state) { return Optional.empty(); }
    default Optional<FacadeStateResult> fromStack(ItemStack stack) { return Optional.empty(); }
    default Optional<ItemStack> createPhased(List<FacadePhase> phases, boolean hollow) { return Optional.empty(); }
}
