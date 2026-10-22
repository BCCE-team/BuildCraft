package dev.bcce.apifixture;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.machine.EngineComponent;
import buildcraft.api.v2.machine.EngineExecutionContext;
import buildcraft.api.v2.machine.EngineHeatSettings;
import buildcraft.api.v2.machine.EngineProfile;
import buildcraft.api.v2.machine.EngineStage;
import buildcraft.api.v2.machine.EngineType;
import buildcraft.api.v2.machine.HeatProfile;
import buildcraft.api.v2.machine.WorkState;
import buildcraft.api.v2.machine.WorkStatus;
import buildcraft.api.v2.persistence.ApiCodec;
import buildcraft.api.v2.persistence.CodecResult;
import buildcraft.api.v2.persistence.OpaqueData;
import buildcraft.api.v2.persistence.PersistentType;
import buildcraft.api.v2.registry.RegistrationContext;
import java.nio.ByteBuffer;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/** Minimal addon-side engine with fuel, thermal stages, a piston stroke and a versioned codec. */
public final class CustomEngineFixtureAddon {
    public static final ResourceLocation ID = ResourceLocation.tryParse("test:steam_engine");
    public static final ResourceLocation FORMAT = ResourceLocation.tryParse("test:engine_fuel");
    public static final ResourceLocation SOUND = ResourceLocation.tryParse("test:steam_chuff");
    public static final EngineType ENGINE = new EngineType(ID,
        new EngineProfile(MjAmount.ofMj(1), MjAmount.ofMj(4), false));
    public static final EngineHeatSettings HEAT = new EngineHeatSettings(new HeatProfile(20, 55, 75), 20, 80, 5);

    public static final class SteamEngine implements EngineComponent {
        public int fuel = 18;
        public int ticks;
        @Override public ResourceLocation typeId() { return ID; }
        @Override public void tickEngine(EngineExecutionContext ctx) {
            ticks++;
            if (fuel > 0) {
                fuel--;
                ctx.addHeat(14);
                double temperature = ctx.visualState().heat().temperature();
                ctx.setStage(new EngineStage(ResourceLocation.tryParse(
                    temperature >= 75 ? "test:overheat" : temperature >= 50 ? "test:hot" : "test:warm"),
                    temperature >= 75 ? 3 : temperature >= 50 ? 2 : 1));
                ctx.generate(MjAmount.ofMj(2), OperationMode.SIMULATE);
                ctx.generate(MjAmount.ofMj(2), OperationMode.EXECUTE);
                ctx.setWorkStatus(new WorkStatus(WorkState.RUNNING, 0, "Steam engine"));
            } else {
                ctx.setStage(new EngineStage(ResourceLocation.tryParse("test:idle"), 0));
                ctx.setWorkStatus(new WorkStatus(WorkState.IDLE, 0, "Fuel empty"));
            }
            double phase = switch ((ticks - 1) % 3) {
                case 0 -> 0.6;
                case 1 -> 0.7;
                default -> 0.1;
            };
            ctx.setAnimation(phase, fuel > 0 ? SOUND : null);
            ctx.emit(Direction.NORTH, MjAmount.ofMj(2), OperationMode.EXECUTE);
            ctx.markChanged();
        }
    }
    public static final PersistentType<SteamEngine, OpaqueData> STATE = PersistentType
        .<SteamEngine, OpaqueData>builder(ID, 1, new ApiCodec<>() {
            @Override public CodecResult<SteamEngine> decode(OpaqueData data) {
                if (!FORMAT.equals(data.format()) || data.size() != 8) return CodecResult.failure("Invalid steam state");
                ByteBuffer input = ByteBuffer.wrap(data.bytes());
                SteamEngine engine = new SteamEngine();
                engine.fuel = input.getInt();
                engine.ticks = input.getInt();
                return engine.fuel >= 0 && engine.ticks >= 0
                    ? CodecResult.success(engine) : CodecResult.failure("Invalid counters");
            }
            @Override public CodecResult<OpaqueData> encode(SteamEngine value) {
                return CodecResult.success(new OpaqueData(FORMAT,
                    ByteBuffer.allocate(8).putInt(value.fuel).putInt(value.ticks).array()));
            }
        }).build();

    public static void register() {
        BuildCraftApi.registry(BuildCraftRegistries.ENGINE_TYPES).register(ID, ENGINE,
            (RegistrationContext) () -> "test");
    }
    private CustomEngineFixtureAddon() { }
}
