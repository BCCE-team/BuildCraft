package buildcraft.api.v2;

import buildcraft.api.v2.automation.AutomationActionType;
import buildcraft.api.v2.automation.StripesHandler;
import buildcraft.api.v2.block.PaintHandler;
import buildcraft.api.v2.block.RotationHandler;
import buildcraft.api.v2.client.ContentPresentation;
import buildcraft.api.v2.client.ParameterPresentation;
import buildcraft.api.v2.client.PipePresentation;
import buildcraft.api.v2.client.StatementPresentation;
import buildcraft.api.v2.robot.RobotEventListener;
import buildcraft.api.v2.map.MapLocationAdapter;
import buildcraft.api.v2.item.ItemLabelAdapter;
import buildcraft.api.v2.energy.MjConnectionRule;
import buildcraft.api.v2.drop.FluidDropProvider;
import buildcraft.api.v2.debug.DebugContributor;
import buildcraft.api.v2.facade.FacadeMaterialAdapter;
import buildcraft.api.v2.fluid.FluidItemAdapter;
import buildcraft.api.v2.filler.FillerPatternType;
import buildcraft.api.v2.list.ListMatchAdapter;
import buildcraft.api.v2.machine.EngineType;
import buildcraft.api.v2.machine.MachineComponentType;
import buildcraft.api.v2.machine.MachineType;
import buildcraft.api.v2.machine.MachineProperty;
import buildcraft.api.v2.machine.LaserTableType;
import buildcraft.api.v2.pipe.PipeAttachmentType;
import buildcraft.api.v2.pipe.PipeComponentType;
import buildcraft.api.v2.pipe.PipeConnectionRule;
import buildcraft.api.v2.pipe.PipeSyncChannel;
import buildcraft.api.v2.pipe.PipeType;
import buildcraft.api.v2.registry.RegistryKey;
import buildcraft.api.v2.robot.DockPortType;
import buildcraft.api.v2.robot.RobotBoardType;
import buildcraft.api.v2.robot.RobotResourceType;
import buildcraft.api.v2.robot.RobotTaskType;
import buildcraft.api.v2.schematic.EntitySchematicAdapter;
import buildcraft.api.v2.schematic.InventoryCopyPolicy;
import buildcraft.api.v2.schematic.SchematicAdapter;
import buildcraft.api.v2.schematic.SnapshotElementType;
import buildcraft.api.v2.signal.SignalChannelType;
import buildcraft.api.v2.statement.ActionType;
import buildcraft.api.v2.statement.ParameterType;
import buildcraft.api.v2.statement.StatementContributor;
import buildcraft.api.v2.statement.TriggerType;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Stable registry keys for immutable extension types. */
public final class BuildCraftRegistries {
    public static final RegistryKey<EngineType> ENGINE_TYPES = key("engine_types");
    public static final RegistryKey<MachineType> MACHINE_TYPES = key("machine_types");
    public static final RegistryKey<MachineComponentType<?>> MACHINE_COMPONENT_TYPES = key("machine_component_types");
    public static final RegistryKey<MachineProperty<?>> MACHINE_PROPERTIES = key("machine_properties");
    public static final RegistryKey<LaserTableType> LASER_TABLE_TYPES = key("laser_table_types");
    public static final RegistryKey<MjConnectionRule> MJ_CONNECTION_RULES = key("mj_connection_rules");


    public static final RegistryKey<PipeType> PIPE_TYPES = key("pipe_types");
    public static final RegistryKey<PipeComponentType<?>> PIPE_COMPONENT_TYPES = key("pipe_component_types");
    public static final RegistryKey<PipeAttachmentType<?>> PIPE_ATTACHMENT_TYPES = key("pipe_attachment_types");
    public static final RegistryKey<PipeConnectionRule> PIPE_CONNECTION_RULES = key("pipe_connection_rules");
    public static final RegistryKey<PipeSyncChannel<?>> PIPE_SYNC_CHANNELS = key("pipe_sync_channels");

