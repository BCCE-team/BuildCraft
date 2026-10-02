package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.facade.FacadeMaterialAdapter;
import buildcraft.api.v2.facade.FacadeService;
import buildcraft.api.v2.facade.FacadeStateResult;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Deterministic facade-material resolver backed by the frozen API2 adapter registry. */
public final class FacadeServiceImpl implements FacadeService {
    public Optional<FacadeStateResult> resolve(BlockState state) {
        if (state == null) return Optional.empty();
        for (FacadeMaterialAdapter adapter : BuildCraftApi.registry(BuildCraftRegistries.FACADE_MATERIAL_ADAPTERS).values()) {
            Optional<FacadeStateResult> result = adapter.fromState(state);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }

    public Optional<FacadeStateResult> resolve(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        for (FacadeMaterialAdapter adapter : BuildCraftApi.registry(BuildCraftRegistries.FACADE_MATERIAL_ADAPTERS).values()) {
            Optional<FacadeStateResult> result = adapter.fromStack(stack);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }
}
