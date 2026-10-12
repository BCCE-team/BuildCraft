package buildcraft.api.v2.content;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.automation.AutomationActionType;
import buildcraft.api.v2.automation.StripesHandler;
import buildcraft.api.v2.filler.FillerPatternType;
import buildcraft.api.v2.fuels.CoolantProfile;
import buildcraft.api.v2.fuels.FuelProfile;
import buildcraft.api.v2.guide.GuideEntry;
import buildcraft.api.v2.guide.GuideSection;
import buildcraft.api.v2.machine.EngineType;
import buildcraft.api.v2.machine.MachineComponentType;
import buildcraft.api.v2.machine.MachineProperty;
import buildcraft.api.v2.machine.MachineType;
import buildcraft.api.v2.pipe.PipeAttachmentType;
import buildcraft.api.v2.pipe.PipeComponentType;
import buildcraft.api.v2.pipe.PipeType;
import buildcraft.api.v2.recipe.DistillationRecipeDefinition;
import buildcraft.api.v2.registry.ApiRegistry;
import buildcraft.api.v2.registry.RegistrationContext;
import buildcraft.api.v2.registry.RegistryKey;
import buildcraft.api.v2.reload.DefinitionProvenance;
import buildcraft.api.v2.robot.DockPortType;
import buildcraft.api.v2.robot.RobotBoardType;
import buildcraft.api.v2.robot.RobotResourceType;
import buildcraft.api.v2.robot.RobotTaskType;
import buildcraft.api.v2.schematic.EntitySchematicAdapter;
import buildcraft.api.v2.schematic.InventoryCopyPolicy;
import buildcraft.api.v2.schematic.SchematicAdapter;
import buildcraft.api.v2.schematic.SnapshotElement;
import buildcraft.api.v2.schematic.SnapshotElementType;
import buildcraft.api.v2.signal.SignalChannelType;
import buildcraft.api.v2.statement.ActionType;
import buildcraft.api.v2.statement.ParameterType;
import buildcraft.api.v2.statement.StatementContributor;
import buildcraft.api.v2.statement.TriggerType;
import buildcraft.api.v2.worldgen.ResourceDepositRule;
import buildcraft.api.v2.worldgen.WorldTargetSelector;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;

/**
 * Convenience facade for registering common addon content without manually handling every
 * BuildCraft registry/service and ownership/provenance object.
 *
 * <p>Advanced addons can always drop down to {@link BuildCraftApi}; this class is deliberately
 * just sugar over the same public contracts.
 */
public final class ContentRegistrar {
    private final String namespace;
    private final int priority;
    private final RegistrationContext registrationContext;
    private final DefinitionProvenance provenance;

    ContentRegistrar(String namespace, int priority) {
        this.namespace = validateNamespace(namespace);
        this.priority = priority;
        this.registrationContext = () -> this.namespace;
        this.provenance = new DefinitionProvenance(this.namespace, "addon-code", priority);
    }

    public String namespace() { return namespace; }
    public int priority() { return priority; }
    public RegistrationContext registrationContext() { return registrationContext; }
    public DefinitionProvenance provenance() { return provenance; }

    public ResourceLocation id(String path) {
        Objects.requireNonNull(path, "path");
        ResourceLocation id = ResourceLocation.tryParse(namespace + ":" + path);
        if (id == null) throw new IllegalArgumentException("Invalid content id: " + namespace + ":" + path);
        return id;
    }

    public <T> T register(RegistryKey<T> key, ResourceLocation id, T value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(value, "value");
        BuildCraftApi.registry(key).register(id, value, registrationContext);
        return value;
    }

    public MachineProperty<?> machineProperty(MachineProperty<?> property) {
        return register(BuildCraftRegistries.MACHINE_PROPERTIES, property.id(), property);
    }

    public MachineComponentType<?> machineComponent(MachineComponentType<?> type) {
        return register(BuildCraftRegistries.MACHINE_COMPONENT_TYPES, type.id(), type);
    }

    public MachineType machine(MachineType type) {
        return register(BuildCraftRegistries.MACHINE_TYPES, type.id(), type);
    }

    public MachineType machineVariant(
        String path, ResourceLocation baseId, Consumer<MachineType.Builder> customization
    ) {
        MachineType base = require(BuildCraftRegistries.MACHINE_TYPES, baseId, "machine");
        MachineType.Builder builder = MachineType.variant(id(path), base);
        Objects.requireNonNull(customization, "customization").accept(builder);
        return machine(builder.build());
    }

