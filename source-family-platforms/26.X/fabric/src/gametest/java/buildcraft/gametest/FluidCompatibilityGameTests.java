package buildcraft.gametest;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.item.ItemEntity;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.lib.recipe.MachineRecipeApiBridge;
import buildcraft.factory.tile.TileDistiller_BC8;
import buildcraft.energy.OilIndustry;
import buildcraft.core.item.ItemFragileFluidContainer;
import buildcraft.core.BCCoreItems;
import java.util.EnumSet;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.material.Fluids;
import net.fabricmc.loader.api.FabricLoader;
import buildcraft.lib.fluid.FluidStack;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.energy.BCEnergyFluids;
import buildcraft.lib.misc.FluidUtilBC;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.compat.neoforge263.fluids.FluidUtil;
import buildcraft.lib.compat.neoforge263.fluids.IFluidHandler.FluidAction;
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
import buildcraft.lib.misc.FluidStackUtil;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.internal.pipe.IPipe.ConnectedType;
import buildcraft.transport.internal.pipe.PipeEventFluid;
import buildcraft.transport.pipe.behaviour.PipeBehaviourDiamond;
import buildcraft.transport.pipe.behaviour.PipeBehaviourDiamondFluid;
import buildcraft.transport.pipe.flow.PipeFlowFluids;

/** Regression coverage for the fluid compatibility/tank changes layered on PR #59. */
public final class FluidCompatibilityGameTests {
    private static final String EMPTY_TEMPLATE = "empty3x3x3";

    private FluidCompatibilityGameTests() {
    }

    public static void petroleumBucketsExposeNativeFluidHandlers(GameTestHelper helper) {
        require(helper, BCEnergyFluids.OIL_BUCKET.size() == 30, "missing petroleum buckets");
        for (int i = 0; i < BCEnergyFluids.OIL_BUCKET.size(); i++) {
            var bucket = BCEnergyFluids.OIL_BUCKET.get(i).get();
            var expected = BCEnergyFluids.OIL_SOURCE.get(i).get();
            var handler = FluidUtil.getFluidHandler(new ItemStack(bucket)).orElse(null);
            require(helper, handler != null, "missing fluid handler for " + bucket);
            FluidStack simulated = handler.drain(1000, FluidAction.SIMULATE);
            require(helper, simulated.getAmount() == 1000 && simulated.getFluid() == expected,
                "bucket simulation returned the wrong contents: " + bucket);
            require(helper, handler.getContainer().is(bucket), "simulation changed the bucket");
            FluidStack drained = handler.drain(1000, FluidAction.EXECUTE);
            require(helper, drained.getAmount() == 1000 && drained.getFluid() == expected,
                "bucket execution returned the wrong contents: " + bucket);
            require(helper, handler.getContainer().is(Items.BUCKET), "draining did not return an empty bucket");
        }
        helper.succeed();
    }

