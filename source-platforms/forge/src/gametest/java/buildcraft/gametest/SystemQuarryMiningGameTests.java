package buildcraft.gametest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import buildcraft.api.v2.energy.MjAmount;
import buildcraft.builders.BCBuildersBlocks;
import buildcraft.builders.tile.TileQuarry;
import buildcraft.builders.item.QuarryEnchantments;
import buildcraft.builders.tile.TileQuarryDrillCollision;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.mj.MjBattery;
import buildcraft.lib.misc.data.Box;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;

/**
 * Real Quarry server-tick workloads, including block break, item export and
 * watchdog rediscovery. Only area selection is fixed by the fixture because
 * the normal default 11x11 area exceeds the existing 7x3x7 GameTest structure.
 * The full marker-to-quarry boundary check is a separate larger-fixture task.
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemQuarryMiningGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final BlockPos QUARRY = new BlockPos(1, 1, 3);
    private static final BlockPos CHEST = new BlockPos(0, 1, 3);
    private static final BlockPos FIRST = new BlockPos(3, 0, 3);
    private static final BlockPos SECOND = new BlockPos(4, 0, 3);
    private static final BlockPos PROTECTED = new BlockPos(3, 0, 4);
    private static final BlockPos OUTSIDE = new BlockPos(6, 0, 3);

    private SystemQuarryMiningGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 1900)
    public static void liveQuarryMinesInsideAreaExportsDropsAndSkipsUnbreakables(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        helper.setBlock(FIRST, Blocks.STONE.defaultBlockState());
        helper.setBlock(SECOND, Blocks.STONE.defaultBlockState());
        helper.setBlock(PROTECTED, Blocks.BEDROCK.defaultBlockState());
        helper.setBlock(OUTSIDE, Blocks.STONE.defaultBlockState());
        rig.activate(helper);
        rig.keepPowered(helper, 20, 1700);
        long power = rig.power.getStored();

        helper.runAfterDelay(1700, () -> {
            require(helper, rig.isMined(helper, FIRST) && rig.isMined(helper, SECOND),
                "live Quarry failed to mine both real stone blocks inside the configured area: " + rig.diagnostics());
            require(helper, PipeGameTestSupport.countItem(rig.warehouse, Items.COBBLESTONE) == 2,
                "Quarry block mining failed to export exactly two cobblestone drops");
            require(helper, helper.getLevel().getBlockState(helper.absolutePos(PROTECTED)).is(Blocks.BEDROCK),
                "Quarry destroyed a non-mineable protected block");
            require(helper, helper.getLevel().getBlockState(helper.absolutePos(OUTSIDE)).is(Blocks.STONE),
                "Quarry mined beyond the selected bounding box");
            require(helper, rig.totalInjected > power && rig.totalInjected > rig.power.getStored(),
                "Quarry excavated blocks without consuming supplied MJ");
            require(helper, rig.power.getStored() >= 0 && rig.power.getStored() <= rig.power.getCapacity(),
                "Quarry's power buffer escaped its advertised bounds");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 2400)
    public static void liveQuarryRevisitsReplacedStoneAndKeepsExportLedgerConservative(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        helper.setBlock(FIRST, Blocks.STONE.defaultBlockState());
        helper.setBlock(PROTECTED, Blocks.BEDROCK.defaultBlockState());
        helper.setBlock(OUTSIDE, Blocks.STONE.defaultBlockState());
        rig.activate(helper);
        rig.keepPowered(helper, 20, 2240);

        helper.runAfterDelay(1050, () -> {
            require(helper, rig.isMined(helper, FIRST),
                "Quarry did not mine the original stone before watchdog recovery phase: " + rig.diagnostics());
            require(helper, PipeGameTestSupport.countItem(rig.warehouse, Items.COBBLESTONE) == 1,
                "Quarry lost or duplicated the original stone drop");
            helper.setBlock(FIRST, Blocks.STONE.defaultBlockState());
        });
        helper.runAfterDelay(2240, () -> {
            require(helper, rig.isMined(helper, FIRST),
                "Quarry ignored a block placed into its previously excavated column: " + rig.diagnostics());
            require(helper, PipeGameTestSupport.countItem(rig.warehouse, Items.COBBLESTONE) == 2,
                "rediscovery generated extra/missing inventory output for exactly two mining operations");
            require(helper, helper.getLevel().getBlockState(helper.absolutePos(PROTECTED)).is(Blocks.BEDROCK)
                && helper.getLevel().getBlockState(helper.absolutePos(OUTSIDE)).is(Blocks.STONE),
                "Quarry watchdog left its assigned area or destroyed unbreakable blocks");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 2540)
    public static void unpoweredQuarryPausesThenMinesAfterRefuelWithoutDuplicateDrops(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        helper.setBlock(FIRST, Blocks.STONE.defaultBlockState());
        helper.setBlock(SECOND, Blocks.STONE.defaultBlockState());
        helper.setBlock(PROTECTED, Blocks.BEDROCK.defaultBlockState());
        helper.setBlock(OUTSIDE, Blocks.STONE.defaultBlockState());
        rig.activate(helper);

        long removed = rig.power.extractAll();
        require(helper, removed > 0 && rig.power.getStored() == 0,
            "fixture failed to cut the real Quarry's MJ supply");
        helper.runAfterDelay(310, () -> {
            require(helper, helper.getLevel().getBlockState(helper.absolutePos(FIRST)).is(Blocks.STONE)
                && helper.getLevel().getBlockState(helper.absolutePos(SECOND)).is(Blocks.STONE),
                "Quarry mined stone despite having no MJ during the power outage");
            require(helper, PipeGameTestSupport.countItem(rig.warehouse, Items.COBBLESTONE) == 0,
                "Quarry generated drops while no block was mined");
            require(helper, rig.quarry.hasWork(),
                "Quarry lost its pending mining work during the MJ outage: " + rig.diagnostics());
            long rejected = rig.power.addPowerChecking(removed, FluidAction.EXECUTE);
            require(helper, rejected == 0, "Quarry did not accept the saved MJ after refuel");
        });
        rig.keepPowered(helper, 320, 2420);
        helper.runAfterDelay(2420, () -> {
            require(helper, rig.dryBoundaryIntact(),
                "Quarry mining fixture was flooded or mined outside its area: " + rig.diagnostics());
            require(helper, rig.isMined(helper, FIRST)
                && rig.isMined(helper, SECOND),
                "Quarry never resumed both pending blocks after its MJ was restored: " + rig.diagnostics());
            require(helper, PipeGameTestSupport.countItem(rig.warehouse, Items.COBBLESTONE) == 2,
                "power outage/resumption created duplicate or lost Quarry drops");
            require(helper, helper.getLevel().getBlockState(helper.absolutePos(PROTECTED)).is(Blocks.BEDROCK)
                && helper.getLevel().getBlockState(helper.absolutePos(OUTSIDE)).is(Blocks.STONE),
                "resumed Quarry touched unbreakable or out-of-bounds blocks");
            require(helper, rig.totalInjected > removed && rig.totalInjected > rig.power.getStored()
                && rig.power.getStored() >= 0,
                "restarted mining did not consume MJ or underflowed its buffer");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 1950)
    public static void enchantedQuarryUsesSilkTouchAndKeepsEnchantmentsOnBreak(GameTestHelper helper) {
        ItemStack item = new ItemStack(BCBuildersBlocks.QUARRY.get());
        item.enchant(Enchantments.SILK_TOUCH, 1);
        item.enchant(Enchantments.BLOCK_EFFICIENCY, 3);
        Rig rig = new Rig(helper, item);
        helper.setBlock(FIRST, Blocks.STONE.defaultBlockState());
        helper.setBlock(SECOND, Blocks.BEDROCK.defaultBlockState());
        require(helper, QuarryEnchantments.breakPowerTarget(1_000_000L, 3) < 1_000_000L,
            "Efficiency did not improve Quarry's own energy-driven work rate");
        rig.activate(helper);
        rig.keepPowered(helper, 20, 1720);
        helper.runAfterDelay(1720, () -> {
            require(helper, rig.isMined(helper, FIRST), "enchanted Quarry failed to mine stone");
            require(helper, PipeGameTestSupport.countItem(rig.warehouse, Items.STONE) == 1,
                "Silk Touch on the Quarry failed to preserve mined stone");
            require(helper, PipeGameTestSupport.countItem(rig.warehouse, Items.COBBLESTONE) == 0,
                "enchanted Quarry emitted cobblestone as well as Silk Touch stone");
            helper.getLevel().destroyBlock(helper.absolutePos(QUARRY), true);
            BlockPos pos = helper.absolutePos(QUARRY);
            boolean found = false;
            for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    new AABB(pos).inflate(2.0))) {
                ItemStack dropped = entity.getItem();
                if (dropped.is(BCBuildersBlocks.QUARRY.get().asItem())) {
                    require(helper, EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SILK_TOUCH, dropped) == 1,
                        "Quarry lost its Silk Touch enchantment when broken");
                    found = true;
                }
            }
            require(helper, found, "breaking the enchanted Quarry did not drop its item");
            helper.succeed();
        });
    }

    private static final class Rig {
        final GameTestHelper helper;
        final TileQuarry quarry;
        final ChestBlockEntity warehouse;
        final MjBattery power;
        long totalInjected;

        Rig(GameTestHelper helper) {
            this(helper, ItemStack.EMPTY);
        }

        Rig(GameTestHelper helper, ItemStack placedItem) {
            this.helper = helper;
            helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
            BlockEntity output = GameTestCompat.getBlockEntity(helper, CHEST);
            if (!(output instanceof ChestBlockEntity chest)) throw new IllegalStateException("missing Quarry warehouse");
            warehouse = chest;
            helper.setBlock(QUARRY, BCBuildersBlocks.QUARRY.get().defaultBlockState());
            BlockEntity tile = GameTestCompat.getBlockEntity(helper, QUARRY);
            if (!(tile instanceof TileQuarry actual)) throw new IllegalStateException("missing real Quarry");
            quarry = actual;
            // A real Quarry is configured by its placement callback. Vanilla
            // GameTestHelper.setBlock only sets block state, leaving the owner and
            // initial frame/iterator state unset unless the fixture invokes it.
            quarry.onPlacedBy(buildcraft.lib.misc.FakePlayerProvider.INSTANCE.getFakePlayer(
                helper.getLevel(), new com.mojang.authlib.GameProfile(
                    java.util.UUID.fromString("54ca00f5-48cd-47ad-9d32-0bca71b5fcfe"), "BCTestQuarry"),
                helper.absolutePos(QUARRY)), placedItem);

            // The frame is wholly inside the structure at Y=1..2, while the real
            // mining plane lies beneath it at Y=0. A normal initial frame scan
            // must not silently demolish the mining blocks as frame obstacles.
            BlockPos frameMin = helper.absolutePos(new BlockPos(2, 1, 1));
            BlockPos frameMax = helper.absolutePos(new BlockPos(5, 2, 5));
            quarry.frameBox.setMin(frameMin);
            quarry.frameBox.setMax(frameMax);
            Box mining = (Box) field(quarry, "miningBox");
            mining.setMin(helper.absolutePos(new BlockPos(3, 0, 2)));
            mining.setMax(helper.absolutePos(new BlockPos(4, 0, 4)));
            for (int x = 2; x <= 5; x++) {
                for (int y = 1; y <= 2; y++) {
                    for (int z = 1; z <= 5; z++) {
                        BlockPos relative = new BlockPos(x, y, z);
                        if (quarry.frameBox.isOnEdge(helper.absolutePos(relative))) {
                            helper.setBlock(relative, BCBuildersBlocks.FRAME.get().defaultBlockState());
                        }
                    }
                }
            }
            // Keep the mining plane isolated from other tests and from water
            // left in the persistent GameTest world. The 7x3x7 template is
            // empty, but its neighbours are not part of the structure reset.
            // A dry, breakable glass perimeter is outside miningBox AND below
            // frameBox, so it cannot become a frame obstacle or be excavated.
            for (int x = 2; x <= 5; x++) {
                for (int z = 1; z <= 5; z++) {
                    if (x == 2 || x == 5 || z == 1 || z == 5) {
                        helper.setBlock(new BlockPos(x, 0, z), Blocks.GLASS.defaultBlockState());
                    }
                }
            }
            for (int x = 3; x <= 4; x++) {
                for (int z = 2; z <= 4; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.AIR.defaultBlockState());
                    helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR.defaultBlockState());
                    helper.setBlock(new BlockPos(x, 2, z), Blocks.AIR.defaultBlockState());
                }
            }
            // Do not scan the mining area before each test has populated its target blocks.
            power = (MjBattery) field(quarry, "battery");
            long injected = 10_000L * MjAmount.MICRO_MJ_PER_MJ;
            require(helper, power.addPowerChecking(injected, FluidAction.EXECUTE) == 0,
                "Quarry test fixture could not supply enough energy for a complete work cycle");
            totalInjected = injected;
        }

        void activate(GameTestHelper helper) {
            // Initialise the real iterator after stone has appeared, just as an already
            // built Quarry sees the populated world before beginning excavation.
            invoke(quarry, "updatePoses");
            require(helper, quarry.hasWork(), "Quarry did not recognise its populated mining area");
        }

        void keepPowered(GameTestHelper helper, int firstTick, int lastTick) {
            // 10k MJ in a 24k MJ Quarry is not enough to guarantee 2,400 ticks
            // of mining. Supply the fixture in measured increments while it runs.
            for (int tick = firstTick; tick <= lastTick; tick += 10) {
                helper.runAfterDelay(tick, () -> {
                    long capacity = 10_000L * MjAmount.MICRO_MJ_PER_MJ;
                    long missing = capacity - power.getStored();
                    require(helper, missing >= 0,
                        "Quarry buffer exceeded the fixture's expected MJ ceiling");
                    if (missing == 0) return;
                    require(helper, power.addPowerChecking(missing, FluidAction.EXECUTE) == 0,
                        "Quarry rejected replenishment from its test power supply");
                    totalInjected += missing;
                });
            }
        }

        boolean dryBoundaryIntact() {
            for (int x = 2; x <= 5; x++) {
                for (int z = 1; z <= 5; z++) {
                    if (x == 2 || x == 5 || z == 1 || z == 5) {
                        if (!helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(x, 0, z)))
                            .is(Blocks.GLASS)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        boolean isMined(GameTestHelper helper, BlockPos relative) {
            BlockPos pos = helper.absolutePos(relative);
            BlockState state = helper.getLevel().getBlockState(pos);
            if (state.isAir()) return true;
            if (state.is(BCBuildersBlocks.QUARRY_DRILL_COLLISION.get())) {
                // Only the active Quarry's own drill placeholder counts as mined,
                // even if that placeholder is waterlogged.
                BlockEntity tile = helper.getLevel().getBlockEntity(pos);
                return tile instanceof TileQuarryDrillCollision collision
                    && quarry.getBlockPos().equals(collision.getOwner());
            }
            // A mined cell may be refilled by flowing water/lava before the
            // final assertion. Exactly two cobblestone drops are independently
            // required by the tests, so a fluid here cannot mask a lost drop.
            return !state.getFluidState().isEmpty();
        }

        private String blockSnapshot(BlockPos relative) {
            BlockPos absolute = helper.absolutePos(relative);
            BlockState state = helper.getLevel().getBlockState(absolute);
            BlockEntity tile = helper.getLevel().getBlockEntity(absolute);
            return state + " fluid=" + state.getFluidState()
                + (tile instanceof TileQuarryDrillCollision collision
                    ? " collisionOwner=" + collision.getOwner() : "");
        }

        String diagnostics() {
            return "first=" + blockSnapshot(FIRST) + ", second=" + blockSnapshot(SECOND)
                + ", cobblestone=" + PipeGameTestSupport.countItem(warehouse, Items.COBBLESTONE)
                + ", firstChecked=" + field(quarry, "firstChecked")
                + ", frameBuild=" + ((java.util.Set<?>) field(quarry, "framePlaceFramePoses")).size()
                + ", frameBreak=" + ((java.util.Set<?>) field(quarry, "frameBreakBlockPoses")).size()
                + ", iterator=" + field(quarry, "boxIterator")
                + ", task=" + (quarry.currentTask == null ? "none" : ((Object) quarry.currentTask).getClass().getSimpleName())
                + ", drill=" + quarry.drillPos + ", MJ=" + power.getStored()
                + ", supplied=" + totalInjected + ", hasWork=" + quarry.hasWork();
        }
    }

    private static Object field(Object owner, String name) {
        try {
            Field f = TileQuarry.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot initialise test-sized Quarry area: " + name, error);
        }
    }

    private static void invoke(Object target, String name) {
        try {
            Method method = TileQuarry.class.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not initialise Quarry frame scan: " + name, error);
        }
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}
