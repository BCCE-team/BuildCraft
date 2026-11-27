package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.ApiFeature;
import buildcraft.api.v2.ApiFeatureSet;
import buildcraft.api.v2.ApiRuntime;
import buildcraft.api.v2.BuildCraftFeatures;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.registry.RegistryKey;
import buildcraft.api.v2.service.ServiceKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Shared availability policy for every loader. Declared levels are upper bounds, not promises of installed backends. */
final class RuntimeFeatures {
    private record Requirements(List<ServiceKey<?>> services, List<RegistryKey<?>> registries) {
        boolean available(ApiRuntime runtime) {
            return services.stream().allMatch(key -> runtime.service(key).isPresent())
                && registries.stream().allMatch(key -> runtime.registry(key).isPresent());
        }
    }

    private static Requirements needs(List<ServiceKey<?>> services, RegistryKey<?>... registries) {
        return new Requirements(List.copyOf(services), List.of(registries));
    }

    private static final Map<ResourceLocation, Requirements> REQUIREMENTS = Map.ofEntries(
        Map.entry(BuildCraftFeatures.REGISTRIES, needs(List.of(), BuildCraftRegistries.MACHINE_PROPERTIES)),
        // Persistence codecs are self-contained public contracts, not a world/save service.
        Map.entry(BuildCraftFeatures.PERSISTENCE, needs(List.of())),
        Map.entry(BuildCraftFeatures.RELOAD, needs(List.of(BuildCraftServices.DEFINITION_RELOAD))),
        Map.entry(BuildCraftFeatures.TRANSFER, needs(List.of(BuildCraftServices.PLATFORM))),
        Map.entry(BuildCraftFeatures.PERMISSIONS, needs(List.of(BuildCraftServices.PERMISSIONS, BuildCraftServices.ACTORS))),
        Map.entry(BuildCraftFeatures.WORLD_CONTEXT, needs(List.of(BuildCraftServices.WORLD_ACCESS, BuildCraftServices.WORLD_PROPERTIES, BuildCraftServices.WORLD_RULES, BuildCraftServices.ACTORS))),
        Map.entry(BuildCraftFeatures.WORLD_EVENTS, needs(List.of(BuildCraftServices.WORLD_EVENTS))),
        Map.entry(BuildCraftFeatures.ENERGY, needs(List.of(BuildCraftServices.ENERGY, BuildCraftServices.MJ_FORMATTER, BuildCraftServices.POWER_LOSS_EFFECTS), BuildCraftRegistries.MJ_CONNECTION_RULES)),
        Map.entry(BuildCraftFeatures.DATA_DOMAINS, needs(List.of(BuildCraftServices.ENERGY_FLUIDS, BuildCraftServices.MACHINE_RECIPES, BuildCraftServices.CROPS, BuildCraftServices.TEMPLATES, BuildCraftServices.FACADE_RULES))),
        Map.entry(BuildCraftFeatures.PIPES, needs(List.of(BuildCraftServices.PIPES), BuildCraftRegistries.PIPE_TYPES, BuildCraftRegistries.PIPE_COMPONENT_TYPES, BuildCraftRegistries.PIPE_ATTACHMENT_TYPES, BuildCraftRegistries.PIPE_CONNECTION_RULES)),
        Map.entry(BuildCraftFeatures.PIPE_FLUID_MOVEMENT, needs(List.of(BuildCraftServices.PIPES, BuildCraftServices.PIPE_RESOURCES), BuildCraftRegistries.PIPE_TYPES)),
        Map.entry(BuildCraftFeatures.PIPE_POWER_MOVEMENT, needs(List.of(BuildCraftServices.PIPES, BuildCraftServices.PIPE_RESOURCES), BuildCraftRegistries.PIPE_TYPES)),
        Map.entry(BuildCraftFeatures.PIPE_ENDPOINTS, needs(List.of(BuildCraftServices.PIPES), BuildCraftRegistries.PIPE_ENDPOINT_PROVIDERS)),
        Map.entry(BuildCraftFeatures.PIPE_ATTACHMENT_PORTS, needs(List.of(BuildCraftServices.PIPES), BuildCraftRegistries.PIPE_ATTACHMENT_TYPES)),
        Map.entry(BuildCraftFeatures.STATEMENTS, needs(List.of(BuildCraftServices.STATEMENTS, BuildCraftServices.GATES), BuildCraftRegistries.PARAMETER_TYPES, BuildCraftRegistries.ACTION_TYPES, BuildCraftRegistries.TRIGGER_TYPES, BuildCraftRegistries.STATEMENT_CONTRIBUTORS)),
        Map.entry(BuildCraftFeatures.SIGNALS, needs(List.of(BuildCraftServices.SIGNALS), BuildCraftRegistries.SIGNAL_CHANNEL_TYPES)),
        Map.entry(BuildCraftFeatures.AUTOMATION, needs(List.of(BuildCraftServices.AUTOMATION), BuildCraftRegistries.AUTOMATION_ACTION_TYPES, BuildCraftRegistries.STRIPES_HANDLERS)),
        Map.entry(BuildCraftFeatures.ROBOTS, needs(List.of(BuildCraftServices.ROBOTS), BuildCraftRegistries.ROBOT_TASK_TYPES, BuildCraftRegistries.ROBOT_RESOURCE_TYPES, BuildCraftRegistries.ROBOT_DOCK_PORT_TYPES, BuildCraftRegistries.ROBOT_BOARD_TYPES)),
        Map.entry(BuildCraftFeatures.ROBOT_BOARD_PROGRAMS, needs(List.of(BuildCraftServices.ROBOTS), BuildCraftRegistries.ROBOT_BOARD_TYPES, BuildCraftRegistries.ROBOT_BOARD_PROGRAMS)),
        Map.entry(BuildCraftFeatures.LOGISTICS, needs(List.of(BuildCraftServices.REQUESTS))),
        Map.entry(BuildCraftFeatures.SCHEMATICS, needs(List.of(BuildCraftServices.SCHEMATICS), BuildCraftRegistries.SCHEMATIC_ADAPTERS, BuildCraftRegistries.SCHEMATIC_ENTITY_ADAPTERS, BuildCraftRegistries.SNAPSHOT_ELEMENT_TYPES, BuildCraftRegistries.INVENTORY_COPY_POLICIES)),
        Map.entry(BuildCraftFeatures.LIBRARY_FORMATS, needs(List.of(BuildCraftServices.LIBRARY))),
        Map.entry(BuildCraftFeatures.MACHINES, needs(List.of(BuildCraftServices.MACHINES, BuildCraftServices.ENGINES, BuildCraftServices.LASER_TARGETS), BuildCraftRegistries.MACHINE_TYPES, BuildCraftRegistries.ENGINE_TYPES, BuildCraftRegistries.MACHINE_PROPERTIES, BuildCraftRegistries.LASER_TABLE_TYPES)),
        Map.entry(BuildCraftFeatures.CLIENT_PRESENTATION, needs(List.of(BuildCraftServices.CLIENT_PRESENTATIONS), BuildCraftRegistries.CLIENT_PRESENTATIONS, BuildCraftRegistries.PIPE_PRESENTATIONS, BuildCraftRegistries.STATEMENT_PRESENTATIONS, BuildCraftRegistries.PARAMETER_PRESENTATIONS)),
        Map.entry(BuildCraftFeatures.CONTENT_EXTENSION, needs(List.of(BuildCraftServices.MODULES), BuildCraftRegistries.MACHINE_TYPES)),
        Map.entry(BuildCraftFeatures.GUIDE, needs(List.of(BuildCraftServices.GUIDE))),
        Map.entry(BuildCraftFeatures.WORLDGEN, needs(List.of(BuildCraftServices.WORLDGEN))),
        Map.entry(BuildCraftFeatures.FACADE_PHASES, needs(List.of(BuildCraftServices.FACADES, BuildCraftServices.FACADE_RULES), BuildCraftRegistries.FACADE_MATERIAL_ADAPTERS)),
        Map.entry(BuildCraftFeatures.NAMED_ITEM_LISTS, needs(List.of(BuildCraftServices.ITEM_LISTS), BuildCraftRegistries.NAMED_ITEM_LISTS, BuildCraftRegistries.LIST_MATCH_ADAPTERS)),
        Map.entry(BuildCraftFeatures.MAP_LOCATIONS, needs(List.of(BuildCraftServices.MAP_LOCATIONS), BuildCraftRegistries.MAP_LOCATION_ADAPTERS)),
        Map.entry(BuildCraftFeatures.WRENCH_ADAPTERS, needs(List.of(BuildCraftServices.WRENCHES), BuildCraftRegistries.WRENCH_ADAPTERS)),
        Map.entry(BuildCraftFeatures.CROPS, needs(List.of(BuildCraftServices.CROPS))),
        Map.entry(BuildCraftFeatures.PAINT, needs(List.of(BuildCraftServices.BLOCK_INTERACTIONS), BuildCraftRegistries.PAINT_HANDLERS)),
        Map.entry(BuildCraftFeatures.CONFIGURATION, needs(List.of(BuildCraftServices.CONFIGURATION)))
    );

