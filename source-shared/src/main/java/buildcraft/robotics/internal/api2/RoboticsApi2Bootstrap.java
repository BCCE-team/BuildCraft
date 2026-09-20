package buildcraft.robotics.internal.api2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.automation.AutomationActionType;
import buildcraft.api.v2.automation.AutomationKinds;
import buildcraft.api.v2.automation.AutomationResult;
import buildcraft.api.v2.automation.BreakBlockRequest;
import buildcraft.api.v2.automation.PlaceBlockRequest;
import buildcraft.api.v2.automation.UseItemRequest;
import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.api.v2.permission.PermissionDecision;
import buildcraft.api.v2.permission.PermissionVerdict;
import buildcraft.api.v2.permission.WorldOperationContext;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.api.v2.permission.WorldOperationTarget;
import buildcraft.api.v2.robot.BlockRobotResource;
import buildcraft.api.v2.robot.BuildCraftDockPorts;
import buildcraft.api.v2.robot.BuildCraftRobotBoards;
import buildcraft.api.v2.robot.DockPortType;
import buildcraft.api.v2.robot.RobotResourceType;
import buildcraft.api.v2.persistence.ApiCodec;
import buildcraft.api.v2.persistence.CodecResult;
import buildcraft.api.v2.persistence.OpaqueData;
import buildcraft.api.v2.persistence.PersistentType;
import buildcraft.api.v2.robot.RobotBoardType;
import buildcraft.lib.internal.api.v2.BuildCraftApiRuntime;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.robotics.BCRoboticsBoards;
import com.mojang.authlib.GameProfile;
import java.nio.ByteBuffer;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Installs the live robotics/request services and built-in robot content into API2. */
public final class RoboticsApi2Bootstrap {
    private static boolean initialized;

    private RoboticsApi2Bootstrap() {}

    public static synchronized void bootstrap() {
        if (initialized) return;
        if (BuildCraftApiRuntime.INSTANCE.service(BuildCraftServices.ROBOTS).isEmpty()) {
            BuildCraftApiRuntime.INSTANCE.installService(BuildCraftServices.ROBOTS, new RobotServiceImpl());
        }
        if (BuildCraftApiRuntime.INSTANCE.service(BuildCraftServices.REQUESTS).isEmpty()) {
            BuildCraftApiRuntime.INSTANCE.installService(BuildCraftServices.REQUESTS, new RequestServiceImpl());
        }
        registerRobotResourceTypes();
        registerDockPortTypes();
        registerBoards();
        registerAutomationActions();
        initialized = true;
    }

    private static void registerRobotResourceTypes() {
        var registry = BuildCraftApi.registry(BuildCraftRegistries.ROBOT_RESOURCE_TYPES);
        if (registry.get(BlockRobotResource.TYPE) != null) return;

        ResourceLocation format = java.util.Objects.requireNonNull(ResourceLocation.tryParse("buildcraft:block_robot_resource_v1"));
        ApiCodec<BlockRobotResource, OpaqueData> codec = new ApiCodec<>() {
            @Override
            public CodecResult<BlockRobotResource> decode(OpaqueData payload) {
                if (!format.equals(payload.format())) {
                    return CodecResult.failure("Unexpected block robot resource format: " + payload.format());
                }
                byte[] bytes = payload.bytes();
                if (bytes.length != 13) {
                    return CodecResult.failure("Invalid block robot resource payload length: " + bytes.length);
                }
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(buffer.getInt(), buffer.getInt(), buffer.getInt());
                int sideId = buffer.get();
                net.minecraft.core.Direction[] directions = net.minecraft.core.Direction.values();
                if (sideId < -1 || sideId >= directions.length) {
                    return CodecResult.failure("Invalid block robot resource side id: " + sideId);
                }
                return CodecResult.success(new BlockRobotResource(
                    pos, sideId < 0 ? java.util.Optional.empty() : java.util.Optional.of(directions[sideId])
                ));
            }

            @Override
            public CodecResult<OpaqueData> encode(BlockRobotResource value) {
                ByteBuffer buffer = ByteBuffer.allocate(13);
                buffer.putInt(value.position().getX());
                buffer.putInt(value.position().getY());
                buffer.putInt(value.position().getZ());
                int sideId = value.side().map(net.minecraft.core.Direction::ordinal).orElse(-1);
                buffer.put((byte) sideId);
                return CodecResult.success(new OpaqueData(format, buffer.array()));
            }
        };
        PersistentType<BlockRobotResource, OpaqueData> persistence = PersistentType
            .<BlockRobotResource, OpaqueData>builder(BlockRobotResource.TYPE, 0, codec)
            .build();
        registry.register(
            BlockRobotResource.TYPE,
            new RobotResourceType<>(
                BlockRobotResource.TYPE,
                BlockRobotResource.class,
                persistence,
                RobotServiceImpl::acquireBlockResource
            ),
            () -> "buildcraftrobotics"
        );
    }

