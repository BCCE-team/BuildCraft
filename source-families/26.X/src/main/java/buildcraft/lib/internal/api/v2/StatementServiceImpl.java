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
import buildcraft.api.v2.statement.StatementScope;
import buildcraft.api.v2.statement.StatementValidation;
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
        return availableTriggers(context, null);
    }

    public Collection<Identifier> availableActions(StatementContext context) {
        return availableActions(context, null);
    }

    public Collection<Identifier> availableTriggers(StatementContext context, StatementScope scope) {
        return collectContributions(context, scope, true);
    }

    public Collection<Identifier> availableActions(StatementContext context, StatementScope scope) {
        return collectContributions(context, scope, false);
    }

    private static Collection<Identifier> collectContributions(StatementContext context, StatementScope scope, boolean triggers) {
        LinkedHashSet<Identifier> result = new LinkedHashSet<>();
        ApiRegistry<StatementContributor> contributors = BuildCraftApi.registry(BuildCraftRegistries.STATEMENT_CONTRIBUTORS);
        for (StatementContributor contributor : contributors.values()) {
            StatementCollector collector = new StatementCollector() {
                public void addTrigger(Identifier id) {
                    if (triggers && id != null && (scope == null || contributor.scope(id) == scope)
                        && BuildCraftApi.registry(BuildCraftRegistries.TRIGGER_TYPES).get(id) != null) result.add(id);
                }
                public void addAction(Identifier id) {
                    if (!triggers && id != null && (scope == null || contributor.scope(id) == scope)
                        && BuildCraftApi.registry(BuildCraftRegistries.ACTION_TYPES).get(id) != null) result.add(id);
                }
            };
            try { contributor.contribute(context, collector); }
            catch (RuntimeException | LinkageError ignored) { /* Failing addons cannot interrupt gate discovery. */ }
        }
        return java.util.List.copyOf(result);
    }

    public boolean evaluateTrigger(Identifier id, StatementContext context, StatementParameters parameters) {
        TriggerType type = BuildCraftApi.registry(BuildCraftRegistries.TRIGGER_TYPES).get(id);
        if (type == null || !StatementValidation.valid(type.parameters(), parameters)) return false;
        try { return type.evaluator().evaluate(context, parameters); }
        catch (RuntimeException | LinkageError ignored) { return false; }
    }

    public StatementResult executeAction(Identifier id, StatementContext context, StatementParameters parameters) {
        ActionType type = BuildCraftApi.registry(BuildCraftRegistries.ACTION_TYPES).get(id);
        if (type == null) return new StatementResult(StatementResult.Status.FAILED, "unknown_action:" + id);
        if (!StatementValidation.valid(type.parameters(), parameters))
            return new StatementResult(StatementResult.Status.FAILED, "invalid_parameters:" + id);
        if (context.mode() == buildcraft.api.v2.OperationMode.SIMULATE) return StatementResult.success();
        try {
            StatementResult result = type.executor().execute(context, parameters);
            return result == null ? new StatementResult(StatementResult.Status.FAILED, "null_result:" + id) : result;
        } catch (RuntimeException | LinkageError ignored) {
            return new StatementResult(StatementResult.Status.FAILED, "callback_failed:" + id);
        }
    }
}
