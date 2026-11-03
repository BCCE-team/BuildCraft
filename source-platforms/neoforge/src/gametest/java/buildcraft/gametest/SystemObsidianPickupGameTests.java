package buildcraft.gametest;

import buildcraft.api.v2.energy.MjAmount;
import buildcraft.lib.BCLib;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.pipe.behaviour.PipeBehaviourObsidian;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.tile.TilePipeHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import buildcraft.lib.misc.ItemStackUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** World-entity suction, actual pipe transit and restart after replacing a transport segment. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemObsidianPickupGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final BlockPos PIPE = new BlockPos(2, 1, 3);
    private static final BlockPos TRANSIT = new BlockPos(3, 1, 3);
    private static final BlockPos SINK = new BlockPos(4, 1, 3);
    private static final BlockPos GROUND = new BlockPos(1, 0, 3);
    private SystemObsidianPickupGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 750)
    public static void obsidianSucksTwoWorldEntitiesWithoutMixingTheirFullItemState(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        ItemStack first = tagged(new ItemStack(Items.APPLE, 11), "world-entity-a", 71_222_001L);
        ItemStack second = tagged(new ItemStack(Items.APPLE, 7), "world-entity-b", 71_222_999L);
        helper.runAfterDelay(15, () -> {
            ItemEntity dropped = spawn(helper, first);
            rig.suck(helper);
            require(helper, dropped.isRemoved() || dropped.getItem().isEmpty(),
                "obsidian did not consume the first physical Item Entity");
        });
        helper.runAfterDelay(295, () -> {
            require(helper, count(rig.sink, first) == 11,
                "first physical pickup did not complete with intact data and quantity");
            ItemEntity dropped = spawn(helper, second);
            rig.suck(helper);
            require(helper, dropped.isRemoved() || dropped.getItem().isEmpty(),
                "obsidian did not consume the second physical Item Entity");
        });
        helper.runAfterDelay(710, () -> {
            require(helper, count(rig.sink, first) == 11 && count(rig.sink, second) == 7,
                "two same-item Item Entities were merged across distinct serialized component values");
            require(helper, !rig.inFlight(), "suction pipeline still contains an orphan travelling item");
            require(helper, !worldCargoExists(helper, first) && !worldCargoExists(helper, second),
                "world entity survived extraction or was duplicated during transport");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 760)
    public static void obsidianRecoversAfterLiveDownstreamPipeReplacement(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        ItemStack before = tagged(new ItemStack(Items.CARROT, 9), "before-rebuild", 100L);
        ItemStack after = tagged(new ItemStack(Items.CARROT, 13), "after-rebuild", 200L);
        helper.runAfterDelay(12, () -> { spawn(helper, before); rig.suck(helper); });
        helper.runAfterDelay(275, () -> {
            require(helper, count(rig.sink, before) == 9 && !rig.inFlight(),
                "initial world pickup did not reach the warehouse before network rebuild");
            helper.setBlock(TRANSIT, Blocks.AIR.defaultBlockState());
        });
        helper.runAfterDelay(310, () -> {
            rig.transit = PipeGameTestSupport.placePipe(helper, TRANSIT, BCTransportPipes.cobbleItem);
        });
        helper.runAfterDelay(352, () -> {
            require(helper, rig.obsidian.getPipe().isConnected(Direction.EAST)
                && rig.transit.getPipe().isConnected(Direction.EAST),
                "new downstream pipe failed to restore actual item connections");
            spawn(helper, after);
            rig.suck(helper);
        });
        helper.runAfterDelay(720, () -> {
            require(helper, count(rig.sink, before) == 9 && count(rig.sink, after) == 13,
                "rebuilding the line changed the cargo ledger or disabled world pickup");
            require(helper, !rig.inFlight() && !worldCargoExists(helper, after),
                "rebuilt suction line left duplicate or stranded items");
            helper.succeed();
        });
    }

    private static final class Rig {
        final TilePipeHolder obsidian;
        TilePipeHolder transit;
        final ChestBlockEntity sink;
        Rig(GameTestHelper helper) {
            helper.setBlock(GROUND, Blocks.STONE.defaultBlockState());
            helper.setBlock(SINK, Blocks.CHEST.defaultBlockState());
            Object tile = GameTestCompat.getBlockEntity(helper, SINK);
            if (!(tile instanceof ChestBlockEntity chest)) throw new IllegalStateException("missing world pickup sink");
            sink = chest;
            obsidian = PipeGameTestSupport.placePipe(helper, PIPE, BCTransportPipes.obsidianItem);
            transit = PipeGameTestSupport.placePipe(helper, TRANSIT, BCTransportPipes.cobbleItem);
        }
        void suck(GameTestHelper helper) {
            require(helper, obsidian.getPipe().isConnected(Direction.EAST)
                && !obsidian.getPipe().isConnected(Direction.WEST),
                "obsidian suction side is not open towards the physical Item Entity");
            PipeBehaviourObsidian behaviour = (PipeBehaviourObsidian) obsidian.getPipe().getBehaviour();
            long offer = 32L * MjAmount.MICRO_MJ_PER_MJ;
            require(helper, behaviour.receivePower(offer, FluidAction.EXECUTE) < offer,
                "powered obsidian pipe did not perform its world entity suction");
        }
        boolean inFlight() {
            return ((PipeFlowItems) obsidian.getPipe().getFlow()).doesContainItems()
                || ((PipeFlowItems) transit.getPipe().getFlow()).doesContainItems();
        }
    }

    private static ItemEntity spawn(GameTestHelper helper, ItemStack stack) {
        BlockPos base = helper.absolutePos(new BlockPos(1, 1, 3));
        ItemEntity entity = new ItemEntity(helper.getLevel(), base.getX() + 0.6,
            base.getY() + 0.5, base.getZ() + 0.5, stack.copy());
        entity.setNoGravity(true);
        entity.setDeltaMovement(Vec3.ZERO);
        if (!helper.getLevel().addFreshEntity(entity)) {
            helper.fail("failed to spawn physical test Item Entity");
        }
        return entity;
    }
    private static int count(Container inventory, ItemStack template) {
        int n = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) n += stack.getCount();
        }
        return n;
    }
    private static boolean worldCargoExists(GameTestHelper helper, ItemStack cargo) {
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 3));
        net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(
            at.getX()-1, at.getY()-1, at.getZ()-1, at.getX()+4, at.getY()+3, at.getZ()+2);
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream()
            .anyMatch(entity -> ItemStack.isSameItemSameComponents(entity.getItem(), cargo));
    }
    private static ItemStack tagged(ItemStack stack, String key, long charge) {
        CompoundTag tag = ItemStackUtil.getCustomData(stack);
        CompoundTag extra = new CompoundTag();
        extra.putString("token", key);
        extra.putLong("energy", charge);
        tag.put("external_mod_payload", extra);
        ItemStackUtil.setCustomData(stack, tag);
        return stack;
    }
    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}
