package buildcraft.api.v2.crops;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Deterministically ordered crop adapter registry. Higher priority runs first. */
public interface CropService {
    void register(ResourceLocation id, int priority, CropAdapter adapter);
    List<CropRegistration> adapters();

    default boolean isSeed(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        for (CropRegistration entry : adapters()) {
            try {
                if (entry.adapter().isSeed(stack)) return true;
            } catch (RuntimeException ignored) { }
        }
        return false;
    }

    default boolean canSustainPlant(Level level, ItemStack seed, BlockPos pos) {
        if (seed == null || seed.isEmpty()) return false;
        for (CropRegistration entry : adapters()) {
            try {
                CropAdapter adapter = entry.adapter();
                if (adapter.isSeed(seed) && adapter.canSustainPlant(level, seed, pos)) return true;
            } catch (RuntimeException ignored) { }
        }
        return false;
    }

    default boolean plant(Level level, Player actor, ItemStack seed, BlockPos pos) {
        if (seed == null || seed.isEmpty()) return false;
        for (CropRegistration entry : adapters()) {
            try {
                CropAdapter adapter = entry.adapter();
                if (adapter.isSeed(seed) && adapter.canSustainPlant(level, seed, pos)
                    && adapter.plant(level, actor, seed, pos)) return true;
            } catch (RuntimeException ignored) { }
        }
        return false;
    }

    default boolean isMature(BlockGetter level, BlockState state, BlockPos pos) {
        for (CropRegistration entry : adapters()) {
            try {
                if (entry.adapter().isMature(level, state, pos)) return true;
            } catch (RuntimeException ignored) { }
        }
        return false;
    }

    default boolean harvest(Level level, BlockPos pos, NonNullList<ItemStack> drops, Player actor) {
        BlockState state = level.getBlockState(pos);
        for (CropRegistration entry : adapters()) {
            CropAdapter adapter = entry.adapter();
            boolean mature;
            try {
                mature = adapter.isMature(level, state, pos);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (!mature) continue;
            NonNullList<ItemStack> staged = NonNullList.create();
            for (ItemStack drop : drops) staged.add(drop.copy());
            try {
                if (adapter.harvest(level, pos, staged, actor)) {
                    drops.clear();
                    drops.addAll(staged);
                    return true;
                }
            } catch (RuntimeException ignored) {
                // A failed harvester may have changed the block: do not invoke a second one.
                return false;
            }
        }
        return false;
    }
}
