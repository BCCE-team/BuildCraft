package buildcraft.lib.net;

import buildcraft.api.v2.config.ConfigDefinition;
import buildcraft.api.v2.config.ConfigDefinition.*;
import buildcraft.lib.internal.config.ConfigurationRegistry;
import net.minecraft.network.FriendlyByteBuf;
import java.util.*;
import java.util.function.*;

/** Authoritative schema and values. Server-only addon definitions work without client registration. */
public record MessageConfigurationResponse(long token, boolean allowed, long revision, String error, List<Entry> entries) {
    public record Entry(ConfigDefinition definition, String value, String effective) {}
    public MessageConfigurationResponse { entries=List.copyOf(entries); }
    private static volatile MessageConfigurationResponse latest;
    public static MessageConfigurationResponse latest(long token) { var result=latest; return result!=null && result.token==token ? result:null; }
    public static MessageConfigurationResponse denied(long token) { return new MessageConfigurationResponse(token,false,0,"",List.of()); }
    public static MessageConfigurationResponse snapshot(long token,String error) {
        var registry=ConfigurationRegistry.INSTANCE;
        var snapshot=registry.snapshot(Scope.SERVER);
        var entries=new ArrayList<Entry>();
        for(var def:registry.definitions()) if(def.scope()==Scope.SERVER) entries.add(new Entry(def,snapshot.values().get(def.id()),snapshot.effective().get(def.id())));
        return new MessageConfigurationResponse(token,true,snapshot.revision(),error,entries);
    }
    private static void strings(FriendlyByteBuf b,List<String> strings,int max) { b.writeVarInt(strings.size()); for(String s:strings)b.writeUtf(s,max); }
    private static List<String> strings(FriendlyByteBuf b,int count,int max) {
        int n=NetworkSecurity.requireCount(b.readVarInt(),count,"configuration list");
        var result=new ArrayList<String>(n); for(int i=0;i<n;i++)result.add(b.readUtf(max)); return result;
    }
    public static void toBytes(MessageConfigurationResponse msg,FriendlyByteBuf b) {
        b.writeLong(msg.token); b.writeBoolean(msg.allowed); b.writeLong(msg.revision); b.writeUtf(msg.error.length()>1024?msg.error.substring(0,1024):msg.error,1024);
        b.writeVarInt(msg.entries.size());
        for(Entry entry:msg.entries) {
            var d=entry.definition;
            b.writeUtf(d.id(),192); b.writeEnum(d.section()); strings(b,d.category(),96); b.writeEnum(d.scope()); b.writeEnum(d.kind());
            b.writeUtf(d.defaultValue(),4096); b.writeDouble(d.minimum()); b.writeDouble(d.maximum()); strings(b,d.choices(),96);
            b.writeUtf(d.nameKey(),512); b.writeUtf(d.descriptionKey(),512); b.writeUtf(d.tooltipKey(),512);
            b.writeUtf(d.fallbackName(),512); b.writeUtf(d.fallbackDescription(),2048); b.writeUtf(d.fallbackTooltip(),4096); b.writeEnum(d.applyPolicy());
            b.writeUtf(entry.value,4096); b.writeUtf(entry.effective,4096);
        }
    }
    public MessageConfigurationResponse(FriendlyByteBuf b) { this(b.readLong(),b.readBoolean(),b.readLong(),b.readUtf(1024),readEntries(b)); }
    private static List<Entry> readEntries(FriendlyByteBuf b) {
        int count=NetworkSecurity.requireCount(b.readVarInt(),2048,"configuration entry count");
        List<Entry> entries=new ArrayList<>(count);
        for(int i=0;i<count;i++) {
            var d=new ConfigDefinition(b.readUtf(192),b.readEnum(Section.class),strings(b,12,96),b.readEnum(Scope.class),b.readEnum(Kind.class),b.readUtf(4096),b.readDouble(),b.readDouble(),strings(b,128,96),b.readUtf(512),b.readUtf(512),b.readUtf(512),b.readUtf(512),b.readUtf(2048),b.readUtf(4096),b.readEnum(ApplyPolicy.class));
            if(d.scope()!=Scope.SERVER) throw new IllegalArgumentException("Unexpected client configuration in server snapshot");
            entries.add(new Entry(d,d.validate(b.readUtf(4096)),d.validate(b.readUtf(4096))));
        }
        return entries;
    }
    public static final BiConsumer<MessageConfigurationResponse,Supplier<BCPacketContext>> HANDLER=(msg,ctx)->{
        var context=ctx.get(); if(context.side()!=BCNetworkSide.CLIENT)return;
        context.enqueueWork(()->latest=msg); context.setPacketHandled(true);
    };
}
