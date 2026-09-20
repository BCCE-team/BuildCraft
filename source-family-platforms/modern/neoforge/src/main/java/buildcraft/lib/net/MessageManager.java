//? source if >=1.21.1
/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.net;

import java.util.Comparator;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import buildcraft.lib.internal.module.IBuildCraftMod;
import buildcraft.lib.internal.debug.BCDebugging;
import buildcraft.lib.internal.debug.BCLog;
import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public final class MessageManager {
    public static final boolean DEBUG = BCDebugging.shouldDebugLog("lib.messages");
    public static final String PROTOCOL_VERSION = BuildCraftTarget.NETWORK_PROTOCOL;
    private static final int MAX_MESSAGE_CLASS_NAME = 256;

    private static final CustomPacketPayload.Type<BuildCraftPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("buildcraftlib", "messages"));

    private static final Map<IBuildCraftMod, PerModHandler> MOD_HANDLERS =
        new TreeMap<>(MessageManager::compareMods);
    private static final Map<Class<?>, PerMessageInfo<?>> MESSAGE_HANDLERS = new ConcurrentHashMap<>();
    private static final Map<String, PerMessageInfo<?>> MESSAGE_HANDLERS_BY_NAME = new ConcurrentHashMap<>();

    private static final StreamCodec<RegistryFriendlyByteBuf, BuildCraftPayload> STREAM_CODEC =
        StreamCodec.of(MessageManager::encodePayload, MessageManager::decodePayload);

    private MessageManager() {
    }

    static final class PerModHandler {
        final IBuildCraftMod module;
        final SortedMap<Class<?>, PerMessageInfo<?>> knownMessages =
            new TreeMap<>(Comparator.comparing(Class::getName));

        PerModHandler(IBuildCraftMod module) {
            this.module = module;
        }
    }

    static final class PerMessageInfo<I> {
        final PerModHandler modHandler;
        final Class<I> messageClass;
        final BiConsumer<I, RegistryFriendlyByteBuf> encoder;
        final Function<RegistryFriendlyByteBuf, I> decoder;

        @Nullable
        BiConsumer<I, Supplier<BCPacketContext>> clientHandler;

        @Nullable
        BiConsumer<I, Supplier<BCPacketContext>> serverHandler;

        PerMessageInfo(
            PerModHandler modHandler,
            Class<I> messageClass,
            BiConsumer<I, RegistryFriendlyByteBuf> encoder,
            Function<RegistryFriendlyByteBuf, I> decoder
        ) {
            this.modHandler = modHandler;
            this.messageClass = messageClass;
            this.encoder = encoder;
            this.decoder = decoder;
        }
    }

    public record BuildCraftPayload(Object message) implements CustomPacketPayload {
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static int compareMods(IBuildCraftMod modA, IBuildCraftMod modB) {
        if (modA instanceof Enum<?> enumA && modB instanceof Enum<?> enumB
            && enumA.getDeclaringClass() == enumB.getDeclaringClass()) {
            return Integer.compare(enumA.ordinal(), enumB.ordinal());
        }
        return modA.getModId().compareTo(modB.getModId());
    }

    /**
     * Registers a message as one that can be sent but has no handler on this physical side.
     */
    public static <I> void registerMessageClass(
        IBuildCraftMod module,
        Class<I> clazz,
        BiConsumer<I, ? super RegistryFriendlyByteBuf> encoder,
        Function<? super RegistryFriendlyByteBuf, I> decoder,
        Dist... sides
    ) {
        registerMessageClass(module, clazz, null, encoder, decoder, sides);
    }

    /** Registers a handler that is valid only for packets delivered to a client. */
    public static <I> void registerClientboundMessageClass(
        IBuildCraftMod module,
        Class<I> messageClass,
        @Nullable BiConsumer<I, Supplier<BCPacketContext>> messageHandler,
        BiConsumer<I, ? super RegistryFriendlyByteBuf> encoder,
        Function<? super RegistryFriendlyByteBuf, I> decoder
    ) {
        registerMessageClass(module, messageClass, messageHandler, encoder, decoder, Dist.CLIENT);
    }

    public static synchronized <I> void registerMessageClass(
        IBuildCraftMod module,
        Class<I> messageClass,
        @Nullable BiConsumer<I, Supplier<BCPacketContext>> messageHandler,
        BiConsumer<I, ? super RegistryFriendlyByteBuf> encoder,
        Function<? super RegistryFriendlyByteBuf, I> decoder,
        Dist... sides
    ) {
        PerModHandler modHandler = MOD_HANDLERS.computeIfAbsent(module, PerModHandler::new);

        @SuppressWarnings("unchecked")
        PerMessageInfo<I> messageInfo = (PerMessageInfo<I>) modHandler.knownMessages.get(messageClass);
        if (messageInfo == null) {
            BiConsumer<I, RegistryFriendlyByteBuf> typedEncoder = (message, buffer) -> encoder.accept(message, buffer);
            Function<RegistryFriendlyByteBuf, I> typedDecoder = buffer -> decoder.apply(buffer);
            messageInfo = new PerMessageInfo<>(modHandler, messageClass, typedEncoder, typedDecoder);
            modHandler.knownMessages.put(messageClass, messageInfo);
            MESSAGE_HANDLERS.put(messageClass, messageInfo);
            MESSAGE_HANDLERS_BY_NAME.put(messageClass.getName(), messageInfo);
        }

        if (messageHandler == null) {
            return;
        }

        Dist specificSide = sides != null && sides.length == 1 ? sides[0] : null;
        if (specificSide == null || specificSide == Dist.CLIENT) {
            messageInfo.clientHandler = messageHandler;
        }
        if (specificSide == null || specificSide == Dist.DEDICATED_SERVER) {
            messageInfo.serverHandler = messageHandler;
        }

        if (DEBUG) {
            BCLog.logger.info("[lib.messages] Registered {} for {}", messageClass.getName(), module.getModId());
        }
    }

    public static <I> void setHandler(
        Class<I> messageClass,
        BiConsumer<I, Supplier<BCPacketContext>> messageHandler,
        Dist side
    ) {
        @SuppressWarnings("unchecked")
        PerMessageInfo<I> info = (PerMessageInfo<I>) MESSAGE_HANDLERS.get(messageClass);
        if (info == null) {
            throw new IllegalArgumentException("Cannot set handler for unregistered message: " + messageClass);
        }
        if (side == Dist.CLIENT) {
            info.clientHandler = messageHandler;
        } else {
            info.serverHandler = messageHandler;
        }
    }

    /** Registers BuildCraft's envelope payload on the NeoForge mod event bus. */
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(PROTOCOL_VERSION)
            .playBidirectional(TYPE, STREAM_CODEC, MessageManager::handlePayload, MessageManager::handlePayload);
    }

    /** Kept for the existing module lifecycle; registration itself happens in RegisterPayloadHandlersEvent. */
    public static void fmlPostInit() {
        if (!DEBUG) {
            return;
        }
        for (PerModHandler handler : MOD_HANDLERS.values()) {
            BCLog.logger.info(
                "[lib.messages] Module {} registered {} message classes",
                handler.module.getModId(),
                handler.knownMessages.size()
            );
        }
    }

    private static void encodePayload(RegistryFriendlyByteBuf buffer, BuildCraftPayload payload) {
        Object message = payload.message();
        PerMessageInfo<Object> info = getInfo(message);
        buffer.writeUtf(info.messageClass.getName(), MAX_MESSAGE_CLASS_NAME);
        info.encoder.accept(message, buffer);
    }

    private static BuildCraftPayload decodePayload(RegistryFriendlyByteBuf buffer) {
        String className = buffer.readUtf(MAX_MESSAGE_CLASS_NAME);
        PerMessageInfo<?> info = MESSAGE_HANDLERS_BY_NAME.get(className);
        if (info == null) {
            throw new DecoderException("Received unregistered BuildCraft message class " + className);
        }
        Object decoded = info.decoder.apply(buffer);
        NetworkSecurity.requireFullyRead(buffer, className);
        return new BuildCraftPayload(decoded);
    }

    private static void handlePayload(BuildCraftPayload payload, IPayloadContext context) {
        Object message = payload.message();
        PerMessageInfo<Object> info = getInfo(message);
        boolean clientbound = context.flow() == PacketFlow.CLIENTBOUND;
        BiConsumer<Object, Supplier<BCPacketContext>> handler =
            clientbound ? info.clientHandler : info.serverHandler;

        if (!clientbound && !(context.player() instanceof ServerPlayer)) {
            BCLog.logger.debug("[lib.messages] Dropped server-bound {} without a ServerPlayer", info.messageClass.getName());
            return;
        }
        if (handler == null) {
            if (clientbound) {
                BCLog.logger.warn("[lib.messages] Dropped {} because it has no client handler", info.messageClass.getName());
            } else {
                BCLog.logger.debug("[lib.messages] Dropped client attempt to send client-only {}", info.messageClass.getName());
            }
            return;
        }

        try {
            handler.accept(message, () -> new NeoForgePacketContext(context));
        } catch (RuntimeException exception) {
            if (clientbound) {
                BCLog.logger.warn("[lib.messages] Failed to handle server payload {}", info.messageClass.getName(), exception);
            } else {
                BCLog.logger.debug("[lib.messages] Dropped invalid client payload {}", info.messageClass.getName(), exception);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static PerMessageInfo<Object> getInfo(Object message) {
        PerMessageInfo<?> info = MESSAGE_HANDLERS.get(message.getClass());
        if (info == null) {
            throw new IllegalArgumentException("Cannot use unregistered message " + message.getClass());
        }
        return (PerMessageInfo<Object>) info;
    }

    private static BuildCraftPayload payload(Object message) {
        getInfo(message);
        return new BuildCraftPayload(message);
    }

    public static void sendToAll(Object message) {
        PacketDistributor.sendToAllPlayers(payload(message));
    }

    public static void sendTo(Object message, ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, payload(message));
    }

    public static void sendToServer(Object message) {
        ClientPacketDistributor.sendToServer(payload(message));
    }

    public static void sendToAllWatching(Object message, LevelChunk levelChunk) {
        if (!(levelChunk.getLevel() instanceof ServerLevel serverLevel)) {
            throw new IllegalStateException("Cannot send a clientbound payload from a client level");
        }
        PacketDistributor.sendToPlayersTrackingChunk(serverLevel, levelChunk.getPos(), payload(message));
    }

    public static void sendToDimension(Object message, ResourceKey<Level> dimensionId) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalStateException("Cannot send a clientbound payload without a running server");
        }
        ServerLevel level = server.getLevel(dimensionId);
        if (level != null) {
            PacketDistributor.sendToPlayersInDimension(level, payload(message));
        }
    }
}
