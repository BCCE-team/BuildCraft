package buildcraft.gametest;

import java.lang.reflect.Field;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import buildcraft.builders.BCBuildersBlocks;
import buildcraft.builders.tile.TileBuilder;
import buildcraft.builders.tile.TileQuarry;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.mj.MjBattery;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.zone.ZonePlan;
import buildcraft.silicon.gate.EnumGateLogic;
import buildcraft.silicon.gate.EnumGateMaterial;
import buildcraft.silicon.gate.EnumGateModifier;
import buildcraft.silicon.gate.GateVariant;
import buildcraft.silicon.plug.PluggableGate;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.pipe.flow.PipeFlowForgeEnergy;
import buildcraft.gametest.PipeGameTestSupport.TestPipe;

/**
 * Deterministic performance smoke workloads. These intentionally assert operation/state budgets rather than wall-clock
 * timings so CI results are stable across shared runners.
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class PerformanceSmokeGameTests {
    private static final String EMPTY_TEMPLATE = "empty7x3x7";

    private PerformanceSmokeGameTests() {
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 60)
    public static void thousandPipeStateRoundTripsStayIdleAndBounded(GameTestHelper helper) {
        for (int i = 0; i < 1_024; i++) {
            TestPipe pipe = new TestPipe(helper.getLevel(), BCTransportPipes.woodFe);
            PipeFlowForgeEnergy flow = new PipeFlowForgeEnergy(pipe);
            pipe.setFlow(flow);
            flow.reconfigure();
            require(helper, flow.getSection(Direction.WEST).receiveEnergy(Integer.MAX_VALUE, false) == 0,
                "idle FE pipe accepted undemanded energy in 1000-pipe smoke workload");

            CompoundTag nbt = flow.writeToNbt();
            TestPipe restoredPipe = new TestPipe(helper.getLevel(), BCTransportPipes.woodFe);
            PipeFlowForgeEnergy restored = new PipeFlowForgeEnergy(restoredPipe, nbt);
            restoredPipe.setFlow(restored);
            restored.reconfigure();
            require(helper, restored.getSection(Direction.WEST).getEnergyStored() == 0,
                "idle pipe round-trip created buffered FE");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 60)
    public static void largeGateNetworkStateRoundTripsStayLinear(GameTestHelper helper) {
        TestPipe pipe = new TestPipe(helper.getLevel(), BCTransportPipes.cobbleItem);
        GateVariant variant = new GateVariant(EnumGateLogic.AND, EnumGateMaterial.GOLD, EnumGateModifier.DIAMOND);
        long serializedBytes = 0;
        for (int i = 0; i < 1_024; i++) {
            Direction side = Direction.values()[i % Direction.values().length];
            PluggableGate gate = new PluggableGate(null, pipe.getHolder(), side, variant);
            CompoundTag nbt = gate.writeToNbt();
            serializedBytes += nbt.toString().length();
            PluggableGate restored = new PluggableGate(null, pipe.getHolder(), side, nbt);
            require(helper, restored.logic.variant.equals(variant), "gate variant changed during large-network state round-trip");
        }
        require(helper, serializedBytes < 4_000_000L, "gate state grew beyond the deterministic smoke budget");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 60)
    public static void idleBuilderAndQuarryMachineTicksRemainPowerNeutral(GameTestHelper helper) {
        BlockPos builderPos = new BlockPos(1, 1, 1);
        BlockPos quarryPos = new BlockPos(4, 1, 4);
        helper.setBlock(builderPos, BCBuildersBlocks.BUILDER.get().defaultBlockState());
        helper.setBlock(quarryPos, BCBuildersBlocks.QUARRY.get().defaultBlockState());
        require(helper, GameTestCompat.getBlockEntity(helper, builderPos) instanceof TileBuilder, "missing Builder in performance smoke");
        require(helper, GameTestCompat.getBlockEntity(helper, quarryPos) instanceof TileQuarry, "missing Quarry in performance smoke");
        TileBuilder builder = (TileBuilder) GameTestCompat.getBlockEntity(helper, builderPos);
        TileQuarry quarry = (TileQuarry) GameTestCompat.getBlockEntity(helper, quarryPos);
        long builderPower = builder.getBattery().getStored();
        MjBattery quarryBattery = (MjBattery) readField(quarry, "battery");
        long quarryPower = quarryBattery.getStored();

        // 256 machine-ticks is equivalent to ticking many idle machines for a small deterministic window.
        for (int i = 0; i < 256; i++) {
            builder.update();
            quarry.update();
        }
        require(helper, builder.getBattery().getStored() == builderPower, "idle Builder consumed power");
        require(helper, quarryBattery.getStored() == quarryPower, "idle Quarry consumed power");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 60)
    public static void manyIdleRobotsKeepChargingStateStable(GameTestHelper helper) {
        for (int i = 0; i < 256; i++) {
            EntityRobot robot = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
            int before = robot.getEnergy();
            long requested = robot.getMjPowerRequestedForCharging();
            for (int probe = 0; probe < 32; probe++) {
                require(helper, robot.getMjPowerRequestedForCharging() == requested,
                    "idle robot charging query mutated robot state");
            }
            require(helper, robot.getEnergy() == before, "idle robot smoke workload changed stored energy");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 60)
    public static void largeZoneAndChunkStyleRoundTripStaysBounded(GameTestHelper helper) {
        ZonePlan zone = new ZonePlan();
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                zone.set(x * 16, z * 16, true);
            }
        }
        require(helper, zone.getChunkMapping().size() == 1_024, "large zone did not create the expected chunk count");
        CompoundTag nbt = new CompoundTag();
        zone.writeToNBT(nbt);
        ZonePlan restored = new ZonePlan();
        restored.readFromNBT(nbt);
        require(helper, restored.getChunkMapping().size() == 1_024, "large zone lost chunks across unload/reload-style round-trip");
        require(helper, restored.get(0, 0) && restored.get(31 * 16, 31 * 16), "large zone lost boundary cells");
        require(helper, restored.getChunkMapping().size() <= ZonePlan.MAX_SERIALIZED_CHUNKS,
            "large zone exceeded the network/persistence chunk budget");
        helper.succeed();
    }

    private static Object readField(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot read " + name + " from " + target.getClass().getName(), e);
            }
        }
        throw new IllegalStateException("Missing field " + name + " on " + target.getClass().getName());
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}