    public static void distillerAcceptsOilFromRegisteredBucket(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, BCFactoryBlocks.DISTILLER_BLOCK.get().defaultBlockState());
        var input = CapUtil.getFluidHandler(helper.getLevel(), helper.absolutePos(pos), Direction.NORTH);
        require(helper, input != null, "distiller has no horizontal fluid input");
        var bucket = BCEnergyFluids.OIL_BUCKET.get(0).get();
        var handler = FluidUtil.getFluidHandler(new ItemStack(bucket)).orElse(null);
        require(helper, handler != null, "oil bucket has no fluid capability");
        FluidStack moved = FluidUtilBC.move(handler, input, 1000);
        require(helper, moved != null && moved.getAmount() == 1000
            && moved.getFluid() == BCEnergyFluids.crudeOil[0], "distiller rejected crude oil");
        require(helper, input.getFluidInTank(0).getAmount() == 1000, "oil did not reach the input tank");
        require(helper, handler.getContainer().is(Items.BUCKET), "transfer did not return the empty bucket");
        helper.succeed();
    }

    public static void classicOilAcceptsAllTemperaturesAndEjectsObsoleteInput(GameTestHelper helper) {
        OilIndustry.Mode previous = OilIndustry.serverMode();
        BlockPos pos = new BlockPos(1, 1, 1);
        BlockPos absolute = helper.absolutePos(pos);
        try {
            OilIndustry.setServerMode(OilIndustry.Mode.MODERN);
            helper.setBlock(pos, BCFactoryBlocks.DISTILLER_BLOCK.get().defaultBlockState());
            var input = CapUtil.getFluidHandler(helper.getLevel(), helper.absolutePos(pos), Direction.NORTH);
            require(helper, input != null, "missing distiller input");
            var distiller = (TileDistiller_BC8) helper.getLevel().getBlockEntity(absolute);
            FluidStack obsolete = new FluidStack(BCEnergyFluids.oilDistilled[0], 1251);
            require(helper, input.fill(obsolete, FluidAction.EXECUTE) == 1251, "modern input was rejected");
            OilIndustry.setServerMode(OilIndustry.Mode.CLASSIC);
            distiller.update();
            require(helper, input.getFluidInTank(0).isEmpty(), "obsolete input still blocks the distiller");
            int recovered = 0;
            int shards = 0;
            for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(absolute).inflate(1.0))) {
                if (!entity.getItem().is(BCCoreItems.FRAGILE_FLUID_SHARD.get())) continue;
                FluidStack contents = ItemFragileFluidContainer.getFluid(entity.getItem());
                require(helper, contents.getFluid() == obsolete.getFluid(), "ejection changed the fluid type");
                recovered += contents.getAmount();
                shards++;
                entity.discard();
            }
            require(helper, recovered == 1251 && shards == 3, "ejection lost or duplicated fluid");
            for (var oil : BCEnergyFluids.crudeOil) {
                FluidStack stack = new FluidStack(oil, 1000);
                require(helper, input.fill(stack, FluidAction.SIMULATE) == 1000, "legacy oil temperature rejected");
                var recipe = MachineRecipeApiBridge.findDistillation(stack);
                require(helper, recipe != null && recipe.gasOutput().isEmpty()
                    && recipe.input().amount().milliBuckets() == recipe.liquidOutput().amount().milliBuckets()
                    && MachineRecipeApiBridge.outputStack(recipe.liquidOutput()).getFluid() == BCEnergyFluids.fuelDense[0]
                    && recipe.powerRequiredMicroMj() == 32 * MjAmount.MICRO_MJ_PER_MJ, "incorrect classic distillation");
                var fuel = BuildCraftApi.service(BuildCraftServices.ENERGY_FLUIDS)
                    .findFuel(FuelApiBridge.variantOf(stack), FuelApiBridge.MATCH_CONTEXT).orElseThrow().profile();
                require(helper, !fuel.hasResidue() && fuel.burnTicksPerBucket() == 10000
                    && fuel.powerPerTickMicroMj() == 3 * MjAmount.MICRO_MJ_PER_MJ, "incorrect classic oil fuel");
            }
            require(helper, input.fill(obsolete, FluidAction.SIMULATE) == 0, "obsolete fluid can re-enter classic input");
            OilIndustry.setServerMode(OilIndustry.Mode.MODERN);
            require(helper, input.fill(obsolete, FluidAction.SIMULATE) == 1251, "modern input did not recover");
        } finally {
            OilIndustry.setServerMode(previous);
        }
        helper.succeed();
    }

    public static void fluidItemServiceResolvesStandardContainersAndRejectsNonFluidItems(GameTestHelper helper) {
        FluidVolume water = BuildCraftApi.service(BuildCraftServices.FLUID_ITEMS).fluid(new ItemStack(Items.WATER_BUCKET));
        FluidVolume lava = BuildCraftApi.service(BuildCraftServices.FLUID_ITEMS).fluid(new ItemStack(Items.LAVA_BUCKET));
        FluidVolume apple = BuildCraftApi.service(BuildCraftServices.FLUID_ITEMS).fluid(new ItemStack(Items.APPLE));

        require(helper, !water.isEmpty(), "water bucket was not resolved by the fluid item service");
        require(helper, !lava.isEmpty(), "lava bucket was not resolved by the fluid item service");
        require(helper, water.amount().milliBuckets() == 1_000,
            "water bucket resolved to the wrong amount");
        require(helper, lava.amount().milliBuckets() == 1_000,
            "lava bucket resolved to the wrong amount");
        require(helper, water.requireVariant().fluidId().equals(FuelApiBridge.variantOf(
            new FluidStack(Fluids.WATER, 1)).fluidId()), "water bucket resolved to the wrong fluid");
        require(helper, lava.requireVariant().fluidId().equals(FuelApiBridge.variantOf(
            new FluidStack(Fluids.LAVA, 1)).fluidId()), "lava bucket resolved to the wrong fluid");
        require(helper, apple.isEmpty(), "non-fluid item was accepted by the fluid item service");
        helper.succeed();
    }

    public static void fluidVariantRoundTripPreservesNeoForgeComponents(GameTestHelper helper) {
        FluidStack original = componentWater(helper, 375, "alpha");
        FluidVolume volume = FuelApiBridge.volumeOf(original);
        FluidStack restored = FuelApiBridge.stackOf(volume);

        require(helper, !volume.requireVariant().components().isEmpty(),
            "Fabric FluidStack components were flattened while converting to FluidVariant");
        require(helper, restored.getFluid() == original.getFluid(), "round-trip changed the fluid registry entry");
        require(helper, restored.getAmount() == original.getAmount(), "round-trip changed the fluid amount");
        require(helper, original.getComponentsPatch().equals(restored.getComponentsPatch()),
            "round-trip lost or changed Fabric FluidStack components");
        require(helper, FluidCompatRegistry.areEquivalent(original, restored),
            "round-trip stack is no longer equivalent to the original variant");
        helper.succeed();
    }

    public static void tankColumnBalanceCompactsMultipleSourcesInOnePass(GameTestHelper helper) {
        TileTank bottom = placeTank(helper, new BlockPos(1, 1, 1));
        TileTank middle = placeTank(helper, new BlockPos(1, 2, 1));
        TileTank top = placeTank(helper, new BlockPos(1, 3, 1));
        int halfTank = 8 * 1_000;

        middle.tank.setFluid(new FluidStack(Fluids.WATER, halfTank));
        top.tank.setFluid(new FluidStack(Fluids.WATER, halfTank));
        bottom.balanceTankFluids();

        require(helper, bottom.tank.getFluidAmount() == 16 * 1_000,
            "column compaction left liquid stranded above a non-full lower tank");
        require(helper, middle.tank.isEmpty(), "middle tank retained liquid after full-column compaction");
        require(helper, top.tank.isEmpty(), "top tank retained liquid after full-column compaction");
        require(helper, bottom.getFluidInTank(0).getAmount() == 16 * 1_000,
            "logical column amount changed during compaction");
        require(helper, bottom.getTankCapacity(0) == 48 * 1_000,
            "logical column capacity changed during compaction");
        helper.succeed();
    }

    public static void tankColumnInitialServerTickCompactsLoadedContents(GameTestHelper helper) {
        TileTank bottom = placeTank(helper, new BlockPos(1, 1, 1));
        TileTank middle = placeTank(helper, new BlockPos(1, 2, 1));
        TileTank top = placeTank(helper, new BlockPos(1, 3, 1));
        int quarterTank = 4 * 1_000;

        middle.tank.setFluid(new FluidStack(Fluids.WATER, quarterTank));
        top.tank.setFluid(new FluidStack(Fluids.WATER, quarterTank));
        bottom.update();

        require(helper, bottom.tank.getFluidAmount() == 8 * 1_000,
            "first server tick did not rebalance pre-existing column contents");
        require(helper, middle.tank.isEmpty(), "initial rebalance left liquid in the middle tank");
        require(helper, top.tank.isEmpty(), "initial rebalance left liquid in the top tank");
        helper.succeed();
    }

    public static void tankMenuSynchronizesWholeColumnState(GameTestHelper helper) {
        TileTank bottom = placeTank(helper, new BlockPos(1, 1, 1));
        TileTank top = placeTank(helper, new BlockPos(1, 2, 1));
        top.tank.setFluid(new FluidStack(Fluids.WATER, 1_000));

        Player player = FakePlayerProvider.INSTANCE.getBuildCraftPlayer(helper.getLevel());
        ContainerTank menu = new ContainerTank(
            1,
            player.getInventory(),
            bottom,
            ContainerLevelAccess.create(helper.getLevel(), bottom.getBlockPos())
        );

        require(helper, menu.getFluidAmount() == 1_000,
            "tank menu synchronized only the clicked block instead of the logical column");
        require(helper, menu.getTankCapacity() == 32 * 1_000,
            "tank menu synchronized local capacity instead of whole-column capacity");
        helper.succeed();
    }

    public static void fluidSmootherSynchronizesVariantChangeWithoutAmountChange(GameTestHelper helper) {
        Tank tank = new Tank("smoother_test", 1_000, null);
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

        tank.setFluid(componentWater(helper, 400, "first"));
        smoother.tick(helper.getLevel());
        require(helper, packets[0] == 1, "initial fluid state was not synchronized");

        helper.runAfterDelay(15, () -> {
            tank.setFluid(componentWater(helper, 400, "first"));
            smoother.tick(helper.getLevel());
            require(helper, packets[0] == 1, "unchanged fluid variant emitted a redundant packet");
            tank.setFluid(componentWater(helper, 400, "second"));
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

    public static void createPotionFiltersKeepPotionIdentityWhenCreateIsLoaded(GameTestHelper helper) {
        if (!FabricLoader.getInstance().isModLoaded("create")) {
            helper.succeed();
            return;
        }

        ItemStack waterPotion = new ItemStack(Items.POTION);
        waterPotion.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.WATER));
        ItemStack strongPotion = new ItemStack(Items.POTION);
        strongPotion.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.STRONG_SWIFTNESS));
        ItemStack splashPotion = new ItemStack(Items.SPLASH_POTION);
        splashPotion.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.STRONG_SWIFTNESS));
        ItemStack lingeringPotion = new ItemStack(Items.LINGERING_POTION);
        lingeringPotion.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.STRONG_SWIFTNESS));
        ItemStack longPotion = new ItemStack(Items.POTION);
        longPotion.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.LONG_SWIFTNESS));
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
            "potion components were lost while converting Create potion fluids through API v2");
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

    private static FluidStack componentWater(GameTestHelper helper, int amount, String marker) {
        CompoundTag customData = new CompoundTag();
        customData.putString("bcce_test_variant", marker);
        CompoundTag components = new CompoundTag();
        components.put("minecraft:custom_data", customData);
        CompoundTag serialized = new CompoundTag();
        serialized.putString("id", "minecraft:water");
        serialized.putInt("amount", amount);
        serialized.put("components", components);
        FluidStack stack = FluidStackUtil.parseOptional(helper.getLevel().registryAccess(), serialized);
        require(helper, !stack.isEmpty(), "failed to construct component-bearing Fabric FluidStack");
        return stack;
    }

    private static TileTank placeTank(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, BCFactoryBlocks.TANK_BLOCK.get().defaultBlockState());
        if (!(GameTestCompat.getBlockEntity(helper, pos) instanceof TileTank tank)) {
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
