package buildcraft.gametest;

import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.tile.TileFloodGate;
import buildcraft.lib.BCLib;
import buildcraft.lib.misc.FakePlayerProvider;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** End-to-end Flood Gate placement tests. Run on every Forge target. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class FloodGateGameTests {
    private static final String EMPTY_TEMPLATE = "empty3x3x3";
    private static final BlockPos GATE = new BlockPos(1, 2, 1);
    private static final BlockPos OUTPUT = GATE.below();
    private static final BlockPos POWER = GATE.above();
    private static final GameProfile BUCKET_ACTOR = new GameProfile(
        UUID.fromString("47e31e56-6830-48bd-aea3-cd3960a04ed5"), "BCTestFloodGate"
    );

    private FloodGateGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 100)
    public static void floodGatePlacesBucketWaterAndConsumesOneBucket(GameTestHelper helper) {
        TileFloodGate gate = placeGate(helper);
        Player actor = FakePlayerProvider.INSTANCE.getFakePlayer(
            helper.getLevel(), BUCKET_ACTOR, gate.getBlockPos());
        gate.onPlacedBy(actor, new ItemStack(BCFactoryBlocks.FLOOD_GATE_BLOCK.get()));
        ItemStack previous = actor.getItemInHand(InteractionHand.MAIN_HAND).copy();
        try {
            actor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
            gate.tankManager.onActivated(actor, gate.getBlockPos(), InteractionHand.MAIN_HAND);
            require(helper, gate.tankManager.get(0).getFluidAmount() == FluidType.BUCKET_VOLUME,
                "water bucket was not transferred into the Flood Gate");
        } finally {
            actor.setItemInHand(InteractionHand.MAIN_HAND, previous);
        }
        gate.onOpenSidesChanged();
        helper.runAfterDelay(40, () -> {
            assertWaterSource(helper);
            require(helper, gate.tankManager.get(0).getFluidAmount() == 0,
                "Flood Gate did not debit exactly one bucket after placement");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 100)
    public static void floodGatePlacesWaterFromFluidHandlerAndConsumesOneBucket(GameTestHelper helper) {
        TileFloodGate gate = placeGate(helper);
        require(helper, gate.tankManager.fill(
            new FluidStack(Fluids.WATER, FluidType.BUCKET_VOLUME), FluidAction.EXECUTE
        ) == FluidType.BUCKET_VOLUME, "Flood Gate rejected water through its fluid handler");
        gate.onOpenSidesChanged();
        helper.runAfterDelay(40, () -> {
            assertWaterSource(helper);
            require(helper, gate.tankManager.get(0).getFluidAmount() == 0,
                "piped water was not debited when the Flood Gate placed a source");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 100)
    public static void floodGateDoesNotConsumeWaterWhenOutputIsBlocked(GameTestHelper helper) {
        TileFloodGate gate = placeGate(helper);
        helper.setBlock(OUTPUT, Blocks.STONE.defaultBlockState());
        fillWater(helper, gate);
        gate.onOpenSidesChanged();
        helper.runAfterDelay(55, () -> {
            require(helper, helper.getLevel().getBlockState(helper.absolutePos(OUTPUT)).is(Blocks.STONE),
                "Flood Gate replaced a non-replaceable block");
            require(helper, gate.tankManager.get(0).getFluidAmount() == FluidType.BUCKET_VOLUME,
                "Flood Gate consumed fluid despite having no placeable output");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 120)
    public static void floodGatePausesWhenPoweredAndResumesAfterUnpowering(GameTestHelper helper) {
        TileFloodGate gate = placeGate(helper);
        fillWater(helper, gate);
        gate.onOpenSidesChanged();
        helper.setBlock(POWER, Blocks.REDSTONE_BLOCK.defaultBlockState());
        require(helper, helper.getLevel().hasNeighborSignal(gate.getBlockPos()),
            "redstone test did not power the Flood Gate");
        helper.runAfterDelay(40, () -> {
            require(helper, helper.getLevel().getFluidState(helper.absolutePos(OUTPUT)).isEmpty(),
                "powered Flood Gate placed water");
            require(helper, gate.tankManager.get(0).getFluidAmount() == FluidType.BUCKET_VOLUME,
                "powered Flood Gate consumed water");
            helper.setBlock(POWER, Blocks.AIR.defaultBlockState());
        });
        helper.runAfterDelay(90, () -> {
            assertWaterSource(helper);
            require(helper, gate.tankManager.get(0).getFluidAmount() == 0,
                "Flood Gate did not consume its bucket after redstone was removed");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 100)
    public static void floodGateClosedOutputDoesNotConsumeWater(GameTestHelper helper) {
        TileFloodGate gate = placeGate(helper);
        gate.openSides.clear();
        fillWater(helper, gate);
        gate.onOpenSidesChanged();
        helper.runAfterDelay(55, () -> {
            require(helper, helper.getLevel().getFluidState(helper.absolutePos(OUTPUT)).isEmpty(),
                "closed Flood Gate output placed water");
            require(helper, gate.tankManager.get(0).getFluidAmount() == FluidType.BUCKET_VOLUME,
                "closed Flood Gate output consumed fluid");
            helper.succeed();
        });
    }

    private static TileFloodGate placeGate(GameTestHelper helper) {
        helper.setBlock(OUTPUT, Blocks.AIR.defaultBlockState());
        helper.setBlock(GATE, BCFactoryBlocks.FLOOD_GATE_BLOCK.get().defaultBlockState());
        var entity = helper.getLevel().getBlockEntity(helper.absolutePos(GATE));
        if (!(entity instanceof TileFloodGate gate)) {
            helper.fail("Flood Gate block did not create TileFloodGate");
            throw new IllegalStateException("missing Flood Gate block entity");
        }
        gate.openSides.clear();
        gate.openSides.add(Direction.DOWN);
        return gate;
    }

    private static void fillWater(GameTestHelper helper, TileFloodGate gate) {
        require(helper, gate.tankManager.fill(
            new FluidStack(Fluids.WATER, FluidType.BUCKET_VOLUME), FluidAction.EXECUTE
        ) == FluidType.BUCKET_VOLUME, "failed to inject water into Flood Gate");
    }

    private static void assertWaterSource(GameTestHelper helper) {
        var state = helper.getLevel().getFluidState(helper.absolutePos(OUTPUT));
        require(helper, state.isSource() && state.getType() == Fluids.WATER,
            "Flood Gate did not place a water source at its open output");
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}
