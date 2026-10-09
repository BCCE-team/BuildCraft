package buildcraft.robotics.ai;

import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.automation.UseItemRequest;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Uses the equipped tool on a target block; the farmer board uses it to hoe dirt into farmland. */
public class AIRobotUseToolOnBlock extends AIRobot {
    private static final int USE_DELAY_TICKS = 12;

    private BlockIndex useToBlock;
    private int useCycles;

    public AIRobotUseToolOnBlock(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotUseToolOnBlock(EntityRobotBase robot, BlockIndex useToBlock) {
        this(robot);
        this.useToBlock = useToBlock;
    }

    @Override
    public void start() {
        if (useToBlock == null) {
            setSuccess(false);
            terminate();
            return;
        }
        robot.aimItemAt(useToBlock.toBlockPos());
        robot.setItemActive(true);
    }

    @Override
    public void update() {
        //? if <1.20 {
        if (!(robot.level instanceof ServerLevel serverLevel) || useToBlock == null) {
        //?} else {
        /*?
        if (!(robot.level() instanceof ServerLevel serverLevel) || useToBlock == null) {
        ?*/
        //?}
            setSuccess(false);
            terminate();
            return;
        }

        if (useCycles++ <= USE_DELAY_TICKS) {
            return;
        }

        ItemStack held = robot.getItemBySlot(EquipmentSlot.MAINHAND);
        if (held.isEmpty()) {
            setSuccess(false);
            terminate();
            return;
        }

        BlockPos pos = useToBlock.toBlockPos();
        if (!RobotAutomationSupport.permits(new UseItemRequest(
                serverLevel, robot.blockPosition(), pos, Direction.UP, held,
                RobotAutomationSupport.actor(robot), OperationMode.SIMULATE))) {
            setSuccess(false);
            terminate();
            return;
        }
        if (!RobotAutomationSupport.permits(new UseItemRequest(
                serverLevel, robot.blockPosition(), pos, Direction.UP, held,
                RobotAutomationSupport.actor(robot), OperationMode.EXECUTE))) {
            setSuccess(false);
            terminate();
            return;
        }
        GameProfile owner = robot instanceof EntityRobot entityRobot
                ? entityRobot.getOwnerProfile()
                : FakePlayerProvider.NULL_PROFILE;
        Player player = FakePlayerProvider.INSTANCE.getFakePlayer(serverLevel, owner, robot.blockPosition());

        ItemStack before = held.copy();
        boolean used = BlockUtil.useItemOnBlock(serverLevel, player, held, pos, Direction.UP);
        if (!used) {
            setSuccess(false);
            if (!before.isDamageableItem()) {
                BlockUtil.dropItem(serverLevel, robot.blockPosition(), 6000, before);
                robot.setItemInUse(ItemStack.EMPTY);
            } else {
                robot.setItemInUse(held);
            }
            terminate();
            return;
        }

        if (held.isDamageableItem()) {
            // Some tool implementations handle damage during useOn(), some do not. Keep the original BuildCraft
            // farmer behaviour: using the hoe costs one durability point.
            if (held.getDamageValue() == before.getDamageValue()) {
                if (held.hurt(1, serverLevel.getRandom(), player instanceof ServerPlayer serverPlayer ? serverPlayer : null)) {
                    held.shrink(1);
                }
            }
            robot.setItemInUse(held.isEmpty() ? ItemStack.EMPTY : held);
        } else {
            // A successful use does not imply that an unbreakable/modded tool was consumed. Keep the stack returned
            // by useOn(); consumable items are already represented by an empty or reduced stack here.
            robot.setItemInUse(held);
        }
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
        if (useToBlock != null) {
            CompoundTag tag = new CompoundTag();
            useToBlock.writeTo(tag);
            nbt.put("blockFound", tag);
        }
        nbt.putInt("useCycles", useCycles);
    }

    @Override
    public void loadSelfFromNBT(CompoundTag nbt) {
        if (nbt.contains("blockFound")) {
            useToBlock = new BlockIndex(nbt.getCompound("blockFound"));
        }
        useCycles = nbt.getInt("useCycles");
    }
}