    public static final RegistryKey<ParameterType<?>> PARAMETER_TYPES = key("parameter_types");
    public static final RegistryKey<ActionType> ACTION_TYPES = key("action_types");
    public static final RegistryKey<TriggerType> TRIGGER_TYPES = key("trigger_types");
    public static final RegistryKey<StatementContributor> STATEMENT_CONTRIBUTORS = key("statement_contributors");

    public static final RegistryKey<FillerPatternType> FILLER_PATTERN_TYPES = key("filler_pattern_types");
    public static final RegistryKey<SignalChannelType<?>> SIGNAL_CHANNEL_TYPES = key("signal_channel_types");
    public static final RegistryKey<AutomationActionType<?>> AUTOMATION_ACTION_TYPES = key("automation_action_types");
    public static final RegistryKey<StripesHandler> STRIPES_HANDLERS = key("stripes_handlers");

    public static final RegistryKey<RobotTaskType<?>> ROBOT_TASK_TYPES = key("robot_task_types");
    public static final RegistryKey<RobotResourceType<?>> ROBOT_RESOURCE_TYPES = key("robot_resource_types");
    public static final RegistryKey<DockPortType<?>> ROBOT_DOCK_PORT_TYPES = key("robot_dock_port_types");
    public static final RegistryKey<RobotBoardType> ROBOT_BOARD_TYPES = key("robot_board_types");
    public static final RegistryKey<RobotEventListener> ROBOT_EVENT_LISTENERS = key("robot_event_listeners");

    public static final RegistryKey<SchematicAdapter> SCHEMATIC_ADAPTERS = key("schematic_adapters");
    public static final RegistryKey<EntitySchematicAdapter> SCHEMATIC_ENTITY_ADAPTERS = key("schematic_entity_adapters");
    public static final RegistryKey<SnapshotElementType<?>> SNAPSHOT_ELEMENT_TYPES = key("snapshot_element_types");
    public static final RegistryKey<InventoryCopyPolicy> INVENTORY_COPY_POLICIES = key("inventory_copy_policies");

    public static final RegistryKey<FacadeMaterialAdapter> FACADE_MATERIAL_ADAPTERS = key("facade_material_adapters");
    public static final RegistryKey<ListMatchAdapter> LIST_MATCH_ADAPTERS = key("list_match_adapters");
    public static final RegistryKey<ItemLabelAdapter> ITEM_LABEL_ADAPTERS = key("item_label_adapters");
    public static final RegistryKey<FluidItemAdapter> FLUID_ITEM_ADAPTERS = key("fluid_item_adapters");
    public static final RegistryKey<MapLocationAdapter> MAP_LOCATION_ADAPTERS = key("map_location_adapters");
    public static final RegistryKey<FluidDropProvider> FLUID_DROP_PROVIDERS = key("fluid_drop_providers");
    public static final RegistryKey<DebugContributor> DEBUG_CONTRIBUTORS = key("debug_contributors");
    public static final RegistryKey<RotationHandler> ROTATION_HANDLERS = key("rotation_handlers");
    public static final RegistryKey<PaintHandler> PAINT_HANDLERS = key("paint_handlers");

    public static final RegistryKey<ContentPresentation> CLIENT_PRESENTATIONS = key("client_presentations");
    public static final RegistryKey<PipePresentation> PIPE_PRESENTATIONS = key("pipe_presentations");
    public static final RegistryKey<StatementPresentation> STATEMENT_PRESENTATIONS = key("statement_presentations");
    public static final RegistryKey<ParameterPresentation> PARAMETER_PRESENTATIONS = key("parameter_presentations");

    private BuildCraftRegistries() {}

    private static <T> RegistryKey<T> key(String path) {
        return new RegistryKey<>(Objects.requireNonNull(ResourceLocation.tryParse("buildcraft:" + path)));
    }
}
