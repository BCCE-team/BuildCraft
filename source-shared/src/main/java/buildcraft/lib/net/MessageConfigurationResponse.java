package buildcraft.lib.net;

import buildcraft.api.v2.config.ConfigDefinition.Scope;
import buildcraft.lib.internal.config.ConfigurationRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.function.*;

/** A fragment of an authoritative configuration snapshot or commit acknowledgement. */
public record MessageConfigurationResponse(ConfigurationWire.Frame frame) {
    private static final int WIRE_VERSION = 2;
    private static final ConfigurationWire.Inbox INBOX = new ConfigurationWire.Inbox();
    public static void begin(long token) { INBOX.begin(token); }
    public static void cancel(long token) { INBOX.cancel(token); }
    public static int received(long token) { return INBOX.received(token); }
    public static ConfigurationWire.Result latest(long token) { return INBOX.take(token); }

    public static void denied(long token, ServerPlayer player) {
        send(token, new ConfigurationWire.Result(false, 0, "", List.of()), player);
    }
    public static void snapshot(long token, ServerPlayer player) {
        var registry = ConfigurationRegistry.INSTANCE;
        var snapshot = registry.snapshot(Scope.SERVER);
        var entries = new ArrayList<ConfigurationWire.Entry>();
        List<ConfigurationWire.Frame> frames;
        try {
            for (var def : registry.definitions()) if (def.scope() == Scope.SERVER)
                entries.add(new ConfigurationWire.Entry(def, snapshot.values().get(def.id()), snapshot.effective().get(def.id())));
            frames = ConfigurationWire.frames(token, new ConfigurationWire.Result(true, snapshot.revision(), "", entries));
        } catch (IllegalArgumentException e) {
            // No partial transfer: frame construction and validation precede sending any packet.
            frames = ConfigurationWire.frames(token, new ConfigurationWire.Result(false, snapshot.revision(), String.valueOf(e.getMessage()), List.of()));
        }
        sendFrames(frames, player);
    }
    public static void acknowledge(long token, long revision, String error, ServerPlayer player) {
        send(token, new ConfigurationWire.Result(true, revision, error, List.of()), player);
    }
    private static void send(long token, ConfigurationWire.Result result, ServerPlayer player) {
        sendFrames(ConfigurationWire.frames(token, result), player);
    }
    private static void sendFrames(List<ConfigurationWire.Frame> frames, ServerPlayer player) {
        for (var frame : frames) MessageManager.sendTo(new MessageConfigurationResponse(frame), player);
    }
    public MessageConfigurationResponse(FriendlyByteBuf b) { this(readFrame(b)); }
    private static ConfigurationWire.Frame readFrame(FriendlyByteBuf b) {
        if (b.readVarInt() != WIRE_VERSION) throw new IllegalArgumentException("Unsupported configuration response protocol");
        long token = b.readLong(); int total = b.readVarInt(), offset = b.readVarInt();
        int length = ConfigurationWire.frameLength(total, offset);
        if (b.readableBytes() != length) throw new IllegalArgumentException("Invalid configuration frame size");
        byte[] bytes = new byte[length]; b.readBytes(bytes);
        return new ConfigurationWire.Frame(token, total, offset, bytes);
    }
    public static void toBytes(MessageConfigurationResponse msg, FriendlyByteBuf b) {
        var frame = msg.frame;
        b.writeVarInt(WIRE_VERSION); b.writeLong(frame.token()); b.writeVarInt(frame.total()); b.writeVarInt(frame.offset());
        b.writeBytes(frame.data());
    }
    public static final BiConsumer<MessageConfigurationResponse, Supplier<BCPacketContext>> HANDLER = (msg, ctx) -> {
        var context = ctx.get(); if (context.side() != BCNetworkSide.CLIENT) return;
        context.enqueueWork(() -> INBOX.accept(msg.frame)); context.setPacketHandled(true);
    };
}
