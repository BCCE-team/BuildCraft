package buildcraft.transport.pipe.behaviour;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import buildcraft.transport.internal.pipe.IPipe.ConnectedType;
import buildcraft.transport.internal.pipe.PipeEventFluid;
import buildcraft.transport.internal.pipe.PipeEventItem;
import buildcraft.transport.internal.pipe.PipeEventItem.ItemEntry;
import buildcraft.gametest.PipeGameTestSupport;
import buildcraft.gametest.PipeGameTestSupport.TestPipe;
import buildcraft.lib.BCLib;
import buildcraft.lib.platform.storage.PlatformFluidPipeTransfer;
import buildcraft.transport.pipe.Pipe;
import buildcraft.lib.misc.NBTUtilBC;

@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class PipeBehaviourGameTests {
    private PipeBehaviourGameTests() {
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void pipeColourCompatibilityIsSymmetric(GameTestHelper helper) {
        require(helper, Pipe.canColoursConnect(null, null), "two unpainted pipes did not connect");
        require(helper, Pipe.canColoursConnect(DyeColor.RED, null), "painted pipe did not connect to unpainted pipe");
        require(helper, Pipe.canColoursConnect(null, DyeColor.BLUE), "unpainted pipe did not connect to painted pipe");
        require(helper, Pipe.canColoursConnect(DyeColor.RED, DyeColor.RED), "equal pipe colours did not connect");
        require(helper, !Pipe.canColoursConnect(DyeColor.RED, DyeColor.BLUE), "different pipe colours connected");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void diamondFiltersPreferExactMatchesAndKeepUnfilteredFallback(GameTestHelper helper) {
        TestPipe pipe = new TestPipe(helper.getLevel())
            .connect(Direction.EAST, ConnectedType.PIPE)
            .connect(Direction.SOUTH, ConnectedType.PIPE)
            .connect(Direction.NORTH, ConnectedType.PIPE);
        PipeBehaviourDiamondItem behaviour = new PipeBehaviourDiamondItem(pipe);
        pipe.setBehaviour(behaviour);

        behaviour.filters.setStackInSlot(Direction.EAST.ordinal() * PipeBehaviourDiamond.FILTERS_PER_SIDE,
            new ItemStack(Items.APPLE));
        behaviour.filters.setStackInSlot(Direction.SOUTH.ordinal() * PipeBehaviourDiamond.FILTERS_PER_SIDE,
            new ItemStack(Items.IRON_INGOT));

        PipeEventItem.SideCheck apple = sideCheck(pipe, new ItemStack(Items.APPLE));
        behaviour.sideCheck(apple);
        List<EnumSet<Direction>> order = apple.getOrder();

        require(helper, order.size() == 2, "diamond filter produced wrong number of priority groups: " + order);
        require(helper, order.get(0).equals(EnumSet.of(Direction.EAST)), "exact match was not first: " + order);
        require(helper, order.get(1).equals(EnumSet.of(Direction.NORTH)), "unfiltered side was not fallback: " + order);
        require(helper, !apple.isAllowed(Direction.SOUTH), "non-matching filtered side remained allowed");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void diamondWeightedSplitUsesFilterStackCountsAndReducesRatio(GameTestHelper helper) {
        assertDiamondSplit(helper, 2, 1, 12, 8, 4);
        assertDiamondSplit(helper, 4, 2, 12, 8, 4);
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void diamondBehaviourNbtRoundTripPreservesEveryFilterSlot(GameTestHelper helper) {
        TestPipe pipe = new TestPipe(helper.getLevel());
        PipeBehaviourDiamondItem original = new PipeBehaviourDiamondItem(pipe);
        original.filters.setStackInSlot(0, new ItemStack(Items.APPLE, 3));
        original.filters.setStackInSlot(17, new ItemStack(Items.IRON_INGOT, 5));
        ItemStack named = new ItemStack(Items.DIAMOND_PICKAXE);
        named.setHoverName(net.minecraft.network.chat.Component.literal("routing filter"));
        original.filters.setStackInSlot(53, named);

        PipeBehaviourDiamondItem restored = new PipeBehaviourDiamondItem(pipe, original.writeToNbt());
        require(helper, ItemStack.matches(original.filters.getStackInSlot(0), restored.filters.getStackInSlot(0)),
            "first filter did not survive NBT");
        require(helper, ItemStack.matches(original.filters.getStackInSlot(17), restored.filters.getStackInSlot(17)),
            "middle filter did not survive NBT");
        require(helper, ItemStack.matches(original.filters.getStackInSlot(53), restored.filters.getStackInSlot(53)),
            "named filter did not survive NBT");
        require(helper, restored.filters.getSlots() == PipeBehaviourDiamond.FILTERS_PER_SIDE * 6,
            "filter inventory changed size after NBT load");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void ironPipeEnforcesOutputBounceAndFluidInputDirection(GameTestHelper helper) {
        TestPipe pipe = new TestPipe(helper.getLevel())
            .connect(Direction.EAST, ConnectedType.PIPE)
            .connect(Direction.WEST, ConnectedType.PIPE)
            .connect(Direction.SOUTH, ConnectedType.PIPE);
        CompoundTag nbt = new CompoundTag();
        nbt.put("currentDir", NBTUtilBC.writeEnum(Direction.EAST));
        PipeBehaviourIron behaviour = new PipeBehaviourIron(pipe, nbt);

        PipeEventItem.SideCheck sideCheck = sideCheck(pipe, new ItemStack(Items.APPLE));
        behaviour.sideCheck(sideCheck);
        require(helper, sideCheck.getOrder().equals(List.of(EnumSet.of(Direction.EAST))),
            "iron pipe allowed a direction other than EAST: " + sideCheck.getOrder());

        PipeEventItem.TryBounce bounce = new PipeEventItem.TryBounce(pipe.getHolder(), null, null,
            Direction.EAST, new ItemStack(Items.APPLE));
        PipeBehaviourIron.tryBounce(bounce);
        require(helper, bounce.canBounce, "iron pipe did not allow an item to bounce");

        PipeEventFluid.TryInsert fromOutput = new PipeEventFluid.TryInsert(pipe.getHolder(), null, Direction.EAST,
            PlatformFluidPipeTransfer.toVolume(new FluidStack(Fluids.WATER, 1000)));
        behaviour.fluidInsert(fromOutput);
        require(helper, fromOutput.isCanceled(), "iron fluid pipe accepted fluid from its output side");

        PipeEventFluid.TryInsert fromInput = new PipeEventFluid.TryInsert(pipe.getHolder(), null, Direction.WEST,
            PlatformFluidPipeTransfer.toVolume(new FluidStack(Fluids.WATER, 1000)));
        behaviour.fluidInsert(fromInput);
        require(helper, !fromInput.isCanceled(), "iron fluid pipe rejected a valid input side");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void lapisAndDaizuliKeepTheBc8ColourContract(GameTestHelper helper) {
        for (DyeColor configured : DyeColor.values()) {
            TestPipe lapisPipe = new TestPipe(helper.getLevel());
            CompoundTag lapisNbt = new CompoundTag();
            lapisNbt.put("colour", NBTUtilBC.writeEnum(configured));
            PipeBehaviourLapis lapis = new PipeBehaviourLapis(lapisPipe, lapisNbt);
            PipeEventItem.ReachCenter centre = new PipeEventItem.ReachCenter(
                lapisPipe.getHolder(), null, null, new ItemStack(Items.APPLE), Direction.WEST
            );
            lapis.onReachCenter(centre);
            require(helper, centre.colour == configured, "lapis failed to apply " + configured);

            TestPipe daizuliPipe = new TestPipe(helper.getLevel())
                .connect(Direction.EAST, ConnectedType.PIPE)
                .connect(Direction.SOUTH, ConnectedType.PIPE);
            CompoundTag daizuliNbt = new CompoundTag();
            daizuliNbt.put("colour", NBTUtilBC.writeEnum(configured));
            daizuliNbt.put("currentDir", NBTUtilBC.writeEnum(Direction.EAST));
            PipeBehaviourDaizuli daizuli = new PipeBehaviourDaizuli(daizuliPipe, daizuliNbt);

            PipeEventItem.SideCheck matching = sideCheck(daizuliPipe, configured, new ItemStack(Items.APPLE));
            daizuli.sideCheck(matching);
            require(helper, matching.getOrder().equals(List.of(EnumSet.of(Direction.EAST))),
                "matching " + configured + " cargo was not forced EAST");

            DyeColor other = DyeColor.byId((configured.getId() + 1) & 15);
            PipeEventItem.SideCheck mismatching = sideCheck(daizuliPipe, other, new ItemStack(Items.APPLE));
            daizuli.sideCheck(mismatching);
            require(helper, !mismatching.isAllowed(Direction.EAST),
                "mismatching " + other + " cargo was allowed through configured side");
            require(helper, mismatching.isAllowed(Direction.SOUTH),
                "mismatching cargo lost its alternate route");
        }
        helper.succeed();
    }

    private static void assertDiamondSplit(GameTestHelper helper, int eastWeight, int southWeight, int input,
        int expectedEast, int expectedSouth) {
        TestPipe pipe = new TestPipe(helper.getLevel())
            .connect(Direction.EAST, ConnectedType.PIPE)
            .connect(Direction.SOUTH, ConnectedType.PIPE);
        PipeBehaviourDiamondItem behaviour = new PipeBehaviourDiamondItem(pipe);
        behaviour.filters.setStackInSlot(Direction.EAST.ordinal() * PipeBehaviourDiamond.FILTERS_PER_SIDE,
            new ItemStack(Items.APPLE, eastWeight));
        behaviour.filters.setStackInSlot(Direction.SOUTH.ordinal() * PipeBehaviourDiamond.FILTERS_PER_SIDE,
            new ItemStack(Items.APPLE, southWeight));

        ItemEntry entry = new ItemEntry(null, new ItemStack(Items.APPLE, input), Direction.WEST);
        PipeEventItem.Split split = new PipeEventItem.Split(
            pipe.getHolder(), null, List.of(EnumSet.of(Direction.EAST, Direction.SOUTH)), entry
        );
        behaviour.split(split);

        Map<Direction, Integer> counts = new HashMap<>();
        int total = 0;
        for (ItemEntry splitEntry : split.items) {
            require(helper, splitEntry.to != null && splitEntry.to.size() == 1,
                "weighted split entry did not have exactly one destination");
            Direction destination = splitEntry.to.get(0);
            counts.merge(destination, splitEntry.stack.getCount(), Integer::sum);
            total += splitEntry.stack.getCount();
        }
        require(helper, total == input, "weighted split changed total count: " + total + " != " + input);
        require(helper, counts.getOrDefault(Direction.EAST, 0) == expectedEast,
            "weighted split sent " + counts + " for EAST");
        require(helper, counts.getOrDefault(Direction.SOUTH, 0) == expectedSouth,
            "weighted split sent " + counts + " for SOUTH");
    }

    private static PipeEventItem.SideCheck sideCheck(TestPipe pipe, ItemStack stack) {
        return sideCheck(pipe, null, stack);
    }

    private static PipeEventItem.SideCheck sideCheck(TestPipe pipe, DyeColor colour, ItemStack stack) {
        PipeEventItem.SideCheck event = new PipeEventItem.SideCheck(pipe.getHolder(), null, colour, Direction.WEST, stack);
        for (Direction side : Direction.values()) {
            if (!pipe.isConnected(side)) {
                event.disallow(side);
            }
        }
        return event;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }
}
