package buildcraft.gametest;

import java.util.EnumSet;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.container.ContainerTank;
import buildcraft.factory.tile.TileTank;
import buildcraft.lib.BCLib;
import buildcraft.lib.fluid.FluidCompatRegistry;
import buildcraft.lib.fluid.FluidDisplayHelper;
import buildcraft.lib.fluid.FluidSmoother;
import buildcraft.lib.fluid.FuelApiBridge;
import buildcraft.lib.fluid.Tank;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.internal.pipe.IPipe.ConnectedType;
import buildcraft.transport.internal.pipe.PipeEventFluid;
import buildcraft.transport.pipe.behaviour.PipeBehaviourDiamond;
import buildcraft.transport.pipe.behaviour.PipeBehaviourDiamondFluid;
import buildcraft.transport.pipe.flow.PipeFlowFluids;

/** Regression coverage for the fluid compatibility/tank changes layered on PR #59. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class FluidCompatibilityGameTests {
    private static final String EMPTY_TEMPLATE = "empty3x3x3";

    private FluidCompatibilityGameTests() {
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void fluidItemServiceResolvesStandardContainersAndRejectsNonFluidItems(GameTestHelper helper) {
        FluidVolume water = BuildCraftApi.service(BuildCraftServices.FLUID_ITEMS).fluid(new ItemStack(Items.WATER_BUCKET));
        FluidVolume lava = BuildCraftApi.service(BuildCraftServices.FLUID_ITEMS).fluid(new ItemStack(Items.LAVA_BUCKET));
        FluidVolume apple = BuildCraftApi.service(BuildCraftServices.FLUID_ITEMS).fluid(new ItemStack(Items.APPLE));

        require(helper, !water.isEmpty(), "water bucket was not resolved by the fluid item service");
        require(helper, !lava.isEmpty(), "lava bucket was not resolved by the fluid item service");
        require(helper, water.amount().milliBuckets() == FluidType.BUCKET_VOLUME,
            "water bucket resolved to the wrong amount");
        require(helper, lava.amount().milliBuckets() == FluidType.BUCKET_VOLUME,
            "lava bucket resolved to the wrong amount");
        require(helper, water.requireVariant().fluidId().equals(FuelApiBridge.variantOf(
            new FluidStack(Fluids.WATER, 1)).fluidId()), "water bucket resolved to the wrong fluid");
        require(helper, lava.requireVariant().fluidId().equals(FuelApiBridge.variantOf(
            new FluidStack(Fluids.LAVA, 1)).fluidId()), "lava bucket resolved to the wrong fluid");
        require(helper, apple.isEmpty(), "non-fluid item was accepted by the fluid item service");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void fluidVariantRoundTripPreservesForgeNbt(GameTestHelper helper) {
        FluidStack original = taggedWater(375, "alpha");
        FluidVolume volume = FuelApiBridge.volumeOf(original);
        FluidStack restored = FuelApiBridge.stackOf(volume);

        require(helper, !volume.requireVariant().components().isEmpty(),
            "Forge FluidStack NBT was flattened while converting to FluidVariant");
        require(helper, restored.getFluid() == original.getFluid(), "round-trip changed the fluid registry entry");
        require(helper, restored.getAmount() == original.getAmount(), "round-trip changed the fluid amount");
        require(helper, original.getTag() != null && original.getTag().equals(restored.getTag()),
            "round-trip lost or changed Forge FluidStack NBT");
        require(helper, FluidCompatRegistry.areEquivalent(original, restored),
            "round-trip stack is no longer equivalent to the original variant");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void tankColumnBalanceCompactsMultipleSourcesInOnePass(GameTestHelper helper) {
        TileTank bottom = placeTank(helper, new BlockPos(1, 1, 1));
        TileTank middle = placeTank(helper, new BlockPos(1, 2, 1));
        TileTank top = placeTank(helper, new BlockPos(1, 3, 1));
        int halfTank = 8 * FluidType.BUCKET_VOLUME;

        middle.tank.setFluid(new FluidStack(Fluids.WATER, halfTank));
        top.tank.setFluid(new FluidStack(Fluids.WATER, halfTank));
        bottom.balanceTankFluids();

        require(helper, bottom.tank.getFluidAmount() == 16 * FluidType.BUCKET_VOLUME,
            "column compaction left liquid stranded above a non-full lower tank");
        require(helper, middle.tank.isEmpty(), "middle tank retained liquid after full-column compaction");
        require(helper, top.tank.isEmpty(), "top tank retained liquid after full-column compaction");
        require(helper, bottom.getFluidInTank(0).getAmount() == 16 * FluidType.BUCKET_VOLUME,
            "logical column amount changed during compaction");
        require(helper, bottom.getTankCapacity(0) == 48 * FluidType.BUCKET_VOLUME,
            "logical column capacity changed during compaction");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void tankColumnInitialServerTickCompactsLoadedContents(GameTestHelper helper) {
        TileTank bottom = placeTank(helper, new BlockPos(1, 1, 1));
        TileTank middle = placeTank(helper, new BlockPos(1, 2, 1));
        TileTank top = placeTank(helper, new BlockPos(1, 3, 1));
        int quarterTank = 4 * FluidType.BUCKET_VOLUME;

        middle.tank.setFluid(new FluidStack(Fluids.WATER, quarterTank));
        top.tank.setFluid(new FluidStack(Fluids.WATER, quarterTank));
        bottom.update();

        require(helper, bottom.tank.getFluidAmount() == 8 * FluidType.BUCKET_VOLUME,
            "first server tick did not rebalance pre-existing column contents");
        require(helper, middle.tank.isEmpty(), "initial rebalance left liquid in the middle tank");
        require(helper, top.tank.isEmpty(), "initial rebalance left liquid in the top tank");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void tankMenuSynchronizesWholeColumnState(GameTestHelper helper) {
        TileTank bottom = placeTank(helper, new BlockPos(1, 1, 1));
        TileTank top = placeTank(helper, new BlockPos(1, 2, 1));
        top.tank.setFluid(new FluidStack(Fluids.WATER, FluidType.BUCKET_VOLUME));

        Player player = FakePlayerProvider.INSTANCE.getBuildCraftPlayer(helper.getLevel());
        ContainerTank menu = new ContainerTank(
            1,
            player.getInventory(),
            bottom,
            ContainerLevelAccess.create(helper.getLevel(), bottom.getBlockPos())
        );

        require(helper, menu.getFluidAmount() == FluidType.BUCKET_VOLUME,
            "tank menu synchronized only the clicked block instead of the logical column");
        require(helper, menu.getTankCapacity() == 32 * FluidType.BUCKET_VOLUME,
            "tank menu synchronized local capacity instead of whole-column capacity");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 70)
    public static void fluidSmootherSynchronizesVariantChangeWithoutAmountChange(GameTestHelper helper) {
        Tank tank = new Tank("smoother_test", FluidType.BUCKET_VOLUME, null);
        int[] packets = {0};
        int[] lastAmount = {-1};
        boolean[] lastHasFluid = {false};

        FluidSmoother smoother = new FluidSmoother(writer -> {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                writer.write(buffer);
                packets[0]++;
                lastAmount[0] = buffer.readInt();
                lastHasFluid[0] = buffer.readBoolean();
            } finally {
                buffer.release();
            }
        }, tank);

        tank.setFluid(taggedWater(400, "first"));
        smoother.tick(helper.getLevel());
        require(helper, packets[0] == 1, "initial fluid state was not synchronized");

        helper.runAfterDelay(15, () -> {
            tank.setFluid(taggedWater(400, "first"));
            smoother.tick(helper.getLevel());
            require(helper, packets[0] == 1, "unchanged fluid variant emitted a redundant packet");
            tank.setFluid(taggedWater(400, "second"));
        });

        helper.runAfterDelay(30, () -> {
            smoother.tick(helper.getLevel());
            require(helper, packets[0] == 2,
                "same-amount fluid variant change did not trigger a synchronization packet");
            require(helper, lastAmount[0] == 400 && lastHasFluid[0],
                "variant-change packet carried the wrong amount/empty state");
            tank.setFluid(FluidStack.EMPTY);
        });

        helper.runAfterDelay(45, () -> {
            smoother.tick(helper.getLevel());
            require(helper, packets[0] == 3, "transition to empty fluid was not synchronized");
            require(helper, lastAmount[0] == 0 && !lastHasFluid[0],
                "empty-fluid packet did not clear the synchronized state");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void createPotionFiltersKeepPotionIdentityWhenCreateIsLoaded(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }

        ItemStack waterPotion = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER);
        ItemStack strongPotion = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.STRONG_SWIFTNESS);
        ItemStack splashPotion = PotionUtils.setPotion(new ItemStack(Items.SPLASH_POTION), Potions.STRONG_SWIFTNESS);
        ItemStack lingeringPotion = PotionUtils.setPotion(new ItemStack(Items.LINGERING_POTION), Potions.STRONG_SWIFTNESS);
        ItemStack longPotion = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.LONG_SWIFTNESS);
        FluidVolume waterVolume = BuildCraftApi.service(BuildCraftServices.FLUID_ITEMS).fluid(waterPotion);
        FluidVolume strongVolume = BuildCraftApi.service(BuildCraftServices.FLUID_ITEMS).fluid(strongPotion);
        FluidVolume splashVolume = BuildCraftApi.service(BuildCraftServices.FLUID_ITEMS).fluid(splashPotion);
        FluidVolume lingeringVolume = BuildCraftApi.service(BuildCraftServices.FLUID_ITEMS).fluid(lingeringPotion);
        FluidVolume longVolume = BuildCraftApi.service(BuildCraftServices.FLUID_ITEMS).fluid(longPotion);

        require(helper, !waterVolume.isEmpty() && !strongVolume.isEmpty() && !splashVolume.isEmpty()
            && !lingeringVolume.isEmpty() && !longVolume.isEmpty(),
            "Create potion adapter did not resolve every vanilla potion bottle form");
        require(helper, FuelApiBridge.stackOf(waterVolume).getFluid() == Fluids.WATER,
            "regular water potion did not keep Create's vanilla-water special case");
        require(helper, !strongVolume.requireVariant().sameVariant(splashVolume.requireVariant()),
            "regular and splash potion bottle types collapsed to one FluidVariant");
        require(helper, !strongVolume.requireVariant().sameVariant(lingeringVolume.requireVariant()),
            "regular and lingering potion bottle types collapsed to one FluidVariant");
        require(helper, strongVolume.requireVariant().sameFluid(longVolume.requireVariant()),
            "different potion bottles did not resolve to the same Create potion-fluid registry entry");
        require(helper, !strongVolume.requireVariant().sameVariant(longVolume.requireVariant()),
            "different potion bottles collapsed to one FluidVariant");

        FluidStack strongFluid = FuelApiBridge.stackOf(strongVolume);
        FluidStack longFluid = FuelApiBridge.stackOf(longVolume);
        require(helper, !FluidCompatRegistry.areEquivalent(strongFluid, longFluid),
            "potion NBT was lost while converting Create potion fluids through API v2");
        require(helper, FluidDisplayHelper.potionAmplifier(strongFluid) > 0,
            "potion amplifier was lost from Create fluid display metadata");
        require(helper, FluidDisplayHelper.potionDurationTicks(longFluid) > 20,
            "potion duration was lost from Create fluid display metadata");

        PipeGameTestSupport.TestPipe pipe = new PipeGameTestSupport.TestPipe(helper.getLevel(), BCTransportPipes.diamondFluid)
            .connect(Direction.EAST, ConnectedType.PIPE)
            .connect(Direction.SOUTH, ConnectedType.PIPE)
            .connect(Direction.WEST, ConnectedType.PIPE);
        PipeFlowFluids flow = new PipeFlowFluids(pipe);
        PipeBehaviourDiamondFluid behaviour = new PipeBehaviourDiamondFluid(pipe);
        pipe.setFlow(flow);
        pipe.setBehaviour(behaviour);
        behaviour.filters.setStackInSlot(Direction.EAST.ordinal() * PipeBehaviourDiamond.FILTERS_PER_SIDE, strongPotion);
        behaviour.filters.setStackInSlot(Direction.SOUTH.ordinal() * PipeBehaviourDiamond.FILTERS_PER_SIDE, longPotion);

        PipeEventFluid.SideCheck strong = sideCheck(pipe, flow, strongFluid);
        behaviour.sideCheck(strong);
        require(helper, strong.isAllowed(Direction.EAST), "strong potion was rejected by its diamond-fluid filter");
        require(helper, !strong.isAllowed(Direction.SOUTH), "strong potion matched the long-potion filter");
        require(helper, strong.isAllowed(Direction.WEST), "unfiltered fallback disappeared for potion fluid");
        require(helper, strong.getOrder().equals(EnumSet.of(Direction.EAST)),
            "strong potion did not prioritize its matching filtered side");

        PipeEventFluid.SideCheck longCheck = sideCheck(pipe, flow, longFluid);
        behaviour.sideCheck(longCheck);
        require(helper, !longCheck.isAllowed(Direction.EAST), "long potion matched the strong-potion filter");
        require(helper, longCheck.isAllowed(Direction.SOUTH), "long potion was rejected by its diamond-fluid filter");
        require(helper, longCheck.getOrder().equals(EnumSet.of(Direction.SOUTH)),
            "long potion did not prioritize its matching filtered side");
        helper.succeed();
    }

    private static PipeEventFluid.SideCheck sideCheck(PipeGameTestSupport.TestPipe pipe, PipeFlowFluids flow,
        FluidStack fluid) {
        PipeEventFluid.SideCheck check = new PipeEventFluid.SideCheck(pipe.getHolder(), flow, fluid);
        check.disallowAllExcept(Direction.EAST, Direction.SOUTH, Direction.WEST);
        return check;
    }

    private static FluidStack taggedWater(int amount, String marker) {
        FluidStack stack = new FluidStack(Fluids.WATER, amount);
        CompoundTag tag = new CompoundTag();
        tag.putString("bcce_test_variant", marker);
        stack.setTag(tag);
        return stack;
    }

    private static TileTank placeTank(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, BCFactoryBlocks.TANK_BLOCK.get().defaultBlockState());
        if (!(helper.getBlockEntity(pos) instanceof TileTank tank)) {
            helper.fail("tank block did not create TileTank at " + pos);
            throw new IllegalStateException("missing TileTank");
        }
        return tank;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }
}
