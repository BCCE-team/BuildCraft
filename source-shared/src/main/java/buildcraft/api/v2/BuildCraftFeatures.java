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
    public static final ResourceLocation ENERGY = id("energy");
    public static final ResourceLocation DATA_DOMAINS = id("data_domains");
    public static final ResourceLocation PIPES = id("pipes");
    public static final ResourceLocation STATEMENTS = id("statements");
    public static final ResourceLocation SIGNALS = id("signals");
    public static final ResourceLocation AUTOMATION = id("automation");
    public static final ResourceLocation ROBOTS = id("robots");
    public static final ResourceLocation SCHEMATICS = id("schematics");
    public static final ResourceLocation MACHINES = id("machines");
    public static final ResourceLocation CLIENT_PRESENTATION = id("client_presentation");
    public static final ResourceLocation CONTENT_EXTENSION = id("content_extension");
    public static final ResourceLocation GUIDE = id("guide");
    public static final ResourceLocation WORLDGEN = id("worldgen");

    private BuildCraftFeatures() {}

    private static ResourceLocation id(String path) {
        return Objects.requireNonNull(ResourceLocation.tryParse("buildcraft:" + path));
    }
}
