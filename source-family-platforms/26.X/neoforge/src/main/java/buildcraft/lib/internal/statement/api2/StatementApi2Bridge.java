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
    private static final Map<Identifier, NativeTriggerAdapter> NATIVE_TRIGGERS = new LinkedHashMap<>();
    private static final Map<Identifier, NativeActionAdapter> NATIVE_ACTIONS = new LinkedHashMap<>();
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
        TriggerType trigger = BuildCraftApi.registry(BuildCraftRegistries.TRIGGER_TYPES).get(id);
        if (trigger != null && !MIRRORED_LEGACY.contains(id)) {
            NativeTriggerAdapter adapter = NATIVE_TRIGGERS.computeIfAbsent(id, ignored -> new NativeTriggerAdapter(trigger));
            StatementManager.statements.put(id.toString(), adapter);
            return adapter;
        }
        ActionType action = BuildCraftApi.registry(BuildCraftRegistries.ACTION_TYPES).get(id);
        if (action != null && !MIRRORED_LEGACY.contains(id)) {
            NativeActionAdapter adapter = NATIVE_ACTIONS.computeIfAbsent(id, ignored -> new NativeActionAdapter(action));
            StatementManager.statements.put(id.toString(), adapter);
            return adapter;
        }
        return null;
    }

    public static List<ITriggerInternal> nativeInternalTriggers(IStatementContainer container) {
        StatementContext context = context(container, null, OperationMode.SIMULATE);
        LinkedHashSet<Identifier> ids = contributedIds(context, true);
        List<ITriggerInternal> result = new ArrayList<>();
        for (Identifier id : ids) {
            if (MIRRORED_LEGACY.contains(id)) continue;
            IStatement statement = ensureNativeAdapter(id);
            if (statement instanceof ITriggerInternal trigger) result.add(trigger);
        }
        return result;
    }

    public static List<IActionInternal> nativeInternalActions(IStatementContainer container) {
        StatementContext context = context(container, null, OperationMode.EXECUTE);
        LinkedHashSet<Identifier> ids = contributedIds(context, false);
        List<IActionInternal> result = new ArrayList<>();
        for (Identifier id : ids) {
            if (MIRRORED_LEGACY.contains(id)) continue;
            IStatement statement = ensureNativeAdapter(id);
            if (statement instanceof IActionInternal action) result.add(action);
        }
        return result;
    }

    private static LinkedHashSet<Identifier> contributedIds(StatementContext context, boolean triggers) {
        LinkedHashSet<Identifier> ids = new LinkedHashSet<>();
        StatementCollector collector = new StatementCollector() {
            public void addTrigger(Identifier id) { if (triggers) ids.add(id); }
            public void addAction(Identifier id) { if (!triggers) ids.add(id); }
        };
        for (StatementContributor contributor : BuildCraftApi.registry(BuildCraftRegistries.STATEMENT_CONTRIBUTORS).values()) {
            contributor.contribute(context, collector);
        }
        return ids;
    }

    public static StatementContext context(IStatementContainer container, Direction side, OperationMode mode) {
        ExtensionContext views = new ExtensionContext() {
            public <T> java.util.Optional<T> get(ContextKey<T> key) {
                if (key.equals(LEGACY_CONTAINER)) return castOptional(container);
                if (key.type().isInstance(container) && key.id().equals(id("gate"))) return castOptional(container);
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

    public static TriggerWrapper toLegacyTrigger(buildcraft.api.v2.statement.StatementSlot slot) {
        IStatement statement = StatementManager.statements.get(slot.statementId().toString());
        if (statement == null) statement = ensureNativeAdapter(slot.statementId());
        return statement instanceof ITrigger ? TriggerWrapper.wrap(statement, slot.side()) : null;
    }

    public static ActionWrapper toLegacyAction(buildcraft.api.v2.statement.StatementSlot slot) {
        IStatement statement = StatementManager.statements.get(slot.statementId().toString());
        if (statement == null) statement = ensureNativeAdapter(slot.statementId());
        return statement instanceof IAction ? ActionWrapper.wrap(statement, slot.side()) : null;
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
            List<?> suggestions = type.suggestions(new StatementContext(ExtensionContext.empty(), AutomationActor.unknown(), OperationMode.SIMULATE, null));
            return suggestions.isEmpty() ? null : Api2ParameterAdapter.of(type, suggestions.get(0));
        }
        public IStatement rotateLeft() { return this; }
        public IStatement[] getPossible() { return new IStatement[] { this }; }
        public Component getDescription() { return Component.literal(id.toString()); }
        public ISprite getSprite() { return null; }
    }

    private static final class NativeTriggerAdapter extends BaseNativeAdapter implements ITriggerInternal {
        final TriggerType type;
        NativeTriggerAdapter(TriggerType type) { super(type.id(), type.parameters()); this.type = type; }
        public boolean isTriggerActive(IStatementContainer source, IStatementParameter[] parameters) {
            return type.evaluator().evaluate(context(source, null, OperationMode.SIMULATE), toApiParameters(this, parameters));
        }
    }

    private static final class NativeActionAdapter extends BaseNativeAdapter implements IActionInternal {
        final ActionType type;
        NativeActionAdapter(ActionType type) { super(type.id(), type.parameters()); this.type = type; }
        public void actionActivate(IStatementContainer source, IStatementParameter[] parameters) {
            type.executor().execute(context(source, null, OperationMode.EXECUTE), toApiParameters(this, parameters));
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
            nbt.putString("api2Type", value.type().id().toString());
            @SuppressWarnings({"rawtypes", "unchecked"}) ApiCodec<Object, OpaqueData> codec = (ApiCodec) value.type().codec();
            CodecResult<OpaqueData> encoded = codec.encode(value.value());
            if (!encoded.successful()) return;
            OpaqueData data = encoded.valueOrThrow();
            nbt.putString("format", data.format().toString());
            nbt.putByteArray("payload", data.bytes());
        }
        static IStatementParameter readFromNbt(CompoundTag nbt) {
            Identifier typeId = parse(NbtCompat.getString(nbt, "api2Type"));
            Identifier format = parse(NbtCompat.getString(nbt, "format"));
            if (typeId == null || format == null) return null;
            ParameterType<?> type = BuildCraftApi.registry(BuildCraftRegistries.PARAMETER_TYPES).get(typeId);
            if (type == null) return null;
            OpaqueData data = new OpaqueData(format, NbtCompat.getByteArray(nbt, "payload"));
            @SuppressWarnings({"rawtypes", "unchecked"}) CodecResult<?> decoded = ((ApiCodec) type.codec()).decode(data);
            return decoded.successful() ? new Api2ParameterAdapter(rawValue(type, decoded.valueOrThrow())) : null;
        }
        public IStatementParameter rotateLeft() { return this; }
        public IStatementParameter[] getPossible(IStatementContainer source) {
            List<?> suggestions = value.type().suggestions(context(source, null, OperationMode.SIMULATE));
            IStatementParameter[] out = new IStatementParameter[suggestions.size()];
            for (int i = 0; i < suggestions.size(); i++) out[i] = of(value.type(), suggestions.get(i));
            return out;
        }
    }
}