    public PipeComponentType<?> pipeComponent(PipeComponentType<?> type) {
        return register(BuildCraftRegistries.PIPE_COMPONENT_TYPES, type.id(), type);
    }

    public PipeAttachmentType<?> pipeAttachment(PipeAttachmentType<?> type) {
        return register(BuildCraftRegistries.PIPE_ATTACHMENT_TYPES, type.id(), type);
    }

    public PipeType pipe(PipeType type) {
        return register(BuildCraftRegistries.PIPE_TYPES, type.id(), type);
    }

    public PipeType pipeVariant(String path, ResourceLocation baseId, Consumer<PipeType.Builder> customization) {
        PipeType base = require(BuildCraftRegistries.PIPE_TYPES, baseId, "pipe");
        PipeType.Builder builder = PipeType.variant(id(path), base);
        Objects.requireNonNull(customization, "customization").accept(builder);
        return pipe(builder.build());
    }

    public EngineType engine(EngineType type) {
        return register(BuildCraftRegistries.ENGINE_TYPES, type.id(), type);
    }

    public FillerPatternType fillerPattern(FillerPatternType type) {
        return register(BuildCraftRegistries.FILLER_PATTERN_TYPES, type.id(), type);
    }

    public RobotTaskType<?> robotTask(RobotTaskType<?> type) {
        return register(BuildCraftRegistries.ROBOT_TASK_TYPES, type.id(), type);
    }

    public RobotResourceType<?> robotResource(RobotResourceType<?> type) {
        return register(BuildCraftRegistries.ROBOT_RESOURCE_TYPES, type.id(), type);
    }

    public DockPortType<?> dockPort(DockPortType<?> type) {
        return register(BuildCraftRegistries.ROBOT_DOCK_PORT_TYPES, type.id(), type);
    }

    public RobotBoardType robotBoard(RobotBoardType type) {
        return register(BuildCraftRegistries.ROBOT_BOARD_TYPES, type.id(), type);
    }

    public ParameterType<?> parameter(ParameterType<?> type) {
        return register(BuildCraftRegistries.PARAMETER_TYPES, type.id(), type);
    }

    public StatementContributor statementContributor(String path, StatementContributor contributor) {
        return register(BuildCraftRegistries.STATEMENT_CONTRIBUTORS, id(path), contributor);
    }

    public TriggerType trigger(TriggerType type) {
        return register(BuildCraftRegistries.TRIGGER_TYPES, type.id(), type);
    }

    public ActionType action(ActionType type) {
        return register(BuildCraftRegistries.ACTION_TYPES, type.id(), type);
    }

    public SignalChannelType<?> signal(SignalChannelType<?> type) {
        return register(BuildCraftRegistries.SIGNAL_CHANNEL_TYPES, type.id(), type);
    }

    public AutomationActionType<?> automationAction(AutomationActionType<?> type) {
        return register(BuildCraftRegistries.AUTOMATION_ACTION_TYPES, type.id(), type);
    }

    public StripesHandler stripesHandler(String path, StripesHandler handler) {
        return register(BuildCraftRegistries.STRIPES_HANDLERS, id(path), handler);
    }

    public SchematicAdapter schematicAdapter(String path, SchematicAdapter adapter) {
        return register(BuildCraftRegistries.SCHEMATIC_ADAPTERS, id(path), adapter);
    }

    public EntitySchematicAdapter entitySchematicAdapter(String path, EntitySchematicAdapter adapter) {
        return register(BuildCraftRegistries.SCHEMATIC_ENTITY_ADAPTERS, id(path), adapter);
    }

    public InventoryCopyPolicy inventoryCopyPolicy(InventoryCopyPolicy policy) {
        return register(BuildCraftRegistries.INVENTORY_COPY_POLICIES, policy.id(), policy);
    }

    public SnapshotElementType<?> snapshotElement(SnapshotElementType<?> type) {
        return register(BuildCraftRegistries.SNAPSHOT_ELEMENT_TYPES, type.id(), type);
    }

    public <E extends SnapshotElement> SnapshotElementType<E> blockSchematic(
        String path,
        SnapshotElementType<E> type,
        SchematicAdapter adapter
    ) {
        ResourceLocation expected = id(path);
        if (!expected.equals(type.id())) {
            throw new IllegalArgumentException("Snapshot element id must match content id " + expected + ": " + type.id());
        }
        Objects.requireNonNull(adapter, "adapter");
        if (BuildCraftApi.registry(BuildCraftRegistries.SCHEMATIC_ADAPTERS).get(expected) != null) {
            throw new IllegalStateException("Duplicate schematic adapter id: " + expected);
        }
        register(BuildCraftRegistries.SNAPSHOT_ELEMENT_TYPES, type.id(), type);
        register(BuildCraftRegistries.SCHEMATIC_ADAPTERS, expected, adapter);
        return type;
    }

