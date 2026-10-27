package buildcraft.lib.net;

import buildcraft.lib.recipe.ClientMachineRecipeCatalog;
import buildcraft.lib.recipe.MachineRecipeDatapackReloadListener;
import buildcraft.lib.recipe.RecipeBlacklist;
import io.netty.handler.codec.DecoderException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-to-client JEI/guide display snapshot. Never used for machine execution. */
public final class MessageMachineRecipes {
    private static final Logger LOG = LoggerFactory.getLogger("BCCE/RecipeSync");
    public static final int MAX_TOTAL_ENTRIES = 8192;
    public static final int MAX_TOTAL_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PARTS = 512;
    private static final int MAX_ENTRIES_PER_PART = 64;
    private static final int MAX_PART_BYTES = 64 * 1024;
    private static final int MAX_JSON_CHARS = 16 * 1024;
    private static final int MAX_ID_CHARS = 1024;
    private static final int MAX_MACHINE_CHARS = 64;

    public record Recipe(String machine, ResourceLocation id, String json) {}
    public record Ban(String machine, ResourceLocation id) {}
    private final long generation;
    private final int partIndex;
    private final int partCount;
    private final List<Recipe> recipes;
    private final List<Ban> bans;

    private MessageMachineRecipes(long generation, int partIndex, int partCount,
                                  List<Recipe> recipes, List<Ban> bans) {
        this.generation = generation;
        this.partIndex = partIndex;
        this.partCount = partCount;
        this.recipes = List.copyOf(recipes);
        this.bans = List.copyOf(bans);
    }
    public long generation() { return generation; }
    public int partIndex() { return partIndex; }
    public int partCount() { return partCount; }
    public List<Recipe> recipes() { return recipes; }
    public List<Ban> bans() { return bans; }

    public MessageMachineRecipes(FriendlyByteBuf buffer) {
        generation = buffer.readLong();
        partIndex = buffer.readVarInt();
        partCount = buffer.readVarInt();
        if (partCount <= 0 || partCount > MAX_PARTS || partIndex < 0 || partIndex >= partCount) {
            throw new DecoderException("Invalid recipe-sync part " + partIndex + "/" + partCount);
        }
        int recipeCount = checkedCount(buffer.readVarInt());
        List<Recipe> readRecipes = new ArrayList<>(recipeCount);
        int start = buffer.readerIndex();
        for (int i = 0; i < recipeCount; i++) {
            String machine = buffer.readUtf(MAX_MACHINE_CHARS);
            ResourceLocation id = parseId(buffer.readUtf(MAX_ID_CHARS));
            String json = buffer.readUtf(MAX_JSON_CHARS);
            if (buffer.readerIndex() - start > MAX_PART_BYTES) throw new DecoderException("Recipe-sync part too large");
            readRecipes.add(new Recipe(machine, id, json));
        }
        int banCount = checkedCount(buffer.readVarInt());
        List<Ban> readBans = new ArrayList<>(banCount);
        for (int i = 0; i < banCount; i++) {
            String machine = buffer.readUtf(MAX_MACHINE_CHARS);
            ResourceLocation id = parseId(buffer.readUtf(MAX_ID_CHARS));
            if (buffer.readerIndex() - start > MAX_PART_BYTES) throw new DecoderException("Recipe-sync part too large");
            readBans.add(new Ban(machine, id));
        }
        recipes = List.copyOf(readRecipes);
        bans = List.copyOf(readBans);
    }

    public static void toBytes(MessageMachineRecipes message, FriendlyByteBuf buffer) {
        buffer.writeLong(message.generation);
        buffer.writeVarInt(message.partIndex);
        buffer.writeVarInt(message.partCount);
        buffer.writeVarInt(message.recipes.size());
        int start = buffer.writerIndex();
        for (Recipe recipe : message.recipes) {
            buffer.writeUtf(recipe.machine(), MAX_MACHINE_CHARS);
            buffer.writeUtf(recipe.id().toString(), MAX_ID_CHARS);
            buffer.writeUtf(recipe.json(), MAX_JSON_CHARS);
            if (buffer.writerIndex() - start > MAX_PART_BYTES) throw new IllegalArgumentException("Recipe-sync part too large");
        }
        buffer.writeVarInt(message.bans.size());
        for (Ban ban : message.bans) {
            buffer.writeUtf(ban.machine(), MAX_MACHINE_CHARS);
            buffer.writeUtf(ban.id().toString(), MAX_ID_CHARS);
            if (buffer.writerIndex() - start > MAX_PART_BYTES) throw new IllegalArgumentException("Recipe-sync part too large");
        }
    }

    private static int checkedCount(int n) {
        if (n < 0 || n > MAX_ENTRIES_PER_PART) throw new DecoderException("Invalid recipe-sync entry count " + n);
        return n;
    }
    private static ResourceLocation parseId(String s) {
        ResourceLocation id = ResourceLocation.tryParse(s);
        if (id == null || !id.toString().equals(s)) throw new DecoderException("Invalid recipe-sync ID " + s);
        return id;
    }

