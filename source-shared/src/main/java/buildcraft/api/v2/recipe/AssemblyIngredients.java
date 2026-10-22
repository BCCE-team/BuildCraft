package buildcraft.api.v2.recipe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

/** Lossless simulation of counted, overlapping tag/exact ingredient allocations. */
public final class AssemblyIngredients {
    private AssemblyIngredients() { }

    /** Remaining slots after consumption, or empty when no valid allocation exists. */
    public static Optional<List<ItemStack>> remaining(
        List<CountedIngredient> ingredients, NonNullList<ItemStack> slots
    ) {
        Objects.requireNonNull(ingredients, "ingredients");
        Objects.requireNonNull(slots, "slots");
        if (ingredients.isEmpty()) return Optional.empty();
        int ni = ingredients.size();
        int ns = slots.size();
        int sink = ni + ns + 1;
        long[][] residual = new long[sink + 1][sink + 1];
        long wanted = 0;
        for (int i = 0; i < ni; i++) {
            CountedIngredient ingredient = Objects.requireNonNull(ingredients.get(i), "ingredient");
            residual[0][i + 1] = ingredient.count();
            wanted += ingredient.count();
            for (int s = 0; s < ns; s++) {
                ItemStack item = slots.get(s);
                if (ingredient.matchesItem(item)) residual[i + 1][ni + s + 1] = ingredient.count();
            }
        }
        long available = 0;
        for (int s = 0; s < ns; s++) {
            ItemStack stack = slots.get(s);
            int amount = stack == null || stack.isEmpty() ? 0 : stack.getCount();
            residual[ni + s + 1][sink] = amount;
            available += amount;
        }
        if (wanted > available) return Optional.empty();
        long[][] initial = new long[ni][ns];
        for (int i = 0; i < ni; i++) for (int s = 0; s < ns; s++) {
            initial[i][s] = residual[i + 1][ni + s + 1];
        }
        long allocated = 0;
        while (allocated < wanted) {
            int[] parent = new int[sink + 1];
            Arrays.fill(parent, -1);
            parent[0] = 0;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(0);
            while (!queue.isEmpty() && parent[sink] == -1) {
                int current = queue.removeFirst();
                for (int next = 1; next <= sink; next++) {
                    if (parent[next] == -1 && residual[current][next] > 0) {
                        parent[next] = current;
                        queue.addLast(next);
                    }
                }
            }
            if (parent[sink] == -1) return Optional.empty();
            long pushed = wanted - allocated;
            for (int v = sink; v != 0; v = parent[v]) pushed = Math.min(pushed, residual[parent[v]][v]);
            for (int v = sink; v != 0; v = parent[v]) {
                residual[parent[v]][v] -= pushed;
                residual[v][parent[v]] += pushed;
            }
            allocated += pushed;
        }
        List<ItemStack> result = new ArrayList<>(ns);
        for (int s = 0; s < ns; s++) {
            ItemStack original = slots.get(s);
            ItemStack copy = original == null || original.isEmpty() ? ItemStack.EMPTY : original.copy();
            long taken = 0;
            for (int i = 0; i < ni; i++) {
                taken += initial[i][s] - residual[i + 1][ni + s + 1];
            }
            if (taken != 0) copy.shrink(Math.toIntExact(taken));
            result.add(copy.isEmpty() ? ItemStack.EMPTY : copy);
        }
        return Optional.of(List.copyOf(result));
    }
}
