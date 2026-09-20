package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.ApiFeature;
import buildcraft.api.v2.ApiFeatureSet;
import buildcraft.api.v2.ApiLifecycle;
import buildcraft.api.v2.ApiRuntime;
import buildcraft.api.v2.ApiVersion;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftFeatures;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.registry.ApiRegistry;
import buildcraft.api.v2.machine.BuiltInMachineProperties;
import buildcraft.api.v2.machine.MachineProperty;
import buildcraft.api.v2.registry.RegistryKey;
import buildcraft.lib.internal.api.v2.registry.ApiRegistryImpl;
import buildcraft.api.v2.service.ServiceKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** Internal runtime implementation. Public callers see only ApiRuntime contracts. */
public final class BuildCraftApiRuntime implements ApiRuntime {
    public static final BuildCraftApiRuntime INSTANCE = new BuildCraftApiRuntime();

    private final Map<ResourceLocation, ApiRegistryImpl<?>> registries = new LinkedHashMap<>();
    private volatile ApiLifecycle lifecycle = ApiLifecycle.DISCOVERY;
    private final Map<ServiceKey<?>, Object> services = new LinkedHashMap<>();
    private final ApiFeatureSet features = ApiFeatureSet.of(List.of(
        new ApiFeature(BuildCraftFeatures.REGISTRIES, 1),
        new ApiFeature(BuildCraftFeatures.PERSISTENCE, 1),
        new ApiFeature(BuildCraftFeatures.RELOAD, 1),
        new ApiFeature(BuildCraftFeatures.TRANSFER, 1),
        new ApiFeature(BuildCraftFeatures.PERMISSIONS, 1),
        new ApiFeature(BuildCraftFeatures.ENERGY, 1),
        new ApiFeature(BuildCraftFeatures.DATA_DOMAINS, 1),
        new ApiFeature(BuildCraftFeatures.PIPES, 1),
        new ApiFeature(BuildCraftFeatures.STATEMENTS, 1),
        new ApiFeature(BuildCraftFeatures.SIGNALS, 1),
        new ApiFeature(BuildCraftFeatures.AUTOMATION, 1),
        new ApiFeature(BuildCraftFeatures.ROBOTS, 1),
        new ApiFeature(BuildCraftFeatures.SCHEMATICS, 1),
        new ApiFeature(BuildCraftFeatures.MACHINES, 1),
        new ApiFeature(BuildCraftFeatures.CLIENT_PRESENTATION, 1),
        new ApiFeature(BuildCraftFeatures.CONTENT_EXTENSION, 1),
        new ApiFeature(BuildCraftFeatures.GUIDE, 1),
        new ApiFeature(BuildCraftFeatures.WORLDGEN, 1)
    ));

    private final EnergyServiceImpl energy = new EnergyServiceImpl();
    private final PermissionServiceRegistryImpl permissions = new PermissionServiceRegistryImpl();
    private final EnergyFluidRegistryImpl energyFluids = new EnergyFluidRegistryImpl();
    private final MachineRecipeRegistryImpl machineRecipes = new MachineRecipeRegistryImpl();
    private final CropServiceImpl crops = new CropServiceImpl();
    private final TemplateServiceImpl templates = new TemplateServiceImpl();
    private final FacadeRuleRegistryImpl facadeRules = new FacadeRuleRegistryImpl();
    private final WorldPropertyServiceImpl worldProperties = new WorldPropertyServiceImpl();
    private final WorldRuleServiceImpl worldRules = new WorldRuleServiceImpl();
    private final ApiDiagnosticsImpl diagnostics = new ApiDiagnosticsImpl();
    private final GuideServiceImpl guide = new GuideServiceImpl();
    private final WorldgenServiceImpl worldgen = new WorldgenServiceImpl();
    private final StatementServiceImpl statements = StatementServiceImpl.INSTANCE;
    private final FillerPatternServiceImpl fillerPatterns = FillerPatternServiceImpl.INSTANCE;
    private final MachineServiceImpl machines = new MachineServiceImpl();
    private final LaserTargetServiceImpl laserTargets = new LaserTargetServiceImpl();
    private final MjFormatterImpl mjFormatter = new MjFormatterImpl();
    private final PowerLossEffectServiceImpl powerLossEffects = new PowerLossEffectServiceImpl();
    private final AutomationServiceImpl automation = new AutomationServiceImpl();
    private final ActorServiceImpl actors = new ActorServiceImpl();
    private final ModuleServiceImpl modules = new ModuleServiceImpl();
    private final WrenchServiceImpl wrenches = new WrenchServiceImpl();
    private final BlockInteractionServiceImpl blockInteractions = new BlockInteractionServiceImpl();
    private final DebugServiceImpl debugViews = new DebugServiceImpl();
    private final ItemListServiceImpl itemLists = new ItemListServiceImpl();
    private final ItemLabelServiceImpl itemLabels = new ItemLabelServiceImpl();
    private final MapLocationServiceImpl mapLocations = new MapLocationServiceImpl();
    private final FluidDropServiceImpl fluidDrops = new FluidDropServiceImpl();
    private final FacadeServiceImpl facades = new FacadeServiceImpl();
    private final ClientPresentationServiceImpl clientPresentations = new ClientPresentationServiceImpl();

