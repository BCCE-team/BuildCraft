package buildcraft.transport.api2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.pipe.ExternalEnergyTransportProfile;
import buildcraft.api.v2.pipe.FluidTransportProfile;
import buildcraft.api.v2.pipe.ItemTransportProfile;
import buildcraft.api.v2.pipe.PipeComponentType;
import buildcraft.api.v2.pipe.PipeMedium;
import buildcraft.api.v2.pipe.PipeType;
import buildcraft.api.v2.pipe.PowerTransportProfile;
import buildcraft.api.v2.registry.ApiRegistry;
import buildcraft.api.v2.registry.RegistrationContext;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pipe.PipeDefinition;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Internal bridge making API2 pipe types authoritative for the legacy-compatible runtime implementation. */
public final class PipeTypeBridge {
    private static final String COMPONENT_PREFIX = "runtime_component/";
    private static final String BUILTIN_NAMESPACE = "buildcrafttransport";
    private static final int DEFAULT_FLUID_BASE_RATE = 10;
    private static final int DEFAULT_MJ_BASE_RATE = 4;
    private static final int DEFAULT_FE_BASE_RATE = 40;

    private PipeTypeBridge() {}

    /** Selects the built-in carrier; custom semantics are supplied exclusively by API components. */
    public static ResourceLocation carrierId(PipeType type) {
        Objects.requireNonNull(type, "type");
        if (type.flowTypeId().isEmpty() && type.media().size() != 1) {
            throw new IllegalArgumentException("Built-in pipe carrier supports one medium: " + type.id());
        }
        if (type.archetypeId().isPresent()) return type.archetypeId().orElseThrow();
        if (type.flowTypeId().isPresent() && type.media().size() != 1) {
            return Objects.requireNonNull(ResourceLocation.tryParse(BUILTIN_NAMESPACE + ":structure"));
        }
        String path = switch (type.media().iterator().next()) {
            case ITEM -> "stone_item";
            case FLUID -> "stone_fluid";
            case MJ -> "stone_power";
            case EXTERNAL_ENERGY -> "stone_fe";
            case STRUCTURE -> "structure";
        };
        return Objects.requireNonNull(ResourceLocation.tryParse(BUILTIN_NAMESPACE + ":" + path));
    }

    /** Rejects mismatched variants before they can join a network with the wrong flow implementation. */
    public static void validateCarrier(PipeType type, PipeDefinition base) {
        if (type.flowTypeId().isPresent()) {
            var flowId = type.flowTypeId().orElseThrow();
            var flow = BuildCraftApi.registry(BuildCraftRegistries.PIPE_FLOW_TYPES).get(flowId);
            if (flow == null) throw new IllegalArgumentException("Unknown custom flow " + flowId + " for " + type.id());
            return; // The custom PipeFlow adapter replaces the carrier's transport implementation.
        }
        if (type.media().size() != 1) {
            throw new IllegalArgumentException("Built-in pipe carrier supports one medium: " + type.id());
        }
        PipeMedium medium = type.media().iterator().next();
        boolean matching = switch (medium) {
            case ITEM -> base.flowType == PipeApi.flowItems;
            case FLUID -> base.flowType == PipeApi.flowFluids;
            case MJ -> base.flowType == PipeApi.flowPower;
            case EXTERNAL_ENERGY -> base.flowType == PipeApi.flowForgeEnergy;
            case STRUCTURE -> base.flowType == PipeApi.flowStructure;
        };
        if (!matching) {
            throw new IllegalArgumentException("Carrier " + base.identifier + " cannot transport " + medium
                + " for " + type.id());
        }
    }

