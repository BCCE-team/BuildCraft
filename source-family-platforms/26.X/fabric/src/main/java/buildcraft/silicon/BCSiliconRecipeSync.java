/*
 * Copyright (c) 2026 BCCE contributors. MPL-2.0.
 */
package buildcraft.silicon;

import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.misc.MessageUtil;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.platform.events.PlatformEvents;
import buildcraft.lib.recipe.AssemblyRecipe;
import buildcraft.lib.recipe.AssemblyRecipeBasic;
import buildcraft.silicon.recipe.FacadeAssemblyRecipes;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.network.codec.StreamCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-authoritative assembly recipe index for Fabric 26.3.
 *
 * <p>Vanilla does not publish BuildCraft's custom assembly recipe type to
 * JEI as a normal crafting recipe. NeoForge sends the recipe map on login
 * and datapack reload; this class preserves that behavior using the existing
 * BuildCraft directional network envelope. It never influences machine
 * execution, which continues to read the server's own recipe map.</p>
 */
public final class BCSiliconRecipeSync {
    private static final Logger LOG = LoggerFactory.getLogger("BCCE/AssemblyRecipeSync");
    private static final int MAX_PER_PART = 16;
    private static final int MAX_PARTS = 512;
    private static final int MAX_BYTES_PER_PART = 128 * 1024;
    private static boolean initialized;
    private static long generation;

    private BCSiliconRecipeSync() {}

