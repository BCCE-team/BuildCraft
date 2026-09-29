package buildcraft.api.v2;

import buildcraft.api.v2.automation.AutomationService;
import buildcraft.api.v2.block.BlockInteractionService;
import buildcraft.api.v2.client.ClientPresentationService;
import buildcraft.api.v2.request.RequestService;
import buildcraft.api.v2.map.MapLocationService;
import buildcraft.api.v2.item.ItemLabelService;
import buildcraft.api.v2.drop.FluidDropService;
import buildcraft.api.v2.debug.DebugService;
import buildcraft.api.v2.diagnostics.ApiDiagnostics;
import buildcraft.api.v2.crops.CropService;
import buildcraft.api.v2.energy.EnergyService;
import buildcraft.api.v2.energy.MjFormatter;
import buildcraft.api.v2.energy.PowerLossEffectService;
import buildcraft.api.v2.facade.FacadeRuleService;
import buildcraft.api.v2.facade.FacadeService;
import buildcraft.api.v2.filler.FillerPatternService;
import buildcraft.api.v2.fluid.FluidItemService;
import buildcraft.api.v2.fuels.EnergyFluidService;
import buildcraft.api.v2.gate.GateService;
import buildcraft.api.v2.guide.GuideService;
import buildcraft.api.v2.list.ItemListService;
import buildcraft.api.v2.machine.MachineService;
import buildcraft.api.v2.machine.LaserTargetService;
import buildcraft.api.v2.module.ModuleService;
import buildcraft.api.v2.permission.PermissionServiceRegistry;
import buildcraft.api.v2.permission.ActorService;
import buildcraft.api.v2.pipe.PipeService;
import buildcraft.api.v2.platform.PlatformServices;
import buildcraft.api.v2.recipe.MachineRecipeService;
import buildcraft.api.v2.robot.RobotService;
import buildcraft.api.v2.schematic.SchematicService;
import buildcraft.api.v2.service.ServiceKey;
import buildcraft.api.v2.signal.SignalService;
import buildcraft.api.v2.statement.StatementService;
import buildcraft.api.v2.template.TemplateService;
import buildcraft.api.v2.tool.WrenchService;
import buildcraft.api.v2.world.WorldPropertyService;
import buildcraft.api.v2.world.WorldRuleService;
import buildcraft.api.v2.worldgen.WorldgenService;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Stable keys for services provided by the BuildCraft API 2 runtime. */
public final class BuildCraftServices {
    public static final ServiceKey<EnergyService> ENERGY = ServiceKey.of(id("energy"));
    public static final ServiceKey<PermissionServiceRegistry> PERMISSIONS = ServiceKey.of(id("permissions"));
    public static final ServiceKey<EnergyFluidService> ENERGY_FLUIDS = ServiceKey.of(id("energy_fluids"));
    public static final ServiceKey<MachineRecipeService> MACHINE_RECIPES = ServiceKey.of(id("machine_recipes"));
    public static final ServiceKey<CropService> CROPS = ServiceKey.of(id("crops"));
    public static final ServiceKey<TemplateService> TEMPLATES = ServiceKey.of(id("templates"));
    public static final ServiceKey<FacadeRuleService> FACADE_RULES = ServiceKey.of(id("facade_rules"));
    public static final ServiceKey<WorldPropertyService> WORLD_PROPERTIES = ServiceKey.of(id("world_properties"));
    public static final ServiceKey<WorldRuleService> WORLD_RULES = ServiceKey.of(id("world_rules"));
    public static final ServiceKey<GuideService> GUIDE = ServiceKey.of(id("guide"));
    public static final ServiceKey<WorldgenService> WORLDGEN = ServiceKey.of(id("worldgen"));

    public static final ServiceKey<ModuleService> MODULES = ServiceKey.of(id("modules"));
    public static final ServiceKey<ActorService> ACTORS = ServiceKey.of(id("actors"));
    public static final ServiceKey<ApiDiagnostics> DIAGNOSTICS = ServiceKey.of(id("diagnostics"));
    public static final ServiceKey<BlockInteractionService> BLOCK_INTERACTIONS = ServiceKey.of(id("block_interactions"));
    public static final ServiceKey<WrenchService> WRENCHES = ServiceKey.of(id("wrenches"));
    public static final ServiceKey<ItemListService> ITEM_LISTS = ServiceKey.of(id("item_lists"));
    public static final ServiceKey<ItemLabelService> ITEM_LABELS = ServiceKey.of(id("item_labels"));
    public static final ServiceKey<FluidItemService> FLUID_ITEMS = ServiceKey.of(id("fluid_items"));
    public static final ServiceKey<MapLocationService> MAP_LOCATIONS = ServiceKey.of(id("map_locations"));
    public static final ServiceKey<RequestService> REQUESTS = ServiceKey.of(id("requests"));
    public static final ServiceKey<FluidDropService> FLUID_DROPS = ServiceKey.of(id("fluid_drops"));
    public static final ServiceKey<DebugService> DEBUG_VIEWS = ServiceKey.of(id("debug_views"));
    public static final ServiceKey<FacadeService> FACADES = ServiceKey.of(id("facades"));
    public static final ServiceKey<FillerPatternService> FILLER_PATTERNS = ServiceKey.of(id("filler_patterns"));
    public static final ServiceKey<StatementService> STATEMENTS = ServiceKey.of(id("statements"));
    public static final ServiceKey<GateService> GATES = ServiceKey.of(id("gates"));
    public static final ServiceKey<SignalService> SIGNALS = ServiceKey.of(id("signals"));
    public static final ServiceKey<AutomationService> AUTOMATION = ServiceKey.of(id("automation"));
    public static final ServiceKey<PipeService> PIPES = ServiceKey.of(id("pipes"));
    public static final ServiceKey<MachineService> MACHINES = ServiceKey.of(id("machines"));
    public static final ServiceKey<LaserTargetService> LASER_TARGETS = ServiceKey.of(id("laser_targets"));
    public static final ServiceKey<RobotService> ROBOTS = ServiceKey.of(id("robots"));
    public static final ServiceKey<SchematicService> SCHEMATICS = ServiceKey.of(id("schematics"));
    public static final ServiceKey<ClientPresentationService> CLIENT_PRESENTATIONS = ServiceKey.of(id("client_presentations"));
    public static final ServiceKey<PlatformServices> PLATFORM = ServiceKey.of(id("platform"));
    public static final ServiceKey<MjFormatter> MJ_FORMATTER = ServiceKey.of(id("mj_formatter"));
    public static final ServiceKey<PowerLossEffectService> POWER_LOSS_EFFECTS = ServiceKey.of(id("power_loss_effects"));

    private BuildCraftServices() {}

    private static ResourceLocation id(String path) {
        return Objects.requireNonNull(ResourceLocation.tryParse("buildcraft:" + path));
    }
}
