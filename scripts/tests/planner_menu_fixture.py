"""Executable Java fake-network check of the real Filler Planner menu.

Simulates client with NO synced VolumeBox, real server addon, NET_DATA exchanges,
lock rejection, delayed snapshot redelivery and persistent configuration. No game runtime.
"""
from __future__ import annotations

import subprocess
import tempfile
from pathlib import Path


def execute(real_source: Path, *, level_method: bool) -> str:
    level_decl='public boolean isClientSide(){return client;}' if level_method else 'public boolean isClientSide;'
    level_constructor='this.client=c;' if level_method else 'this.isClientSide=c;'
    level_property='public final boolean client;' if level_method else ''
    sources={
        'net/minecraft/world/level/Level':f'''package net.minecraft.world.level;
public class Level{{{level_property} {level_decl} public Level(boolean c){{{level_constructor}}}}}''',
        'net/minecraft/world/phys/Vec3':'''package net.minecraft.world.phys;
public record Vec3(double x,double y,double z){
public double distanceToSqr(Vec3 p){return Math.pow(x-p.x(),2)+Math.pow(y-p.y(),2)+Math.pow(z-p.z(),2);}}''',
        'net/minecraft/world/entity/player/Player':'''package net.minecraft.world.entity.player;
import net.minecraft.world.level.Level;import net.minecraft.world.phys.Vec3;
public class Player {public final Level level;public Vec3 eye=new Vec3(1,2,3);
public Player(Level l){level=l;}public Level level(){return level;}
public Vec3 getEyePosition(){return eye;}}''',
        'net/minecraft/world/entity/player/Inventory':'''package net.minecraft.world.entity.player;
public class Inventory{public final Player player;public Inventory(Player p){player=p;}}''',
        'net/minecraft/network/FriendlyByteBuf':'''package net.minecraft.network;
import java.util.*;
public class FriendlyByteBuf{
private final List<Object> payload=new ArrayList<>();private int cursor=0;
public void writeBoolean(boolean x){payload.add(x);}public boolean readBoolean(){return (Boolean)payload.get(cursor++);}
public void writeUUID(UUID x){payload.add(x);}public UUID readUUID(){return (UUID)payload.get(cursor++);}
public <T extends Enum<T>> void writeEnum(T value){payload.add(value);}
@SuppressWarnings("unchecked")public <T extends Enum<T>> T readEnum(Class<T> cls){return (T)payload.get(cursor++);}
public void writeUtf(String s){payload.add(s);}public String readUtf(){return (String)payload.get(cursor++);}
public void writeInt(int i){payload.add(i);}public int readInt(){return (Integer)payload.get(cursor++);}
public int remaining(){return payload.size()-cursor;}}
''',
        'buildcraft/lib/net/BCNetworkSide':'''package buildcraft.lib.net;
public enum BCNetworkSide{CLIENT,SERVER}''',
        'buildcraft/lib/net/BCPacketContext':'''package buildcraft.lib.net; public class BCPacketContext{}''',
        'buildcraft/lib/net/IPayloadWriter':'''package buildcraft.lib.net;
import net.minecraft.network.FriendlyByteBuf;
public interface IPayloadWriter{void write(FriendlyByteBuf buf);}''',
        'buildcraft/lib/gui/MenuBC_Neptune':'''package buildcraft.lib.gui;
import java.util.*;import java.io.*;import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.*;import buildcraft.lib.net.*;
public class MenuBC_Neptune{
public static final int NET_DATA=5;public final Inventory playerInventory;
public final List<FriendlyByteBuf> outgoing=new ArrayList<>();
public MenuBC_Neptune(Inventory i,Object type,int id){playerInventory=i;}
public void broadcastChanges(){}public boolean stillValid(Player p){return true;}
public void readMessage(int id,FriendlyByteBuf buf,BCNetworkSide side,BCPacketContext ctx)throws IOException{}
public void sendMessage(int id,IPayloadWriter writer){
if(id!=NET_DATA)throw new AssertionError("bad menu packet id");
var buf=new FriendlyByteBuf();writer.write(buf);outgoing.add(buf);}
public FriendlyByteBuf pop(){if(outgoing.isEmpty())throw new AssertionError("missing packet");return outgoing.remove(0);}}
''',
        'buildcraft/builders/BCBuildersGuis':'''package buildcraft.builders;
public class BCBuildersGuis {public static final Ref MENU_FILLER_PLANNER=new Ref();
public static class Ref {public Object get(){return this;}}}''',
        'buildcraft/builders/internal/filler/legacy/IFillerPattern':'''package buildcraft.builders.internal.filler.legacy;
public interface IFillerPattern{}''',
        'buildcraft/builders/filler/FillerType':'''package buildcraft.builders.filler;
public class FillerType{public static final Object INSTANCE=new Object();}''',
        'buildcraft/lib/statement/FullStatement':'''package buildcraft.lib.statement;
import java.io.*;import java.util.*;import net.minecraft.network.FriendlyByteBuf;
import buildcraft.builders.internal.filler.legacy.IFillerPattern;
public class FullStatement<S> {
public interface Listener{void notify(FullStatement<?> s,int index);}
public static enum Pattern implements IFillerPattern {CLEAR,FILL}
public boolean canInteract;public final Object[] params;public S statement;
public FullStatement(Object type,int len,Listener listener){params=new Object[len];
@SuppressWarnings("unchecked")S def=(S)Pattern.CLEAR;statement=def;}
public S get(){return statement;}public void set(S val){statement=val;}
public void set(int i,Object val){params[i]=val;}public Object get(int i){return params[i];}
public void writeToBuffer(FriendlyByteBuf b){b.writeUtf(((Enum<?>)statement).name());
for(var p:params)b.writeInt(p==null?-1:(Integer)p);}
@SuppressWarnings("unchecked")public void readFromBuffer(FriendlyByteBuf b)throws IOException{
statement=(S)Pattern.valueOf(b.readUtf());for(int i=0;i<params.length;i++){
int p=b.readInt();params[i]=p==-1?null:p;}}
}''',
        'buildcraft/core/marker/volume/EnumAddonSlot':'''package buildcraft.core.marker.volume;
public enum EnumAddonSlot{CORNER}''',
        'buildcraft/core/marker/volume/Lock':'''package buildcraft.core.marker.volume;
public class Lock{public abstract static class Target{
public static class TargetResize extends Target{}
public static class TargetAddon extends Target{public final EnumAddonSlot slot;
public TargetAddon(EnumAddonSlot s){slot=s;}}}}''',
        'buildcraft/builders/addon/AddonFillerPlanner':'''package buildcraft.builders.addon;
import net.minecraft.world.phys.Vec3;import net.minecraft.world.entity.player.Player;
import buildcraft.builders.internal.filler.legacy.IFillerPattern;
import buildcraft.lib.statement.FullStatement;
public class AddonFillerPlanner{
public final FullStatement<IFillerPattern> patternStatement=new FullStatement<>(new Object(),4,null);
public boolean inverted;public int updated;
public void updateBuildingInfo(){updated++;}
public Bounds getBoundingBox(){return new Bounds();}
public static class Bounds{public Vec3 getCenter(){return new Vec3(1,2,3);}}
}''',
        'buildcraft/core/marker/volume/VolumeBox':'''package buildcraft.core.marker.volume;
import java.util.*;import java.util.stream.*;import net.minecraft.world.level.Level;
public class VolumeBox{
public UUID id=UUID.randomUUID();public final Level world;
public final Map<EnumAddonSlot,buildcraft.builders.addon.AddonFillerPlanner> addons=new HashMap<>();
public final List<Lock.Target> locks=new ArrayList<>();public boolean editing;
public VolumeBox(Level l){world=l;}
public boolean isEditing(){return editing;}
public Stream<Lock.Target> getLockTargetsStream(){return locks.stream();}}''',
        'buildcraft/core/marker/volume/WorldSavedDataVolumeBoxes':'''package buildcraft.core.marker.volume;
import java.util.*;import net.minecraft.world.level.Level;
public class WorldSavedDataVolumeBoxes{
public static WorldSavedDataVolumeBoxes DATA=new WorldSavedDataVolumeBoxes();
public VolumeBox box;public int dirty;
public static WorldSavedDataVolumeBoxes get(Level level){return DATA;}
public VolumeBox getVolumeBoxFromId(UUID id){return box!=null&&box.id.equals(id)?box:null;}
public void setDirty(){dirty++;}}''',
        'buildcraft/builders/menu/IContainerFilling':'''package buildcraft.builders.menu;
import buildcraft.lib.statement.FullStatement;
import buildcraft.builders.internal.filler.legacy.IFillerPattern;
import net.minecraft.world.entity.player.Player;
public interface IContainerFilling{
Player getPlayer();FullStatement<IFillerPattern> getPatternStatementClient();
FullStatement<IFillerPattern> getPatternStatement();boolean isInverted();
void setInverted(boolean value);boolean isLocked();void valuesChanged();
void onStatementChange();void sendData();
default void init(){sendData();}
default void sendInverted(boolean value){setInverted(value);sendData();}}
''',
        'buildcraft/builders/menu/Check':'''package buildcraft.builders.menu;
import java.util.*;import buildcraft.builders.addon.AddonFillerPlanner;
import buildcraft.core.marker.volume.*;import buildcraft.lib.gui.MenuBC_Neptune;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.statement.FullStatement.Pattern;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.level.Level;
import net.minecraft.network.FriendlyByteBuf;
public class Check{
static int n;static void ok(boolean v){n++;if(!v)throw new AssertionError("check "+n);}
public static void main(String[] args)throws Exception{
var level=new Level(false);var clientWorld=new Level(true);
var serverPlayer=new Player(level);var clientPlayer=new Player(clientWorld);
var addon=new AddonFillerPlanner();var box=new VolumeBox(level);
box.addons.put(EnumAddonSlot.CORNER,addon);
WorldSavedDataVolumeBoxes.DATA.box=box;
var server=new ContainerFillerPlanner(1,new Inventory(serverPlayer),box.id,EnumAddonSlot.CORNER);
var client=new ContainerFillerPlanner(1,new Inventory(clientPlayer),box.id,EnumAddonSlot.CORNER);
// Client render boxes are completely absent, but the editor must remain valid.
ok(client.stillValid(clientPlayer));ok(client.isLocked());
ok(server.stillValid(serverPlayer));ok(server.outgoing.size()==1);
client.readMessage(MenuBC_Neptune.NET_DATA,server.pop(),BCNetworkSide.CLIENT,null);
ok(!client.isLocked());ok(client.isInverted()==false);
ok(client.getPatternStatementClient().get()==Pattern.CLEAR);
// Real GUI edit: submit a changed pattern+parameter and invert toggle.
client.getPatternStatementClient().set(Pattern.FILL);
client.getPatternStatementClient().set(0,3);
client.sendInverted(true);
ok(client.outgoing.size()==1);
server.readMessage(MenuBC_Neptune.NET_DATA,client.pop(),BCNetworkSide.SERVER,null);
ok(addon.patternStatement.get()==Pattern.FILL);
ok(Objects.equals(addon.patternStatement.get(0),3));ok(addon.inverted);
ok(addon.updated==1);ok(WorldSavedDataVolumeBoxes.DATA.dirty==1);
client.readMessage(MenuBC_Neptune.NET_DATA,server.pop(),BCNetworkSide.CLIENT,null);
ok(client.getPatternStatementClient().get()==Pattern.FILL);
ok(client.isInverted());ok(!client.isLocked());
// Lock a working planner: update must be rejected, and client lock is synced.
box.locks.add(new Lock.Target.TargetAddon(EnumAddonSlot.CORNER));server.sendData();
client.readMessage(MenuBC_Neptune.NET_DATA,server.pop(),BCNetworkSide.CLIENT,null);
ok(client.isLocked());
client.getPatternStatementClient().set(Pattern.CLEAR);
client.sendInverted(false);
server.readMessage(MenuBC_Neptune.NET_DATA,client.pop(),BCNetworkSide.SERVER,null);
ok(addon.patternStatement.get()==Pattern.FILL);ok(addon.inverted);
ok(WorldSavedDataVolumeBoxes.DATA.dirty==1);
client.readMessage(MenuBC_Neptune.NET_DATA,server.pop(),BCNetworkSide.CLIENT,null);
ok(client.isLocked());ok(client.getPatternStatementClient().get()==Pattern.FILL);
// Snapshot redelivery: a lost first network frame must recover on its own.
for(int i=0;i<20;i++)server.broadcastChanges();
ok(server.outgoing.size()==1);
server.pop();
// Stale removed addon can never receive edits.
box.addons.clear();ok(!server.stillValid(serverPlayer));
System.out.println(n+" assertions PASS");
}}
'''
    }
    with tempfile.TemporaryDirectory(prefix='bc-planner-menu-java-') as temp:
        root=Path(temp)
        paths=[]
        for name,body in sources.items():
            p=root/'src'/(name+'.java');p.parent.mkdir(parents=True,exist_ok=True)
            p.write_text(body);paths.append(str(p))
        f=root/'src/buildcraft/builders/menu/ContainerFillerPlanner.java'
        f.write_bytes(real_source.read_bytes());paths.append(str(f))
        result=subprocess.run(['javac','--release','21','-encoding','UTF-8','-d',str(root/'classes'),*paths],
            capture_output=True,text=True,timeout=70)
        if result.returncode:raise AssertionError('Planner menu Java compile failed:\n'+result.stdout+'\n'+result.stderr)
        result=subprocess.run(['java','-cp',str(root/'classes'),'buildcraft.builders.menu.Check'],
            capture_output=True,text=True,timeout=30)
        if result.returncode:raise AssertionError('Planner menu protocol failed:\n'+result.stdout+'\n'+result.stderr)
        return result.stdout.strip()
