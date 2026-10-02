package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.registry.ApiRegistry;
import buildcraft.api.v2.statement.ActionType;
import buildcraft.api.v2.statement.ParameterType;
import buildcraft.api.v2.statement.StatementCollector;
import buildcraft.api.v2.statement.StatementContext;
import buildcraft.api.v2.statement.StatementContributor;
import buildcraft.api.v2.statement.StatementParameters;
import buildcraft.api.v2.statement.StatementResult;
import buildcraft.api.v2.statement.StatementService;
import buildcraft.api.v2.statement.TriggerType;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/** Internal implementation of the supported API2 statements facade. */
public final class StatementServiceImpl implements StatementService {
    public static final StatementServiceImpl INSTANCE = new StatementServiceImpl();

    private StatementServiceImpl() {}

    public Optional<ParameterType<?>> parameterType(Identifier id) {
        return Optional.ofNullable(BuildCraftApi.registry(BuildCraftRegistries.PARAMETER_TYPES).get(id));
    }

    public Optional<TriggerType> triggerType(Identifier id) {
        return Optional.ofNullable(BuildCraftApi.registry(BuildCraftRegistries.TRIGGER_TYPES).get(id));
    }

    public Optional<ActionType> actionType(Identifier id) {
        return Optional.ofNullable(BuildCraftApi.registry(BuildCraftRegistries.ACTION_TYPES).get(id));
    }

    public Collection<Identifier> availableTriggers(StatementContext context) {
        LinkedHashSet<Identifier> result = new LinkedHashSet<>();
        collectContributions(context, result, null);
        return java.util.List.copyOf(result);
    }

    public Collection<Identifier> availableActions(StatementContext context) {
        LinkedHashSet<Identifier> result = new LinkedHashSet<>();
        collectContributions(context, null, result);
        return java.util.List.copyOf(result);
    }

    private static void collectContributions(
        StatementContext context,
        LinkedHashSet<Identifier> triggers,
        LinkedHashSet<Identifier> actions
    ) {
        ApiRegistry<StatementContributor> contributors = BuildCraftApi.registry(BuildCraftRegistries.STATEMENT_CONTRIBUTORS);
        StatementCollector collector = new StatementCollector() {
            public void addTrigger(Identifier id) { if (triggers != null) triggers.add(id); }
            public void addAction(Identifier id) { if (actions != null) actions.add(id); }
        };
        for (StatementContributor contributor : contributors.values()) {
            contributor.contribute(context, collector);
        }
    }

    public boolean evaluateTrigger(Identifier id, StatementContext context, StatementParameters parameters) {
        TriggerType type = BuildCraftApi.registry(BuildCraftRegistries.TRIGGER_TYPES).get(id);
        return type != null && type.evaluator().evaluate(context, parameters);
    }

    public StatementResult executeAction(Identifier id, StatementContext context, StatementParameters parameters) {
        ActionType type = BuildCraftApi.registry(BuildCraftRegistries.ACTION_TYPES).get(id);
        return type == null
            ? new StatementResult(StatementResult.Status.FAILED, "unknown_action:" + id)
            : type.executor().execute(context, parameters);
    }
}
