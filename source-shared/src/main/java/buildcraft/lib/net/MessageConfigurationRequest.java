package buildcraft.lib.net;

import buildcraft.api.v2.config.ConfigDefinition.Scope;
import buildcraft.lib.internal.config.ConfigurationLifecycle;
import buildcraft.lib.internal.config.ConfigurationRegistry;
import net.minecraft.network.FriendlyByteBuf;
import java.util.*;
import java.util.function.*;
import java.io.IOException;

/** Snapshot request or atomic commit; never trusts the client to authorize access. */
public record MessageConfigurationRequest(long token, long revision, boolean commit, Map<String,String> changes) {
    public MessageConfigurationRequest {
        changes = Map.copyOf(changes);
        if (!commit && !changes.isEmpty()) throw new IllegalArgumentException("Snapshot request contains changes");
        if (ConfigurationWire.requestBytes(changes) > ConfigurationWire.MAX_REQUEST_BYTES)
            throw new IllegalArgumentException("Configuration request exceeds size limit");
    }
    public MessageConfigurationRequest(FriendlyByteBuf buf) {
        this(readToken(buf), buf.readLong(), buf.readBoolean(), readChanges(buf));
    }
    private static long readToken(FriendlyByteBuf b) {
        NetworkSecurity.requireCount(b.readableBytes(), ConfigurationWire.MAX_REQUEST_BYTES, "configuration request bytes");
        return b.readLong();
    }
    private static Map<String,String> readChanges(FriendlyByteBuf b) {
        int count = NetworkSecurity.requireCount(b.readVarInt(), ConfigurationWire.MAX_ENTRIES, "configuration change count");
        Map<String,String> values = new LinkedHashMap<>();
        for (int i=0;i<count;i++) {
            String id=b.readUtf(192), value=b.readUtf(4096);
            if (values.putIfAbsent(id,value)!=null) throw new IllegalArgumentException("Duplicate setting");
        }
        NetworkSecurity.requireFullyRead(b, "configuration request");
        return values;
    }
    public static void toBytes(MessageConfigurationRequest msg, FriendlyByteBuf b) {
        b.writeLong(msg.token); b.writeLong(msg.revision); b.writeBoolean(msg.commit);
        b.writeVarInt(msg.changes.size());
        msg.changes.forEach((id,value)->{ b.writeUtf(id,192); b.writeUtf(value,4096); });
    }
    public static final BiConsumer<MessageConfigurationRequest,Supplier<BCPacketContext>> HANDLER = (message,ctx)->{
        var context=ctx.get();
        if(context.side()!=BCNetworkSide.SERVER) return;
        context.enqueueWork(()->{
            var player=context.getSender();
            if(player==null) return;
            if(!ConfigurationLifecycle.canEdit(player)) {
                MessageConfigurationResponse.denied(message.token, player); return;
            }
            var registry=ConfigurationRegistry.INSTANCE;
            if (!message.commit) { MessageConfigurationResponse.snapshot(message.token, player); return; }
            // One atomic commit, then a small acknowledgement. Do not resend a large schema on save.
            String error="";
            long revision;
            try { revision = registry.commit(Scope.SERVER,message.revision,message.changes).revision(); }
            catch(IOException|IllegalArgumentException|IllegalStateException e) {
                error=e.getMessage()==null || e.getMessage().isBlank()?"Configuration commit rejected":e.getMessage();
                revision=registry.snapshot(Scope.SERVER).revision();
            }
            MessageConfigurationResponse.acknowledge(message.token, revision, error, player);
        });
        context.setPacketHandled(true);
    };
}
