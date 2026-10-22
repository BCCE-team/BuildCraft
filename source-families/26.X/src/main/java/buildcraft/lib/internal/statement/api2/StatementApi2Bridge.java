package buildcraft.lib.internal.statement.api2;

import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.context.ContextKey;
import buildcraft.api.v2.context.ExtensionContext;
import buildcraft.api.v2.gate.GateView;
import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.api.v2.persistence.ApiCodec;
import buildcraft.api.v2.persistence.CodecResult;
import buildcraft.api.v2.persistence.OpaqueData;
import buildcraft.api.v2.registry.ApiRegistry;
import buildcraft.api.v2.statement.ActionType;
import buildcraft.api.v2.statement.ParameterSchema;
import buildcraft.api.v2.statement.ParameterSpec;
import buildcraft.api.v2.statement.ParameterType;
import buildcraft.api.v2.statement.ParameterValue;
import buildcraft.api.v2.statement.StatementCollector;
import buildcraft.api.v2.statement.StatementContext;
import buildcraft.api.v2.statement.StatementContributor;
import buildcraft.api.v2.statement.StatementParameters;
import buildcraft.api.v2.statement.StatementResult;
import buildcraft.api.v2.statement.StatementScope;
import buildcraft.api.v2.statement.StatementContexts;
import buildcraft.api.v2.statement.StatementValidation;
import buildcraft.api.v2.statement.StatementSlot;
import buildcraft.api.v2.statement.TriggerType;
import buildcraft.lib.internal.statement.IAction;
import buildcraft.lib.internal.statement.IActionExternal;
import buildcraft.lib.internal.statement.IActionInternal;
import buildcraft.lib.internal.statement.IActionInternalSided;
import buildcraft.lib.internal.statement.IStatement;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.statement.ITrigger;
import buildcraft.lib.internal.statement.ITriggerExternal;
import buildcraft.lib.internal.statement.ITriggerInternal;
import buildcraft.lib.internal.statement.ITriggerInternalSided;
import buildcraft.lib.internal.statement.StatementManager;
import buildcraft.lib.internal.statement.StatementMouseClick;
import buildcraft.lib.statement.ActionWrapper;
import buildcraft.lib.statement.StatementTypeParam;
import buildcraft.lib.statement.TriggerWrapper;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import buildcraft.lib.compat.NbtCompat;

/**
 * Compatibility bridge between the BC8 statement runtime and the supported API2 statement contracts.
 * The legacy interfaces are internal only; addons interact with TriggerType/ActionType/ParameterType.
 */
public final class StatementApi2Bridge {
    public static final Identifier LEGACY_PARAMETER_TYPE_ID = id("internal/legacy_statement_parameter");
    public static final Identifier LEGACY_PARAMETER_FORMAT = id("internal/legacy_statement_parameter_snbt");
    public static final Identifier API2_PARAMETER_TAG = id("internal/api2_parameter");

    private static final ContextKey<IStatementContainer> LEGACY_CONTAINER =
        ContextKey.of(id("internal/statement_container"), IStatementContainer.class);
    private static final Set<Identifier> MIRRORED_LEGACY = new LinkedHashSet<>();
    private static final Map<Identifier, IStatement> NATIVE_TRIGGERS = new LinkedHashMap<>();
    private static final Map<Identifier, IStatement> NATIVE_ACTIONS = new LinkedHashMap<>();
    private static final int MAX_PARAMETER_BYTES = 64 * 1024;
    private static boolean parameterBridgeRegistered;

    private StatementApi2Bridge() {}

    public static synchronized void registerParameterBridge() {
        if (parameterBridgeRegistered) return;
        ensureLegacyParameterType();
        StatementManager.registerParameter(
            API2_PARAMETER_TAG.toString(),
            (StatementManager.IParameterReader) Api2ParameterAdapter::readFromNbt
        );
        StatementManager.registerParameter(
            API2_PARAMETER_TAG.toString(),
            (StatementManager.IParamReaderBuf) buffer -> Api2ParameterAdapter.readFromNbt(buffer.readNbt())
        );
        parameterBridgeRegistered = true;
    }

    public static void mirrorLegacyStatements(IStatement... statements) {
        Objects.requireNonNull(statements, "statements");
        for (IStatement statement : statements) {
            mirrorLegacyStatement(statement);
        }
    }

