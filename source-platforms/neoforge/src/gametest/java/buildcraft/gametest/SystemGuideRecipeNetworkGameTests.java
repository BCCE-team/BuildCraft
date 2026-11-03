//? if >=1.21.11 {
package buildcraft.gametest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import buildcraft.lib.BCLib;
import buildcraft.lib.net.GuideRecipeDisplayCache;
import buildcraft.lib.net.MessageGuideRecipeDisplays;

/**
 * Network-facing guide regression tests using an actual vanilla crafting display
 * obtained from the launched game's recipe manager, not a hard-coded AE2 recipe.
 * The unsupported entry and corrupt wire frame are deliberately unrelated to
 * any named add-on. Actual client join remains covered by IT16 separately.
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemGuideRecipeNetworkGameTests {
    private static final String TEMPLATE = "empty3x3x3";

    private SystemGuideRecipeNetworkGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 90)
    public static void guideNetworkFiltersInvalidEntriesAndRetainsValidNeighbors(GameTestHelper helper) {
        GuideRecipeDisplayCache.Entry fixture = supportedVanillaDisplay(helper);
        GuideRecipeDisplayCache.Entry before = rekey(fixture, "before_bad_recipe");
        GuideRecipeDisplayCache.Entry invalid = new GuideRecipeDisplayCache.Entry(
            Identifier.fromNamespaceAndPath("buildcraft", "broken_recipe"), null);
        GuideRecipeDisplayCache.Entry after = rekey(fixture, "after_bad_recipe");

        RegistryFriendlyByteBuf packet = buffer(helper);
        try {
            // The normal encoder has to stage every recipe independently. A broken entry
            // in the middle must never poison the data before or after it.
            MessageGuideRecipeDisplays.toBytes(
                new MessageGuideRecipeDisplays(List.of(before, invalid, after)), packet);
            packet.readerIndex(0);
            List<GuideRecipeDisplayCache.Entry> decoded = entries(new MessageGuideRecipeDisplays(packet));
            require(helper, decoded.size() == 2 && decoded.get(0).recipeId().equals(before.recipeId())
                && decoded.get(1).recipeId().equals(after.recipeId()),
                "Guide encoder dropped a valid recipe or transmitted an unsupported display");

            synchronized (GuideRecipeDisplayCache.class) {
                GuideRecipeDisplayCache.clear();
                try {
                    GuideRecipeDisplayCache.appendChunk(decoded, 0, 1);
                    List<GuideRecipeDisplayCache.Entry> result = GuideRecipeDisplayCache.entriesOr(List.of());
                    require(helper, result.size() == 2
                        && result.get(0).recipeId().equals(before.recipeId())
                        && result.get(1).recipeId().equals(after.recipeId()),
                        "Guide receiver cache did not retain both supported recipes in order");
                } finally {
                    GuideRecipeDisplayCache.clear();
                }
            }
        } finally {
            packet.release();
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 90)
    public static void guideNetworkSkipsCorruptFramedRecipeAndRecoversOnFreshIndex(GameTestHelper helper) {
        GuideRecipeDisplayCache.Entry fixture = supportedVanillaDisplay(helper);
        GuideRecipeDisplayCache.Entry a = rekey(fixture, "first_encoded_recipe");
        GuideRecipeDisplayCache.Entry b = rekey(fixture, "last_encoded_recipe");
        RegistryFriendlyByteBuf encoded = buffer(helper);
        RegistryFriendlyByteBuf corrupted = buffer(helper);
        try {
            MessageGuideRecipeDisplays.toBytes(new MessageGuideRecipeDisplays(List.of(a, b)), encoded);
            encoded.readerIndex(0);
            int magic = encoded.readVarInt();
            int version = encoded.readVarInt();
            int partIndex = encoded.readVarInt();
            int partCount = encoded.readVarInt();
            int frameCount = encoded.readVarInt();
            require(helper, magic == -1 && version == 2 && partIndex == 0
                && partCount == 1 && frameCount == 2, "unexpected Guide packet format");
            byte[] first = readFrame(encoded);
            byte[] last = readFrame(encoded);

            // Model a client receiving an unsupported/corrupt modded display between
            // two legitimate entries. Length framing must permit recovery after it.
            corrupted.writeVarInt(magic);
            corrupted.writeVarInt(version);
            corrupted.writeVarInt(0);
            corrupted.writeVarInt(1);
            corrupted.writeVarInt(3);
            writeFrame(corrupted, first);
            writeFrame(corrupted, new byte[] {0}); // Incomplete recipe identifier/display.
            writeFrame(corrupted, last);
            corrupted.readerIndex(0);
            List<GuideRecipeDisplayCache.Entry> surviving = entries(new MessageGuideRecipeDisplays(corrupted));
            require(helper, surviving.size() == 2
                && surviving.get(0).recipeId().equals(a.recipeId())
                && surviving.get(1).recipeId().equals(b.recipeId()),
                "corrupt framed display broke the following valid recipe");

            // Also check a failed multi-packet login cannot publish a partial index
            // or contaminate the index of the next successful connection.
            synchronized (GuideRecipeDisplayCache.class) {
                GuideRecipeDisplayCache.clear();
                try {
                    GuideRecipeDisplayCache.appendChunk(List.of(a), 0, 3);
                    require(helper, GuideRecipeDisplayCache.entriesOr(List.of()).isEmpty(),
                        "incomplete Guide index was published before final chunk");
                    GuideRecipeDisplayCache.appendChunk(List.of(b), 2, 3); // Missing middle chunk.
                    require(helper, GuideRecipeDisplayCache.entriesOr(List.of()).isEmpty(),
                        "failed chunk sequence published a partial recipe index");
                    GuideRecipeDisplayCache.appendChunk(List.of(a), 0, 2);
                    GuideRecipeDisplayCache.appendChunk(List.of(b), 1, 2);
                    List<GuideRecipeDisplayCache.Entry> fresh = GuideRecipeDisplayCache.entriesOr(List.of());
                    require(helper, fresh.size() == 2
                        && fresh.get(0).recipeId().equals(a.recipeId())
                        && fresh.get(1).recipeId().equals(b.recipeId()),
                        "reconnected Guide client inherited stale or duplicate index entries");
                } finally {
                    GuideRecipeDisplayCache.clear();
                }
            }
        } finally {
            encoded.release();
            corrupted.release();
        }
        helper.succeed();
    }

    private static GuideRecipeDisplayCache.Entry supportedVanillaDisplay(GameTestHelper helper) {
        RecipeManager manager = helper.getLevel().getServer().getRecipeManager();
        List<RecipeDisplayEntry> displays = new ArrayList<>();
        //? if >=26.3 {
        for (RecipeHolder<?> recipe : manager.recipeMap().values()) {
        //? } else {
        for (RecipeHolder<?> recipe : manager.getRecipes()) {
        //? }
            if (recipe.value().getType() != RecipeType.CRAFTING) continue;
            try {
                manager.listDisplaysForRecipe(recipe.id(), display -> {
                    if (displays.isEmpty()) {
                        RegistryFriendlyByteBuf probe = buffer(helper);
                        try {
                            RecipeDisplayEntry.STREAM_CODEC.encode(probe, display);
                            displays.add(display);
                        } catch (RuntimeException | LinkageError ignored) {
                            // Not everything registered server-side can be sent to a client.
                        } finally {
                            probe.release();
                        }
                    }
                });
            } catch (RuntimeException | LinkageError ignored) {
                // An installed mod may expose a recipe with a broken display enumerator.
            }
            if (!displays.isEmpty()) break;
        }
        require(helper, !displays.isEmpty(), "no network-encodable vanilla crafting display in the actual recipe registry");
        return new GuideRecipeDisplayCache.Entry(
            Identifier.fromNamespaceAndPath("buildcraft", "network_fixture"), displays.get(0));
    }

    private static GuideRecipeDisplayCache.Entry rekey(GuideRecipeDisplayCache.Entry entry, String path) {
        return new GuideRecipeDisplayCache.Entry(
            Identifier.fromNamespaceAndPath("buildcraft", "gametest/" + path), entry.display());
    }

    private static RegistryFriendlyByteBuf buffer(GameTestHelper helper) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
    }

    @SuppressWarnings("unchecked")
    private static List<GuideRecipeDisplayCache.Entry> entries(MessageGuideRecipeDisplays message) {
        try {
            Field field = MessageGuideRecipeDisplays.class.getDeclaredField("entries");
            field.setAccessible(true);
            return (List<GuideRecipeDisplayCache.Entry>) field.get(message);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot inspect decoded Guide packet", failure);
        }
    }

    private static byte[] readFrame(RegistryFriendlyByteBuf buffer) {
        int length = buffer.readVarInt();
        if (length <= 0 || length > 128 * 1024) throw new IllegalStateException("invalid fixture frame size " + length);
        byte[] frame = new byte[length];
        buffer.readBytes(frame);
        return frame;
    }

    private static void writeFrame(RegistryFriendlyByteBuf buffer, byte[] bytes) {
        buffer.writeVarInt(bytes.length);
        buffer.writeBytes(bytes);
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}

//? }
