package buildcraft.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.SchematicBlockManager;
import buildcraft.builders.snapshot.UnavailableSchematicBlock;
import buildcraft.lib.BCLib;
import buildcraft.lib.misc.JsonUtil;
import buildcraft.lib.statement.ActionWrapper;
import buildcraft.transport.BCTransportStatements;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class CoreIntegrityGameTests {
    private static final String EMPTY_TEMPLATE = "empty3x3x3";

    private CoreIntegrityGameTests() {
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void unavailableBlueprintElementsRoundTripLosslessly(GameTestHelper helper) throws Exception {
        CompoundTag raw = new CompoundTag();
        raw.putString("name", "missing_addon:custom_machine");
        CompoundTag rawData = new CompoundTag();
        rawData.putString("opaque", "keep-me");
        raw.put("data", rawData);

        CompoundTag rawEntity = new CompoundTag();
        rawEntity.putString("name", "missing_addon:custom_entity");
        CompoundTag rawEntityData = new CompoundTag();
        rawEntityData.putString("opaque", "keep-entity-too");
        rawEntity.put("data", rawEntityData);

        buildcraft.api.v2.schematic.UnknownSnapshotElement unknownApi2 = new buildcraft.api.v2.schematic.UnknownSnapshotElement(
            java.util.Objects.requireNonNull(net.minecraft.resources.ResourceLocation.tryParse("missing_addon:api2_machine")),
            0,
            new buildcraft.api.v2.persistence.OpaqueData(
                java.util.Objects.requireNonNull(net.minecraft.resources.ResourceLocation.tryParse("buildcraft:test_opaque")),
                new byte[] { 1, 2, 3 }
            )
        );

        Blueprint blueprint = new Blueprint();
        blueprint.size = new BlockPos(2, 1, 1);
        blueprint.facing = Direction.NORTH;
        blueprint.offset = BlockPos.ZERO;
        blueprint.palette.add(new UnavailableSchematicBlock(raw));
        blueprint.palette.add(new buildcraft.builders.internal.schematic.api2.Api2SchematicBlock(
            unknownApi2, buildcraft.builders.internal.schematic.api2.UnavailableSchematicAdapters.BLOCK
        ));
        blueprint.data = new int[] { 0, 1 };
        blueprint.entities.add(new buildcraft.builders.snapshot.UnavailableSchematicEntity(rawEntity));

        CompoundTag serialized = blueprint.serializeNBT();
        Blueprint loaded = new Blueprint();
        loaded.deserializeNBT(serialized);

        require(helper, loaded.getUnavailableSchematicCount() == 3,
            "missing legacy/API2 block/entity schematics were not retained as unavailable blueprint elements");
        require(helper, SchematicBlockManager.isUnavailable(loaded.palette.get(0)),
            "missing legacy schematic was not represented by the unavailable adapter");
        require(helper, SchematicBlockManager.isUnavailable(loaded.palette.get(1)),
            "missing API2 schematic was not represented by the unavailable adapter");
        require(helper, loaded.palette.get(1).isBuilt(helper.getLevel(), new BlockPos(2, 1, 1)),
            "missing API2 schematic must be treated as skipped instead of retried forever");
        require(helper, SchematicBlockManager.writeToNBT(loaded.palette.get(0)).equals(raw),
            "missing legacy schematic payload changed during load/save round-trip");
        require(helper, buildcraft.builders.snapshot.SchematicEntityManager.writeToNBT(loaded.entities.get(0)).equals(rawEntity),
            "missing legacy entity schematic payload changed during load/save round-trip");
        require(helper, loaded.palette.get(0).isBuilt(helper.getLevel(), new BlockPos(1, 1, 1)),
            "unavailable schematic must skip world mutation");
        require(helper, loaded.palette.get(0).getRotated(Rotation.CLOCKWISE_90) == loaded.palette.get(0),
            "unavailable schematic should preserve opaque rotation semantics");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void relatedGateActionVariantsTargetOneSetting(GameTestHelper helper) {
        ActionWrapper east = ActionWrapper.wrap(BCTransportStatements.ACTION_PIPE_DIRECTION[Direction.EAST.ordinal()], null);
        ActionWrapper west = ActionWrapper.wrap(BCTransportStatements.ACTION_PIPE_DIRECTION[Direction.WEST.ordinal()], null);
        require(helper, east != null && west != null && east.targetsSameSetting(west),
            "pipe direction variants were not classified as the same gate setting");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void jsonInlineCopiesAreIndependent(GameTestHelper helper) {
        JsonObject root = new JsonObject();
        JsonObject inlines = new JsonObject();
        JsonObject shared = new JsonObject();
        JsonObject nested = new JsonObject();
        nested.addProperty("value", 1);
        shared.add("nested", nested);
        inlines.add("shared", shared);
        root.add("inlines", inlines);

        JsonArray uses = new JsonArray();
        for (int i = 0; i < 2; i++) {
            JsonObject use = new JsonObject();
            use.addProperty("inline", "shared");
            uses.add(use);
        }
        root.add("uses", uses);

        JsonUtil.inlineCustom(root);
        JsonObject first = uses.get(0).getAsJsonObject().getAsJsonObject("nested");
        JsonObject second = uses.get(1).getAsJsonObject().getAsJsonObject("nested");
        require(helper, first != second, "inline expansion reused a mutable JsonElement instance");
        first.addProperty("mutated", true);
        require(helper, !second.has("mutated"), "mutating one inline expansion leaked into another use");
        helper.succeed();
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }
}