    private static void registerDockPortTypes() {
        var registry = BuildCraftApi.registry(BuildCraftRegistries.ROBOT_DOCK_PORT_TYPES);
        registerDockPort(registry, BuildCraftDockPorts.ITEMS);
        registerDockPort(registry, BuildCraftDockPorts.FLUIDS);
        registerDockPort(registry, new DockPortType<>(
            BuildCraftDockPorts.MJ.id(),
            buildcraft.api.v2.energy.MjPort.class,
            context -> BuildCraftApi.service(BuildCraftServices.ENERGY).port(
                context.level(), context.position(), context.side().orElse(null)
            )
        ));
        registerDockPort(registry, new DockPortType<>(
            BuildCraftDockPorts.EXTERNAL_ENERGY.id(),
            buildcraft.api.v2.platform.ExternalEnergyPort.class,
            context -> BuildCraftApi.service(BuildCraftServices.PLATFORM).energyTransfer()
                .flatMap(transfer -> transfer.find(context.level(), context.position(), context.side().orElse(null)))
        ));
    }

    private static <T> void registerDockPort(
        buildcraft.api.v2.registry.ApiRegistry<DockPortType<?>> registry, DockPortType<T> type
    ) {
        if (registry.get(type.id()) == null) {
            registry.register(type.id(), type, () -> "buildcraftrobotics");
        }
    }

    private static void registerBoards() {
        var registry = BuildCraftApi.registry(BuildCraftRegistries.ROBOT_BOARD_TYPES);
        for (BCRoboticsBoards.BoardEntry entry : BCRoboticsBoards.entriesWithEmpty()) {
            ResourceLocation id = BuildCraftRobotBoards.id(entry.key());
            if (registry.get(id) != null) continue;
            int tier = entry.energyCost() <= 0 ? 0
                : entry.energyCost() <= 8_000 ? 1
                : entry.energyCost() <= 32_000 ? 2
                : entry.energyCost() <= 64_000 ? 3 : 4;
            registry.register(id, new RobotBoardType(id, tier, Set.of()), () -> "buildcraftrobotics");
        }
    }

    private static void registerAutomationActions() {
        var registry = BuildCraftApi.registry(BuildCraftRegistries.AUTOMATION_ACTION_TYPES);
        if (registry.get(AutomationKinds.BREAK_BLOCK) == null) {
            registry.register(AutomationKinds.BREAK_BLOCK,
                new AutomationActionType<>(AutomationKinds.BREAK_BLOCK, BreakBlockRequest.class, RoboticsApi2Bootstrap::breakBlock),
                () -> "buildcraftrobotics");
        }
        if (registry.get(AutomationKinds.PLACE_BLOCK) == null) {
            registry.register(AutomationKinds.PLACE_BLOCK,
                new AutomationActionType<>(AutomationKinds.PLACE_BLOCK, PlaceBlockRequest.class, RoboticsApi2Bootstrap::placeBlock),
                () -> "buildcraftrobotics");
        }
        if (registry.get(AutomationKinds.USE_ITEM) == null) {
            registry.register(AutomationKinds.USE_ITEM,
                new AutomationActionType<>(AutomationKinds.USE_ITEM, UseItemRequest.class, RoboticsApi2Bootstrap::useItem),
                () -> "buildcraftrobotics");
        }
    }