    public <E extends SnapshotElement> SnapshotElementType<E> entitySchematic(
        String path,
        SnapshotElementType<E> type,
        EntitySchematicAdapter adapter
    ) {
        ResourceLocation expected = id(path);
        if (!expected.equals(type.id())) {
            throw new IllegalArgumentException("Snapshot element id must match content id " + expected + ": " + type.id());
        }
        Objects.requireNonNull(adapter, "adapter");
        if (BuildCraftApi.registry(BuildCraftRegistries.SCHEMATIC_ENTITY_ADAPTERS).get(expected) != null) {
            throw new IllegalStateException("Duplicate schematic adapter id: " + expected);
        }
        register(BuildCraftRegistries.SNAPSHOT_ELEMENT_TYPES, type.id(), type);
        register(BuildCraftRegistries.SCHEMATIC_ENTITY_ADAPTERS, expected, adapter);
        return type;
    }


    public FuelProfile fuel(String path, FuelProfile profile) {
        service(BuildCraftServices.ENERGY_FLUIDS).register(id(path), profile, provenance);
        return profile;
    }

    public CoolantProfile coolant(String path, CoolantProfile profile) {
        service(BuildCraftServices.ENERGY_FLUIDS).register(id(path), profile, provenance);
        return profile;
    }

    public DistillationRecipeDefinition distillation(String path, DistillationRecipeDefinition recipe) {
        service(BuildCraftServices.MACHINE_RECIPES).register(id(path), recipe, provenance);
        return recipe;
    }

    public DistillationRecipeDefinition distillation(
        String path, Consumer<DistillationRecipeDefinition.Builder> definition
    ) {
        DistillationRecipeDefinition.Builder builder = DistillationRecipeDefinition.builder();
        Objects.requireNonNull(definition, "definition").accept(builder);
        return distillation(path, builder.build());
    }

    public GuideSection guideSection(GuideSection section) {
        service(BuildCraftServices.GUIDE).registerSection(section, registrationContext);
        return section;
    }

    public GuideEntry guideEntry(GuideEntry entry) {
        service(BuildCraftServices.GUIDE).registerEntry(entry, registrationContext);
        return entry;
    }

    public ResourceDepositRule worldgen(ResourceDepositRule rule) {
        service(BuildCraftServices.WORLDGEN).register(rule, registrationContext);
        return rule;
    }

    /** Add standard BuildCraft oil generation to one dimension with default frequency. */
    public ResourceDepositRule oilInDimension(String path, ResourceLocation dimension) {
        return oilInDimension(path, dimension, 1.0);
    }

    /** Add standard BuildCraft oil generation to one dimension with a frequency multiplier. */
    public ResourceDepositRule oilInDimension(String path, ResourceLocation dimension, double frequencyMultiplier) {
        ResourceDepositRule rule = ResourceDepositRule.builder(id(path), BuildCraftContentIds.Worldgen.STANDARD_OIL)
            .target(WorldTargetSelector.builder().dimension(Objects.requireNonNull(dimension, "dimension")).build())
            .frequencyMultiplier(frequencyMultiplier)
            .priority(priority)
            .build();
        return worldgen(rule);
    }

    private <T> T service(buildcraft.api.v2.service.ServiceKey<T> key) {
        return BuildCraftApi.service(key);
    }

    private static <T> T require(RegistryKey<T> key, ResourceLocation id, String kind) {
        ApiRegistry<T> registry = BuildCraftApi.registry(key);
        T value = registry.get(Objects.requireNonNull(id, "id"));
        if (value == null) throw new IllegalStateException("Unknown BuildCraft " + kind + " archetype: " + id);
        return value;
    }

    private static String validateNamespace(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        if (namespace.isBlank()) throw new IllegalArgumentException("namespace must not be blank");
        ResourceLocation probe = ResourceLocation.tryParse(namespace + ":probe");
        if (probe == null || !probe.getNamespace().equals(namespace)) {
            throw new IllegalArgumentException("Invalid addon namespace: " + namespace);
        }
        return namespace;
    }
}
