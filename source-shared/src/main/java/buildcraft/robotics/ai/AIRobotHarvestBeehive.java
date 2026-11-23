package buildcraft.robotics.ai;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.automation.UseItemRequest;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.robotics.boards.BoardRobotBeekeeper;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.scheduler.RobotProcessorWorkScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Harvest a full smoked hive, matching vanilla yield and tool consumption without angering bees. */
public final class AIRobotHarvestBeehive extends AIRobot {
    private static final int HARVEST_DELAY_TICKS = 12;
    private BlockIndex hive;
    private int delay;

    /** Required by RobotManager when reconstructing a saved AI. */
    public AIRobotHarvestBeehive(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotHarvestBeehive(EntityRobotBase robot, BlockIndex hive) {
        this(robot);
        this.hive = hive;
    }

    @Override
    public void start() {
        if (hive != null) {
            robot.aimItemAt(hive.toBlockPos());
            robot.setItemActive(true);
        }
    }

    @Override
    public void update() {
        if (!(robot.getCommandSenderWorld() instanceof ServerLevel level) || hive == null) {
            fail();
            return;
        }
        if (++delay <= HARVEST_DELAY_TICKS) return;

        BlockPos pos = hive.toBlockPos();
        ItemStack held = robot.getItemBySlot(EquipmentSlot.MAINHAND);
        if (!level.hasChunkAt(pos) || !BoardRobotBeekeeper.isHarvestable(level, pos)
                || !BoardRobotBeekeeper.isUsableTool(held)
                || !RobotProcessorWorkScheduler.allowsRobotTarget(robot, pos)) {
            fail();
            return;
        }
        // Simulate the public API permission before an authoritative world edit. Do not
        // double-use the block via an EXECUTE UseItemRequest (that may perform the use).
        if (!RobotAutomationSupport.permits(new UseItemRequest(
                level, robot.blockPosition(), pos, Direction.UP, held,
                RobotAutomationSupport.actor(robot), OperationMode.SIMULATE))
                || !RobotAutomationSupport.permitsBlock(robot, level, pos,
                WorldOperationKind.ITEM_USE, OperationMode.EXECUTE)) {
            fail();
            return;
        }

        // Check the honey level and smoke a second time immediately before the edit;
        // reservations prevent two robots from draining the same hive simultaneously.
        if (!BoardRobotBeekeeper.isHarvestable(level, pos)) {
            fail();
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!level.setBlock(pos, state.setValue(BeehiveBlock.HONEY_LEVEL, 0), 3)) {
            fail();
            return;
        }

        boolean bottle = held.is(Items.GLASS_BOTTLE);
        if (bottle) {
            held.shrink(1);
            robot.setItemInUse(held.isEmpty() ? ItemStack.EMPTY : held);
            BlockUtil.dropItem(level, pos, 6000, new ItemStack(Items.HONEY_BOTTLE));
            level.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
        } else {
            // Vanilla shearing yields three combs and costs one durability point.
            Player player = FakePlayerProvider.INSTANCE.getFakePlayer(level,
                    RobotAutomationSupport.owner(robot), robot.blockPosition());
            //? if <1.21 {
            if (held.hurt(1, level.getRandom(), player instanceof ServerPlayer serverPlayer ? serverPlayer : null)) {
                held.shrink(1);
            }
            //?} else {
            /*?
            held.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
            ?*/
            //?}
            robot.setItemInUse(held.isEmpty() ? ItemStack.EMPTY : held);
            BlockUtil.dropItem(level, pos, 6000, new ItemStack(Items.HONEYCOMB, 3));
            level.playSound(null, pos, SoundEvents.BEEHIVE_SHEAR, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        // The smoke is mandatory: no angry bees are created or released by automation.
        // World drops are collected by Picker Robots, just like Harvester and Shepherd outputs.
        terminate();
    }

    private void fail() {
        setSuccess(false);
        terminate();
    }

    @Override
    public void end() {
        robot.setItemActive(false);
    }

    @Override
    public int getEnergyCost() {
        return 8;
    }

    @Override
    public boolean canLoadFromNBT() {
        return true;
    }

    @Override
    public void writeSelfToNBT(CompoundTag nbt) {
        if (hive != null) {
            CompoundTag position = new CompoundTag();
            hive.writeTo(position);
            nbt.put("hive", position);
        }
        nbt.putInt("delay", delay);
    }

    @Override
    public void loadSelfFromNBT(CompoundTag nbt) {
        if (nbt.contains("hive")) hive = new BlockIndex(nbt.getCompound("hive"));
        delay = nbt.getInt("delay");
    }
}
