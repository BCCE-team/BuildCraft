package dev.bcce.apifixture;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.persistence.ApiCodec;
import buildcraft.api.v2.persistence.CodecResult;
import buildcraft.api.v2.persistence.OpaqueData;
import buildcraft.api.v2.persistence.PersistentType;
import buildcraft.api.v2.pipe.*;
import buildcraft.api.v2.registry.RegistrationContext;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Two independent placeable addon pipes using only the public API and Minecraft classes. */
public final class CustomPipeFixtureAddon {
    private static final String OWNER = "api_fixture";
    private static final ResourceLocation FILTER = id("api_fixture:iron_filter");
    private static final ResourceLocation DISTRIBUTOR = id("api_fixture:round_robin");
    private static final ResourceLocation FILTER_LOGIC = id("api_fixture:iron_filter_logic");
    private static final ResourceLocation DISTRIBUTOR_LOGIC = id("api_fixture:round_robin_logic");
    private static final ResourceLocation COUNTER_FORMAT = id("api_fixture:round_robin_counter");
    private static final RegistrationContext REGISTRATION = () -> OWNER;

    private CustomPipeFixtureAddon() { }

    /** Call during addon bootstrap after the BCCE Transport carrier is registered. Register the items normally. */
    public static List<Item> register() {
        BuildCraftApi.registry(BuildCraftRegistries.PIPE_COMPONENT_TYPES).register(FILTER_LOGIC,
            new PipeComponentType<>(FILTER_LOGIC, pipe -> new IronFilter(), null), REGISTRATION);
        BuildCraftApi.registry(BuildCraftRegistries.PIPE_COMPONENT_TYPES).register(DISTRIBUTOR_LOGIC,
            new PipeComponentType<>(DISTRIBUTOR_LOGIC, pipe -> new RoundRobin(),
                PersistentType.builder(DISTRIBUTOR_LOGIC, 1, COUNTER_CODEC).build()), REGISTRATION);

        PipeType filtered = PipeType.builder(FILTER)
            .itemProfile(new ItemTransportProfile(16, 1)).component(FILTER_LOGIC).build();
        PipeType balanced = PipeType.builder(DISTRIBUTOR)
            .itemProfile(new ItemTransportProfile(64, 1)).component(DISTRIBUTOR_LOGIC).build();
        BuildCraftApi.registry(BuildCraftRegistries.PIPE_TYPES).register(FILTER, filtered, REGISTRATION);
        BuildCraftApi.registry(BuildCraftRegistries.PIPE_TYPES).register(DISTRIBUTOR, balanced, REGISTRATION);
        return List.of(
            BuildCraftApi.service(BuildCraftServices.PIPES).createItem(FILTER),
            BuildCraftApi.service(BuildCraftServices.PIPES).createItem(DISTRIBUTOR)
        );
    }

    /** Rejects non-iron stacks at every entry point; also blocks unrelated physical faces. */
    public static final class IronFilter implements ItemAdmissionComponent, ItemRouteComponent, PipeConnectionComponent {
        @Override public ResourceLocation typeId() { return FILTER_LOGIC; }
        @Override public boolean accepts(ItemAdmissionContext request) { return request.stack().is(Items.IRON_INGOT); }
        @Override public RouteDecision route(ItemRouteContext context) {
            EnumMap<Direction, Integer> weights = new EnumMap<>(Direction.class);
            for (Direction side : context.candidates()) weights.put(side, side == Direction.EAST ? 1 : 0);
            return new RouteDecision(weights);
        }
        @Override public PipeConnectionDecision connection(PipeConnectionContext context) {
            return context.side() == Direction.WEST || context.side() == Direction.EAST
                ? PipeConnectionDecision.PASS : PipeConnectionDecision.DENY;
        }
    }

    /** Routes sequential stacks in a rotating order. The index persists across world reloads. */
    public static final class RoundRobin implements ItemTransitComponent, PipeComponentLifecycle {
        int next;
        private PipeMutationContext pipe;
        public RoundRobin() { }
        public RoundRobin(int index) { next = index; }
        @Override public ResourceLocation typeId() { return DISTRIBUTOR_LOGIC; }
        @Override public void attached(PipeView view) {
            if (view instanceof PipeMutationContext mutable) pipe = mutable;
        }
        @Override public void onTransit(ItemTransitContext context) {
            if (context.phase() != ItemTransitPhase.CENTER) return;
            List<Direction> directions = context.pipe().connectedSides().stream()
                .filter(side -> context.input().isEmpty() || side != context.input().orElseThrow())
                .sorted().toList();
            if (directions.isEmpty()) return;
            int candidate = Math.floorMod(next, directions.size());
            if (context.redirect(directions.get(candidate))) {
                next = next == Integer.MAX_VALUE ? 0 : next + 1;
                if (pipe != null) pipe.markChanged();
            }
        }
    }

    private static final ApiCodec<RoundRobin, OpaqueData> COUNTER_CODEC = new ApiCodec<>() {
        @Override public CodecResult<OpaqueData> encode(RoundRobin component) {
            return CodecResult.success(new OpaqueData(COUNTER_FORMAT,
                ByteBuffer.allocate(4).putInt(component.next).array()));
        }
        @Override public CodecResult<RoundRobin> decode(OpaqueData payload) {
            if (!COUNTER_FORMAT.equals(payload.format()) || payload.size() != 4)
                return CodecResult.failure("Invalid round-robin counter payload");
            int count = ByteBuffer.wrap(payload.bytes()).getInt();
            return count < 0 ? CodecResult.failure("Negative counter")
                : CodecResult.success(new RoundRobin(count));
        }
    };

    private static ResourceLocation id(String name) { return ResourceLocation.tryParse(name); }
}
