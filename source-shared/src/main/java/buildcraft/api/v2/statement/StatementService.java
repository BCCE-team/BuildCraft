package buildcraft.api.v2.statement;

import java.util.Collection;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public interface StatementService {
    Optional<ParameterType<?>> parameterType(ResourceLocation id);
    Optional<TriggerType> triggerType(ResourceLocation id);
    Optional<ActionType> actionType(ResourceLocation id);
    Collection<ResourceLocation> availableTriggers(StatementContext context);
    Collection<ResourceLocation> availableActions(StatementContext context);
    /** Restricts discovery to a single placement without altering legacy discovery. */
    default Collection<ResourceLocation> availableTriggers(StatementContext context, StatementScope scope) {
        return scope == StatementScope.INTERNAL ? availableTriggers(context) : java.util.List.of();
    }
    default Collection<ResourceLocation> availableActions(StatementContext context, StatementScope scope) {
        return scope == StatementScope.INTERNAL ? availableActions(context) : java.util.List.of();
    }
    boolean evaluateTrigger(ResourceLocation id, StatementContext context, StatementParameters parameters);
    StatementResult executeAction(ResourceLocation id, StatementContext context, StatementParameters parameters);
}
