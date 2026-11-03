package buildcraft.gametest;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.PermissionDecision;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotUseToolOnBlock;
import buildcraft.robotics.ai.AIRobotPlant;
import buildcraft.robotics.ai.AIRobotHarvest;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.pipe.behaviour.PipeBehaviourObsidian;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.tile.TilePipeHolder;
import com.mojang.authlib.GameProfile;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Real crop changes by robotic AIs and world drops picked up by Obsidian into a warehouse. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemRobotFarmWarehouseGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final BlockPos SOIL = new BlockPos(2, 1, 2);
    private static final BlockPos FARM_ROBOT = new BlockPos(3, 1, 2);
    private static final BlockPos OBSIDIAN = new BlockPos(4, 1, 2);
    private static final BlockPos TRANSIT = new BlockPos(5, 1, 2);
    private static final BlockPos OUTPUT = new BlockPos(6, 1, 2);
    private static final UUID BLOCKED_ID = UUID.fromString("84d6d7d7-182b-43f2-9961-7fa6de289541");
    private static final UUID WORKER_ID = UUID.fromString("55c463b1-f2ca-43a6-94f8-b3540d3ae35d");
    private static final ResourceLocation DENIAL = ResourceLocation.tryParse("buildcraft:gametest_robot_farm_denial");
    private static final GameProfile WORKER = new GameProfile(WORKER_ID, "BCTestFarmer");
    // Concurrent GameTests must not share a cached mutable FakePlayer by UUID.
    private static final GameProfile AUTHORIZED_AFTER_DENIAL = new GameProfile(
        UUID.fromString("7d298587-30f1-4350-8909-a9adf4f6645e"), "BCTestFarm2");
    private static final GameProfile BLOCKED = new GameProfile(BLOCKED_ID, "BCTestBlocked");
    private SystemRobotFarmWarehouseGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 680)
    public static void farmerHoesPlantsHarvestsAndObsidianMovesDropsIntoWarehouse(GameTestHelper helper) {
        FarmRig rig = new FarmRig(helper);
        EntityRobot robot = rig.robot(helper, WORKER);
        int[] expected = { -1, -1 };
        // World lighting is propagated asynchronously. Wait for the fixture's
        // light source before exercising the actual vanilla seed placement.
        helper.runAfterDelay(8, () -> {
            rig.till(helper, robot);
            rig.plant(helper, robot);
            rig.harvest(helper, robot, expected);
        });
        rig.suck(helper, 25, 275);
        helper.runAfterDelay(585, () -> {
            rig.assertDelivered(helper, expected);
            require(helper, rig.outputWheat() > 0, "harvest did not deliver any real wheat item");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 700)
    public static void blockedFarmerPreservesWorldAndToolWhileAuthorizedFarmCompletes(GameTestHelper helper) {
        installDenialProvider();
        FarmRig rig = new FarmRig(helper);
        EntityRobot blocked = rig.robot(helper, BLOCKED);
        blocked.setItemInUse(new ItemStack(Items.IRON_HOE));
        AIRobotUseToolOnBlock denial = new AIRobotUseToolOnBlock(blocked,
            new BlockIndex(helper.absolutePos(SOIL)));
        denial.start();
        for (int i = 0; i < 23; i++) denial.update();
        require(helper, helper.getLevel().getBlockState(helper.absolutePos(SOIL)).is(Blocks.DIRT),
            "blocked owner tilled an otherwise protected plot");
        require(helper, blocked.getItemBySlot(EquipmentSlot.MAINHAND).getDamageValue() == 0,
            "denied farming consumed tool durability");
        require(helper, rig.outputWheat() == 0, "warehouse received output before authorized work");

        int[] expected = { -1, -1 };
        // Exercise the denied -> authorized transition immediately, as a real
        // serialized server-thread interaction. The actors have different UUIDs,
        // so no mutable FakePlayer is shared between the two operations.
        EntityRobot authorized = rig.robot(helper, AUTHORIZED_AFTER_DENIAL);
        require(helper, !BLOCKED_ID.equals(authorized.getOwnerProfile().getId()),
            "authorized farmer inherited the blocked owner's identity");
        // The denied actor is checked immediately; the independent permitted
        // actor works after the test world's crop lighting has settled.
        helper.runAfterDelay(8, () -> {
            rig.till(helper, authorized);
            rig.plant(helper, authorized);
            rig.harvest(helper, authorized, expected);
        });
        rig.suck(helper, 25, 275);
        helper.runAfterDelay(595, () -> {
            rig.assertDelivered(helper, expected);
            require(helper, blocked.getItemBySlot(EquipmentSlot.MAINHAND).getDamageValue() == 0,
                "authorized work modified the blocked owner's separate equipment state");
            helper.succeed();
        });
    }

    private static final class FarmRig {
        final TilePipeHolder obsidian;
        final TilePipeHolder transit;
        final ChestBlockEntity output;
        FarmRig(GameTestHelper helper) {
            // The harvester spawns genuine ItemEntities at FARM_ROBOT, not at SOIL.
            // Give those world drops a physical collection floor while the Obsidian
            // pipe waits for its first power pulse. Without a floor, item drift
            // and gravity can move some stacks outside the pipe's suction AABB,
            // making the end-to-end ledger dependent on vanilla entity physics.
            for (int x = FARM_ROBOT.getX() - 1; x <= FARM_ROBOT.getX() + 1; x++) {
                for (int z = FARM_ROBOT.getZ() - 1; z <= FARM_ROBOT.getZ() + 1; z++) {
                    helper.setBlock(new BlockPos(x, FARM_ROBOT.getY() - 1, z),
                        Blocks.STONE.defaultBlockState());
                }
            }
            helper.setBlock(SOIL, Blocks.DIRT.defaultBlockState());
            helper.setBlock(SOIL.above(), Blocks.AIR.defaultBlockState());
            // Vanilla crops cannot survive in a dark underground GameTest
            // structure. Place a real light source, not a mock planting hook.
            helper.setBlock(SOIL.above().north(), Blocks.GLOWSTONE.defaultBlockState());
            helper.setBlock(OUTPUT, Blocks.CHEST.defaultBlockState());
            Object tile = GameTestCompat.getBlockEntity(helper, OUTPUT);
            if (!(tile instanceof ChestBlockEntity chest)) throw new IllegalStateException("missing farm output chest");
            output = chest;
            obsidian = PipeGameTestSupport.placePipe(helper, OBSIDIAN, BCTransportPipes.obsidianItem);
            transit = PipeGameTestSupport.placePipe(helper, TRANSIT, BCTransportPipes.cobbleItem);
        }
        EntityRobot robot(GameTestHelper helper, GameProfile owner) {
            EntityRobot robot = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
            robot.setOwner(owner);
            BlockPos at = helper.absolutePos(FARM_ROBOT);
            robot.setPos(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
            return robot;
        }
        void till(GameTestHelper helper, EntityRobot robot) {
            robot.setItemInUse(new ItemStack(Items.IRON_HOE));
            AIRobotUseToolOnBlock ai = new AIRobotUseToolOnBlock(robot,
                new BlockIndex(helper.absolutePos(SOIL)));
            ai.start();
            for (int i = 0; i < 25 && !helper.getLevel().getBlockState(helper.absolutePos(SOIL)).is(Blocks.FARMLAND); i++) ai.update();
            require(helper, helper.getLevel().getBlockState(helper.absolutePos(SOIL)).is(Blocks.FARMLAND)
                && robot.getItemBySlot(EquipmentSlot.MAINHAND).getDamageValue() == 1,
                "authorized robot failed to prepare farmland with one real tool use");
        }
        void plant(GameTestHelper helper, EntityRobot robot) {
            require(helper, helper.getLevel().getBlockState(helper.absolutePos(SOIL)).is(Blocks.FARMLAND),
                "farmland changed state before authorized planting");
            BlockPos cropPos = helper.absolutePos(SOIL.above());
            require(helper, Blocks.WHEAT.defaultBlockState().canSurvive(helper.getLevel(), cropPos),
                "GameTest planting plot has invalid vanilla survival conditions: rawLight="
                    + helper.getLevel().getRawBrightness(cropPos, 0)
                    + ", sky=" + helper.getLevel().canSeeSky(cropPos));
            robot.setItemInUse(new ItemStack(Items.WHEAT_SEEDS, 6));
            boolean plantable = BuildCraftApi.service(BuildCraftServices.CROPS).canSustainPlant(
                helper.getLevel(), robot.getItemBySlot(EquipmentSlot.MAINHAND), helper.absolutePos(SOIL));
            AIRobotPlant ai = new AIRobotPlant(robot, new BlockIndex(helper.absolutePos(SOIL)));
            ai.start();
            for (int i = 0; i < 24 && !helper.getLevel().getBlockState(helper.absolutePos(SOIL.above())).is(Blocks.WHEAT); i++) ai.update();
            require(helper, helper.getLevel().getBlockState(helper.absolutePos(SOIL.above())).is(Blocks.WHEAT),
                "real planter AI failed to put seed on its freshly prepared farmland: soil="
                    + helper.getLevel().getBlockState(helper.absolutePos(SOIL))
                    + ", cropServiceCanPlant=" + plantable
                    + ", remainingSeeds=" + robot.getItemBySlot(EquipmentSlot.MAINHAND).getCount()
                    + plantingDiagnostics(helper, robot));
            require(helper, robot.getItemBySlot(EquipmentSlot.MAINHAND).getCount() == 5,
                "planter did not use exactly one seed from its held stack");
        }
        void harvest(GameTestHelper helper, EntityRobot robot, int[] expected) {
            // Force a mature vanilla crop instead of relying on randomTick timing.
            helper.setBlock(SOIL.above(), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
            AIRobotHarvest ai = new AIRobotHarvest(robot, new BlockIndex(helper.absolutePos(SOIL.above())));
            ai.start();
            for (int i = 0; i < 24 && !helper.getLevel().getBlockState(helper.absolutePos(SOIL.above())).isAir(); i++) ai.update();
            require(helper, helper.getLevel().getBlockState(helper.absolutePos(SOIL.above())).isAir(),
                "robot did not harvest a physically mature wheat crop");
            expected[0] = groundDrops(helper, Items.WHEAT);
            expected[1] = groundDrops(helper, Items.WHEAT_SEEDS);
            require(helper, expected[0] > 0, "mature harvest did not spawn any world wheat drops");
        }
        void suck(GameTestHelper helper, int begin, int end) {
            for (int tick = begin; tick <= end; tick += 14) {
                helper.runAfterDelay(tick, () -> {
                    require(helper, obsidian.getPipe().isConnected(Direction.EAST)
                        && transit.getPipe().isConnected(Direction.EAST),
                        "farm-to-warehouse pipeline disconnected during real pickup");
                    ((PipeBehaviourObsidian) obsidian.getPipe().getBehaviour())
                        .receivePower(16L * MjAmount.MICRO_MJ_PER_MJ, FluidAction.EXECUTE);
                });
            }
        }
        void assertDelivered(GameTestHelper helper, int[] expected) {
            require(helper, expected[0] > 0 && expected[1] >= 0,
                "harvest amount was not captured before the suction pipeline started");
            int wheatDelivered = PipeGameTestSupport.countItem(output, Items.WHEAT);
            int seedsDelivered = PipeGameTestSupport.countItem(output, Items.WHEAT_SEEDS);
            require(helper, wheatDelivered == expected[0] && seedsDelivered == expected[1],
                "robotic farm->world drops->obsidian->warehouse changed the harvest ledger: "
                    + "expected wheat=" + expected[0] + ", seeds=" + expected[1]
                    + "; warehouse wheat=" + wheatDelivered + ", seeds=" + seedsDelivered
                    + "; remaining world wheat=" + groundDrops(helper, Items.WHEAT)
                    + ", seeds=" + groundDrops(helper, Items.WHEAT_SEEDS)
                    + "; obsidian inFlight=" + ((PipeFlowItems) obsidian.getPipe().getFlow()).doesContainItems()
                    + ", transit inFlight=" + ((PipeFlowItems) transit.getPipe().getFlow()).doesContainItems());
            require(helper, groundDrops(helper, Items.WHEAT) == 0
                && groundDrops(helper, Items.WHEAT_SEEDS) == 0,
                "harvested Item Entities were duplicated or stranded outside the warehouse");
            require(helper, !((PipeFlowItems) obsidian.getPipe().getFlow()).doesContainItems()
                && !((PipeFlowItems) transit.getPipe().getFlow()).doesContainItems(),
                "farm automation finished with phantom items in the transport network");
        }
        int outputWheat() { return PipeGameTestSupport.countItem(output, Items.WHEAT); }
    }

    /** Read-only evidence when a real placement fails; never retries or bypasses a denied action. */
    private static String plantingDiagnostics(GameTestHelper helper, EntityRobot robot) {
        BlockPos soil = helper.absolutePos(SOIL);
        Player actor = FakePlayerProvider.INSTANCE.getFakePlayer(
            helper.getLevel(), robot.getOwnerProfile(), robot.blockPosition());
        ItemStack seeds = robot.getItemBySlot(EquipmentSlot.MAINHAND);
        return ", owner=" + robot.getOwnerProfile().getId()
            + ", permission=" + RobotAutomationSupport.permitsBlock(
                robot, helper.getLevel(), soil, WorldOperationKind.ITEM_USE, OperationMode.EXECUTE)
            + ", mayBuild=" + actor.getAbilities().mayBuild
            + ", mayInteract=" + helper.getLevel().mayInteract(actor, soil.above())
            + ", mayUseItemAt=" + actor.mayUseItemAt(soil, Direction.UP, seeds)
            + ", soilPos=" + soil
            + ", above=" + helper.getLevel().getBlockState(soil.above())
            + ", rawLight=" + helper.getLevel().getRawBrightness(soil.above(), 0)
            + ", sky=" + helper.getLevel().canSeeSky(soil.above())
            + ", wheatCanSurvive=" + Blocks.WHEAT.defaultBlockState().canSurvive(helper.getLevel(), soil.above());
    }
    private static int groundDrops(GameTestHelper helper, net.minecraft.world.item.Item item) {
        BlockPos first = helper.absolutePos(BlockPos.ZERO);
        BlockPos last = helper.absolutePos(new BlockPos(6, 2, 6));
        AABB area = new AABB(first.getX(), first.getY(), first.getZ(),
            last.getX() + 1, last.getY() + 1, last.getZ() + 1);
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream()
            .filter(e -> e.getItem().is(item)).mapToInt(e -> e.getItem().getCount()).sum();
    }
    private static synchronized void installDenialProvider() {
        var permissions = BuildCraftApi.service(BuildCraftServices.PERMISSIONS);
        if (permissions.providers().stream().anyMatch(p -> DENIAL.equals(p.id()))) return;
        permissions.register(DENIAL, 100_001, context ->
            context.actor().playerId().filter(BLOCKED_ID::equals).isPresent()
                && (context.operation() == WorldOperationKind.ITEM_USE
                    || context.operation() == WorldOperationKind.BLOCK_BREAK
                    || context.operation() == WorldOperationKind.BLOCK_PLACE)
                ? PermissionDecision.deny(DENIAL, "test farm plot owned by another player")
                : PermissionDecision.pass());
    }
    private static void require(GameTestHelper helper, boolean ok, String message) {
        if (!ok) helper.fail(message);
    }
}
