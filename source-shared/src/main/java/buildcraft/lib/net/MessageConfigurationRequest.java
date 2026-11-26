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
    public MessageConfigurationRequest { changes = Map.copyOf(changes); }
    public MessageConfigurationRequest(FriendlyByteBuf buf) {
        this(buf.readLong(), buf.readLong(), buf.readBoolean(), readChanges(buf));
    }
    private static Map<String,String> readChanges(FriendlyByteBuf b) {
        int count = NetworkSecurity.requireCount(b.readVarInt(), 2048, "configuration change count");
        Map<String,String> values = new LinkedHashMap<>();
        for (int i=0;i<count;i++) {
            String id=b.readUtf(192), value=b.readUtf(4096);
            if (values.putIfAbsent(id,value)!=null) throw new IllegalArgumentException("Duplicate setting");
        }
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
                MessageManager.sendTo(MessageConfigurationResponse.denied(message.token),player); return;
            }
            var registry=ConfigurationRegistry.INSTANCE;
            String error="";
            try { if(message.commit) registry.commit(Scope.SERVER,message.revision,message.changes); }
            catch(IOException|IllegalArgumentException|IllegalStateException e) { error=String.valueOf(e.getMessage()); }
            MessageManager.sendTo(MessageConfigurationResponse.snapshot(message.token,error),player);
        });
        context.setPacketHandled(true);
    };
}
