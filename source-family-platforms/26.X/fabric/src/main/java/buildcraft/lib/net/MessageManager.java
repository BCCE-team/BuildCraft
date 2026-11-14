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
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.server.MinecraftServer;

public final class MessageManager {
    public static final boolean DEBUG = BCDebugging.shouldDebugLog("lib.messages");
    public static final String PROTOCOL_VERSION = BuildCraftTarget.NETWORK_PROTOCOL;
    private static final int MAX_MESSAGE_CLASS_NAME = 256;

    // Separate protocol types allow packet direction to be established before
    // any message-specific decoder runs on untrusted network input.
    private static final CustomPacketPayload.Type<BuildCraftPayload> CLIENTBOUND_TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("buildcraftlib", "messages_to_client"));
    private static final CustomPacketPayload.Type<BuildCraftPayload> SERVERBOUND_TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("buildcraftlib", "messages_to_server"));

    private static final Map<IBuildCraftMod, PerModHandler> MOD_HANDLERS =
        new TreeMap<>(MessageManager::compareMods);
    private static final Map<Class<?>, PerMessageInfo<?>> MESSAGE_HANDLERS = new ConcurrentHashMap<>();
    private static final Map<String, PerMessageInfo<?>> MESSAGE_HANDLERS_BY_NAME = new ConcurrentHashMap<>();

    private static final StreamCodec<RegistryFriendlyByteBuf, BuildCraftPayload> CLIENTBOUND_CODEC =
        StreamCodec.of(MessageManager::encodePayload, MessageManager::decodeClientboundPayload);
    private static final StreamCodec<RegistryFriendlyByteBuf, BuildCraftPayload> SERVERBOUND_CODEC =
        StreamCodec.of(MessageManager::encodePayload, MessageManager::decodeServerboundPayload);

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

    public record BuildCraftPayload(Object message, boolean clientbound) implements CustomPacketPayload {
        public Type<? extends CustomPacketPayload> type() {
            return clientbound ? CLIENTBOUND_TYPE : SERVERBOUND_TYPE;
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
        BCNetworkSide... sides
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
        registerMessageClass(module, messageClass, messageHandler, encoder, decoder, BCNetworkSide.CLIENT);
    }

    public static synchronized <I> void registerMessageClass(
        IBuildCraftMod module,
        Class<I> messageClass,
        @Nullable BiConsumer<I, Supplier<BCPacketContext>> messageHandler,
        BiConsumer<I, ? super RegistryFriendlyByteBuf> encoder,
        Function<? super RegistryFriendlyByteBuf, I> decoder,
        BCNetworkSide... sides
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

        BCNetworkSide specificSide = sides != null && sides.length == 1 ? sides[0] : null;
        if (specificSide == null || specificSide == BCNetworkSide.CLIENT) {
            messageInfo.clientHandler = messageHandler;
        }
        if (specificSide == null || specificSide == BCNetworkSide.SERVER) {
            messageInfo.serverHandler = messageHandler;
        }

        if (DEBUG) {
            BCLog.logger.info("[lib.messages] Registered {} for {}", messageClass.getName(), module.getModId());
        }
    }

    public static <I> void setHandler(
        Class<I> messageClass,
        BiConsumer<I, Supplier<BCPacketContext>> messageHandler,
        BCNetworkSide side
    ) {
        @SuppressWarnings("unchecked")
        PerMessageInfo<I> info = (PerMessageInfo<I>) MESSAGE_HANDLERS.get(messageClass);
        if (info == null) {
            throw new IllegalArgumentException("Cannot set handler for unregistered message: " + messageClass);
        }
        if (side == BCNetworkSide.CLIENT) {
            info.clientHandler = messageHandler;
        } else {
            info.serverHandler = messageHandler;
        }
    }

    private static boolean payloadTypesRegistered;
    private static boolean serverReceiversRegistered;

    /**
     * Register the same two directional envelopes as NeoForge, before any
     * client/server networking play state begins.
     */
    public static synchronized void registerPayloads() {
        if (payloadTypesRegistered) return;
        PayloadTypeRegistry.clientboundPlay().register(CLIENTBOUND_TYPE, CLIENTBOUND_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SERVERBOUND_TYPE, SERVERBOUND_CODEC);
        payloadTypesRegistered = true;
        installServerReceivers();
    }

    private static synchronized void installServerReceivers() {
        if (serverReceiversRegistered) return;
        ServerPlayNetworking.registerGlobalReceiver(SERVERBOUND_TYPE, (payload, context) -> {
            var player = context.player();
            MinecraftServer server = FabricServerState.current();
            if (server == null) return;
            server.execute(() -> handlePayload(payload,
                new FabricPacketContext(BCNetworkSide.SERVER, player, server::execute),
                false));
        });
        serverReceiversRegistered = true;
    }

    /** Client networking is loaded only through ClientModInitializer. */
    public static void registerClientReceivers() {
        ClientReceiver.register();
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

    private static BuildCraftPayload decodeClientboundPayload(RegistryFriendlyByteBuf buffer) {
        return decodePayload(buffer, true);
    }

    private static BuildCraftPayload decodeServerboundPayload(RegistryFriendlyByteBuf buffer) {
        return decodePayload(buffer, false);
    }

    private static BuildCraftPayload decodePayload(RegistryFriendlyByteBuf buffer, boolean clientbound) {
        String className = buffer.readUtf(MAX_MESSAGE_CLASS_NAME);
        PerMessageInfo<?> info = MESSAGE_HANDLERS_BY_NAME.get(className);
        if (info == null) {
            throw new DecoderException("Received unregistered BuildCraft message class " + className);
        }
        // A client cannot force parsing of a client-only message: validate the
        // directional registration BEFORE running the potentially expensive decoder.
        if (clientbound ? info.clientHandler == null : info.serverHandler == null) {
            throw new DecoderException("BuildCraft message not registered in this direction: " + className);
        }
        Object decoded = info.decoder.apply(buffer);
        NetworkSecurity.requireFullyRead(buffer, className);
        return new BuildCraftPayload(decoded, clientbound);
    }

    private static void handlePayload(BuildCraftPayload payload, BCPacketContext context, boolean clientbound) {
        Object message = payload.message();
        PerMessageInfo<Object> info = getInfo(message);
        if (payload.clientbound() != clientbound || context.side().isClient() != clientbound) {
            BCLog.logger.debug("[lib.messages] Rejected {} with mismatched payload direction",
                info.messageClass.getName());
            return;
        }
        if (!clientbound && !(context.player() instanceof ServerPlayer)) {
            BCLog.logger.debug("[lib.messages] Dropped server-bound {} without authenticated player",
                info.messageClass.getName());
            return;
        }
        BiConsumer<Object, Supplier<BCPacketContext>> handler =
            clientbound ? info.clientHandler : info.serverHandler;
        if (handler == null) {
            BCLog.logger.debug("[lib.messages] Dropped {} without {} handler",
                info.messageClass.getName(), clientbound ? "client" : "server");
            return;
        }
        try {
            // Every BuildCraft handler sees exactly the same loader-neutral context.
            // Individual handlers control their own enqueueWork boundary.
            handler.accept(message, () -> context);
        } catch (RuntimeException exception) {
            if (clientbound) {
                BCLog.logger.warn("[lib.messages] Failed to process clientbound {}",
                    info.messageClass.getName(), exception);
            } else {
                BCLog.logger.debug("[lib.messages] Dropped invalid serverbound {}",
                    info.messageClass.getName(), exception);
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

    private static BuildCraftPayload payload(Object message, boolean clientbound) {
        getInfo(message);
        return new BuildCraftPayload(message, clientbound);
    }

    /** Match the NeoForge server-thread safety gate. */
    public static boolean isAuthoritativeServerThread() {
        MinecraftServer server = FabricServerState.current();
        return server != null && server.isSameThread();
    }

    public static void sendToAll(Object message) {
        MinecraftServer server = FabricServerState.current();
        if (server == null) throw new IllegalStateException("No running Fabric BuildCraft server");
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendTo(message, player);
        }
    }

    public static void sendTo(Object message, ServerPlayer player) {
        ServerPlayNetworking.send(player, payload(message, true));
    }

    public static void sendToServer(Object message) {
        ClientSender.send(message);
    }

    public static void sendToAllWatching(Object message, LevelChunk levelChunk) {
        if (!(levelChunk.getLevel() instanceof ServerLevel level)) {
            throw new IllegalStateException("Cannot send a server payload from a client level");
        }
        for (ServerPlayer player : PlayerLookup.tracking(level, levelChunk.getPos().getWorldPosition())) {
            sendTo(message, player);
        }
    }

    public static void sendToDimension(Object message, ResourceKey<Level> dimensionId) {
        MinecraftServer server = FabricServerState.current();
        if (server == null) throw new IllegalStateException("No running Fabric BuildCraft server");
        ServerLevel level = server.getLevel(dimensionId);
        if (level != null) {
            for (ServerPlayer player : level.players()) {
                sendTo(message, player);
            }
        }
    }

    /**
     * Client-only class: defer native client API resolution so the dedicated
     * server can load MessageManager without loading Minecraft client classes.
     */
    private static final class ClientSender {
        static void send(Object message) {
            ClientPlayNetworking.send(payload(message, false));
        }
    }

    private static final class ClientReceiver {
        private static boolean registered;

        static synchronized void register() {
            if (registered) return;
            ClientPlayNetworking.registerGlobalReceiver(CLIENTBOUND_TYPE, (payload, context) -> {
                net.minecraft.client.Minecraft.getInstance().execute(() -> handlePayload(payload,
                    new FabricPacketContext(BCNetworkSide.CLIENT, context.player(),
                        task -> net.minecraft.client.Minecraft.getInstance().execute(task)),
                    true));
            });
            registered = true;
        }
    }
}
