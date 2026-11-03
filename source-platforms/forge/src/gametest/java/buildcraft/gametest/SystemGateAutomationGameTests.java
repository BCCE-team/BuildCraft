package buildcraft.gametest;

import buildcraft.core.BCCoreBlocks;
import buildcraft.core.BCCoreStatements;
import buildcraft.core.blockEntity.TileEngineRedstone_BC8;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.enums.EnumEngineType;
import buildcraft.lib.internal.properties.BuildCraftProperties;
import buildcraft.lib.statement.ActionWrapper;
import buildcraft.lib.statement.TriggerWrapper;
import buildcraft.silicon.BCSiliconPlugs;
import buildcraft.silicon.BCSiliconStatements;
import buildcraft.silicon.gate.EnumGateLogic;
import buildcraft.silicon.gate.EnumGateMaterial;
import buildcraft.silicon.gate.EnumGateModifier;
import buildcraft.silicon.gate.GateVariant;
import buildcraft.silicon.plug.PluggableGate;
import buildcraft.silicon.plug.PluggableTimer;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.BCTransportStatements;
import buildcraft.transport.internal.pipe.IFlowItems;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.pipe.behaviour.PipeBehaviourDirectional;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.tile.TilePipeHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Actual Gates attached to placed pipe holders. The world tick drives the Gate,
 * redstone source, iron pipe routing and item flow; no manual Gate.resolveActions()
 * or PipeBehaviour.onActionActivate() calls.
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemGateAutomationGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final BlockPos ENGINE = new BlockPos(2, 0, 3);
    private static final BlockPos ENGINE_POWER = new BlockPos(2, 0, 2);
    private static final BlockPos GATE_INPUT = new BlockPos(3, 1, 2);
    private static final BlockPos SOURCE = new BlockPos(1, 1, 3);
    private static final BlockPos WOOD = new BlockPos(2, 1, 3);
    private static final BlockPos IRON = new BlockPos(3, 1, 3);
    private static final BlockPos EAST = new BlockPos(4, 1, 3);
    private static final BlockPos SOUTH = new BlockPos(3, 1, 4);

    private SystemGateAutomationGameTests() {}

    /** Engine piston -> wooden extraction -> Gate-controlled iron pipe -> two real inventories. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 1470)
    public static void poweredGateSwitchesLiveIronPipeRoutesWhenRedstoneChanges(GameTestHelper helper) {
        ChestBlockEntity source = chest(helper, SOURCE);
        ItemStack original = cargo(Items.APPLE, 64, "powered-gate", 731_007L);
        source.setItem(0, original.copy());
        source.setChanged();
        TilePipeHolder wood = PipeGameTestSupport.placePipe(helper, WOOD, BCTransportPipes.woodItem);
        TilePipeHolder iron = PipeGameTestSupport.placePipe(helper, IRON, BCTransportPipes.ironItem);
        ChestBlockEntity east = chest(helper, EAST);
        ChestBlockEntity south = chest(helper, SOUTH);
        PluggableGate gate = attachRedstoneGate(helper, iron, false);
        TileEngineRedstone_BC8 engine = powerEngine(helper, wood);
        helper.setBlock(GATE_INPUT, Blocks.REDSTONE_BLOCK.defaultBlockState());

        helper.runAfterDelay(45, () -> {
            require(helper, engine.isRedstonePowered, "engine did not detect physical redstone power");
            require(helper, gate.logic.triggerOn[0] && gate.logic.actionOn[0],
                "placed gate did not activate its redstone input rule");
            require(helper, facing(iron) == Direction.EAST, "gate did not direct actual iron pipe east");
        });
        helper.runAfterDelay(360, () -> {
            require(helper, count(east, original) > 0, "powered engine did not deliver items to gate-selected east inventory");
            require(helper, count(source, original) > 0, "source exhausted before the redstone route switch");
            helper.setBlock(GATE_INPUT, Blocks.AIR.defaultBlockState());
        });
        helper.runAfterDelay(395, () -> {
            require(helper, gate.logic.triggerOn[1] && gate.logic.actionOn[1],
                "redstone-off rule was not activated after the physical switch");
            require(helper, facing(iron) == Direction.SOUTH, "gate did not turn live iron pipe south");
        });
        helper.runAfterDelay(1000, () -> {
            require(helper, count(south, original) > 0, "newly selected south route never received live inventory cargo");
            helper.setBlock(ENGINE_POWER, Blocks.AIR.defaultBlockState());
        });
        helper.runAfterDelay(1400, () -> {
            require(helper, !engine.isRedstonePowered, "engine did not stop after power removal");
            require(helper, count(source, original) + count(east, original) + count(south, original) == 64,
                "Gate/engine/iron network duplicated or lost a charged ItemStack");
            require(helper, !containsItems(wood) && !containsItems(iron),
                "Gate route transition left cargo stuck in the pipe network");
            helper.succeed();
        });
    }

    /** Swapping an installed Gate during operation must not preserve the old program/listeners. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 550)
    public static void replacingGateDuringActiveTransitClearsOldActionAndPreservesCargo(GameTestHelper helper) {
        chest(helper, SOURCE); // A real input-side neighbour is required for pipe injection.
        TilePipeHolder inlet = PipeGameTestSupport.placePipe(helper, WOOD, BCTransportPipes.cobbleItem);
        TilePipeHolder iron = PipeGameTestSupport.placePipe(helper, IRON, BCTransportPipes.ironItem);
        ChestBlockEntity east = chest(helper, EAST);
        ChestBlockEntity south = chest(helper, SOUTH);
        PluggableGate old = attachRedstoneGate(helper, iron, false);
        helper.setBlock(GATE_INPUT, Blocks.REDSTONE_BLOCK.defaultBlockState());
        ItemStack first = cargo(Items.APPLE, 9, "gate-before-swap", 101L);
        ItemStack second = cargo(Items.CARROT, 6, "gate-after-swap", 202L);

        helper.runAfterDelay(35, () -> {
            require(helper, facing(iron) == Direction.EAST && old.logic.isOn,
                "original Gate failed to select east before the first payload");
            inject(helper, inlet, first);
        });
        helper.runAfterDelay(230, () -> {
            require(helper, count(east, first) == 9, "the first payload never reached the original Gate output");
            require(helper, !containsItems(inlet) && !containsItems(iron),
                "old Gate route was not clear before replacement");
            PipePluggable detached = iron.replacePluggable(Direction.UP, PipePluggable.EMPTY);
            require(helper, detached == old && iron.getPluggable(Direction.UP) == PipePluggable.EMPTY,
                "old Gate was not detached from its real holder");
            PluggableGate replacement = attachRedstoneGate(helper, iron, true);
            require(helper, replacement != old, "replacement reused the removed Gate instance");
        });
        helper.runAfterDelay(265, () -> {
            require(helper, facing(iron) == Direction.SOUTH,
                "new Gate's inverted program was not applied to the physical iron pipe");
            inject(helper, inlet, second);
        });
        helper.runAfterDelay(515, () -> {
            require(helper, count(east, first) == 9 && count(south, second) == 6,
                "remounted Gate delivered cargo to the wrong warehouse or lost its components");
            require(helper, count(east, second) == 0 && count(south, first) == 0,
                "removed Gate program continued acting after replacement");
            require(helper, !containsItems(inlet) && !containsItems(iron),
                "reconfiguration left a phantom travelling item");
            helper.succeed();
        });
    }

    /** Multiple live Gate rules target one pipe direction: the last active rule wins. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 370)
    public static void conflictingActiveGateActionsCommitOnlyFinalPhysicalDirection(GameTestHelper helper) {
        chest(helper, SOURCE);
        TilePipeHolder inlet = PipeGameTestSupport.placePipe(helper, WOOD, BCTransportPipes.cobbleItem);
        TilePipeHolder iron = PipeGameTestSupport.placePipe(helper, IRON, BCTransportPipes.ironItem);
        ChestBlockEntity east = chest(helper, EAST);
        ChestBlockEntity south = chest(helper, SOUTH);
        PluggableGate gate = new PluggableGate(BCSiliconPlugs.gate, iron, Direction.UP,
            new GateVariant(EnumGateLogic.OR, EnumGateMaterial.GOLD, EnumGateModifier.NO_MODIFIER));
        // Both triggers are active, but SOUTH is later in the gate's ordered program.
        rule(gate, 0, true, Direction.EAST);
        rule(gate, 1, true, Direction.SOUTH);
        iron.replacePluggable(Direction.UP, gate);
        helper.setBlock(GATE_INPUT, Blocks.REDSTONE_BLOCK.defaultBlockState());
        ItemStack cargo = cargo(Items.IRON_INGOT, 11, "ordered-gate", 99_099L);

        helper.runAfterDelay(40, () -> {
            require(helper, gate.logic.actionOn[1] && !gate.logic.actionOn[0],
                "Gate did not suppress the overridden action");
            require(helper, facing(iron) == Direction.SOUTH,
                "Gate applied an obsolete earlier iron-pipe direction");
            inject(helper, inlet, cargo);
        });
        helper.runAfterDelay(330, () -> {
            require(helper, count(south, cargo) == 11 && count(east, cargo) == 0,
                "conflicting Gate actions physically misrouted or duplicated cargo");
            require(helper, !containsItems(inlet) && !containsItems(iron), "ordered Gate kept a ghost cargo");
            helper.succeed();
        });
    }

    /** A real Timer pluggable causes a live Gate to reorient a physical iron pipe at the next pulse. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 480)
    public static void timerDrivenGateReorientsLiveItemNetworkWithoutManualGateTicks(GameTestHelper helper) {
        chest(helper, SOURCE);
        TilePipeHolder inlet = PipeGameTestSupport.placePipe(helper, WOOD, BCTransportPipes.cobbleItem);
        TilePipeHolder iron = PipeGameTestSupport.placePipe(helper, IRON, BCTransportPipes.ironItem);
        ChestBlockEntity east = chest(helper, EAST);
        ChestBlockEntity south = chest(helper, SOUTH);
        PluggableTimer timer = new PluggableTimer(BCSiliconPlugs.timer, iron, Direction.DOWN);
        iron.replacePluggable(Direction.DOWN, timer);
        PluggableGate gate = new PluggableGate(BCSiliconPlugs.gate, iron, Direction.UP,
            new GateVariant(EnumGateLogic.OR, EnumGateMaterial.GOLD, EnumGateModifier.NO_MODIFIER));
        gate.logic.statements[0].trigger.set(TriggerWrapper.wrap(BCSiliconStatements.TRIGGER_TIMER_SHORT, null));
        gate.logic.statements[0].action.set(ActionWrapper.wrap(
            BCTransportStatements.ACTION_PIPE_DIRECTION[Direction.SOUTH.ordinal()], null));
        iron.replacePluggable(Direction.UP, gate);

        helper.runAfterDelay(12, () -> {
            PipeBehaviourDirectional behaviour = (PipeBehaviourDirectional) iron.getPipe().getBehaviour();
            for (int i = 0; i < 6 && behaviour.getCurrentDir() != Direction.EAST; i++) behaviour.advanceFacing();
            require(helper, facing(iron) == Direction.EAST, "test could not seed an east-facing iron pipe");
        });
        helper.runAfterDelay(170, () -> {
            require(helper, iron.getPluggable(Direction.DOWN) == timer && iron.getPluggable(Direction.UP) == gate,
                "placed timer or Gate disappeared while waiting for a world-time pulse");
            require(helper, facing(iron) == Direction.SOUTH,
                "live Timer/Gate never changed the iron pipe direction during 158 world ticks");
            inject(helper, inlet, cargo(Items.GOLD_INGOT, 8, "timer-live", 3_333L));
        });
        ItemStack target = cargo(Items.GOLD_INGOT, 8, "timer-live", 3_333L);
        helper.runAfterDelay(440, () -> {
            require(helper, count(south, target) == 8 && count(east, target) == 0,
                "timer-driven Gate did not deliver the payload to its actual selected output");
            require(helper, !containsItems(inlet) && !containsItems(iron),
                "timer-triggered routing left an item stranded in the pipe network");
            helper.succeed();
        });
    }

    private static PluggableGate attachRedstoneGate(GameTestHelper helper, TilePipeHolder iron, boolean inverted) {
        PluggableGate gate = new PluggableGate(BCSiliconPlugs.gate, iron, Direction.UP,
            new GateVariant(EnumGateLogic.OR, EnumGateMaterial.GOLD, EnumGateModifier.NO_MODIFIER));
        rule(gate, 0, true, inverted ? Direction.SOUTH : Direction.EAST);
        rule(gate, 1, false, inverted ? Direction.EAST : Direction.SOUTH);
        iron.replacePluggable(Direction.UP, gate);
        require(helper, iron.getPluggable(Direction.UP) == gate, "real pipe holder rejected Gate placement");
        return gate;
    }

    private static void rule(PluggableGate gate, int index, boolean powered, Direction direction) {
        gate.logic.statements[index].trigger.set(TriggerWrapper.wrap(
            powered ? BCCoreStatements.TRIGGER_REDSTONE_ACTIVE : BCCoreStatements.TRIGGER_REDSTONE_INACTIVE, null));
        gate.logic.statements[index].action.set(ActionWrapper.wrap(
            BCTransportStatements.ACTION_PIPE_DIRECTION[direction.ordinal()], null));
    }

    private static TileEngineRedstone_BC8 powerEngine(GameTestHelper helper, TilePipeHolder extractor) {
        helper.setBlock(ENGINE, BCCoreBlocks.ENGINE_BC8.get().defaultBlockState()
            .setValue(BuildCraftProperties.ENGINE_TYPE, EnumEngineType.WOOD));
        BlockEntity tile = GameTestCompat.getBlockEntity(helper, ENGINE);
        if (!(tile instanceof TileEngineRedstone_BC8 engine)) {
            helper.fail("missing real Redstone Engine");
            throw new IllegalStateException("missing Redstone Engine");
        }
        helper.runAfterDelay(12, () -> {
            PipeBehaviourDirectional wood = (PipeBehaviourDirectional) extractor.getPipe().getBehaviour();
            for (int i = 0; i < 6 && wood.getCurrentDir() != Direction.WEST; i++) wood.advanceFacing();
            require(helper, wood.getCurrentDir() == Direction.WEST, "wooden pipe did not face actual source inventory");
            engine.preferDirectionOnPlacement(Direction.UP);
        });
        helper.setBlock(ENGINE_POWER, Blocks.REDSTONE_BLOCK.defaultBlockState());
        return engine;
    }

    private static ChestBlockEntity chest(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, Blocks.CHEST.defaultBlockState());
        BlockEntity tile = GameTestCompat.getBlockEntity(helper, pos);
        if (!(tile instanceof ChestBlockEntity chest)) {
            helper.fail("missing chest at " + pos);
            throw new IllegalStateException("missing chest");
        }
        return chest;
    }

    private static ItemStack cargo(net.minecraft.world.item.Item item, int count, String marker, long charge) {
        ItemStack result = new ItemStack(item, count);
        CompoundTag tag = result.getOrCreateTag();
        tag.putString("bcce_case", marker);
        tag.putLong("test_addon_energy", charge);
        return result;
    }

    private static int count(Container inventory, ItemStack original) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (same(item, original)) total += item.getCount();
        }
        return total;
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return !a.isEmpty() && ItemStack.isSameItemSameTags(a, b);
    }

    private static Direction facing(TilePipeHolder iron) {
        return ((PipeBehaviourDirectional) iron.getPipe().getBehaviour()).getCurrentDir();
    }

    private static void inject(GameTestHelper helper, TilePipeHolder inlet, ItemStack stack) {
        IFlowItems flow = (IFlowItems) inlet.getPipe().getFlow();
        require(helper, flow.injectItem(stack.copy(), true, Direction.WEST, null, 0.05D).isEmpty(),
            "real inlet rejected tagged cargo");
    }

    private static boolean containsItems(TilePipeHolder holder) {
        return ((PipeFlowItems) holder.getPipe().getFlow()).doesContainItems();
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}