    private static final Map<ResourceLocation, Requirements> LEVEL_TWO = Map.of(
        BuildCraftFeatures.PIPES, needs(List.of(BuildCraftServices.PIPE_RESOURCES), BuildCraftRegistries.PIPE_FLOW_TYPES, BuildCraftRegistries.PIPE_BEHAVIOUR_TYPES, BuildCraftRegistries.PIPE_ENDPOINT_PROVIDERS, BuildCraftRegistries.PIPE_SYNC_CHANNELS),
        BuildCraftFeatures.ROBOTS, needs(List.of(BuildCraftServices.ROBOTS), BuildCraftRegistries.ROBOT_BOARD_PROGRAMS, BuildCraftRegistries.ROBOT_EVENT_LISTENERS),
        BuildCraftFeatures.MACHINES, needs(List.of(), BuildCraftRegistries.MACHINE_COMPONENT_TYPES),
        BuildCraftFeatures.CLIENT_PRESENTATION, needs(List.of(), BuildCraftRegistries.DYNAMIC_PRESENTATIONS, BuildCraftRegistries.MENU_PRESENTATIONS),
        BuildCraftFeatures.RELOAD, needs(List.of(BuildCraftServices.DEFINITION_RELOAD))
    );

    private RuntimeFeatures() {}

    /** Fail closed on unknown features/levels. Optional modules need not be installed. */
    static ApiFeatureSet available(ApiRuntime runtime, ApiFeatureSet declared) {
        List<ApiFeature> available = new ArrayList<>();
        for (ApiFeature feature : declared.all()) {
            Requirements required = REQUIREMENTS.get(feature.id());
            if (required == null || !required.available(runtime)) continue;
            if (feature.id().equals(BuildCraftFeatures.TRANSFER) && !runtime.service(BuildCraftServices.PLATFORM)
                .map(platform -> platform.itemTransfer().isPresent() && platform.fluidTransfer().isPresent()
                    && platform.energyTransfer().isPresent()).orElse(false)) continue;
            Requirements levelTwo = LEVEL_TWO.get(feature.id());
            int level = feature.level() >= 2 && levelTwo != null && levelTwo.available(runtime) ? 2 : 1;
            available.add(new ApiFeature(feature.id(), level));
        }
        return ApiFeatureSet.of(available);
    }
}