    private BuildCraftApiRuntime() {
        services.put(BuildCraftServices.ENERGY, energy);
        services.put(BuildCraftServices.PERMISSIONS, permissions);
        services.put(BuildCraftServices.ENERGY_FLUIDS, energyFluids);
        services.put(BuildCraftServices.MACHINE_RECIPES, machineRecipes);
        services.put(BuildCraftServices.CROPS, crops);
        services.put(BuildCraftServices.TEMPLATES, templates);
        services.put(BuildCraftServices.FACADE_RULES, facadeRules);
        services.put(BuildCraftServices.WORLD_PROPERTIES, worldProperties);
        services.put(BuildCraftServices.WORLD_RULES, worldRules);
        services.put(BuildCraftServices.DIAGNOSTICS, diagnostics);
        services.put(BuildCraftServices.GUIDE, guide);
        services.put(BuildCraftServices.WORLDGEN, worldgen);
        services.put(BuildCraftServices.STATEMENTS, statements);
        services.put(BuildCraftServices.FILLER_PATTERNS, fillerPatterns);
        services.put(BuildCraftServices.MACHINES, machines);
        services.put(BuildCraftServices.LASER_TARGETS, laserTargets);
        services.put(BuildCraftServices.MJ_FORMATTER, mjFormatter);
        services.put(BuildCraftServices.POWER_LOSS_EFFECTS, powerLossEffects);
        services.put(BuildCraftServices.AUTOMATION, automation);
        services.put(BuildCraftServices.ACTORS, actors);
        services.put(BuildCraftServices.MODULES, modules);
        services.put(BuildCraftServices.WRENCHES, wrenches);
        services.put(BuildCraftServices.BLOCK_INTERACTIONS, blockInteractions);
        services.put(BuildCraftServices.DEBUG_VIEWS, debugViews);
        services.put(BuildCraftServices.ITEM_LISTS, itemLists);
        services.put(BuildCraftServices.ITEM_LABELS, itemLabels);
        services.put(BuildCraftServices.MAP_LOCATIONS, mapLocations);
        services.put(BuildCraftServices.FLUID_DROPS, fluidDrops);
        services.put(BuildCraftServices.FACADES, facades);
        services.put(BuildCraftServices.CLIENT_PRESENTATIONS, clientPresentations);

        registerRegistry(BuildCraftRegistries.ENGINE_TYPES);
        registerRegistry(BuildCraftRegistries.MACHINE_TYPES);
        registerRegistry(BuildCraftRegistries.MACHINE_COMPONENT_TYPES);
        registerRegistry(BuildCraftRegistries.MACHINE_PROPERTIES);
        registerRegistry(BuildCraftRegistries.LASER_TABLE_TYPES);
        registerRegistry(BuildCraftRegistries.MJ_CONNECTION_RULES);
        registerRegistry(BuildCraftRegistries.PIPE_TYPES);
        registerRegistry(BuildCraftRegistries.PIPE_COMPONENT_TYPES);
        registerRegistry(BuildCraftRegistries.PIPE_ATTACHMENT_TYPES);
        registerRegistry(BuildCraftRegistries.PIPE_CONNECTION_RULES);
        registerRegistry(BuildCraftRegistries.PIPE_SYNC_CHANNELS);
        registerRegistry(BuildCraftRegistries.PARAMETER_TYPES);
        registerRegistry(BuildCraftRegistries.ACTION_TYPES);
        registerRegistry(BuildCraftRegistries.TRIGGER_TYPES);
        registerRegistry(BuildCraftRegistries.STATEMENT_CONTRIBUTORS);
        registerRegistry(BuildCraftRegistries.FILLER_PATTERN_TYPES);
        registerRegistry(BuildCraftRegistries.SIGNAL_CHANNEL_TYPES);
        registerRegistry(BuildCraftRegistries.AUTOMATION_ACTION_TYPES);
        registerRegistry(BuildCraftRegistries.STRIPES_HANDLERS);
        registerRegistry(BuildCraftRegistries.ROBOT_TASK_TYPES);
        registerRegistry(BuildCraftRegistries.ROBOT_RESOURCE_TYPES);
        registerRegistry(BuildCraftRegistries.ROBOT_DOCK_PORT_TYPES);
        registerRegistry(BuildCraftRegistries.ROBOT_BOARD_TYPES);
        registerRegistry(BuildCraftRegistries.ROBOT_EVENT_LISTENERS);
        registerRegistry(BuildCraftRegistries.SCHEMATIC_ADAPTERS);
        registerRegistry(BuildCraftRegistries.SCHEMATIC_ENTITY_ADAPTERS);
        registerRegistry(BuildCraftRegistries.SNAPSHOT_ELEMENT_TYPES);
        registerRegistry(BuildCraftRegistries.INVENTORY_COPY_POLICIES);
        registerRegistry(BuildCraftRegistries.FACADE_MATERIAL_ADAPTERS);
        registerRegistry(BuildCraftRegistries.LIST_MATCH_ADAPTERS);
        registerRegistry(BuildCraftRegistries.ITEM_LABEL_ADAPTERS);
        registerRegistry(BuildCraftRegistries.MAP_LOCATION_ADAPTERS);
        registerRegistry(BuildCraftRegistries.FLUID_DROP_PROVIDERS);
        registerRegistry(BuildCraftRegistries.DEBUG_CONTRIBUTORS);
        registerRegistry(BuildCraftRegistries.ROTATION_HANDLERS);
        registerRegistry(BuildCraftRegistries.PAINT_HANDLERS);
        registerRegistry(BuildCraftRegistries.CLIENT_PRESENTATIONS);
        registerRegistry(BuildCraftRegistries.PIPE_PRESENTATIONS);
        registerRegistry(BuildCraftRegistries.STATEMENT_PRESENTATIONS);
        registerRegistry(BuildCraftRegistries.PARAMETER_PRESENTATIONS);

        registerBuiltInMachineProperties();
    }