    public static PipeType ensureRegistered(PipeDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        ApiRegistry<PipeType> registry = BuildCraftApi.registry(BuildCraftRegistries.PIPE_TYPES);
        PipeType existing = registry.get(definition.identifier);
        if (existing == null && definition.getApiType() != null) {
            throw new IllegalStateException("API pipe " + definition.identifier
                + " must be registered in PIPE_TYPES before materialization");
        }
        if (existing != null) {
            definition.setApiType(existing);
            return existing;
        }

        ResourceLocation componentId = runtimeComponentId(definition.identifier);
        ensureRuntimeComponent(componentId, definition.identifier.getNamespace());

        PipeType type = inferType(definition, componentId);
        registry.register(definition.identifier, type, owner(definition.identifier.getNamespace()));
        definition.setApiType(type);
        definition.setRuntimeComponentId(componentId);
        return type;
    }

    public static void registerAlias(ResourceLocation alias, PipeDefinition target) {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(target, "target");
        ensureRegistered(target);
        BuildCraftApi.registry(BuildCraftRegistries.PIPE_TYPES)
            .registerAlias(alias, target.identifier, owner(target.identifier.getNamespace()));
    }

    public static ResourceLocation runtimeComponentId(ResourceLocation pipeId) {
        return Objects.requireNonNull(ResourceLocation.tryParse(
            pipeId.getNamespace() + ":" + COMPONENT_PREFIX + pipeId.getPath()
        ));
    }

    private static void ensureRuntimeComponent(ResourceLocation id, String owner) {
        ApiRegistry<PipeComponentType<?>> registry = BuildCraftApi.registry(BuildCraftRegistries.PIPE_COMPONENT_TYPES);
        if (registry.get(id) != null) return;
        PipeComponentType<RuntimePipeComponent> type = new PipeComponentType<>(
            id,
            pipe -> new RuntimePipeComponent(id),
            null
        );
        registry.register(id, type, owner(owner));
    }

    private static PipeType inferType(PipeDefinition definition, ResourceLocation runtimeComponent) {
        PipeType.Builder builder = PipeType.builder(definition.identifier).component(runtimeComponent).colorable(definition.canBeColoured);
        if (definition.flowType == PipeApi.flowItems) {
            // Item throughput is governed by the travelling-item implementation. This profile is intentionally
            // conservative metadata rather than a second source of runtime truth.
            return builder.itemProfile(new ItemTransportProfile(64, 1)).build();
        }
        if (definition.flowType == PipeApi.flowFluids) {
            FluidTransportProfile profile = builtinFluidProfile(definition);
            if (profile == null) {
                PipeApi.FluidTransferInfo info = PipeApi.getFluidTransferInfo(definition);
                profile = new FluidTransportProfile(
                    FluidAmount.of(Math.max(0, info.transferPerTick)),
                    Math.max(1, (int) Math.ceil(info.transferDelayMultiplier))
                );
            }
            return builder.fluidProfile(profile).build();
        }
        if (definition.flowType == PipeApi.flowPower) {
            PowerTransportProfile profile = builtinPowerProfile(definition);
            if (profile == null) {
                PipeApi.PowerTransferInfo info = PipeApi.getPowerTransferInfo(definition);
                profile = new PowerTransportProfile(
                    MjAmount.ofMicro(Math.max(1, info.transferPerTick)),
                    MjAmount.ofMicro(Math.max(0, info.lossPerTick)),
                    Math.max(0, Math.min(MjAmount.MICRO_MJ_PER_MJ, info.resistancePerTick)),
                    info.isReceiver
                );
            }
            return builder.mjProfile(profile).build();
        }
        if (definition.flowType == PipeApi.flowForgeEnergy) {
            ExternalEnergyTransportProfile profile = builtinExternalEnergyProfile(definition);
            if (profile == null) {
                PipeApi.ForgeEnergyTransferInfo info = PipeApi.getForgeEnergyTransferInfo(definition);
                profile = new ExternalEnergyTransportProfile(Math.max(1, info.transferPerTick), info.isReceiver);
            }
            return builder.externalEnergyProfile(profile).build();
        }
        return builder.medium(PipeMedium.STRUCTURE).build();
    }

