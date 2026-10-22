package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.facade.FacadeRuleService;
import buildcraft.api.v2.reload.DefinitionProvenance;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class FacadeRuleRegistryImpl implements FacadeRuleService {
    private record DisabledRule(Identifier id, DefinitionProvenance provenance) {}
    private record MappingRule(Identifier id, ItemStack stack, DefinitionProvenance provenance) {}

    private final Set<Identifier> ruleIds = new HashSet<>();
    private final Map<Block, DisabledRule> disabled = new LinkedHashMap<>();
    private final Map<BlockState, MappingRule> mappings = new LinkedHashMap<>();

    public synchronized void disable(Identifier ruleId, Block block, DefinitionProvenance provenance) {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(provenance, "provenance");
        ensureNewRuleId(ruleId);
        DisabledRule candidate = new DisabledRule(ruleId, provenance);
        disabled.merge(block, candidate, this::winningDisabled);
    }

    public synchronized void mapState(
        Identifier ruleId, BlockState state, ItemStack stack, DefinitionProvenance provenance
    ) {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(provenance, "provenance");
        if (stack.isEmpty()) throw new IllegalArgumentException("Facade mapped stack must not be empty");
        ensureNewRuleId(ruleId);
        MappingRule candidate = new MappingRule(ruleId, stack.copy(), provenance);
        mappings.merge(state, candidate, this::winningMapping);
    }

    public synchronized Optional<DefinitionProvenance> disabledBy(Block block) {
        DisabledRule value = disabled.get(block);
        return value == null ? Optional.empty() : Optional.of(value.provenance());
    }

    public synchronized Optional<ItemStack> mappedStack(BlockState state) {
        MappingRule value = mappings.get(state);
        return value == null ? Optional.empty() : Optional.of(value.stack().copy());
    }

    private void ensureNewRuleId(Identifier id) {
        if (!ruleIds.add(id)) throw new IllegalStateException("Duplicate facade rule id: " + id);
    }

    private DisabledRule winningDisabled(DisabledRule current, DisabledRule candidate) {
        return compare(current.provenance(), current.id(), candidate.provenance(), candidate.id()) >= 0 ? current : candidate;
    }

    private MappingRule winningMapping(MappingRule current, MappingRule candidate) {
        return compare(current.provenance(), current.id(), candidate.provenance(), candidate.id()) >= 0 ? current : candidate;
    }

    private static int compare(DefinitionProvenance left, Identifier leftId, DefinitionProvenance right, Identifier rightId) {
        int priority = Integer.compare(left.priority(), right.priority());
        if (priority != 0) return priority;
        return -leftId.toString().compareTo(rightId.toString());
    }
}
