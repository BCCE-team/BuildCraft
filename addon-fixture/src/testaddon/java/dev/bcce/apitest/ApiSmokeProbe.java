package dev.bcce.apitest;

import buildcraft.api.v2.*;
import buildcraft.api.v2.config.*;
import buildcraft.api.v2.energy.*;
import buildcraft.api.v2.module.ModuleInfo;
import buildcraft.api.v2.permission.*;
import buildcraft.api.v2.persistence.*;
import buildcraft.api.v2.pipe.*;
import buildcraft.api.v2.signal.SignalChannelType;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** A physically separate mod: no implementation classes, reflection or TestKit. */
public final class ApiSmokeProbe {
    public static final String MOD_ID = "bcceapitest";
    private static final ConfigKey<Integer> CAPACITY = ConfigKey.integer(definition("capacity", "37"));
    private static ConfigValue<Integer> capacity;
    private static PipeType pipe;
    private static SignalChannelType<Boolean> signal;
    private static boolean registered;
    private ApiSmokeProbe() {}

    private static ConfigDefinition definition(String name, String value) {
        return new ConfigDefinition(MOD_ID + ":" + name, ConfigDefinition.Section.WORLD, List.of("CI"),
            ConfigDefinition.Scope.SERVER, ConfigDefinition.Kind.INTEGER, value, 1, 100, List.of(),
            "", "", "", name, "API test setting", "", ConfigDefinition.ApplyPolicy.WORLD_RESTART);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void rejectFrozen(Runnable action, String name) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError(name + " accepted late registration");
    }
    public static void register() {
        var runtime = BuildCraftApi.runtime();
        require(!registered && runtime.lifecycle().ordinal() < ApiLifecycle.FROZEN.ordinal(), "Registration phase");
        require(runtime.version().major() == 2 && runtime.version().minor() >= 2, "Expected API 2.2+");
        var config = runtime.requireService(BuildCraftServices.CONFIGURATION);
        config.registerSchema(new ConfigSchema(MOD_ID, ConfigDefinition.Scope.SERVER, 1, List.of(values -> {
            String old = values.remove("old_capacity");
            if (old != null) values.putIfAbsent("capacity", old);
        })));
        capacity = config.register(CAPACITY);
        runtime.requireService(BuildCraftServices.MODULES).registerExternal(
            new ModuleInfo(Ids.id("module"), "1.0.0", true), List.of(), () -> MOD_ID);
        pipe = PipeType.builder(Ids.id("test_pipe")).itemProfile(new ItemTransportProfile(8, 2)).build();
        var pipes = runtime.requireRegistry(BuildCraftRegistries.PIPE_TYPES);
        pipes.register(pipe.id(), pipe, () -> MOD_ID);
        pipes.registerAlias(Ids.id("old_pipe"), pipe.id(), () -> MOD_ID);
        signal = new SignalChannelType<>(Ids.id("signal"), false, new ApiCodec<Boolean, OpaqueData>() {
            @Override public CodecResult<Boolean> decode(OpaqueData value) {
                byte[] bytes = value.bytes();
                return value.format().equals(Ids.id("signal")) && bytes.length == 1
                    ? CodecResult.success(bytes[0] != 0) : CodecResult.failure("Bad test payload");
            }
            @Override public CodecResult<OpaqueData> encode(Boolean value) {
                return CodecResult.success(new OpaqueData(Ids.id("signal"), new byte[] {(byte) (value ? 1 : 0)}));
            }
        }, (a, b) -> a || b);
        runtime.requireRegistry(BuildCraftRegistries.SIGNAL_CHANNEL_TYPES).register(signal.id(), signal, () -> MOD_ID);
        runtime.requireService(BuildCraftServices.PERMISSIONS).register(Ids.id("permission"), Integer.MAX_VALUE,
            context -> context.reasonId().filter(Ids.id("probe")::equals).isPresent()
                && context.actor().sourceId().filter(Ids.id("probe")::equals).isPresent()
                ? PermissionDecision.deny(Ids.id("permission"), "API smoke probe") : PermissionDecision.pass());
        registered = true;
        System.out.println("BCCE_API_TEST REGISTERED v1");
    }
    /** Actual server thread after world load. No blocks, inventories or saved gameplay are modified. */
    public static void verify(Level level) {
        try {
            require(registered, "Independent registration callback did not run");
            var runtime = BuildCraftApi.runtime();
            require(runtime.lifecycle() == ApiLifecycle.RUNNING, "API not running");
            var features = runtime.features();
            for (var feature : List.of(BuildCraftFeatures.REGISTRIES, BuildCraftFeatures.PERSISTENCE,
                BuildCraftFeatures.CONFIGURATION, BuildCraftFeatures.ENERGY, BuildCraftFeatures.TRANSFER,
                BuildCraftFeatures.PERMISSIONS, BuildCraftFeatures.WORLD_CONTEXT, BuildCraftFeatures.PIPES,
                BuildCraftFeatures.SIGNALS, BuildCraftFeatures.ROBOTS, BuildCraftFeatures.LOGISTICS,
                BuildCraftFeatures.SCHEMATICS, BuildCraftFeatures.MACHINES)) {
                require(features.supports(feature), "Missing feature: " + feature);
            }
            var platform = runtime.requireService(BuildCraftServices.PLATFORM);
            require(platform.itemTransfer().isPresent() && platform.fluidTransfer().isPresent()
                && platform.energyTransfer().isPresent(), "Missing installed transfer adapter");
            require(runtime.requireService(BuildCraftServices.MODULES).loaded(Ids.id("module")), "External module lost");
            var pipes = runtime.requireRegistry(BuildCraftRegistries.PIPE_TYPES);
            require(pipes.frozen() && pipes.get(Ids.id("old_pipe")) == pipe, "Pipe alias or freeze failed");
            require(pipes.entry(pipe.id()).orElseThrow().owner().equals(MOD_ID), "Wrong registration owner");
            require(pipe.itemProfile().orElseThrow().maxItemsPerCycle() == 8, "Pipe type profile lost");
            rejectFrozen(() -> pipes.register(Ids.id("late_pipe"), pipe, () -> MOD_ID), "Pipe registry");
            var channels = runtime.requireRegistry(BuildCraftRegistries.SIGNAL_CHANNEL_TYPES);
            require(channels.frozen() && channels.get(signal.id()) == signal, "Signal registration lost");
            require(signal.combine(false, true) && signal.codec().decode(signal.codec().encode(true).valueOrThrow()).valueOrThrow(),
                "Signal codec/combiner failed");
            var config = runtime.requireService(BuildCraftServices.CONFIGURATION);
            require(capacity.get().equals(config.read(CAPACITY).orElseThrow()), "Typed config read disagrees with handle");
            require(capacity.get() >= 1 && capacity.get() <= 100, "Typed world configuration out of bounds");
            String expected = System.getenv("BCCE_API_TEST_EXPECTED_CAPACITY");
            if (expected != null) require(capacity.get() == Integer.parseInt(expected), "World configuration migration/override failed");
            rejectFrozen(() -> config.register(ConfigKey.integer(definition("late", "1"))), "Configuration");
            var energy = runtime.requireService(BuildCraftServices.ENERGY);
            var storage = energy.createStorage(MjAmount.ofMj(10), MjAmount.ofMj(2));
            require(storage.insert(MjAmount.ofMj(4), OperationMode.SIMULATE).completed()
                && storage.stored().equals(MjAmount.ofMj(2)), "Simulation mutated MJ");
            require(storage.insert(MjAmount.ofMj(12), OperationMode.EXECUTE).transferred().equals(MjAmount.ofMj(8)), "MJ capacity");
            var saved = storage.snapshot();
            require(storage.extract(MjAmount.ofMj(3), OperationMode.EXECUTE).completed()
                && storage.stored().equals(MjAmount.ofMj(7)), "MJ extraction");
            storage.restore(saved);
            require(storage.stored().equals(MjAmount.ofMj(10)), "MJ snapshot restore");
            var actor = runtime.requireService(BuildCraftServices.ACTORS).system(Ids.id("probe"));
            var pos = new BlockPos(0, 64, 0);
            var verdict = runtime.requireService(BuildCraftServices.PERMISSIONS).decide(new WorldOperationContext(
                actor, level, pos, WorldOperationTarget.block(pos), WorldOperationKind.BLOCK_INTERACT,
                OperationMode.SIMULATE, Ids.id("probe")));
            require(verdict.isTerminalDeny() && verdict.authority().filter(Ids.id("permission")::equals).isPresent(), "Addon permission callback not invoked");
            var lease = new AtomicBoolean(true);
            var world = runtime.requireService(BuildCraftServices.WORLD_ACCESS).scoped(level, pos, actor, lease::get);
            require(world.isServerSide() && world.gameTime() >= 0, "Real server-thread world context unavailable");
            lease.set(false);
            require(!world.isLoaded(pos) && world.blockState(pos).isEmpty() && world.gameTime() == -1, "Expired world lease leaked access");
            require(world.permission(pos, WorldOperationKind.BLOCK_INTERACT, OperationMode.SIMULATE).isTerminalDeny(), "Expired lease not denied");
            require(runtime.requireService(BuildCraftServices.ROBOTS).robots(level) != null, "Robot query failed");
            require(runtime.requireService(BuildCraftServices.REQUESTS).providers(level) != null, "Logistics query failed");
            System.out.println("BCCE_API_TEST PASS v1");
        } catch (RuntimeException | AssertionError | LinkageError failure) {
            System.err.println("BCCE_API_TEST FAIL v1");
            throw failure;
        }
    }
}