    public static synchronized void mirrorLegacyStatement(IStatement statement) {
        Objects.requireNonNull(statement, "statement");
        if (statement instanceof NativeStatementAdapter) return;
        Identifier id = parse(statement.getUniqueTag());
        if (id == null) return;
        ensureLegacyParameterType();
        ParameterSchema schema = legacySchema(statement);
        if (statement instanceof ITrigger) {
            ApiRegistry<TriggerType> registry = BuildCraftApi.registry(BuildCraftRegistries.TRIGGER_TYPES);
            if (registry.get(id) == null) {
                registry.register(id, new TriggerType(id, schema, (context, parameters) ->
                    evaluateLegacyTrigger(statement, context, parameters)), () -> id.getNamespace());
            }
            MIRRORED_LEGACY.add(id);
        }
        if (statement instanceof IAction) {
            ApiRegistry<ActionType> registry = BuildCraftApi.registry(BuildCraftRegistries.ACTION_TYPES);
            if (registry.get(id) == null) {
                registry.register(id, new ActionType(id, schema, (context, parameters) ->
                    executeLegacyAction(statement, context, parameters)), () -> id.getNamespace());
            }
            MIRRORED_LEGACY.add(id);
        }
    }

    public static synchronized IStatement ensureNativeAdapter(String rawId) {
        Identifier id = parse(rawId);
        return id == null ? null : ensureNativeAdapter(id);
    }

    public static synchronized IStatement ensureNativeAdapter(Identifier id) {
        IStatement existing = StatementManager.statements.get(id.toString());
        if (existing != null) return existing;
        StatementScope scope = scopeFor(id);
        TriggerType trigger = BuildCraftApi.registry(BuildCraftRegistries.TRIGGER_TYPES).get(id);
        if (trigger != null && !MIRRORED_LEGACY.contains(id)) {
            IStatement adapter = NATIVE_TRIGGERS.computeIfAbsent(id, ignored -> switch (scope) {
                case INTERNAL -> new NativeTriggerAdapter(trigger);
                case INTERNAL_SIDED -> new NativeSidedTriggerAdapter(trigger);
                case EXTERNAL -> new NativeExternalTriggerAdapter(trigger);
            });
            StatementManager.statements.put(id.toString(), adapter);
            return adapter;
        }
        ActionType action = BuildCraftApi.registry(BuildCraftRegistries.ACTION_TYPES).get(id);
        if (action != null && !MIRRORED_LEGACY.contains(id)) {
            IStatement adapter = NATIVE_ACTIONS.computeIfAbsent(id, ignored -> switch (scope) {
                case INTERNAL -> new NativeActionAdapter(action);
                case INTERNAL_SIDED -> new NativeSidedActionAdapter(action);
                case EXTERNAL -> new NativeExternalActionAdapter(action);
            });
            StatementManager.statements.put(id.toString(), adapter);
            return adapter;
        }
        return null;
    }

    private static StatementScope scopeFor(Identifier id) {
        StatementScope selected = StatementScope.INTERNAL;
        for (StatementContributor contributor : BuildCraftApi.registry(BuildCraftRegistries.STATEMENT_CONTRIBUTORS).values()) {
            try {
                StatementScope scope = contributor.scope(id);
                if (scope != null && scope != StatementScope.INTERNAL) {
                    if (selected != StatementScope.INTERNAL && scope != selected) return StatementScope.INTERNAL;
                    selected = scope;
                }
            } catch (RuntimeException | LinkageError ignored) { /* Ignore broken addon metadata. */ }
        }
        return selected;
    }

    public static List<ITriggerInternal> nativeInternalTriggers(IStatementContainer container) {
        StatementContext context = context(container, null, OperationMode.SIMULATE);
        List<ITriggerInternal> result = new ArrayList<>();
        for (Identifier id : contributedIds(context, StatementScope.INTERNAL, true)) {
            if (MIRRORED_LEGACY.contains(id)) continue;
            IStatement statement = ensureNativeAdapter(id);
            if (statement instanceof ITriggerInternal trigger) result.add(trigger);
        }
        return result;
    }

