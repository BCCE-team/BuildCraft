package buildcraft.robotics.ai;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.FakePlayerProvider;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Harvests one mature crop block and drops the crop output at the robot, matching BuildCraft 7.1.x behavior. */
public class AIRobotHarvest extends AIRobot {
    private static final int HARVEST_DELAY_TICKS = 12;
    private BlockIndex blockFound;
    private int delay;

    public AIRobotHarvest(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotHarvest(EntityRobotBase robot, BlockIndex blockFound) {
        this(robot);
        this.blockFound = blockFound;
    }

    @Override
    public void start() {
        if (blockFound != null) {
            robot.aimItemAt(blockFound.toBlockPos());
            robot.setItemActive(true);
        }
    }

    @Override
    public void update() {
        //? if <1.20 {
        if (!(robot.level instanceof ServerLevel serverLevel) || blockFound == null) {
        //?} else {
        /*?
        if (!(robot.level() instanceof ServerLevel serverLevel) || blockFound == null) {
        ?*/
        //?}
            setSuccess(false);
            terminate();
            return;
        }

        if (delay++ <= HARVEST_DELAY_TICKS) {
            return;
        }

        BlockPos pos = blockFound.toBlockPos();
        if (!BuildCraftApi.service(BuildCraftServices.CROPS).isMature(serverLevel, serverLevel.getBlockState(pos), pos)) {
            setSuccess(false);
            terminate();
            return;
        }

        if (!RobotAutomationSupport.permitsBlock(
            robot, serverLevel, pos, WorldOperationKind.BLOCK_BREAK, OperationMode.EXECUTE
        )) {
            setSuccess(false);
            terminate();
            return;
        }
        GameProfile owner = RobotAutomationSupport.owner(robot);
        var fakePlayer = FakePlayerProvider.INSTANCE.getFakePlayer(serverLevel, owner, robot.blockPosition());
        if (!BlockUtil.canBreakBlock(serverLevel, pos, fakePlayer)) {
            setSuccess(false);
            terminate();
            return;
        }

        NonNullList<ItemStack> drops = NonNullList.create();
        if (!BuildCraftApi.service(BuildCraftServices.CROPS).harvest(serverLevel, pos, drops, fakePlayer)) {
            setSuccess(false);
            terminate();
            return;
        }

        for (ItemStack stack : drops) {
            if (!stack.isEmpty()) {
                BlockUtil.dropItem(serverLevel, robot.blockPosition(), 6000, stack.copy());
            }
        }
        terminate();
    }

    @Override
    public void end() {
        robot.setItemActive(false);
    }

    @Override
    public boolean canLoadFromNBT() {
        return true;
    }

    @Override
    public void writeSelfToNBT(CompoundTag nbt) {
        if (blockFound != null) {
            CompoundTag tag = new CompoundTag();
            blockFound.writeTo(tag);
            nbt.put("blockFound", tag);
        }
        nbt.putInt("delay", delay);
    }

    @Override
    public void loadSelfFromNBT(CompoundTag nbt) {
        if (nbt.contains("blockFound")) {
            blockFound = new BlockIndex(nbt.getCompound("blockFound"));
        }
        delay = nbt.getInt("delay");
    }
}