    /**
     * Built-in pipe types are registered before Forge/NeoForge config values are loaded. Do not snapshot
     * PipeApi's generic fallback here: it would make every built-in pipe advertise the same transfer profile.
     * These are the canonical BCCE defaults; runtime config overrides are applied by PipeApi later.
     */
    private static FluidTransportProfile builtinFluidProfile(PipeDefinition definition) {
        if (!BUILTIN_NAMESPACE.equals(definition.identifier.getNamespace())) return null;
        String path = definition.identifier.getPath();
        int rate;
        int delay = 10;
        switch (path) {
            case "wood_fluid", "cobblestone_fluid" -> rate = DEFAULT_FLUID_BASE_RATE;
            case "stone_fluid", "sandstone_fluid" -> rate = DEFAULT_FLUID_BASE_RATE * 2;
            case "clay_fluid", "iron_fluid", "quartz_fluid" -> rate = DEFAULT_FLUID_BASE_RATE * 4;
            case "diamond_fluid", "diamond_wood_fluid", "void_fluid" -> rate = DEFAULT_FLUID_BASE_RATE * 8;
            case "gold_fluid" -> {
                rate = DEFAULT_FLUID_BASE_RATE * 8;
                delay = 2;
            }
            default -> { return null; }
        }
        return new FluidTransportProfile(FluidAmount.of(rate), delay);
    }

    private static PowerTransportProfile builtinPowerProfile(PipeDefinition definition) {
        if (!BUILTIN_NAMESPACE.equals(definition.identifier.getNamespace())) return null;
        int multiplier;
        int resistanceDivisor;
        boolean extractor;
        switch (definition.identifier.getPath()) {
            case "cobblestone_power" -> { multiplier = 1; resistanceDivisor = 16; extractor = false; }
            case "stone_power" -> { multiplier = 2; resistanceDivisor = 32; extractor = false; }
            case "wood_power" -> { multiplier = 4; resistanceDivisor = 128; extractor = true; }
            case "sandstone_power" -> { multiplier = 4; resistanceDivisor = 32; extractor = false; }
            case "quartz_power", "iron_power" -> { multiplier = 8; resistanceDivisor = 32; extractor = false; }
            case "gold_power" -> { multiplier = 16; resistanceDivisor = 32; extractor = false; }
            case "diamond_power" -> { multiplier = 64; resistanceDivisor = 32; extractor = false; }
            case "diamond_wood_power" -> { multiplier = 64; resistanceDivisor = 32; extractor = true; }
            default -> { return null; }
        }
        long max = (long) DEFAULT_MJ_BASE_RATE * multiplier * MjAmount.MICRO_MJ_PER_MJ;
        long resistance = MjAmount.MICRO_MJ_PER_MJ / resistanceDivisor;
        return PowerTransportProfile.fromResistance(MjAmount.ofMicro(max), resistance, extractor);
    }

    private static ExternalEnergyTransportProfile builtinExternalEnergyProfile(PipeDefinition definition) {
        if (!BUILTIN_NAMESPACE.equals(definition.identifier.getNamespace())) return null;
        int multiplier;
        boolean extractor;
        switch (definition.identifier.getPath()) {
            case "cobblestone_fe" -> { multiplier = 1; extractor = false; }
            case "stone_fe" -> { multiplier = 2; extractor = false; }
            case "wood_fe" -> { multiplier = 4; extractor = true; }
            case "sandstone_fe" -> { multiplier = 4; extractor = false; }
            case "quartz_fe", "iron_fe" -> { multiplier = 8; extractor = false; }
            case "gold_fe" -> { multiplier = 32; extractor = false; }
            case "diamond_fe" -> { multiplier = 64; extractor = false; }
            case "diamond_wood_fe" -> { multiplier = 64; extractor = true; }
            default -> { return null; }
        }
        return new ExternalEnergyTransportProfile((long) DEFAULT_FE_BASE_RATE * multiplier, extractor);
    }

    private static RegistrationContext owner(String namespace) {
        return () -> namespace;
    }
}
