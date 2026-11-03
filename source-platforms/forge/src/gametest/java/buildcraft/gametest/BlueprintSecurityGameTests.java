package buildcraft.gametest;

import buildcraft.builders.internal.schematic.legacy.SchematicBlockContext;
import buildcraft.builders.snapshot.BlueprintSecurity;
import buildcraft.builders.snapshot.SchematicBlockDefault;
import buildcraft.builders.snapshot.SchematicBlockFluid;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.core.InvalidInputDataException;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Security regressions for untrusted, user-authored .bcnbt blueprints. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class BlueprintSecurityGameTests {
    private static final String EMPTY = "empty3x3x3";

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY, timeoutTicks = 20)
    public static void forgedFluidTypeCannotPlaceOrdinaryBlocks(GameTestHelper helper) throws Exception {
        CompoundTag malicious = new CompoundTag();
        malicious.put("blockState", NbtUtils.writeBlockState(Blocks.DIAMOND_BLOCK.defaultBlockState()));
        malicious.putBoolean("isFlowing", false);
        try {
            new SchematicBlockFluid().deserializeNBT(malicious);
            helper.fail("A non-fluid diamond block was accepted as a fluid blueprint entry");
            return;
        } catch (InvalidInputDataException expected) {
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY, timeoutTicks = 20)
    public static void defaultSchematicCannotSubstituteExpensivePlacement(GameTestHelper helper) throws Exception {
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, Blocks.STONE.defaultBlockState());
        BlockPos absolute = helper.absolutePos(relative);
        SchematicBlockDefault original = new SchematicBlockDefault();
        original.init(new SchematicBlockContext(helper.getLevel(), absolute, absolute,
            Blocks.STONE.defaultBlockState(), Blocks.STONE));
        CompoundTag forged = original.serializeNBT();
        forged.putString("placeBlock", "minecraft:diamond_block");
        try {
            new SchematicBlockDefault().deserializeNBT(forged);
            helper.fail("Forged placeBlock bypassed the trusted material rules");
            return;
        } catch (InvalidInputDataException expected) {
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY, timeoutTicks = 20)
    public static void defaultSchematicRejectsForgedNeighborUpdateOffsets(GameTestHelper helper) throws Exception {
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, Blocks.STONE.defaultBlockState());
        BlockPos absolute = helper.absolutePos(relative);
        SchematicBlockDefault original = new SchematicBlockDefault();
        original.init(new SchematicBlockContext(helper.getLevel(), absolute, absolute,
            Blocks.STONE.defaultBlockState(), Blocks.STONE));
        CompoundTag serialized = original.serializeNBT();
        try {
            new SchematicBlockDefault().deserializeNBT(serialized);
        } catch (InvalidInputDataException unexpected) {
            helper.fail("A legitimately captured schematic was rejected: " + unexpected.getMessage());
            return;
        }

        CompoundTag forged = serialized.copy();
        forged.put("updateBlockOffsets", new ListTag());
        try {
            new SchematicBlockDefault().deserializeNBT(forged);
            helper.fail("A blueprint with missing neighbor-update offsets bypassed server rules");
            return;
        } catch (InvalidInputDataException expected) {
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY, timeoutTicks = 20)
    public static void blueprintPipeDropsLiveStateAndOwner(GameTestHelper helper) {
        CompoundTag source = new CompoundTag();
        source.putString("id", "buildcrafttransport:pipe_holder");
        CompoundTag owner = new CompoundTag();
        owner.putString("Name", "pretend-admin");
        source.put("owner", owner);
        CompoundTag pipe = new CompoundTag();
        pipe.putString("def", "buildcrafttransport:wooden_item_pipe");
        pipe.putByteArray("apiComponents", new byte[] {1, 2, 3});
        CompoundTag flow = new CompoundTag();
        flow.putInt("capacity", Integer.MAX_VALUE);
        flow.putString("items", "free cargo");
        pipe.put("flow", flow);
        source.put("pipe", pipe);
        CompoundTag cleaned = BlueprintSecurity.forPipe(source);
        CompoundTag cleanPipe = (CompoundTag) cleaned.get("pipe");
        require(helper, !cleaned.contains("owner"), "Pipe reused the blueprint author's owner UUID");
        require(helper, !cleanPipe.contains("apiComponents"), "Pipe retained runtime API components");
        require(helper, ((CompoundTag) cleanPipe.get("flow")).isEmpty(),
            "Pipe retained unpaid inventory/fluid state");
        require(helper, cleanPipe.contains("def"), "Pipe definition was lost");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY, timeoutTicks = 20)
    public static void copiedMachineCannotHydrateStoredResources(GameTestHelper helper) {
        CompoundTag source = new CompoundTag();
        source.putString("id", "minecraft:chest");
        source.put("Items", new CompoundTag());
        source.put("owner", new CompoundTag());
        source.put("battery", new CompoundTag());
        source.put("tanks", new CompoundTag());
        source.putString("LootTable", "minecraft:chests/end_city_treasure");
        source.putString("loot_table", "minecraft:chests/end_city_treasure");
        source.put("builder", new CompoundTag());
        source.putString("CustomName", "Safe label");
        CompoundTag cleaned = BlueprintSecurity.forBlockEntity(Blocks.CHEST.defaultBlockState(), source);
        require(helper, !cleaned.contains("Items") && !cleaned.contains("owner")
            && !cleaned.contains("battery") && !cleaned.contains("tanks")
            && !cleaned.contains("LootTable") && !cleaned.contains("loot_table")
            && !cleaned.contains("builder"),
            "Snapshot rehydrated unauthorized runtime resources");
        require(helper, cleaned.contains("CustomName"), "Safe user-authored label was discarded");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY, timeoutTicks = 20)
    public static void oversizedBlueprintAllocationFailsFast(GameTestHelper helper) {
        boolean blocked = false;
        try { Snapshot.getDataSize(512, 512, 9); }
        catch (IllegalArgumentException expected) { blocked = true; }
        require(helper, blocked, "Overlarge blueprint would allocate huge construction arrays");
        helper.succeed();
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}
