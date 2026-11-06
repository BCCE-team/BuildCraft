package buildcraft.gametest;

import buildcraft.builders.internal.schematic.legacy.SchematicBlockContext;
import buildcraft.builders.snapshot.SchematicBlockDefault;
import buildcraft.builders.snapshot.SchematicBlockFluid;
import buildcraft.core.BCCoreBlocks;
import buildcraft.lib.BCLib;
import buildcraft.lib.compat.SavedBlockStateNbt;
import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.lib.internal.enums.EnumEngineType;
import buildcraft.lib.internal.properties.BuildCraftProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Regression for snapshots/facades written by older Minecraft versions. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SavedBlockStateCompatibilityGameTests {
    private static final String EMPTY = "empty3x3x3";

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY, timeoutTicks = 20)
    public static void creativeEngineLegacyBlockStateRoundTrip(GameTestHelper helper) throws Exception {
        BlockState creative = BCCoreBlocks.ENGINE_BC8.get().defaultBlockState()
            .setValue(BuildCraftProperties.ENGINE_TYPE, EnumEngineType.CREATIVE);
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, creative);
        BlockPos absolute = helper.absolutePos(relative);

        SchematicBlockDefault original = new SchematicBlockDefault();
        original.init(new SchematicBlockContext(helper.getLevel(), absolute, absolute,
            creative, creative.getBlock()));
        CompoundTag saved = original.serializeNBT();
        require(helper, saved.getCompound("blockState").contains("Name"),
            "Saved blueprint lost its portable legacy block-state ID");

        SchematicBlockDefault loaded = new SchematicBlockDefault();
        loaded.deserializeNBT(saved);
        require(helper, creative.equals(loaded.getBlockStateForRender()),
            "Creative engine type was lost when reading its saved block state");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY, timeoutTicks = 20)
    public static void legacyFluidStateRoundTrip(GameTestHelper helper) throws Exception {
        CompoundTag saved = new CompoundTag();
        CompoundTag state = new CompoundTag();
        state.putString("Name", "minecraft:water");
        CompoundTag properties = new CompoundTag();
        properties.putString("level", "0");
        state.put("Properties", properties);
        saved.put("blockState", state);
        saved.putBoolean("isFlowing", false);
        SchematicBlockFluid loaded = new SchematicBlockFluid();
        loaded.deserializeNBT(saved);
        require(helper, loaded.getBlockStateForRender().getBlock() == Blocks.WATER,
            "Legacy fluid block state decoded as air");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY, timeoutTicks = 20)
    public static void legacyGlassFacadeStateRoundTrip(GameTestHelper helper) throws Exception {
        BlockState glass = Blocks.GLASS.defaultBlockState();
        CompoundTag saved = SavedBlockStateNbt.write(glass);
        require(helper, saved.contains("Name"), "Facade NBT was saved using a version-specific key");
        CompoundTag original = saved.copy();
        require(helper, SavedBlockStateNbt.read(saved).equals(glass), "Saved glass facade state was lost");
        require(helper, original.equals(saved), "Block-state decoder modified its cached NBT");
        helper.succeed();
    }

//? if >=26.3 {
    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY, timeoutTicks = 20)
    public static void conflictingLegacyAndModernFieldsAreRejected(GameTestHelper helper) throws Exception {
        CompoundTag malicious = new CompoundTag();
        malicious.putString("Name", "minecraft:stone");
        malicious.putString("id", "minecraft:bedrock");
        try {
            SavedBlockStateNbt.read(malicious);
            helper.fail("Conflicting block-state IDs were accepted");
        } catch (InvalidInputDataException expected) {
            helper.succeed();
        }
    }
//? }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}