    public static List<IActionInternal> nativeInternalActions(IStatementContainer container) {
        StatementContext context = context(container, null, OperationMode.EXECUTE);
        List<IActionInternal> result = new ArrayList<>();
        for (Identifier id : contributedIds(context, StatementScope.INTERNAL, false)) {
            if (MIRRORED_LEGACY.contains(id)) continue;
            IStatement statement = ensureNativeAdapter(id);
            if (statement instanceof IActionInternal action) result.add(action);
        }
        return result;
    }

    public static List<ITriggerInternalSided> nativeInternalSidedTriggers(IStatementContainer container, Direction side) {
        StatementContext context = context(container, side, OperationMode.SIMULATE);
        List<ITriggerInternalSided> result = new ArrayList<>();
        for (Identifier id : contributedIds(context, StatementScope.INTERNAL_SIDED, true)) {
            if (MIRRORED_LEGACY.contains(id)) continue;
            IStatement statement = ensureNativeAdapter(id);
            if (statement instanceof ITriggerInternalSided trigger) result.add(trigger);
        }
        return result;
    }

    public static List<IActionInternalSided> nativeInternalSidedActions(IStatementContainer container, Direction side) {
        StatementContext context = context(container, side, OperationMode.EXECUTE);
        List<IActionInternalSided> result = new ArrayList<>();
        for (Identifier id : contributedIds(context, StatementScope.INTERNAL_SIDED, false)) {
            if (MIRRORED_LEGACY.contains(id)) continue;
            IStatement statement = ensureNativeAdapter(id);
            if (statement instanceof IActionInternalSided action) result.add(action);
        }
        return result;
    }

    public static List<ITriggerExternal> nativeExternalTriggers(Direction side, BlockEntity target) {
        List<ITriggerExternal> result = new ArrayList<>();
        for (Identifier id : contributedIds(externalContext(side, target, OperationMode.SIMULATE), StatementScope.EXTERNAL, true)) {
            if (MIRRORED_LEGACY.contains(id)) continue;
            IStatement statement = ensureNativeAdapter(id);
            if (statement instanceof ITriggerExternal trigger) result.add(trigger);
        }
        return result;
    }

    public static List<IActionExternal> nativeExternalActions(Direction side, BlockEntity target) {
        List<IActionExternal> result = new ArrayList<>();
        for (Identifier id : contributedIds(externalContext(side, target, OperationMode.EXECUTE), StatementScope.EXTERNAL, false)) {
            if (MIRRORED_LEGACY.contains(id)) continue;
            IStatement statement = ensureNativeAdapter(id);
            if (statement instanceof IActionExternal action) result.add(action);
        }
        return result;
    }

    private static LinkedHashSet<Identifier> contributedIds(StatementContext context, StatementScope scope, boolean triggers) {
        LinkedHashSet<Identifier> ids = new LinkedHashSet<>();
        for (StatementContributor contributor : BuildCraftApi.registry(BuildCraftRegistries.STATEMENT_CONTRIBUTORS).values()) {
            StatementCollector collector = new StatementCollector() {
                public void addTrigger(Identifier id) {
                    if (triggers && id != null && scope == contributor.scope(id)
                        && BuildCraftApi.registry(BuildCraftRegistries.TRIGGER_TYPES).get(id) != null) ids.add(id);
                }
                public void addAction(Identifier id) {
                    if (!triggers && id != null && scope == contributor.scope(id)
                        && BuildCraftApi.registry(BuildCraftRegistries.ACTION_TYPES).get(id) != null) ids.add(id);
                }
            };
            try { contributor.contribute(context, collector); }
            catch (RuntimeException | LinkageError ignored) { /* Malformed addon contributions are isolated. */ }
        }
        return ids;
    }

    private static StatementContext externalContext(Direction side, BlockEntity target, OperationMode mode) {
        ExtensionContext views = new ExtensionContext() {
            public <T> java.util.Optional<T> get(ContextKey<T> key) {
                if (key.equals(StatementContexts.EXTERNAL_TARGET)) return castOptional(target);
                return java.util.Optional.empty();
            }
        };
        return new StatementContext(views, AutomationActor.unknown(), mode, side);
    }

    public static StatementContext context(IStatementContainer container, Direction side, OperationMode mode) {
        return context(container, side, mode, null);
    }

