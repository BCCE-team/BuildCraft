package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.facade.FacadePhase;
import buildcraft.api.v2.facade.FacadeMaterialAdapter;
import buildcraft.api.v2.facade.FacadeService;
import buildcraft.api.v2.facade.FacadeStateResult;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Deterministic facade-material resolver backed by the frozen API2 adapter registry. */
public final class FacadeServiceImpl implements FacadeService {
    @Override
    public Optional<FacadeStateResult> resolve(BlockState state) {
        if (state == null) return Optional.empty();
        for (FacadeMaterialAdapter adapter : BuildCraftApi.registry(BuildCraftRegistries.FACADE_MATERIAL_ADAPTERS).values()) {
            Optional<FacadeStateResult> result = adapter.fromState(state);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }

    @Override
    public Optional<FacadeStateResult> resolve(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        for (FacadeMaterialAdapter adapter : BuildCraftApi.registry(BuildCraftRegistries.FACADE_MATERIAL_ADAPTERS).values()) {
            Optional<FacadeStateResult> result = adapter.fromStack(stack);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }
    @Override
    public Optional<ItemStack> createPhased(List<FacadePhase> phases, boolean hollow) {
        if (phases == null || phases.isEmpty() || phases.size() > 17) return Optional.empty();
        Set<DyeColor> colours = new HashSet<>();
        boolean hasUncoloured = false;
        for (FacadePhase phase : phases) {
            if (phase == null) return Optional.empty();
            if (phase.activeColour() == null) {
                if (hasUncoloured) return Optional.empty();
                hasUncoloured = true;
            } else if (!colours.add(phase.activeColour())) {
                return Optional.empty();
            }
        }
        List<FacadePhase> stable = List.copyOf(phases);
        for (FacadeMaterialAdapter adapter : BuildCraftApi.registry(BuildCraftRegistries.FACADE_MATERIAL_ADAPTERS).values()) {
            try {
                Optional<ItemStack> result = adapter.createPhased(stable, hollow);
                if (result != null && result.isPresent() && !result.get().isEmpty()) {
                    return Optional.of(result.get().copy());
                }
            } catch (RuntimeException ignored) {
                // A malformed addon factory must not break other registered factories.
            }
        }
        return Optional.empty();
    }
}
