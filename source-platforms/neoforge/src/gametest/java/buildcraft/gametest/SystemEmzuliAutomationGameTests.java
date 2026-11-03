package buildcraft.gametest;

import buildcraft.core.BCCoreStatements;
import buildcraft.lib.BCLib;
import buildcraft.lib.statement.ActionWrapper;
import buildcraft.lib.statement.TriggerWrapper;
import buildcraft.silicon.BCSiliconPlugs;
import buildcraft.silicon.gate.EnumGateLogic;
import buildcraft.silicon.gate.EnumGateMaterial;
import buildcraft.silicon.gate.EnumGateModifier;
import buildcraft.silicon.gate.GateVariant;
import buildcraft.silicon.plug.PluggableGate;
import buildcraft.transport.BCTransportConfig;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.BCTransportStatements;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.pipe.behaviour.PipeBehaviourDirectional;
import buildcraft.transport.pipe.behaviour.PipeBehaviourEmzuli;
import buildcraft.transport.pipe.behaviour.PipeBehaviourEmzuli.SlotIndex;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.tile.TilePipeHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real gate-driven Emzuli extraction presets, world redstone and item deliveries. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemEmzuliAutomationGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final BlockPos SOURCE = new BlockPos(1, 1, 3);
    private static final BlockPos EMZULI = new BlockPos(2, 1, 3);
    private static final BlockPos PIPE = new BlockPos(3, 1, 3);
    private static final BlockPos WAREHOUSE = new BlockPos(4, 1, 3);
    private static final BlockPos SIGNAL = new BlockPos(2, 1, 2);
    private SystemEmzuliAutomationGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 710)
    public static void redstoneGateCyclesEmzuliPresetsAndRemovalExpiresExtraction(GameTestHelper helper) {
        ChestBlockEntity input = chest(helper, SOURCE);
        ChestBlockEntity output = chest(helper, WAREHOUSE);
        TilePipeHolder emitter = PipeGameTestSupport.placePipe(helper, EMZULI, BCTransportPipes.emzuliItem);
        TilePipeHolder transporter = PipeGameTestSupport.placePipe(helper, PIPE, BCTransportPipes.cobbleItem);
        PipeBehaviourEmzuli behaviour = (PipeBehaviourEmzuli) emitter.getPipe().getBehaviour();
        behaviour.invFilters.setStackInSlot(SlotIndex.SQUARE.ordinal(), new ItemStack(Items.APPLE));
        behaviour.invFilters.setStackInSlot(SlotIndex.CIRCLE.ordinal(), new ItemStack(Items.CARROT));
        PluggableGate gate = new PluggableGate(BCSiliconPlugs.gate, emitter, Direction.UP,
            new GateVariant(EnumGateLogic.OR, EnumGateMaterial.GOLD, EnumGateModifier.NO_MODIFIER));
        gate.logic.statements[0].trigger.set(TriggerWrapper.wrap(BCCoreStatements.TRIGGER_REDSTONE_ACTIVE, null));
        gate.logic.statements[0].action.set(ActionWrapper.wrap(
            BCTransportStatements.ACTION_EXTRACTION_PRESET[SlotIndex.SQUARE.ordinal()], null));
        gate.logic.statements[1].trigger.set(TriggerWrapper.wrap(BCCoreStatements.TRIGGER_REDSTONE_INACTIVE, null));
        gate.logic.statements[1].action.set(ActionWrapper.wrap(
            BCTransportStatements.ACTION_EXTRACTION_PRESET[SlotIndex.CIRCLE.ordinal()], null));
        emitter.replacePluggable(Direction.UP, gate);
        input.setItem(0, new ItemStack(Items.APPLE, 7));
        input.setItem(1, new ItemStack(Items.CARROT, 6));
        input.setChanged();
        helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK.defaultBlockState());

        helper.runAfterDelay(65, () -> {
            PipeBehaviourDirectional direction = (PipeBehaviourDirectional) emitter.getPipe().getBehaviour();
            for (int i = 0; i < 6 && direction.getCurrentDir() != Direction.WEST; i++) direction.advanceFacing();
            require(helper, direction.getCurrentDir() == Direction.WEST,
                "Emzuli extractor was not facing its actual source chest");
            require(helper, emitter.getPipe().isConnected(Direction.WEST)
                && emitter.getPipe().isConnected(Direction.EAST)
                && transporter.getPipe().isConnected(Direction.EAST),
                "Emzuli/Gate network has a disconnected physical item route");
            require(helper, gate.logic.triggerOn[0], "redstone signal did not reach the installed Gate");
            extract(helper, behaviour, 7);
        });
        helper.runAfterDelay(170, () -> {
            require(helper, PipeGameTestSupport.countItem(output, Items.APPLE) == 7,
                "active Gate/Emzuli apple preset did not deliver its seven items");
            helper.setBlock(SIGNAL, Blocks.AIR.defaultBlockState());
        });
        helper.runAfterDelay(210, () -> {
            require(helper, gate.logic.triggerOn[1], "Gate did not activate its inverted redstone rule");
            extract(helper, behaviour, 6);
        });
        helper.runAfterDelay(365, () -> {
            require(helper, PipeGameTestSupport.countItem(output, Items.APPLE) == 7
                && PipeGameTestSupport.countItem(output, Items.CARROT) == 6,
                "switching Emzuli presets corrupted the real two-item delivery ledger");
            require(helper, PipeGameTestSupport.countItem(input, Items.APPLE) == 0
                && PipeGameTestSupport.countItem(input, Items.CARROT) == 0,
                "successful preset extractions left duplicates in the original chest");
            require(helper, !containsItems(emitter) && !containsItems(transporter),
                "completed Emzuli deliveries left travelling items");
            PipePluggable detached = emitter.replacePluggable(Direction.UP, PipePluggable.EMPTY);
            require(helper, detached == gate, "real Gate detachment failed");
            input.setItem(0, new ItemStack(Items.APPLE, 4));
            input.setChanged();
        });
        helper.runAfterDelay(430, () -> {
            long offered = BCTransportConfig.mjPerItem * 4L;
            long unused = behaviour.receivePower(offered, FluidAction.EXECUTE);
            require(helper, unused == offered,
                "expired Gate preset still extracted items after pluggable removal");
        });
        helper.runAfterDelay(660, () -> {
            require(helper, PipeGameTestSupport.countItem(input, Items.APPLE) == 4
                && PipeGameTestSupport.countItem(output, Items.APPLE) == 7
                && PipeGameTestSupport.countItem(output, Items.CARROT) == 6,
                "removed Gate continued executing an obsolete extraction program");
            require(helper, !containsItems(emitter) && !containsItems(transporter),
                "removed preset left a travelling ghost item");
            helper.succeed();
        });
    }

    private static void extract(GameTestHelper helper, PipeBehaviourEmzuli behaviour, int amount) {
        long offered = BCTransportConfig.mjPerItem * amount;
        require(helper, behaviour.receivePower(offered, FluidAction.EXECUTE) < offered,
            "Emzuli pipe failed to extract through its live Gate preset");
    }

    private static boolean containsItems(TilePipeHolder holder) {
        return ((PipeFlowItems) holder.getPipe().getFlow()).doesContainItems();
    }

    private static ChestBlockEntity chest(GameTestHelper helper, BlockPos relative) {
        helper.setBlock(relative, Blocks.CHEST.defaultBlockState());
        Object entity = GameTestCompat.getBlockEntity(helper, relative);
        if (!(entity instanceof ChestBlockEntity chest)) {
            helper.fail("missing Emzuli source/target warehouse");
            throw new IllegalStateException("missing chest");
        }
        return chest;
    }

    private static void require(GameTestHelper helper, boolean ok, String message) {
        if (!ok) helper.fail(message);
    }
}
