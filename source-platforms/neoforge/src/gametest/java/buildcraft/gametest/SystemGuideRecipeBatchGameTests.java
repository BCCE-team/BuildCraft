//? if >=1.21.11 {
package buildcraft.gametest;

import java.lang.reflect.Constructor;
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

/** IT03: the actual Guide Book network codec and client-side chunk assembler under large mixed indexes. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemGuideRecipeBatchGameTests {
    private static final int CHUNK_SIZE = 100;
    private static final int CHUNKS = 3;
    private static final int TOTAL = CHUNK_SIZE * CHUNKS;
    private static final int CORRUPTED_INDEX = 150;
    private static final String TEMPLATE = "empty3x3x3";

    private SystemGuideRecipeBatchGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 160)
    public static void multiPacketGuideIndexSkipsBadMiddleDisplayAndAcceptsFreshCompleteIndex(GameTestHelper helper) {
        RecipeDisplayEntry display = vanillaCraftingDisplay(helper);
        List<GuideRecipeDisplayCache.Entry> expected = new ArrayList<>(TOTAL);
        for (int i = 0; i < TOTAL; i++) {
            expected.add(new GuideRecipeDisplayCache.Entry(
                Identifier.fromNamespaceAndPath("buildcraft", "gametest/large_index_" + i), display));
        }

        synchronized (GuideRecipeDisplayCache.class) {
            GuideRecipeDisplayCache.clear();
            try {
                for (int part = 0; part < CHUNKS; part++) {
                    List<GuideRecipeDisplayCache.Entry> slice = expected.subList(
                        part * CHUNK_SIZE, (part + 1) * CHUNK_SIZE);
                    RegistryFriendlyByteBuf encoded = buffer(helper);
                    RegistryFriendlyByteBuf altered = buffer(helper);
                    try {
                        MessageGuideRecipeDisplays.toBytes(packet(slice, part, CHUNKS), encoded);
                        require(helper, encoded.writerIndex() < 128 * 1024 + 64,
                            "Guide network batch exceeded the packet byte budget");
                        encoded.readerIndex(0);
                        RegistryFriendlyByteBuf wire = encoded;
                        if (part == 1) {
                            insertDamagedMiddleFrame(helper, encoded, altered);
                            altered.readerIndex(0);
                            wire = altered;
                        }
                        List<GuideRecipeDisplayCache.Entry> received = entries(new MessageGuideRecipeDisplays(wire));
                        require(helper, received.size() == (part == 1 ? CHUNK_SIZE - 1 : CHUNK_SIZE),
                            "Guide packet lost valid entries or accepted the malformed frame");
                        GuideRecipeDisplayCache.appendChunk(received, part, CHUNKS);
                        if (part < CHUNKS - 1) {
                            require(helper, GuideRecipeDisplayCache.entriesOr(List.of()).isEmpty(),
                                "Guide published a partial recipe index before its final packet");
                        }
                    } finally {
                        encoded.release();
                        altered.release();
                    }
                }
                List<GuideRecipeDisplayCache.Entry> completed = GuideRecipeDisplayCache.entriesOr(List.of());
                require(helper, completed.size() == TOTAL - 1,
                    "Guide multi-packet assembler did not isolate exactly one malformed display");
                for (int i = 0, j = 0; i < TOTAL; i++) {
                    if (i == CORRUPTED_INDEX) continue;
                    require(helper, completed.get(j++).recipeId().equals(expected.get(i).recipeId()),
                        "Guide multi-packet assembly reordered/duplicated recipe " + i);
                }

                // The following connection must replace, not append to, the previous index.
                // A previously rejected display must be accepted when the next wire frame is valid.
                for (int part = 0; part < CHUNKS; part++) {
                    RegistryFriendlyByteBuf encoded = buffer(helper);
                    try {
                        List<GuideRecipeDisplayCache.Entry> slice = expected.subList(
                            part * CHUNK_SIZE, (part + 1) * CHUNK_SIZE);
                        MessageGuideRecipeDisplays.toBytes(packet(slice, part, CHUNKS), encoded);
                        encoded.readerIndex(0);
                        GuideRecipeDisplayCache.appendChunk(
                            entries(new MessageGuideRecipeDisplays(encoded)), part, CHUNKS);
                    } finally {
                        encoded.release();
                    }
                }
                List<GuideRecipeDisplayCache.Entry> fresh = GuideRecipeDisplayCache.entriesOr(List.of());
                require(helper, fresh.size() == TOTAL, "fresh Guide index inherited missing or stale entries");
                for (int i = 0; i < TOTAL; i++) {
                    require(helper, fresh.get(i).recipeId().equals(expected.get(i).recipeId()),
                        "fresh Guide index contained a stale/duplicate recipe at " + i);
                }
            } finally {
                GuideRecipeDisplayCache.clear();
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 90)
    public static void invalidGuideFrameLengthRetainsValidPrefixAndDoesNotContaminateReconnect(GameTestHelper helper) {
        RecipeDisplayEntry display = vanillaCraftingDisplay(helper);
        List<GuideRecipeDisplayCache.Entry> original = List.of(
            new GuideRecipeDisplayCache.Entry(
                Identifier.fromNamespaceAndPath("buildcraft", "gametest/before_corrupt_length"), display),
            new GuideRecipeDisplayCache.Entry(
                Identifier.fromNamespaceAndPath("buildcraft", "gametest/after_corrupt_length"), display)
        );
        RegistryFriendlyByteBuf encoded = buffer(helper);
        RegistryFriendlyByteBuf truncated = buffer(helper);
        try {
            MessageGuideRecipeDisplays.toBytes(packet(original, 0, 1), encoded);
            encoded.readerIndex(0);
            int magic = encoded.readVarInt();
            int version = encoded.readVarInt();
            int partIndex = encoded.readVarInt();
            int partCount = encoded.readVarInt();
            require(helper, encoded.readVarInt() == 2 && partIndex == 0 && partCount == 1,
                "Guide encoder unexpectedly changed packet framing");
            byte[] valid = readFrame(encoded);
            List<GuideRecipeDisplayCache.Entry> prefix = List.of();
            // Test both a truncated in-budget entry and an oversized one. Each
            // must preserve earlier valid frames without allocating its payload.
            for (int invalidLength : new int[] {4096, 128 * 1024}) {
                truncated.clear();
                truncated.writeVarInt(magic);
                truncated.writeVarInt(version);
                truncated.writeVarInt(0);
                truncated.writeVarInt(1);
                truncated.writeVarInt(2);
                writeFrame(truncated, valid);
                truncated.writeVarInt(invalidLength);
                truncated.writeByte(0);
                truncated.readerIndex(0);
                prefix = entries(new MessageGuideRecipeDisplays(truncated));
                require(helper, prefix.size() == 1 && prefix.get(0).recipeId().equals(original.get(0).recipeId()),
                    "invalid/oversized Guide frame length lost the preceding supported display");
            }
            synchronized (GuideRecipeDisplayCache.class) {
                GuideRecipeDisplayCache.clear();
                try {
                    GuideRecipeDisplayCache.appendChunk(prefix, 0, 1);
                    require(helper, GuideRecipeDisplayCache.entriesOr(List.of()).size() == 1,
                        "corrupted packet published an unverified recipe suffix");
                    encoded.readerIndex(0);
                    GuideRecipeDisplayCache.appendChunk(
                        entries(new MessageGuideRecipeDisplays(encoded)), 0, 1);
                    List<GuideRecipeDisplayCache.Entry> recovered = GuideRecipeDisplayCache.entriesOr(List.of());
                    require(helper, recovered.size() == 2
                        && recovered.get(0).recipeId().equals(original.get(0).recipeId())
                        && recovered.get(1).recipeId().equals(original.get(1).recipeId()),
                        "reconnected Guide index retained corruption from the previous packet");
                } finally {
                    GuideRecipeDisplayCache.clear();
                }
            }
        } finally {
            encoded.release();
            truncated.release();
        }
        helper.succeed();
    }

    private static void insertDamagedMiddleFrame(GameTestHelper helper, RegistryFriendlyByteBuf encoded,
                                                  RegistryFriendlyByteBuf altered) {
        int magic = encoded.readVarInt();
        int version = encoded.readVarInt();
        int partIndex = encoded.readVarInt();
        int partCount = encoded.readVarInt();
        int frameCount = encoded.readVarInt();
        require(helper, magic == -1 && version == 2 && partIndex == 1 && partCount == CHUNKS
            && frameCount == CHUNK_SIZE, "Guide batch did not produce the expected framed index packet");
        altered.writeVarInt(magic);
        altered.writeVarInt(version);
        altered.writeVarInt(partIndex);
        altered.writeVarInt(partCount);
        // Replace one valid frame, rather than inserting an extra frame. Exactly
        // CHUNK_SIZE - 1 valid records must survive the corrupt middle record.
        altered.writeVarInt(frameCount);
        for (int i = 0; i < frameCount; i++) {
            byte[] frame = readFrame(encoded);
            writeFrame(altered, i == CHUNK_SIZE / 2 ? new byte[] {0} : frame);
        }
        require(helper, encoded.readableBytes() == 0, "Guide test fixture left trailing packet bytes");
    }

    private static MessageGuideRecipeDisplays packet(List<GuideRecipeDisplayCache.Entry> entries,
                                                      int partIndex, int partCount) {
        try {
            Constructor<MessageGuideRecipeDisplays> constructor = MessageGuideRecipeDisplays.class
                .getDeclaredConstructor(List.class, int.class, int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(entries, partIndex, partCount);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot create Guide chunk fixture", failure);
        }
    }

    private static RecipeDisplayEntry vanillaCraftingDisplay(GameTestHelper helper) {
        RecipeManager manager = helper.getLevel().getServer().getRecipeManager();
        List<RecipeDisplayEntry> matches = new ArrayList<>(1);
        //? if >=26.3 {
        for (RecipeHolder<?> recipe : manager.recipeMap().values()) {
        //? } else {
        for (RecipeHolder<?> recipe : manager.getRecipes()) {
        //? }
            if (recipe.value().getType() != RecipeType.CRAFTING) continue;
            try {
                manager.listDisplaysForRecipe(recipe.id(), display -> {
                    if (!matches.isEmpty()) return;
                    RegistryFriendlyByteBuf candidate = buffer(helper);
                    try {
                        RecipeDisplayEntry.STREAM_CODEC.encode(candidate, display);
                        matches.add(display);
                    } catch (RuntimeException | LinkageError ignored) {
                        // An unknown mod recipe display is not a usable network fixture.
                    } finally {
                        candidate.release();
                    }
                });
            } catch (RuntimeException | LinkageError ignored) {
                // Ignore a third-party enumerator failure and continue to the next recipe.
            }
            if (!matches.isEmpty()) break;
        }
        require(helper, !matches.isEmpty(), "no real network-compatible crafting display in loaded recipe manager");
        return matches.get(0);
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

    private static byte[] readFrame(RegistryFriendlyByteBuf bytes) {
        int length = bytes.readVarInt();
        if (length <= 0 || length > 128 * 1024 || length > bytes.readableBytes()) {
            throw new IllegalStateException("invalid Guide fixture frame length " + length);
        }
        byte[] value = new byte[length];
        bytes.readBytes(value);
        return value;
    }

    private static void writeFrame(RegistryFriendlyByteBuf bytes, byte[] frame) {
        bytes.writeVarInt(frame.length);
        bytes.writeBytes(frame);
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}
//? }
