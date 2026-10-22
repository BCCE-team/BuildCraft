package buildcraft.robotics.ai;

import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.api.v2.automation.StripesOutput;
import buildcraft.transport.internal.pipe.IItemPipe;
import buildcraft.transport.pipe.StripesRegistry;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.robotics.entity.EntityRobot;
import com.mojang.authlib.GameProfile;
import buildcraft.lib.misc.InventoryUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Applies the transport Stripes item handlers from a robot-held item to the reserved target block. */
public class AIRobotStripesHandler extends AIRobot implements StripesOutput {
    private static final int USE_DELAY_TICKS = 12;

    private BlockIndex useToBlock;
    private int useCycles;

    public AIRobotStripesHandler(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotStripesHandler(EntityRobotBase robot, BlockIndex useToBlock) {
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

        BlockPos target = useToBlock.toBlockPos();
        Direction direction = chooseDirection(target);
        if (!RobotAutomationSupport.permitsBlock(
            robot, serverLevel, target, WorldOperationKind.ITEM_USE, OperationMode.EXECUTE
        ) || workingIsBlockPlacement(held) && !RobotAutomationSupport.permitsBlock(
            robot, serverLevel, target, WorldOperationKind.BLOCK_PLACE, OperationMode.EXECUTE
        )) {
            setSuccess(false);
            terminate();
            return;
        }
        GameProfile owner = RobotAutomationSupport.owner(robot);
        Player player = FakePlayerProvider.INSTANCE.getFakePlayer(serverLevel, owner, robot.blockPosition());
        player.getInventory().clearContent();
        player.setPos(robot.getX(), robot.getY(), robot.getZ());

        ItemStack working = held.copy();
        player.getInventory().setItem(player.getInventory().selected, working);

        boolean handled = handleHeldItem(serverLevel, target, direction, working, player);
        if (handled) {
            robot.setItemInUse(ItemStack.EMPTY);
            returnPlayerInventory(player, direction);
        } else {
            player.getInventory().clearContent();
            setSuccess(false);
        }
        terminate();
    }

    private static boolean workingIsBlockPlacement(ItemStack stack) {
        return stack.getItem() instanceof BlockItem;
    }

    private boolean handleHeldItem(ServerLevel serverLevel, BlockPos target, Direction direction, ItemStack working,
                                   Player player) {
        if (working.getItem() instanceof IItemPipe && working.getItem() instanceof BlockItem) {
            return placePipeAsBlock(serverLevel, target, direction, working, player);
        }

        BlockPos activatorPos = target.relative(direction.getOpposite());
        return StripesRegistry.INSTANCE.handleItem(serverLevel, activatorPos, direction, working, player, this);
    }

    private boolean placePipeAsBlock(Level level, BlockPos target, Direction direction, ItemStack working, Player player) {
        if (!level.isEmptyBlock(target)) {
            return false;
        }
        InteractionResult result = working.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(target), direction, target, false)));
        return result.consumesAction();
    }

    private void returnPlayerInventory(Player player, Direction direction) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().removeItemNoUpdate(slot);
            if (!stack.isEmpty()) {
                sendItem(stack, direction);
            }
        }
        player.getInventory().clearContent();
    }

    private Direction chooseDirection(BlockPos target) {
        BlockPos robotPos = robot.blockPosition();
        int dx = target.getX() - robotPos.getX();
        int dy = target.getY() - robotPos.getY();
        int dz = target.getZ() - robotPos.getZ();
        int ax = Math.abs(dx);
        int ay = Math.abs(dy);
        int az = Math.abs(dz);

        if (ay > ax && ay > az) {
            return dy >= 0 ? Direction.UP : Direction.DOWN;
        }
        if (ax >= az && ax > 0) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        if (az > 0) {
            return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return Direction.NORTH;
    }

    @Override
    public void end() {
        robot.setItemActive(false);
    }

    @Override
    public int getEnergyCost() {
        return 15;
    }

    @Override
    public boolean sendItem(ItemStack itemStack, Direction from) {
        if (itemStack.isEmpty()) {
            return true;
        }
        //? if <1.20 {
        InventoryUtil.drop(robot.level, robot.blockPosition(), itemStack.copy());
        //?} else {
        /*?
        InventoryUtil.drop(robot.level(), robot.blockPosition(), itemStack.copy());
        ?*/
        //?}
        return true;
    }

    @Override
    public void dropItem(ItemStack itemStack, Direction from) {
        if (!itemStack.isEmpty()) {
            //? if <1.20 {
            InventoryUtil.drop(robot.level, robot.blockPosition(), itemStack.copy());
            //?} else {
            /*?
            InventoryUtil.drop(robot.level(), robot.blockPosition(), itemStack.copy());
            ?*/
            //?}
        }
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
            nbt.put("useToBlock", tag);
        }
        nbt.putInt("useCycles", useCycles);
    }

    @Override
    public void loadSelfFromNBT(CompoundTag nbt) {
        if (nbt.contains("useToBlock")) {
            useToBlock = new BlockIndex(nbt.getCompound("useToBlock"));
        }
        useCycles = nbt.getInt("useCycles");
    }
}
