package buildcraft.api.v2;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Stable feature identifiers for API contract negotiation. A feature means that the public contract is present at
 * the advertised level; callers must still query the matching service when they need a live runtime backend. This
 * distinction keeps implementation domains independent behind an already-stable API surface.
 */
public final class BuildCraftFeatures {
    public static final ResourceLocation REGISTRIES = id("registries");
    public static final ResourceLocation PERSISTENCE = id("persistence");
    public static final ResourceLocation RELOAD = id("reload");
    public static final ResourceLocation TRANSFER = id("transfer");
    public static final ResourceLocation PERMISSIONS = id("permissions");
    public static final ResourceLocation WORLD_CONTEXT = id("world_context");
    public static final ResourceLocation WORLD_EVENTS = id("world_events");
    public static final ResourceLocation ENERGY = id("energy");
    public static final ResourceLocation DATA_DOMAINS = id("data_domains");
    public static final ResourceLocation PIPES = id("pipes");
    public static final ResourceLocation PIPE_FLUID_MOVEMENT = id("pipe_fluid_movement");
    public static final ResourceLocation PIPE_POWER_MOVEMENT = id("pipe_power_movement");
    public static final ResourceLocation PIPE_ENDPOINTS = id("pipe_endpoints");
    public static final ResourceLocation PIPE_ATTACHMENT_PORTS = id("pipe_attachment_ports");
    public static final ResourceLocation STATEMENTS = id("statements");
    public static final ResourceLocation SIGNALS = id("signals");
    public static final ResourceLocation AUTOMATION = id("automation");
    public static final ResourceLocation ROBOTS = id("robots");
    public static final ResourceLocation ROBOT_BOARD_PROGRAMS = id("robot_board_programs");
    public static final ResourceLocation LOGISTICS = id("logistics");
    public static final ResourceLocation SCHEMATICS = id("schematics");
    public static final ResourceLocation LIBRARY_FORMATS = id("library_formats");
    public static final ResourceLocation MACHINES = id("machines");
    public static final ResourceLocation CLIENT_PRESENTATION = id("client_presentation");
    public static final ResourceLocation CONTENT_EXTENSION = id("content_extension");
    public static final ResourceLocation GUIDE = id("guide");
    public static final ResourceLocation WORLDGEN = id("worldgen");
    public static final ResourceLocation FACADE_PHASES = id("facade_phases");
    public static final ResourceLocation NAMED_ITEM_LISTS = id("named_item_lists");
    public static final ResourceLocation MAP_LOCATIONS = id("map_locations");
    public static final ResourceLocation WRENCH_ADAPTERS = id("wrench_adapters");
    public static final ResourceLocation CROPS = id("crops");
    public static final ResourceLocation PAINT = id("paint");

    private BuildCraftFeatures() {}

    private static ResourceLocation id(String path) {
        return Objects.requireNonNull(ResourceLocation.tryParse("buildcraft:" + path));
    }
}
