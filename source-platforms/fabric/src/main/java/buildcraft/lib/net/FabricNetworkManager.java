package buildcraft.lib.net;

import buildcraft.lib.internal.module.IBuildCraftMod;
import buildcraft.lib.platform.network.PlatformNetworkTransport;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Fabric transport for the loader-neutral legacy packet catalogue.
 * Message identity is stable and derived from module id + registered class name, not registration order.
 */
public final class FabricNetworkManager {
    private static final Map<Class<?>, MessageInfo<?>> BY_CLASS = new IdentityHashMap<>();
    private static final Map<ResourceLocation, MessageInfo<?>> BY_CHANNEL = new LinkedHashMap<>();
    private static final List<MessageInfo<?>> CLIENTBOUND = new ArrayList<>();
    private static boolean serverReceiversInstalled;
    private static ClientBridge clientBridge = ClientBridge.UNAVAILABLE;

    private FabricNetworkManager() {
    }

    public static <I> void registerCatalogMessage(
        IBuildCraftMod module,
        Class<I> messageClass,
        BiConsumer<I, Supplier<BCPacketContext>> handler,
        BiConsumer<I, FriendlyByteBuf> encoder,
        Function<FriendlyByteBuf, I> decoder,
        BCMessageDirection direction
    ) {
        ResourceLocation channel = channel(module, messageClass);
        MessageInfo<I> info = new MessageInfo<>(channel, messageClass, handler, encoder, decoder, direction);
        MessageInfo<?> oldClass = BY_CLASS.putIfAbsent(messageClass, info);
        MessageInfo<?> oldChannel = BY_CHANNEL.putIfAbsent(channel, info);
        if (oldClass != null || oldChannel != null) {
            throw new IllegalStateException("Duplicate Fabric BuildCraft packet registration: " + messageClass.getName() + " / " + channel);
        }
        if (direction != BCMessageDirection.SERVERBOUND) {
            CLIENTBOUND.add(info);
            if (clientBridge != ClientBridge.UNAVAILABLE) {
                clientBridge.register(info);
            }
        }
        if (serverReceiversInstalled && direction != BCMessageDirection.CLIENTBOUND) {
            registerServerReceiver(info);
        }
    }

    /** Register all serverbound receivers after the common catalogue has been populated. */
    public static synchronized void installServerReceivers() {
        if (serverReceiversInstalled) {
            return;
        }
        for (MessageInfo<?> raw : BY_CHANNEL.values()) {
            if (raw.direction != BCMessageDirection.CLIENTBOUND) {
                registerServerReceiver(raw);
            }
        }
        serverReceiversInstalled = true;
    }

    /** Called from the client entrypoint; keeps client-only Fabric classes out of dedicated-server class loading. */
    public static synchronized void installClientBridge(ClientBridge bridge) {
        clientBridge = java.util.Objects.requireNonNull(bridge, "bridge");
        for (MessageInfo<?> info : CLIENTBOUND) {
            bridge.register(info);
        }
    }

    private static <I> void registerServerReceiver(MessageInfo<I> info) {
        ServerPlayNetworking.registerGlobalReceiver(info.channel, (server, player, handler, buf, responseSender) -> {
            final I message;
            try {
                message = info.decoder.apply(buf);
            } catch (RuntimeException exception) {
                return;
            }
            server.execute(() -> info.handler.accept(message,
                () -> new FabricPacketContext(BCNetworkSide.SERVER, player, server::execute)));
        });
    }

    public static PlatformNetworkTransport transport() {
        return Transport.INSTANCE;
    }

    private static FriendlyByteBuf encode(Object message) {
        MessageInfo<Object> info = info(message);
        FriendlyByteBuf buf = PacketByteBufs.create();
        info.encoder.accept(message, buf);
        return buf;
    }

    @SuppressWarnings("unchecked")
    private static MessageInfo<Object> info(Object message) {
        MessageInfo<?> info = BY_CLASS.get(message.getClass());
        if (info == null) {
            throw new IllegalArgumentException("Cannot send unregistered message " + message.getClass());
        }
        return (MessageInfo<Object>) info;
    }

    private static ResourceLocation channel(IBuildCraftMod module, Class<?> messageClass) {
        String simple = messageClass.getName().replace('.', '/').toLowerCase(java.util.Locale.ROOT);
        int hash = simple.hashCode();
        return new ResourceLocation(module.getModId(), "bc/" + Integer.toUnsignedString(hash, 36));
    }

    public interface ClientBridge {
        ClientBridge UNAVAILABLE = new ClientBridge() {
            @Override public void register(MessageInfo<?> info) { }
            @Override public void send(ResourceLocation channel, FriendlyByteBuf buf) {
                throw new IllegalStateException("Cannot send a serverbound BuildCraft packet without a Fabric client");
            }
        };
        void register(MessageInfo<?> info);
        void send(ResourceLocation channel, FriendlyByteBuf buf);
    }

    public static final class MessageInfo<I> {
        public final ResourceLocation channel;
        public final Class<I> messageClass;
        public final BiConsumer<I, Supplier<BCPacketContext>> handler;
        public final BiConsumer<I, FriendlyByteBuf> encoder;
        public final Function<FriendlyByteBuf, I> decoder;
        public final BCMessageDirection direction;
        private MessageInfo(ResourceLocation channel, Class<I> messageClass,
            BiConsumer<I, Supplier<BCPacketContext>> handler, BiConsumer<I, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, I> decoder, BCMessageDirection direction) {
            this.channel = channel;
            this.messageClass = messageClass;
            this.handler = handler;
            this.encoder = encoder;
            this.decoder = decoder;
            this.direction = direction;
        }
    }

    private enum Transport implements PlatformNetworkTransport {
        INSTANCE;

        @Override
        public void sendToAll(Object message) {
            MessageInfo<Object> info = info(message);
            for (ServerPlayer player : currentPlayers()) {
                ServerPlayNetworking.send(player, info.channel, encode(message));
            }
        }

        @Override
        public void sendToPlayer(Object message, ServerPlayer player) {
            MessageInfo<Object> info = info(message);
            ServerPlayNetworking.send(player, info.channel, encode(message));
        }

        @Override
        public void sendToServer(Object message) {
            MessageInfo<Object> info = info(message);
            clientBridge.send(info.channel, encode(message));
        }

        @Override
        public void sendToTrackingChunk(Object message, LevelChunk chunk) {
            MessageInfo<Object> info = info(message);
            for (ServerPlayer player : PlayerLookup.tracking(chunk)) {
                ServerPlayNetworking.send(player, info.channel, encode(message));
            }
        }

        @Override
        public void sendToDimension(Object message, ResourceKey<Level> dimension) {
            MessageInfo<Object> info = info(message);
            for (ServerPlayer player : currentPlayers()) {
                if (player.level().dimension().equals(dimension)) {
                    ServerPlayNetworking.send(player, info.channel, encode(message));
                }
            }
        }

        private static List<ServerPlayer> currentPlayers() {
            return FabricServerState.players();
        }
    }
}
