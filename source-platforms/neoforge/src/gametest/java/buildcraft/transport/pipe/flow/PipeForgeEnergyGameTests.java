package buildcraft.transport.pipe.flow;

import buildcraft.gametest.GameTestCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import buildcraft.gametest.PipeGameTestSupport;
import buildcraft.gametest.PipeGameTestSupport.TestPipe;
import buildcraft.lib.BCLib;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.internal.pipe.IPipe.ConnectedType;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pipe.PipeEventForgeEnergy;
import buildcraft.transport.pipe.behaviour.PipeBehaviourLimiter;

@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class PipeForgeEnergyGameTests {
    private PipeForgeEnergyGameTests() {
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void feReceiverRejectsUndemandedEnergyAndPersistsBoundedBuffer(GameTestHelper helper) {
        TestPipe pipe = new TestPipe(helper.getLevel(), BCTransportPipes.woodFe)
            .connect(Direction.WEST, ConnectedType.TILE)
            .connect(Direction.EAST, ConnectedType.TILE);
        PipeFlowForgeEnergy flow = new PipeFlowForgeEnergy(pipe);
        pipe.setFlow(flow);
        flow.reconfigure();

        int max = PipeApi.getForgeEnergyTransferInfo(BCTransportPipes.woodFe).transferPerTick;
        PipeFlowForgeEnergy.Section input = flow.getSection(Direction.WEST);
        require(helper, input.receiveEnergy(max, false) == 0, "FE receiver accepted energy with no downstream demand");
        require(helper, input.getEnergyStored() == 0, "undemanded FE mutated the receiver buffer");

        flow.getSection(Direction.EAST).nextPowerQuery = max;
        int simulated = input.receiveEnergy(max * 2, true);
        require(helper, simulated == max, "FE simulation ignored demand or pipe capacity");
        require(helper, input.getEnergyStored() == 0, "FE simulation mutated the receiver buffer");

        int executed = input.receiveEnergy(max * 2, false);
        require(helper, executed == simulated, "FE execute disagreed with simulation");
        require(helper, input.getEnergyStored() == max, "FE receiver stored the wrong amount");
        require(helper, input.getEnergyStored() <= input.getMaxEnergyStored(), "FE receiver overflowed its advertised capacity");
        require(helper, input.receiveEnergy(max, false) == 0, "full FE receiver accepted additional energy");

        CompoundTag saved = flow.writeToNbt();
        TestPipe restoredPipe = new TestPipe(helper.getLevel(), BCTransportPipes.woodFe);
        PipeFlowForgeEnergy restored = new PipeFlowForgeEnergy(restoredPipe, saved);
        restoredPipe.setFlow(restored);
        restored.reconfigure();
        require(helper, restored.getSection(Direction.WEST).getEnergyStored() == max,
            "FE buffer changed across NBT round-trip");
        require(helper, restored.requiresPeriodicSave(), "buffered FE did not request periodic persistence");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 30)
    public static void bufferlessConsumersCreateDemandOnMultipleSides(GameTestHelper helper) {
        int max = PipeApi.getForgeEnergyTransferInfo(BCTransportPipes.woodFe).transferPerTick;
        BufferlessReceiver east = new BufferlessReceiver(max / 2);
        BufferlessReceiver south = new BufferlessReceiver(max / 4);
        TestPipe pipe = new TestPipe(helper.getLevel(), BCTransportPipes.woodFe)
            .connect(Direction.WEST, ConnectedType.TILE)
            .connect(Direction.EAST, ConnectedType.TILE)
            .connect(Direction.SOUTH, ConnectedType.TILE)
            .exposeCapability(Direction.EAST, Capabilities.EnergyStorage.BLOCK, east)
            .exposeCapability(Direction.SOUTH, Capabilities.EnergyStorage.BLOCK, south);
        PipeFlowForgeEnergy flow = new PipeFlowForgeEnergy(pipe);
        pipe.setFlow(flow);
        flow.reconfigure();

        helper.runAfterDelay(1, flow::onTick);
        helper.runAfterDelay(2, () -> {
            flow.onTick();
            int expected = max / 2 + max / 4;
            int accepted = flow.getSection(Direction.WEST).receiveEnergy(max, false);
            require(helper, accepted == expected,
                "bufferless FE consumers requested " + accepted + " instead of " + expected + " FE");
        });
        helper.runAfterDelay(3, () -> {
            flow.onTick();
            int expected = max / 2 + max / 4;
            require(helper, east.accepted + south.accepted == expected,
                "multi-side FE distribution created, lost, or stranded demanded energy");
            require(helper, east.accepted > 0 && south.accepted > 0, "one FE consumer side was starved");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void woodenFeExtractionUsesSimulationAndNeverOverfills(GameTestHelper helper) {
        int max = PipeApi.getForgeEnergyTransferInfo(BCTransportPipes.woodFe).transferPerTick;
        TrackingEnergyStorage source = new TrackingEnergyStorage(max * 4, max * 4, true, false);
        ChestBlockEntity sourceTile = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        TestPipe pipe = new TestPipe(helper.getLevel(), BCTransportPipes.woodFe)
            .connectTile(Direction.WEST, sourceTile)
            .exposeCapability(Direction.WEST, Capabilities.EnergyStorage.BLOCK, source)
            .connect(Direction.EAST, ConnectedType.TILE);
        PipeFlowForgeEnergy flow = new PipeFlowForgeEnergy(pipe);
        pipe.setFlow(flow);
        flow.reconfigure();
        flow.getSection(Direction.EAST).nextPowerQuery = max * 2;

        int accepted = flow.tryExtractPower(max * 2, Direction.WEST);
        require(helper, accepted == max, "Wooden FE Pipe extracted outside its per-section capacity");
        require(helper, source.simulateExtractions == 1 && source.executeExtractions == 1,
            "Wooden FE Pipe did not perform one simulate + one execute extraction");
        require(helper, source.lastSimulated == max && source.lastExecuted == max,
            "Wooden FE Pipe executed more FE than it simulated");
        require(helper, flow.getSection(Direction.WEST).getEnergyStored() == max,
            "Wooden FE Pipe did not retain extracted FE in its input section");

        int second = flow.tryExtractPower(max, Direction.WEST);
        require(helper, second == 0, "full Wooden FE Pipe extracted FE a second time in the same tick");
        require(helper, source.executeExtractions == 1, "full FE section still executed source extraction");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void feLimiterModesClampPersistAndDisableTransfer(GameTestHelper helper) {
        int base = PipeApi.getForgeEnergyTransferInfo(BCTransportPipes.ironFe).transferPerTick;
        TestPipe pipe = new TestPipe(helper.getLevel(), BCTransportPipes.ironFe);
        PipeFlowForgeEnergy flow = new PipeFlowForgeEnergy(pipe);
        pipe.setFlow(flow);

        CompoundTag halfNbt = new CompoundTag();
        halfNbt.putInt("limitShift", 1);
        PipeBehaviourLimiter half = new PipeBehaviourLimiter(pipe, halfNbt);
        PipeEventForgeEnergy.Configure halfEvent = configuredFeEvent(pipe, flow, base);
        half.configureForgeEnergy(halfEvent);
        require(helper, halfEvent.getMaxPower() == base / 2, "Iron FE limiter shift=1 did not halve transfer");
        require(helper, !halfEvent.isTransferDisabled(), "half-rate FE limiter disabled transfer");
        require(helper, GameTestCompat.readInt(half.writeToNbt(), "limitShift") == 1, "FE limiter mode was not persisted");

        CompoundTag disabledNbt = new CompoundTag();
        disabledNbt.putInt("limitShift", PipeBehaviourLimiter.MAX_SHIFT);
        PipeBehaviourLimiter disabled = new PipeBehaviourLimiter(pipe, disabledNbt);
        PipeEventForgeEnergy.Configure disabledEvent = configuredFeEvent(pipe, flow, base);
        disabled.configureForgeEnergy(disabledEvent);
        require(helper, disabledEvent.isTransferDisabled(), "maximum Iron FE limiter mode did not disable transfer");

        int diamondBase = PipeApi.getForgeEnergyTransferInfo(BCTransportPipes.diamondFe).transferPerTick;
        TestPipe diamondPipe = new TestPipe(helper.getLevel(), BCTransportPipes.diamondFe);
        PipeFlowForgeEnergy diamondFlow = new PipeFlowForgeEnergy(diamondPipe);
        diamondPipe.setFlow(diamondFlow);
        PipeBehaviourLimiter diamondHalf = new PipeBehaviourLimiter(diamondPipe, halfNbt);
        PipeEventForgeEnergy.Configure diamondEvent = configuredFeEvent(diamondPipe, diamondFlow, diamondBase);
        diamondHalf.configureForgeEnergy(diamondEvent);
        require(helper, diamondEvent.getMaxPower() == diamondBase / 2, "Diamond FE limiter did not apply the same rate contract");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void feOverflowRequestsAreSaturatedInsteadOfWrapping(GameTestHelper helper) {
        int max = PipeApi.getForgeEnergyTransferInfo(BCTransportPipes.diaWoodFe).transferPerTick;
        TestPipe pipe = new TestPipe(helper.getLevel(), BCTransportPipes.diaWoodFe)
            .connect(Direction.WEST, ConnectedType.TILE)
            .connect(Direction.EAST, ConnectedType.TILE)
            .connect(Direction.SOUTH, ConnectedType.TILE);
        PipeFlowForgeEnergy flow = new PipeFlowForgeEnergy(pipe);
        pipe.setFlow(flow);
        flow.reconfigure();
        flow.getSection(Direction.EAST).nextPowerQuery = Integer.MAX_VALUE;
        flow.getSection(Direction.SOUTH).nextPowerQuery = Integer.MAX_VALUE;

        require(helper, flow.getPowerRequested(Direction.WEST) == max, "FE request summation overflowed instead of clamping to max transfer");
        int accepted = flow.getSection(Direction.WEST).receiveEnergy(Integer.MAX_VALUE, false);
        require(helper, accepted == max, "FE receiver did not saturate an oversized offer at max transfer");
        require(helper, flow.getSection(Direction.WEST).getEnergyStored() == max,
            "oversized FE offer overflowed or underfilled the internal buffer");
        helper.succeed();
    }

    private static PipeEventForgeEnergy.Configure configuredFeEvent(TestPipe pipe, PipeFlowForgeEnergy flow, int maxPower) {
        PipeEventForgeEnergy.Configure event = new PipeEventForgeEnergy.Configure(pipe.getHolder(), flow);
        event.setReceiver(false);
        event.setMaxPower(maxPower);
        return event;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }

    private static final class BufferlessReceiver implements IEnergyStorage {
        private final int capacity;
        private int accepted;

        private BufferlessReceiver(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int amount = Math.min(Math.max(0, maxReceive), Math.max(0, capacity - accepted));
            if (!simulate) accepted += amount;
            return amount;
        }

        @Override public int extractEnergy(int maxExtract, boolean simulate) { return 0; }
        @Override public int getEnergyStored() { return 0; }
        @Override public int getMaxEnergyStored() { return 0; }
        @Override public boolean canExtract() { return false; }
        @Override public boolean canReceive() { return true; }
    }

    private static final class TrackingEnergyStorage implements IEnergyStorage {
        private int stored;
        private final int capacity;
        private final boolean canExtract;
        private final boolean canReceive;
        private int simulateExtractions;
        private int executeExtractions;
        private int lastSimulated;
        private int lastExecuted;

        private TrackingEnergyStorage(int stored, int capacity, boolean canExtract, boolean canReceive) {
            this.stored = stored;
            this.capacity = capacity;
            this.canExtract = canExtract;
            this.canReceive = canReceive;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            if (!canReceive) return 0;
            int amount = Math.min(Math.max(0, maxReceive), capacity - stored);
            if (!simulate) stored += amount;
            return amount;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            if (!canExtract) return 0;
            int amount = Math.min(Math.max(0, maxExtract), stored);
            if (simulate) {
                simulateExtractions++;
                lastSimulated = maxExtract;
            } else {
                executeExtractions++;
                lastExecuted = maxExtract;
                stored -= amount;
            }
            return amount;
        }

        @Override public int getEnergyStored() { return stored; }
        @Override public int getMaxEnergyStored() { return capacity; }
        @Override public boolean canExtract() { return canExtract; }
        @Override public boolean canReceive() { return canReceive; }
    }

}
