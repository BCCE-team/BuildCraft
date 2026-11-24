/* This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. */
package buildcraft.silicon.plug;

import buildcraft.lib.internal.debug.BCLog;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** Per-generation item templates. Mutable ItemStacks are never exposed to a consumer. */
public final class FacadeCreativeCache {
    private final Map<FacadeBlockStateInfo, ItemStack[]> templates = new IdentityHashMap<>();
    private long generation = Long.MIN_VALUE;
    private List<ItemStack> creativeTab;

    private void refresh(long current) {
        if (generation != current) {
            templates.clear();
            creativeTab = null;
            generation = current;
        }
    }

    public synchronized ItemStack get(FacadeBlockStateInfo info, boolean hollow,
                                      Function<FacadeInstance, ItemStack> factory) {
        refresh(FacadeStateManager.indexGeneration());
        ItemStack[] stacks = templates.computeIfAbsent(info, key -> new ItemStack[2]);
        int slot = hollow ? 1 : 0;
        if (stacks[slot] == null) stacks[slot] = factory.apply(FacadeInstance.createSingle(info, hollow));
        return stacks[slot].copy();
    }

    public void append(FacadeBlockStateInfo info, Function<FacadeInstance, ItemStack> factory,
                       Consumer<ItemStack> output) {
        output.accept(get(info, false, factory));
        output.accept(get(info, true, factory));
    }

    public void appendAll(Consumer<Consumer<ItemStack>> builder, Consumer<ItemStack> output) {
        long current = FacadeStateManager.indexGeneration();
        List<ItemStack> snapshot;
        synchronized (this) {
            refresh(current);
            snapshot = creativeTab;
        }
        if (snapshot == null) {
            // Do not hold the cache monitor while querying the manager/registries.
            long start = System.nanoTime();
            List<ItemStack> built = new ArrayList<>();
            builder.accept(built::add);
            snapshot = List.copyOf(built);
            synchronized (this) {
                if (current == FacadeStateManager.indexGeneration() && generation == current) {
                    if (creativeTab == null) {
                        creativeTab = snapshot;
                        BCLog.explicitEvent("[silicon.facade] Creative tab: {} variants built in {} ms",
                            snapshot.size(), (System.nanoTime() - start) / 1_000_000L);
                    } else {
                        snapshot = creativeTab;
                    }
                }
            }
        }
        for (ItemStack stack : snapshot) output.accept(stack.copy());
    }
}