    private static StatementContext context(IStatementContainer container, Direction side, OperationMode mode, BlockEntity target) {
        ExtensionContext views = new ExtensionContext() {
            public <T> java.util.Optional<T> get(ContextKey<T> key) {
                if (key.equals(LEGACY_CONTAINER)) return castOptional(container);
                if (key.equals(StatementContexts.GATE) && container instanceof GateView gate)
                    return castOptional(gate);
                if (key.equals(StatementContexts.GATE_POSITION) && container instanceof GateView gate)
                    return castOptional(gate.position());
                if (key.equals(StatementContexts.EXTERNAL_TARGET) && target != null)
                    return castOptional(target);
                return java.util.Optional.empty();
            }
        };
        return new StatementContext(views, AutomationActor.unknown(), mode, side);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> java.util.Optional<T> castOptional(Object value) {
        return java.util.Optional.of((T) value);
    }

    public static StatementParameters toApiParameters(IStatementParameter[] parameters) {
        return toApiParameters(null, parameters);
    }

    public static StatementParameters toApiParameters(IStatement statement, IStatementParameter[] parameters) {
        if (parameters == null || parameters.length == 0) return StatementParameters.EMPTY;
        Map<Identifier, ParameterValue<?>> values = new LinkedHashMap<>();
        ParameterType<OpaqueData> legacyType = legacyParameterType();
        for (int i = 0; i < parameters.length; i++) {
            IStatementParameter parameter = parameters[i];
            if (parameter == null) continue;
            Identifier slot = parameterSlot(statement, i);
            if (parameter instanceof Api2ParameterAdapter api2) {
                values.put(slot, api2.parameterValue());
                continue;
            }
            CompoundTag tag = StatementTypeParam.INSTANCE.writeToNbt(parameter);
            OpaqueData data = new OpaqueData(LEGACY_PARAMETER_FORMAT, tag.toString().getBytes(StandardCharsets.UTF_8));
            values.put(slot, new ParameterValue<>(legacyType, data));
        }
        return values.isEmpty() ? StatementParameters.EMPTY : new StatementParameters(values);
    }

    public static IStatementParameter[] toLegacyParameters(IStatement statement, StatementParameters parameters) {
        int count = statement.maxParameters();
        IStatementParameter[] result = new IStatementParameter[count];
        for (int i = 0; i < count; i++) {
            ParameterValue<?> value = parameters.values().get(parameterSlot(statement, i));
            IStatementParameter converted = value == null ? null : fromApiValue(value);
            result[i] = converted != null ? converted : statement.createParameter(i);
        }
        return result;
    }

    private static IStatementParameter fromApiValue(ParameterValue<?> value) {
        if (value.type().id().equals(LEGACY_PARAMETER_TYPE_ID) && value.value() instanceof OpaqueData data) {
            try {
                CompoundTag tag = NbtCompat.parseTag(new String(data.bytes(), StandardCharsets.UTF_8));
                return StatementTypeParam.INSTANCE.readFromNbt(tag);
            } catch (CommandSyntaxException | RuntimeException ignored) {
                return null;
            }
        }
        return new Api2ParameterAdapter(value);
    }

    /** Rejects mismatched, missing or unknown addon arguments before mutating a gate. */
    public static boolean validateSlot(StatementSlot slot) {
        if (slot == null) return true;
        if (slot.kind() == buildcraft.api.v2.statement.StatementKind.TRIGGER) {
            TriggerType type = BuildCraftApi.registry(BuildCraftRegistries.TRIGGER_TYPES).get(slot.statementId());
            return type != null && StatementValidation.valid(type.parameters(), slot.parameters());
        }
        ActionType type = BuildCraftApi.registry(BuildCraftRegistries.ACTION_TYPES).get(slot.statementId());
        return type != null && StatementValidation.valid(type.parameters(), slot.parameters());
    }

    public static TriggerWrapper toLegacyTrigger(buildcraft.api.v2.statement.StatementSlot slot) {
        IStatement statement = StatementManager.statements.get(slot.statementId().toString());
        if (statement == null) statement = ensureNativeAdapter(slot.statementId());
        if (!(statement instanceof ITrigger)) return null;
        if (statement instanceof ITriggerInternalSided || statement instanceof ITriggerExternal)
            return slot.side() == null ? null : TriggerWrapper.wrap(statement, slot.side());
        return TriggerWrapper.wrap(statement, null);
    }

    public static ActionWrapper toLegacyAction(buildcraft.api.v2.statement.StatementSlot slot) {
        IStatement statement = StatementManager.statements.get(slot.statementId().toString());
        if (statement == null) statement = ensureNativeAdapter(slot.statementId());
        if (!(statement instanceof IAction)) return null;
        if (statement instanceof IActionInternalSided || statement instanceof IActionExternal)
            return slot.side() == null ? null : ActionWrapper.wrap(statement, slot.side());
        return ActionWrapper.wrap(statement, null);
    }

    private static boolean evaluateLegacyTrigger(IStatement statement, StatementContext context, StatementParameters parameters) {
        IStatementContainer container = context.views().get(LEGACY_CONTAINER).orElse(null);
        if (container == null) return false;
        IStatementParameter[] legacy = toLegacyParameters(statement, parameters);
        Direction side = context.side();
        if (statement instanceof ITriggerInternal trigger) return trigger.isTriggerActive(container, legacy);
        if (statement instanceof ITriggerInternalSided trigger && side != null) return trigger.isTriggerActive(side, container, legacy);
        if (statement instanceof ITriggerExternal trigger && side != null) {
            BlockEntity target = container.getNeighbourTile(side);
            return target != null && trigger.isTriggerActive(target, side, container, legacy);
        }
        return false;
    }

    private static StatementResult executeLegacyAction(IStatement statement, StatementContext context, StatementParameters parameters) {
        IStatementContainer container = context.views().get(LEGACY_CONTAINER).orElse(null);
        if (container == null) return new StatementResult(StatementResult.Status.FAILED, "missing_internal_statement_container");
        if (context.mode() == OperationMode.SIMULATE) return StatementResult.success();
        IStatementParameter[] legacy = toLegacyParameters(statement, parameters);
        Direction side = context.side();
        if (statement instanceof IActionInternal action) {
            action.actionActivate(container, legacy);
            return StatementResult.success();
        }
        if (statement instanceof IActionInternalSided action && side != null) {
            action.actionActivate(side, container, legacy);
            return StatementResult.success();
        }
        if (statement instanceof IActionExternal action && side != null) {
            BlockEntity target = container.getNeighbourTile(side);
            if (target != null) {
                action.actionActivate(target, side, container, legacy);
                return StatementResult.success();
            }
        }
        return new StatementResult(StatementResult.Status.FAILED, "unsupported_statement_scope");
    }

    private static ParameterSchema legacySchema(IStatement statement) {
        if (statement.maxParameters() <= 0) return ParameterSchema.EMPTY;
        List<ParameterSpec> specs = new ArrayList<>(statement.maxParameters());
        for (int i = 0; i < statement.maxParameters(); i++) {
            specs.add(new ParameterSpec(slotId(i), LEGACY_PARAMETER_TYPE_ID, i < statement.minParameters()));
        }
        return new ParameterSchema(specs);
    }

    private static Identifier parameterSlot(IStatement statement, int index) {
        if (statement instanceof BaseNativeAdapter nativeAdapter && index < nativeAdapter.schema.parameters().size()) {
            return nativeAdapter.schema.parameters().get(index).slotId();
        }
        return slotId(index);
    }

    private static Identifier slotId(int index) { return id("arg_" + index); }

    @SuppressWarnings("unchecked")
    private static ParameterType<OpaqueData> legacyParameterType() {
        return (ParameterType<OpaqueData>) BuildCraftApi.registry(BuildCraftRegistries.PARAMETER_TYPES).get(LEGACY_PARAMETER_TYPE_ID);
    }

    private static synchronized void ensureLegacyParameterType() {
        ApiRegistry<ParameterType<?>> registry = BuildCraftApi.registry(BuildCraftRegistries.PARAMETER_TYPES);
        if (registry.get(LEGACY_PARAMETER_TYPE_ID) != null) return;
        ApiCodec<OpaqueData, OpaqueData> identity = new ApiCodec<>() {
            public CodecResult<OpaqueData> decode(OpaqueData payload) { return CodecResult.success(payload); }
            public CodecResult<OpaqueData> encode(OpaqueData value) { return CodecResult.success(value); }
        };
        registry.register(LEGACY_PARAMETER_TYPE_ID, new ParameterType<>(LEGACY_PARAMETER_TYPE_ID, identity), () -> "buildcraft");
    }

    private static Identifier parse(String id) {
        try { return Identifier.tryParse(id); } catch (RuntimeException ignored) { return null; }
    }

    private static Identifier id(String path) {
        return Objects.requireNonNull(Identifier.tryParse("buildcraft:" + path));
    }

    private interface NativeStatementAdapter {}

    private abstract static class BaseNativeAdapter implements IStatement, NativeStatementAdapter {
        final Identifier id;
        final ParameterSchema schema;
        BaseNativeAdapter(Identifier id, ParameterSchema schema) { this.id = id; this.schema = schema; }
        public String getUniqueTag() { return id.toString(); }
        public int maxParameters() { return schema.parameters().size(); }
        public int minParameters() { return (int) schema.parameters().stream().filter(ParameterSpec::required).count(); }
        public IStatementParameter createParameter(int index) {
            if (index < 0 || index >= schema.parameters().size()) return null;
            ParameterSpec spec = schema.parameters().get(index);
            ParameterType<?> type = BuildCraftApi.registry(BuildCraftRegistries.PARAMETER_TYPES).get(spec.typeId());
            if (type == null) return null;
            try {
                List<?> suggestions = type.suggestions(new StatementContext(ExtensionContext.empty(), AutomationActor.unknown(), OperationMode.SIMULATE, null));
                return suggestions.isEmpty() ? null : Api2ParameterAdapter.of(type, suggestions.get(0));
            } catch (RuntimeException | LinkageError ignored) { return null; }
        }
        public IStatement rotateLeft() { return this; }
        public IStatement[] getPossible() { return new IStatement[] { this }; }
        public Component getDescription() { return Component.literal(id.toString()); }
        public ISprite getSprite() { return null; }
    }

    private static boolean evaluate(TriggerType type, StatementContext context, StatementParameters parameters) {
        if (!StatementValidation.valid(type.parameters(), parameters)) return false;
        try { return type.evaluator().evaluate(context, parameters); }
        catch (RuntimeException | LinkageError ignored) { return false; }
    }

    private static void execute(ActionType type, StatementContext context, StatementParameters parameters) {
        if (!StatementValidation.valid(type.parameters(), parameters)) return;
        try { type.executor().execute(context, parameters); }
        catch (RuntimeException | LinkageError ignored) { /* Addons cannot interrupt a gate tick. */ }
    }

    private static final class NativeTriggerAdapter extends BaseNativeAdapter implements ITriggerInternal {
        final TriggerType type;
        NativeTriggerAdapter(TriggerType type) { super(type.id(), type.parameters()); this.type = type; }
        public boolean isTriggerActive(IStatementContainer source, IStatementParameter[] parameters) {
            return evaluate(type, context(source, null, OperationMode.SIMULATE), toApiParameters(this, parameters));
        }
    }

    private static final class NativeSidedTriggerAdapter extends BaseNativeAdapter implements ITriggerInternalSided {
        final TriggerType type;
        NativeSidedTriggerAdapter(TriggerType type) { super(type.id(), type.parameters()); this.type = type; }
        public boolean isTriggerActive(Direction side, IStatementContainer source, IStatementParameter[] parameters) {
            return evaluate(type, context(source, side, OperationMode.SIMULATE), toApiParameters(this, parameters));
        }
    }

    private static final class NativeExternalTriggerAdapter extends BaseNativeAdapter implements ITriggerExternal {
        final TriggerType type;
        NativeExternalTriggerAdapter(TriggerType type) { super(type.id(), type.parameters()); this.type = type; }
        public boolean isTriggerActive(BlockEntity target, Direction side, IStatementContainer source, IStatementParameter[] parameters) {
            return evaluate(type, context(source, side, OperationMode.SIMULATE, target), toApiParameters(this, parameters));
        }
    }

    private static final class NativeActionAdapter extends BaseNativeAdapter implements IActionInternal {
        final ActionType type;
        NativeActionAdapter(ActionType type) { super(type.id(), type.parameters()); this.type = type; }
        public void actionActivate(IStatementContainer source, IStatementParameter[] parameters) {
            execute(type, context(source, null, OperationMode.EXECUTE), toApiParameters(this, parameters));
        }
    }

    private static final class NativeSidedActionAdapter extends BaseNativeAdapter implements IActionInternalSided {
        final ActionType type;
        NativeSidedActionAdapter(ActionType type) { super(type.id(), type.parameters()); this.type = type; }
        public void actionActivate(Direction side, IStatementContainer source, IStatementParameter[] parameters) {
            execute(type, context(source, side, OperationMode.EXECUTE), toApiParameters(this, parameters));
        }
    }

    private static final class NativeExternalActionAdapter extends BaseNativeAdapter implements IActionExternal {
        final ActionType type;
        NativeExternalActionAdapter(ActionType type) { super(type.id(), type.parameters()); this.type = type; }
        public void actionActivate(BlockEntity target, Direction side, IStatementContainer source, IStatementParameter[] parameters) {
            execute(type, context(source, side, OperationMode.EXECUTE, target), toApiParameters(this, parameters));
        }
    }

    private static final class Api2ParameterAdapter implements IStatementParameter {
        private final ParameterValue<?> value;
        Api2ParameterAdapter(ParameterValue<?> value) { this.value = Objects.requireNonNull(value, "value"); }
        static Api2ParameterAdapter of(ParameterType<?> type, Object value) { return new Api2ParameterAdapter(rawValue(type, value)); }
        @SuppressWarnings({"rawtypes", "unchecked"})
        private static ParameterValue<?> rawValue(ParameterType type, Object value) { return new ParameterValue(type, value); }
        ParameterValue<?> parameterValue() { return value; }
        public String getUniqueTag() { return API2_PARAMETER_TAG.toString(); }
        public ItemStack getItemStack() { return ItemStack.EMPTY; }
        public Component getDescription() { return Component.literal(value.type().id().toString()); }
        public ISprite getSprite() { return null; }
        public IStatementParameter onClick(IStatementContainer source, IStatement stmt, ItemStack stack, StatementMouseClick mouse) { return null; }
        public void writeToNbt(CompoundTag nbt) {
            try {
                @SuppressWarnings({"rawtypes", "unchecked"}) ApiCodec<Object, OpaqueData> codec = (ApiCodec) value.type().codec();
                CodecResult<OpaqueData> encoded = codec.encode(value.value());
                if (!encoded.successful()) return;
                OpaqueData data = encoded.valueOrThrow();
                if (data.size() > MAX_PARAMETER_BYTES) return;
                nbt.putString("api2Type", value.type().id().toString());
                nbt.putString("format", data.format().toString());
                nbt.putByteArray("payload", data.bytes());
            } catch (RuntimeException | LinkageError ignored) { /* A bad addon codec cannot break gate saving. */ }
        }
        static IStatementParameter readFromNbt(CompoundTag nbt) {
            if (nbt == null) return null;
            try {
                var typeId = parse(NbtCompat.getString(nbt, "api2Type"));
                var format = parse(NbtCompat.getString(nbt, "format"));
                if (typeId == null || format == null) return null;
                ParameterType<?> type = BuildCraftApi.registry(BuildCraftRegistries.PARAMETER_TYPES).get(typeId);
                if (type == null) return null;
                byte[] bytes = NbtCompat.getByteArray(nbt, "payload");
                if (bytes.length > MAX_PARAMETER_BYTES) return null;
                OpaqueData data = new OpaqueData(format, bytes);
                @SuppressWarnings({"rawtypes", "unchecked"}) CodecResult<?> decoded = ((ApiCodec) type.codec()).decode(data);
                return decoded.successful() ? new Api2ParameterAdapter(rawValue(type, decoded.valueOrThrow())) : null;
            } catch (RuntimeException | LinkageError ignored) { return null; }
        }
        public IStatementParameter rotateLeft() { return this; }
        public IStatementParameter[] getPossible(IStatementContainer source) {
            List<?> suggestions;
            try { suggestions = value.type().suggestions(context(source, null, OperationMode.SIMULATE)); }
            catch (RuntimeException | LinkageError ignored) { return new IStatementParameter[0]; }
            IStatementParameter[] out = new IStatementParameter[suggestions.size()];
            for (int i = 0; i < suggestions.size(); i++) out[i] = of(value.type(), suggestions.get(i));
            return out;
        }
    }
}
