package buildcraft.gametest;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.permission.PermissionDecision;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.lib.internal.statement.StatementSlot;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.ai.AIRobotLoad;
import buildcraft.robotics.ai.AIRobotUnload;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.statements.ActionStationProvideItems;
import buildcraft.robotics.statements.ActionStationAcceptItems;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.internal.IInjectable;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.tile.TilePipeHolder;
import com.mojang.authlib.GameProfile;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Two robot owners share real inventories; permission denial never debits or mints items. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemOwnerWarehouseGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final UUID DENIED_ID = UUID.fromString("4ed3a74e-4ff0-433b-a0fd-b9d0ecba04ef");
    private static final UUID ALLOWED_ID = UUID.fromString("b64b3673-07b7-4027-adb6-71e60b840bda");
    private static final ResourceLocation PROVIDER = ResourceLocation.tryParse("buildcraft:gametest_denied_warehouse");
    private static final GameProfile DENIED = new GameProfile(DENIED_ID, "BCTestBlockedCarrier");
    private static final GameProfile ALLOWED = new GameProfile(ALLOWED_ID, "BCTestAllowedCarrier");
    private static final BlockPos SOURCE = new BlockPos(1, 1, 3);
    private static final BlockPos PIPE = new BlockPos(2, 1, 3);
    private static final BlockPos TRANSIT = new BlockPos(3, 1, 3);
    private static final BlockPos SINK = new BlockPos(4, 1, 3);
    private SystemOwnerWarehouseGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 560)
    public static void deniedRobotCannotWithdrawWhileAllowedRobotDeliversChargedStackViaPipes(GameTestHelper helper) {
        installPermissionProvider();
        ChestBlockEntity source = chest(helper, SOURCE);
        ChestBlockEntity sink = chest(helper, SINK);
        TilePipeHolder first = PipeGameTestSupport.placePipe(helper, PIPE, BCTransportPipes.cobbleItem);
        TilePipeHolder last = PipeGameTestSupport.placePipe(helper, TRANSIT, BCTransportPipes.cobbleItem);
        ItemStack payload = tagged(new ItemStack(Items.APPLE, 18), "same-warehouse", 123_400L);
        source.setItem(0, payload.copy());
        source.setChanged();
        EntityRobot denied = robot(helper, DENIED);
        EntityRobot allowed = robot(helper, ALLOWED);
        ChestStation sourceDock = new ChestStation(helper, SOURCE, source, true);
        PipeStation delivery = new PipeStation(helper, first);

        helper.runAfterDelay(12, () -> {
            require(helper, first.getPipe().isConnected(Direction.WEST)
                && last.getPipe().isConnected(Direction.EAST),
                "source-to-warehouse physical pipe line did not connect");
            require(helper, !AIRobotLoad.load(denied, sourceDock, stack -> same(stack, payload), 18, false)
                && !AIRobotLoad.load(denied, sourceDock, stack -> same(stack, payload), 18, true),
                "blocked owner bypassed the inventory permission provider");
            require(helper, count(source, payload) == 18 && count(denied, payload) == 0,
                "permission denial consumed source cargo or injected it into the robot");
            require(helper, AIRobotLoad.load(allowed, sourceDock, stack -> same(stack, payload), 18, true),
                "authorized owner could not retrieve the permitted charged stack");
            require(helper, source.getItem(0).isEmpty() && count(allowed, payload) == 18,
                "authorized withdrawal changed ItemStack identity or source count");
            require(helper, AIRobotUnload.unload(allowed, delivery, true, true),
                "authorized carrier could not dispatch stock into physical BCCE pipes");
        });
        helper.runAfterDelay(495, () -> {
            require(helper, count(sink, payload) == 18,
                "permitted carrier did not complete the world-to-pipe-to-warehouse delivery");
            require(helper, count(source, payload) == 0 && count(allowed, payload) == 0
                && count(denied, payload) == 0,
                "owner permission transition duplicated inventory or robot cargo");
            require(helper, !((PipeFlowItems) first.getPipe().getFlow()).doesContainItems()
                && !((PipeFlowItems) last.getPipe().getFlow()).doesContainItems(),
                "completed authorized delivery left phantom travelling items");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 120)
    public static void deniedRobotUnloadDoesNotTouchDestinationOrOtherOwnersCargo(GameTestHelper helper) {
        installPermissionProvider();
        ChestBlockEntity output = chest(helper, SINK);
        ItemStack deniedCargo = tagged(new ItemStack(Items.CARROT, 9), "denied-charge", 101L);
        ItemStack allowedCargo = tagged(new ItemStack(Items.CARROT, 7), "allowed-charge", 202L);
        EntityRobot blocked = robot(helper, DENIED);
        EntityRobot permitted = robot(helper, ALLOWED);
        blocked.setItem(0, deniedCargo.copy());
        permitted.setItem(0, allowedCargo.copy());
        ChestStation destination = new ChestStation(helper, SINK, output, false);
        require(helper, !AIRobotUnload.unload(blocked, destination, false, true)
            && !AIRobotUnload.unload(blocked, destination, true, true),
            "unauthorized robot simulation/execute accepted a protected unload");
        require(helper, output.isEmpty() && count(blocked, deniedCargo) == 9,
            "denied unload changed the output inventory or stripped the robot's components");
        require(helper, AIRobotUnload.unload(permitted, destination, false, true),
            "authorized unload simulation did not accept the compatible destination");
        require(helper, count(permitted, allowedCargo) == 7 && output.isEmpty(),
            "authorized SIMULATE modified the real warehouse or the robot");
        require(helper, AIRobotUnload.unload(permitted, destination, true, true),
            "authorized robot failed to unload in the same protected factory");
        require(helper, count(output, allowedCargo) == 7 && count(output, deniedCargo) == 0,
            "target warehouse accepted forbidden cargo or lost the permitted charge component");
        require(helper, count(blocked, deniedCargo) == 9 && count(permitted, allowedCargo) == 0,
            "multi-owner transaction changed the blocked owner's inventory");
        helper.succeed();
    }

    private static synchronized void installPermissionProvider() {
        var registry = BuildCraftApi.service(BuildCraftServices.PERMISSIONS);
        if (registry.providers().stream().anyMatch(p -> PROVIDER.equals(p.id()))) return;
        registry.register(PROVIDER, 100_000, context ->
            context.operation() == WorldOperationKind.INVENTORY_ACCESS
                && context.actor().playerId().filter(DENIED_ID::equals).isPresent()
                ? PermissionDecision.deny(PROVIDER, "test owner cannot withdraw or deliver inventory")
                : PermissionDecision.pass());
    }
    private static EntityRobot robot(GameTestHelper helper, GameProfile owner) {
        EntityRobot robot = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
        robot.setOwner(owner);
        return robot;
    }
    private static ChestBlockEntity chest(GameTestHelper helper, BlockPos position) {
        helper.setBlock(position, Blocks.CHEST.defaultBlockState());
        Object entity = GameTestCompat.getBlockEntity(helper, position);
        if (!(entity instanceof ChestBlockEntity chest)) throw new IllegalStateException("missing real warehouse");
        return chest;
    }
    private static class ChestStation extends DockingStation {
        private final Container inventory;
        private final List<StatementSlot> actions;
        ChestStation(GameTestHelper helper, BlockPos location, Container inventory, boolean pickup) {
            super(new BlockIndex(helper.absolutePos(location)), Direction.UP);
            this.inventory = inventory;
            setLevel(helper.getLevel());
            StatementSlot slot = new StatementSlot();
            slot.statement = pickup ? new ActionStationProvideItems() : new ActionStationAcceptItems();
            slot.parameters = new IStatementParameter[0];
            actions = List.of(slot);
        }
        @Override public Iterable<StatementSlot> getActiveActions() { return actions; }
        @Override public Container getItemInput() { return inventory; }
    }
    private static class PipeStation extends DockingStation {
        private final List<StatementSlot> actions;
        private final IInjectable output;
        PipeStation(GameTestHelper helper, TilePipeHolder pipe) {
            super(new BlockIndex(pipe.getPipePos()), Direction.WEST);
            setLevel(helper.getLevel());
            StatementSlot slot = new StatementSlot();
            slot.statement = new ActionStationAcceptItems();
            slot.parameters = new IStatementParameter[0];
            actions = List.of(slot);
            output = new IInjectable() {
                @Override public boolean canInjectItems(Direction side) {
                    return side == Direction.WEST
                        && !((PipeFlowItems) pipe.getPipe().getFlow()).doesContainItems();
                }
                @Override public ItemStack injectItem(ItemStack stack, boolean execute, Direction side,
                        DyeColor color, double speed) {
                    PipeFlowItems flow = (PipeFlowItems) pipe.getPipe().getFlow();
                    if (side != Direction.WEST || flow.doesContainItems()) return stack;
                    return flow.injectItemFromRobotStation(stack.copy(), execute, side, color, speed);
                }
            };
        }
        @Override public Iterable<StatementSlot> getActiveActions() { return actions; }
        @Override public IInjectable getItemOutput() { return output; }
        @Override public Direction getItemOutputSide() { return Direction.WEST; }
    }
    private static boolean same(ItemStack left, ItemStack right) {
        return ItemStack.isSameItemSameTags(left, right);
    }

    private static int count(Container inventory, ItemStack expected) {
        int result = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameTags(stack, expected)) result += stack.getCount();
        }
        return result;
    }
    private static ItemStack tagged(ItemStack stack, String key, long energy) {
        CompoundTag custom = stack.getOrCreateTag();
        CompoundTag external = new CompoundTag();
        external.putString("charge_owner", key);
        external.putLong("stored_energy", energy);
        custom.put("external_components", external);
        
        return stack;
    }
    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}
