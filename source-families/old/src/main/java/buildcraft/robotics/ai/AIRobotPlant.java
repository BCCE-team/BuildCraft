package buildcraft.robotics.ai;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.lib.misc.FakePlayerProvider;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Plants one item from the robot's held seed stack on the reserved soil block. */
public class AIRobotPlant extends AIRobot {
    private static final int PLANT_DELAY_TICKS = 12;

    private BlockIndex blockFound;
    private int delay;

    public AIRobotPlant(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotPlant(EntityRobotBase robot, BlockIndex blockFound) {
        this(robot);
        this.blockFound = blockFound;
    }

    @Override
    public void start() {
        if (blockFound == null) {
            setSuccess(false);
            terminate();
            return;
        }
        robot.aimItemAt(blockFound.toBlockPos());
        robot.setItemActive(true);
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

        if (delay++ <= PLANT_DELAY_TICKS) {
            return;
        }

        ItemStack held = robot.getItemBySlot(EquipmentSlot.MAINHAND);
        if (held.isEmpty()) {
            setSuccess(false);
            terminate();
            return;
        }

        BlockPos pos = blockFound.toBlockPos();
        if (!RobotAutomationSupport.permitsBlock(
            robot, serverLevel, pos, WorldOperationKind.ITEM_USE, OperationMode.EXECUTE
        )) {
            setSuccess(false);
            terminate();
            return;
        }
        GameProfile owner = RobotAutomationSupport.owner(robot);
        Player player = FakePlayerProvider.INSTANCE.getFakePlayer(serverLevel, owner, robot.blockPosition());
        player.setPos(robot.getX(), robot.getY(), robot.getZ());

        ItemStack before = held.copy();
        int beforeCount = held.getCount();
        boolean planted = BuildCraftApi.service(BuildCraftServices.CROPS).plant(serverLevel, player, held, pos);
        if (!planted) {
            setSuccess(false);
            robot.setItemInUse(before);
            terminate();
            return;
        }

        // Keep the remaining seeds in the robot's hand so the planter can work a whole batch before returning
        // to a station. Some modded plant handlers may not shrink the stack themselves, so consume one item here
        // if the use action reported success but left the count unchanged.
        if (held.getCount() >= beforeCount && ItemStack.isSameItemSameTags(held, before)) {
            held.shrink(1);
        }
        robot.setItemInUse(held.isEmpty() ? ItemStack.EMPTY : held);
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