    private static AutomationResult breakBlock(BreakBlockRequest request) {
        if (!(request.level() instanceof ServerLevel level)) return AutomationResult.pass();
        PermissionDecision permission = permission(request.actor(), level, request.origin(), request.target(), WorldOperationKind.BLOCK_BREAK, request.mode(), request.kind());
        if (permission.verdict() == PermissionVerdict.DENY) return denied(permission);
        if (level.getBlockState(request.target()).isAir()) return new AutomationResult(AutomationResult.Status.FAILED, 0, "air");
        Player player = fakePlayer(level, request.actor(), request.origin());
        if (!BlockUtil.canBreakBlock(level, request.target(), player)) {
            return new AutomationResult(AutomationResult.Status.DENIED, 0, "platform_break_denied");
        }
        if (request.mode() == OperationMode.SIMULATE) return AutomationResult.success(1);
        boolean harvested = BlockUtil.harvestBlock(level, request.target(), ItemStack.EMPTY, profile(request.actor()));
        return harvested ? AutomationResult.success(1) : new AutomationResult(AutomationResult.Status.FAILED, 0, "break_failed");
    }

    private static AutomationResult placeBlock(PlaceBlockRequest request) {
        if (!(request.level() instanceof ServerLevel level)) return AutomationResult.pass();
        PermissionDecision permission = permission(request.actor(), level, request.origin(), request.target(), WorldOperationKind.BLOCK_PLACE, request.mode(), request.kind());
        if (permission.verdict() == PermissionVerdict.DENY) return denied(permission);
        if (request.mode() == OperationMode.SIMULATE) return AutomationResult.success(1);
        Player player = fakePlayer(level, request.actor(), request.origin());
        return BlockUtil.placeBlock(level, request.target(), request.state(), player, Direction.UP, 3)
            ? AutomationResult.success(1)
            : new AutomationResult(AutomationResult.Status.FAILED, 0, "place_failed");
    }

    private static AutomationResult useItem(UseItemRequest request) {
        if (!(request.level() instanceof ServerLevel level)) return AutomationResult.pass();
        PermissionDecision permission = permission(request.actor(), level, request.origin(), request.target(), WorldOperationKind.ITEM_USE, request.mode(), request.kind());
        if (permission.verdict() == PermissionVerdict.DENY) return denied(permission);
        if (request.stack().isEmpty()) return new AutomationResult(AutomationResult.Status.FAILED, 0, "empty_stack");
        if (request.mode() == OperationMode.SIMULATE) return AutomationResult.success(1);
        Player player = fakePlayer(level, request.actor(), request.origin());
        ItemStack working = request.stack();
        return BlockUtil.useItemOnBlock(level, player, working, request.target(), request.side())
            ? AutomationResult.success(1)
            : new AutomationResult(AutomationResult.Status.FAILED, 0, "use_failed");
    }

    private static PermissionDecision permission(AutomationActor actor, ServerLevel level, net.minecraft.core.BlockPos origin,
                                                 net.minecraft.core.BlockPos target, WorldOperationKind kind, OperationMode mode,
                                                 ResourceLocation reason) {
        return BuildCraftApi.service(BuildCraftServices.PERMISSIONS).decide(new WorldOperationContext(
            actor, level, origin, WorldOperationTarget.block(target), kind, mode, reason));
    }

    private static AutomationResult denied(PermissionDecision permission) {
        return new AutomationResult(AutomationResult.Status.DENIED, 0,
            permission.reason().orElseGet(() -> permission.authority().map(Object::toString).orElse("denied")));
    }

    private static Player fakePlayer(ServerLevel level, AutomationActor actor, net.minecraft.core.BlockPos origin) {
        return FakePlayerProvider.INSTANCE.getFakePlayer(level, profile(actor), origin);
    }

    private static GameProfile profile(AutomationActor actor) {
        if (actor.playerId().isPresent()) {
            return new GameProfile(actor.playerId().get(), actor.playerName().orElse("BuildCraft Automation"));
        }
        return FakePlayerProvider.NULL_PROFILE;
    }
}