    public static final BiConsumer<MessageMachineRecipes, Supplier<BCPacketContext>> HANDLER = (message, context) -> {
        BCPacketContext ctx = context.get();
        if (ctx.side() != BCNetworkSide.CLIENT) return;
        ctx.enqueueWork(() -> ClientMachineRecipeCatalog.accept(message));
        ctx.setPacketHandled(true);
    };

    /** Bounded chunking; never silently drop the tail of a large datapack. */
    private static List<MessageMachineRecipes> parts() {
        List<Recipe> recipes = MachineRecipeDatapackReloadListener.syncedMachineRecipes();
        List<Ban> bans = new ArrayList<>();
        RecipeBlacklist.snapshot().denied().forEach((machine, ids) -> ids.forEach(id -> bans.add(new Ban(machine, id))));
        if (recipes.size() + bans.size() > MAX_TOTAL_ENTRIES) {
            throw new IllegalStateException("Machine recipe display index exceeds " + MAX_TOTAL_ENTRIES);
        }
        List<List<Recipe>> recipeParts = new ArrayList<>();
        int totalBytes = 0;
        List<List<Ban>> banParts = new ArrayList<>();
        List<Recipe> recipePart = new ArrayList<>();
        List<Ban> banPart = new ArrayList<>();
        int currentBytes = 0;
        for (Recipe recipe : recipes) {
            int bytes = bytes(recipe.machine()) + bytes(recipe.id().toString()) + bytes(recipe.json()) + 32;
            totalBytes = Math.addExact(totalBytes, bytes);
            if (totalBytes > MAX_TOTAL_BYTES) throw new IllegalStateException("Recipe display index exceeds byte budget");
            if (recipe.json().length() > MAX_JSON_CHARS || bytes > MAX_PART_BYTES) {
                throw new IllegalStateException("Oversized recipe JSON " + recipe.id());
            }
            if (!recipePart.isEmpty() && (recipePart.size() + banPart.size() >= MAX_ENTRIES_PER_PART
                || currentBytes + bytes > MAX_PART_BYTES)) {
                recipeParts.add(List.copyOf(recipePart)); banParts.add(List.copyOf(banPart));
                recipePart.clear(); banPart.clear(); currentBytes = 0;
            }
            recipePart.add(recipe); currentBytes += bytes;
        }
        for (Ban ban : bans) {
            int bytes = bytes(ban.machine()) + bytes(ban.id().toString()) + 32;
            totalBytes = Math.addExact(totalBytes, bytes);
            if (totalBytes > MAX_TOTAL_BYTES) throw new IllegalStateException("Recipe display index exceeds byte budget");
            if (bytes > MAX_PART_BYTES) throw new IllegalStateException("Oversized ban ID " + ban.id());
            if (recipePart.size() + banPart.size() >= MAX_ENTRIES_PER_PART || currentBytes + bytes > MAX_PART_BYTES) {
                recipeParts.add(List.copyOf(recipePart)); banParts.add(List.copyOf(banPart));
                recipePart.clear(); banPart.clear(); currentBytes = 0;
            }
            banPart.add(ban); currentBytes += bytes;
        }
        recipeParts.add(List.copyOf(recipePart)); banParts.add(List.copyOf(banPart));
        if (recipeParts.size() > MAX_PARTS) throw new IllegalStateException("Too many recipe-sync parts");
        long generation = RecipeBlacklist.generation();
        List<MessageMachineRecipes> result = new ArrayList<>();
        for (int i = 0; i < recipeParts.size(); i++) {
            result.add(new MessageMachineRecipes(generation, i, recipeParts.size(), recipeParts.get(i), banParts.get(i)));
        }
        return result;
    }

    private static int bytes(String s) { return s.getBytes(StandardCharsets.UTF_8).length; }

    public static void sendAll(ServerPlayer player) {
        if (!MachineRecipeDatapackReloadListener.hasSyncedRecipeSnapshot()) return;
        try {
            for (MessageMachineRecipes part : parts()) MessageManager.sendTo(part, player);
        } catch (RuntimeException failure) {
            LOG.warn("Unable to synchronize machine recipe displays with {}: {}", player.getName().getString(), failure.toString());
        }
    }
    public static void broadcast() {
        if (!MachineRecipeDatapackReloadListener.hasSyncedRecipeSnapshot()) return;
        // The reload listener also runs in the client resource manager. Broadcasting
        // from that thread throws on NeoForge (and can race the integrated server).
        // Only the authoritative server thread may emit clientbound recipe indexes.
        if (!MessageManager.isAuthoritativeServerThread()) return;
        try {
            for (MessageMachineRecipes part : parts()) MessageManager.sendToAll(part);
        } catch (RuntimeException failure) {
            LOG.warn("Unable to broadcast machine recipe displays: {}", failure.toString());
        }
    }
}