    private void registerBuiltInMachineProperties() {
        ApiRegistry<MachineProperty<?>> registry = registry(BuildCraftRegistries.MACHINE_PROPERTIES)
            .orElseThrow(() -> new IllegalStateException("Machine property registry was not created"));
        for (MachineProperty<?> property : BuiltInMachineProperties.values()) {
            registry.register(property.id(), property, () -> "buildcraft");
        }
    }

    private <T> void registerRegistry(RegistryKey<T> key) {
        if (registries.putIfAbsent(key.id(), new ApiRegistryImpl<T>()) != null) {
            throw new IllegalStateException("Duplicate API registry key: " + key.id());
        }
    }

    /** Installs an internal domain backend before the API is frozen. */
    public synchronized <T> void installService(ServiceKey<T> key, T service) {
        if (lifecycle.ordinal() >= ApiLifecycle.FROZEN.ordinal()) {
            throw new IllegalStateException("Cannot install API service after freeze: " + key.id());
        }
        if (services.putIfAbsent(key, service) != null) {
            throw new IllegalStateException("Duplicate API service: " + key.id());
        }
    }

    public static synchronized void bootstrap() {
        ApiRuntime discovered = BuildCraftApi.runtime();
        if (!(discovered instanceof BuildCraftApiRuntimeProvider provider) || provider.delegate() != INSTANCE) {
            throw new IllegalStateException("BuildCraft API runtime provider did not resolve to the BCCE Lib runtime");
        }
        // Several BuildCraft modules call bootstrap independently during construction.
        // Once Lib has advanced into content registration those later calls are a no-op,
        // never an attempt to move the lifecycle backwards.
        if (INSTANCE.lifecycle == ApiLifecycle.DISCOVERY) {
            INSTANCE.advanceLifecycle(ApiLifecycle.TYPE_REGISTRATION);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<ApiRegistry<T>> registry(ResourceLocation id) {
        return Optional.ofNullable((ApiRegistry<T>) registries.get(id));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> service(ServiceKey<T> key) {
        return Optional.ofNullable((T) services.get(key));
    }

    @Override public ApiVersion version() { return BuildCraftApi.VERSION; }
    @Override public ApiFeatureSet features() { return features; }
    @Override public ApiLifecycle lifecycle() { return lifecycle; }

    /** Advances the internal API lifecycle through its ordered bootstrap phases. */
    public synchronized void advanceLifecycle(ApiLifecycle next) {
        if (next.ordinal() < lifecycle.ordinal()) {
            throw new IllegalStateException("API lifecycle cannot move backwards: " + lifecycle + " -> " + next);
        }
        if (next == lifecycle) return;
        if (next.ordinal() > lifecycle.ordinal() + 1) {
            throw new IllegalStateException("API lifecycle phase skipped: " + lifecycle + " -> " + next);
        }
        if (next == ApiLifecycle.FROZEN) {
            for (ApiRegistryImpl<?> registry : registries.values()) registry.freeze();
        }
        lifecycle = next;
    }

    public EnergyFluidRegistryImpl energyFluids() { return energyFluids; }
    public MachineRecipeRegistryImpl machineRecipes() { return machineRecipes; }
    public CropServiceImpl crops() { return crops; }
    public TemplateServiceImpl templates() { return templates; }
    public FacadeRuleRegistryImpl facadeRules() { return facadeRules; }
}
