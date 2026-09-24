package buildcraft.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import buildcraft.energy.BCEnergyFluids;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.tile.TileTank;
import buildcraft.lib.BCLib;
import buildcraft.lib.BCLibItems;

@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class BuildCraftGameTests {
    private static final String EMPTY_TEMPLATE = "empty3x3x3";

    private BuildCraftGameTests() {
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 100)
    public static void oilSourceDoesNotDestroyWater(GameTestHelper helper) {
        BlockPos waterPos = new BlockPos(1, 1, 1);
        BlockPos oilPos = waterPos.above();
        Fluid oil = BCEnergyFluids.OIL_SOURCE.get(0).get();

        helper.setBlock(waterPos, Blocks.WATER.defaultBlockState());
        helper.setBlock(oilPos, BCEnergyFluids.OIL_BLOCK.get(0).get().defaultBlockState());
        helper.runAfterDelay(60, () -> {
            if (!helper.getLevel().getBlockState(helper.absolutePos(waterPos)).is(Blocks.WATER)) {
                helper.fail("oil replaced or removed the water source");
                return;
            }
            if (helper.getLevel().getFluidState(helper.absolutePos(oilPos)).getType() != oil) {
                helper.fail("oil source disappeared after touching water");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void tankBlockEntityAcceptsAndSimulatesFluid(GameTestHelper helper) {
        BlockPos tankPos = new BlockPos(1, 1, 1);
        helper.setBlock(tankPos, BCFactoryBlocks.TANK_BLOCK.get().defaultBlockState());

        BlockEntity blockEntity = helper.getBlockEntity(tankPos);
        if (!(blockEntity instanceof TileTank tank)) {
            helper.fail("tank block did not create TileTank");
            return;
        }

        FluidStack input = new FluidStack(Fluids.WATER, 1_000);
        int simulated = tank.fill(input, FluidAction.SIMULATE);
        if (simulated != 1_000 || !tank.tank.isEmpty()) {
            helper.fail("simulated tank fill mutated state or returned the wrong amount");
            return;
        }

        int filled = tank.fill(input, FluidAction.EXECUTE);
        if (filled != 1_000 || tank.tank.getFluidAmount() != 1_000) {
            helper.fail("tank did not accept one bucket of water");
            return;
        }
        if (tank.tank.getFluid().getFluid() != Fluids.WATER) {
            helper.fail("tank stored the wrong fluid");
            return;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void energyFluidFamiliesAreFullyRegistered(GameTestHelper helper) {
        int expected = BCEnergyFluids.NAME.length * BCEnergyFluids.HEAT_NAMES.length;
        if (BCEnergyFluids.OIL_SOURCE.size() != expected
            || BCEnergyFluids.OIL_BLOCK.size() != expected
            || BCEnergyFluids.OIL_BUCKET.size() != expected
            || BCEnergyFluids.OIL_TYPE.size() != expected) {
            helper.fail("energy fluid registration lists have different or incomplete sizes");
            return;
        }

        for (int index = 0; index < expected; index++) {
            ResourceLocation fluidId = ForgeRegistries.FLUIDS.getKey(BCEnergyFluids.OIL_SOURCE.get(index).get());
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(BCEnergyFluids.OIL_BLOCK.get(index).get());
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(BCEnergyFluids.OIL_BUCKET.get(index).get());
            if (fluidId == null || blockId == null || itemId == null) {
                helper.fail("energy fluid family " + index + " contains an unregistered object");
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void guideBookItemAndRecipeAreRegistered(GameTestHelper helper) {
        Item guide = BCLibItems.GUIDE.get();
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(guide);
        if (!new ResourceLocation(BCLib.MODID, "guide").equals(itemId)) {
            helper.fail("guide book item is not registered as buildcraftlib:guide");
            return;
        }

        Recipe<?> recipe = helper.getLevel().getRecipeManager()
            .byKey(new ResourceLocation(BCLib.MODID, "guide_book"))
            .orElse(null);
        if (recipe == null) {
            helper.fail("guide book crafting recipe was not loaded");
            return;
        }
        if (recipe.getResultItem().getItem() != guide) {
            helper.fail("guide book recipe produces the wrong item");
            return;
        }
        helper.succeed();
    }

}