    public static synchronized void bootstrap() {
        if (initialized) return;
        initialized = true;
        MessageManager.registerClientboundMessageClass(
            BCModules.SILICON, SnapshotPart.class, SnapshotPart.HANDLER,
            SnapshotPart::toBytes, SnapshotPart::new);

        PlatformEvents.entityJoin(event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                MessageUtil.doDelayedServer(8, () -> sendAll(player));
            }
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (!success) return;
            generation++;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                sendAll(player);
            }
        });
    }

    private static void sendAll(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        try {
            List<RecipeHolder<AssemblyRecipeBasic>> recipes =
                level.recipeAccess().getRecipes().stream()
                    .filter(holder -> holder.value().getType() == BCSiliconRecipes.ASSEMBLY_TYPE.get())
                    .map(holder -> { @SuppressWarnings("unchecked")
                        RecipeHolder<AssemblyRecipeBasic> assembly = (RecipeHolder<AssemblyRecipeBasic>) holder;
                        return assembly;
                    }).toList();
            // Builtin and addon recipe serializers use the same packet format.
            // Preserve recipe-holder IDs, ingredient counts and item components.
            List<RecipeHolder<AssemblyRecipeBasic>> encoded = recipes.stream()
                .sorted(Comparator.comparing(holder -> holder.id().identifier().toString()))
                .toList();
            if (encoded.size() > MAX_PARTS * MAX_PER_PART) {
                LOG.warn("Assembly recipe sync exceeds the supported limit: {}", encoded.size());
                return;
            }
            int partCount = Math.max(1, (encoded.size() + MAX_PER_PART - 1) / MAX_PER_PART);
            long snapshot = generation;
            for (int i = 0; i < partCount; i++) {
                int from = i * MAX_PER_PART;
                int to = Math.min(encoded.size(), from + MAX_PER_PART);
                MessageManager.sendTo(new SnapshotPart(snapshot, i, partCount, encoded.subList(from, to)), player);
            }
        } catch (RuntimeException error) {
            LOG.warn("Could not synchronize assembly recipes with {}: {}",
                player.getName().getString(), error.toString());
        }
    }

    /** Bounded, ordered part of one complete server recipe snapshot. */
    public static final class SnapshotPart {
        private static final int STANDARD = 0;
        private static final int FACADE = 1;
        private static final int EXTENSION = 2;
        final long generation;
        final int index;
        final int count;
        final List<RecipeHolder<AssemblyRecipeBasic>> recipes;

        SnapshotPart(long generation, int index, int count, List<RecipeHolder<AssemblyRecipeBasic>> recipes) {
            this.generation = generation;
            this.index = index;
            this.count = count;
            this.recipes = List.copyOf(recipes);
        }

        public SnapshotPart(RegistryFriendlyByteBuf buffer) {
            generation = buffer.readLong();
            index = buffer.readVarInt();
            count = buffer.readVarInt();
            if (count < 1 || count > MAX_PARTS || index < 0 || index >= count) {
                throw new DecoderException("Invalid assembly snapshot part " + index + "/" + count);
            }
            int size = buffer.readVarInt();
            if (size < 0 || size > MAX_PER_PART) {
                throw new DecoderException("Invalid assembly snapshot recipe count " + size);
            }
            int start = buffer.readerIndex();
            List<RecipeHolder<AssemblyRecipeBasic>> decoded = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                Identifier id = buffer.readIdentifier();
                int kind = buffer.readUnsignedByte();
                AssemblyRecipeBasic recipe = switch (kind) {
                    case STANDARD -> AssemblyRecipe.SERIALIZER.streamCodec().decode(buffer);
                    case FACADE -> FacadeAssemblyRecipes.getInstance(id);
                    case EXTENSION -> decodeExtension(buffer);
                    default -> throw new DecoderException("Unknown assembly recipe kind " + kind);
                };
                if (buffer.readerIndex() - start > MAX_BYTES_PER_PART) {
                    throw new DecoderException("Assembly recipe part exceeded byte budget");
                }
                decoded.add(new RecipeHolder<>(ResourceKey.create(Registries.RECIPE, id), recipe));
            }
            recipes = List.copyOf(decoded);
        }

        static void toBytes(SnapshotPart part, RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(part.generation);
            buffer.writeVarInt(part.index);
            buffer.writeVarInt(part.count);
            buffer.writeVarInt(part.recipes.size());
            int start = buffer.writerIndex();
            for (RecipeHolder<AssemblyRecipeBasic> holder : part.recipes) {
                buffer.writeIdentifier(holder.id().identifier());
                AssemblyRecipeBasic recipe = holder.value();
                if (recipe instanceof AssemblyRecipe assembly) {
                    buffer.writeByte(STANDARD);
                    AssemblyRecipe.SERIALIZER.streamCodec().encode(buffer, assembly);
                } else if (recipe instanceof FacadeAssemblyRecipes) {
                    buffer.writeByte(FACADE);
                } else {
                    buffer.writeByte(EXTENSION);
                    RecipeSerializer<?> serializer = recipe.getSerializer();
                    Identifier serializerId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer);
                    if (serializerId == null) {
                        throw new IllegalArgumentException("Unregistered assembly serializer " + recipe.getClass());
                    }
                    buffer.writeIdentifier(serializerId);
                    writeExtension(buffer, serializer, recipe);
                }
                if (buffer.writerIndex() - start > MAX_BYTES_PER_PART) {
                    throw new IllegalArgumentException("Assembly recipe part exceeded byte budget");
                }
            }
        }

        /** Use each addon's registered vanilla stream codec; the server owns the recipe. */
        @SuppressWarnings("unchecked")
        private static void writeExtension(RegistryFriendlyByteBuf buffer,
                                           RecipeSerializer<?> serializer, AssemblyRecipeBasic recipe) {
            StreamCodec<RegistryFriendlyByteBuf, Recipe<?>> codec =
                (StreamCodec<RegistryFriendlyByteBuf, Recipe<?>>) (StreamCodec<?, ?>) serializer.streamCodec();
            codec.encode(buffer, recipe);
        }

        private static AssemblyRecipeBasic decodeExtension(RegistryFriendlyByteBuf buffer) {
            Identifier serializerId = buffer.readIdentifier();
            RecipeSerializer<?> serializer = BuiltInRegistries.RECIPE_SERIALIZER.getValue(serializerId);
            if (serializer == null) throw new DecoderException("Unknown assembly serializer " + serializerId);
            Recipe<?> recipe = serializer.streamCodec().decode(buffer);
            if (!(recipe instanceof AssemblyRecipeBasic assembly)) {
                throw new DecoderException("Non-assembly recipe in assembly snapshot: " + serializerId);
            }
            return assembly;
        }

        public static final BiConsumer<SnapshotPart, Supplier<BCPacketContext>> HANDLER = (part, context) -> {
            BCPacketContext packetContext = context.get();
            if (packetContext.side() != BCNetworkSide.CLIENT) return;
            packetContext.enqueueWork(() -> Client.accept(part));
            packetContext.setPacketHandled(true);
        };
    }

    /** Client-only snapshot cache used by JEI; never execute recipes on the client. */
    public static final class Client {
        private static List<RecipeHolder<AssemblyRecipeBasic>> assembly = List.of();
        private static Runnable assemblyRecipesChanged;
        private static long appliedGeneration = -1;
        private static long pendingGeneration = -1;
        private static int pendingCount;
        private static final Map<Integer, SnapshotPart> pending = new HashMap<>();

        private Client() {}

        public static List<RecipeHolder<AssemblyRecipeBasic>> assemblyRecipes() {
            return assembly;
        }

        public static void setAssemblyRecipesChangedListener(Runnable listener) {
            assemblyRecipesChanged = listener;
            if (listener != null) listener.run();
        }

        private static void changed() {
            if (assemblyRecipesChanged != null) assemblyRecipesChanged.run();
        }

        /** Only called on the Minecraft client thread. */
        static void accept(SnapshotPart part) {
            if (part.generation < appliedGeneration || part.generation < pendingGeneration) return;
            if (part.generation == appliedGeneration && pendingGeneration < 0) return;
            if (pendingGeneration != part.generation || pendingCount != part.count) {
                pending.clear();
                pendingGeneration = part.generation;
                pendingCount = part.count;
            }
            pending.putIfAbsent(part.index, part);
            if (pending.size() != pendingCount) return;
            List<RecipeHolder<AssemblyRecipeBasic>> snapshot = new ArrayList<>();
            for (int index = 0; index < pendingCount; index++) {
                SnapshotPart next = pending.get(index);
                if (next == null) return;
                snapshot.addAll(next.recipes);
            }
            assembly = List.copyOf(snapshot);
            appliedGeneration = pendingGeneration;
            pendingGeneration = -1;
            pendingCount = 0;
            pending.clear();
            changed();
        }

        public static void clear() {
            assembly = List.of();
            appliedGeneration = -1;
            pendingGeneration = -1;
            pendingCount = 0;
            pending.clear();
            changed();
        }
    }
}
