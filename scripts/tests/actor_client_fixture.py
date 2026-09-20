"""Offline contract probes for actor and client boundary implementations.

Native doubles model only the APIs exercised here. These tests check generic
signatures, event/phase delegation and lifecycle behavior, not full Minecraft
binary compatibility. Gradle builds and in-game acceptance remain required.
"""
from __future__ import annotations

from pathlib import Path
import shutil
import subprocess


def compile_probe(java: Path, work: Path, actual: list[str], stubs: dict[str, str], probe: str, args=()) -> str:
    if not shutil.which('javac') or not shutil.which('java'):
        raise RuntimeError('Java 21 is required for actor/client boundary probes')
    source, classes = work / 'src', work / 'classes'
    for directory in (source, classes):
        if directory.exists(): shutil.rmtree(directory)
        directory.mkdir(parents=True, exist_ok=True)
    for relative in actual:
        path = source / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(java / relative, path)
    for relative, text in {**stubs, 'probe/Probe.java': probe}.items():
        path = source / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')
    commands = [
        ['javac', '--release', '21', '-encoding', 'UTF-8', '-d', str(classes), *map(str, sorted(source.rglob('*.java')))],
        ['java', '-ea', '-cp', str(classes), 'probe.Probe', *map(str, args)],
    ]
    output = []
    for command in commands:
        result = subprocess.run(command, text=True, capture_output=True, timeout=90)
        if result.returncode:
            raise AssertionError(f'{command[0]} failed:\n{result.stdout}\n{result.stderr}')
        output.append(result.stdout.strip())
    return '\n'.join(part for part in output if part)


def server_stubs(target: str) -> dict[str, str]:
    forge, newest = target.endswith('-forge'), target.startswith('1.21.11')
    native = 'net.minecraftforge' if forge else 'net.neoforged.neoforge'
    identifier = 'Identifier' if newest else 'ResourceLocation'
    stubs: dict[str, str] = {}
    def put(name, text): stubs[name.replace('.', '/') + '.java'] = text
    put('javax.annotation.Nullable', 'package javax.annotation;import java.lang.annotation.*;@Target({ElementType.TYPE_USE,ElementType.METHOD,ElementType.PARAMETER,ElementType.FIELD})public @interface Nullable {}')
    put('com.mojang.authlib.GameProfile', '''package com.mojang.authlib;import java.util.*;
public record GameProfile(UUID id,String name){public UUID getId(){return id;}public String getName(){return name;}}''')
    put('buildcraft.lib.compat.GameProfileCompat', '''package buildcraft.lib.compat;import com.mojang.authlib.GameProfile;import java.util.UUID;
public final class GameProfileCompat{public static UUID id(GameProfile p){return p==null?null:p.id();}public static String name(GameProfile p){return p==null?null:p.name();}}''')
    put('net.minecraft.core.BlockPos', '''package net.minecraft.core; public record BlockPos(int x,int y,int z) {
public static final BlockPos ZERO=new BlockPos(0,0,0);public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}
public BlockPos immutable(){return this;}public BlockPos offset(BlockPos p){return new BlockPos(x+p.x,y+p.y,z+p.z);}public BlockPos relative(Direction d){return offset(d.getNormal());}}''')
    put('net.minecraft.core.Direction', '''package net.minecraft.core;public enum Direction{NORTH(0,0,-1),SOUTH(0,0,1),WEST(-1,0,0),EAST(1,0,0),UP(0,1,0),DOWN(0,-1,0);
private final BlockPos n;Direction(int x,int y,int z){n=new BlockPos(x,y,z);}public BlockPos getNormal(){return n;}public BlockPos getUnitVec3i(){return n;}public static class Plane{public static final Direction[] HORIZONTAL={NORTH,SOUTH,WEST,EAST};}}''')
    put('net.minecraft.resources.'+identifier, f'''package net.minecraft.resources;public record {identifier}(String value){{public static {identifier} tryParse(String s){{return new {identifier}(s);}}public static {identifier} fromNamespaceAndPath(String n,String p){{return new {identifier}(n+":"+p);}}}}''')
    put('net.minecraft.world.InteractionHand', 'package net.minecraft.world;public enum InteractionHand{MAIN_HAND,OFF_HAND}')
    put('net.minecraft.world.item.ItemStack', '''package net.minecraft.world.item;public class ItemStack {public static final ItemStack EMPTY=new ItemStack(0);public int durability;public ItemStack(int d){durability=d;}}''')
    put('net.minecraft.world.entity.Entity', 'package net.minecraft.world.entity;import java.util.UUID;public class Entity{private final UUID id=UUID.randomUUID();public UUID getUUID(){return id;}}')
    put('net.minecraft.world.entity.player.Player', '''package net.minecraft.world.entity.player;import net.minecraft.world.entity.Entity;import net.minecraft.world.item.ItemStack;import net.minecraft.world.InteractionHand;
public class Player extends Entity {private double x,y,z;private ItemStack hand=ItemStack.EMPTY;public void setPos(double a,double b,double c){x=a;y=b;z=c;}public double getX(){return x;}public double getY(){return y;}public double getZ(){return z;}
public ItemStack getItemInHand(InteractionHand h){return hand;}public void setItemInHand(InteractionHand h,ItemStack s){hand=s;}}''')
    put('net.minecraft.server.level.ServerPlayer', '''package net.minecraft.server.level;import net.minecraft.world.entity.player.Player;import com.mojang.authlib.GameProfile;
public class ServerPlayer extends Player {public final ServerLevel world;public final GameProfile profile;private final Advancements advancements=new Advancements();
public ServerPlayer(ServerLevel w,GameProfile p){world=w;profile=p;}public Advancements getAdvancements(){return advancements;}
public static class Advancements{public ServerPlayer assigned;public void setPlayer(ServerPlayer p){assigned=p;}}}''')
    put('net.minecraft.world.level.block.state.BlockState', 'package net.minecraft.world.level.block.state;public record BlockState(String value){public boolean isAir(){return value.equals("air");}}')
    put('net.minecraft.world.level.Level', '''package net.minecraft.world.level;import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.world.level.block.state.BlockState;
public class Level{public final Map<BlockPos,BlockState> blocks=new HashMap<>();public boolean refuseSet;public int setCalls,lastFlags;
public BlockState getBlockState(BlockPos p){return blocks.getOrDefault(p,new BlockState("air"));}public boolean setBlock(BlockPos p,BlockState s,int f){setCalls++;lastFlags=f;if(refuseSet)return false;blocks.put(p,s);return true;}}''')
    put('net.minecraft.server.level.ServerLevel', '''package net.minecraft.server.level;import java.util.*;import net.minecraft.world.level.Level;
public class ServerLevel extends Level {private final Server server=new Server();public Server getServer(){return server;}public Object dimension(){return this;}
public static class Server{public boolean dedicated;private final PlayerList players=new PlayerList();public boolean isDedicatedServer(){return dedicated;}public PlayerList getPlayerList(){return players;}}
public static class PlayerList{public final Map<UUID,ServerPlayer> players=new HashMap<>();public ServerPlayer getPlayer(UUID id){return players.get(id);}}}''')
    put('net.minecraft.world.level.ChunkPos', '''package net.minecraft.world.level;import net.minecraft.core.BlockPos;public class ChunkPos{public final int x,z;
public ChunkPos(int a,int b){x=a;z=b;}public ChunkPos(BlockPos p){this(p.getX()>>4,p.getZ()>>4);}public boolean equals(Object o){return o instanceof ChunkPos p&&p.x==x&&p.z==z;}public int hashCode(){return x*31+z;}}''')
    put('net.minecraft.world.level.block.entity.BlockEntity', '''package net.minecraft.world.level.block.entity;import net.minecraft.core.BlockPos;import net.minecraft.world.level.Level;
public class BlockEntity{private final Level level;private final BlockPos pos;public boolean removed;public BlockEntity(Level l,BlockPos p){level=l;pos=p;}public Level getLevel(){return level;}public BlockPos getBlockPos(){return pos;}public boolean isRemoved(){return removed;}}''')
    put(native+'.common.util.FakePlayer', f'''package {native}.common.util;import net.minecraft.server.level.*;import com.mojang.authlib.GameProfile;
public class FakePlayer extends ServerPlayer{{public FakePlayer(ServerLevel w,GameProfile p){{super(w,p);}}}}''')
    # The no-sign-editor subclass is unchanged production code. This double exposes only its native constructor.
    put('buildcraft.lib.fake.FakePlayerBC', f'''package buildcraft.lib.fake;import net.minecraft.server.level.*;import com.mojang.authlib.GameProfile;
public class FakePlayerBC extends {native}.common.util.FakePlayer{{public FakePlayerBC(ServerLevel w,GameProfile p){{super(w,p);}}}}''')
    put('buildcraft.lib.internal.debug.BCLog', '''package buildcraft.lib.internal.debug;public class BCLog{public static final Logger logger=new Logger();public static class Logger{public void warn(String s,Object...a){}public void info(String s,Object...a){}}}''')
    put('buildcraft.lib.BCLib', 'package buildcraft.lib;public class BCLib{public static final String MODID="buildcraftlib";}')
    put('buildcraft.lib.BCLibConfig', '''package buildcraft.lib;import buildcraft.lib.chunkload.IChunkLoadingTile.LoadType;
public class BCLibConfig{public static ChunkLoaderType chunkLoadingType=ChunkLoaderType.ON;public static ChunkLoaderLevel chunkLoadingLevel=ChunkLoaderLevel.STRICT_TILES;
public enum ChunkLoaderType{ON,AUTO,OFF}public enum ChunkLoaderLevel{NONE,STRICT_TILES,SELF_TILES,ALL_TILES;public boolean canLoad(LoadType t){return this!=NONE&&(this!=STRICT_TILES||t==LoadType.HARD);}}}''')
    # Record native event inputs and restore the *same snapshot* after cancellation.
    put(native+'.event.level.BlockEvent', f'''package {native}.event.level;import net.minecraft.core.*;import net.minecraft.server.level.ServerLevel;import net.minecraft.world.entity.player.Player;import net.minecraft.world.level.block.state.BlockState;
public class BlockEvent{{public static class BreakEvent{{public final ServerLevel world;public final BlockPos pos;public final Player player;public final BlockState state;public boolean canceled;public BreakEvent(ServerLevel w,BlockPos p,BlockState s,Player a){{world=w;pos=p;state=s;player=a;}}public boolean isCanceled(){{return canceled;}}}}}}''')
    busname = 'MinecraftForge' if forge else 'NeoForge'
    post = f'''public boolean post({native}.event.level.BlockEvent.BreakEvent e){{calls++;last=e;e.canceled=deny;return deny;}}''' if forge else f'''public <T extends {native}.event.level.BlockEvent.BreakEvent> T post(T e){{calls++;last=e;e.canceled=deny;return e;}}'''
    put(native+'.common.'+busname, f'''package {native}.common;public class {busname}{{public static final Bus EVENT_BUS=new Bus();public static class Bus{{public int calls;public boolean deny;public {native}.event.level.BlockEvent.BreakEvent last;{post}}}}}''')
    createargs = ',int flags' if not forge else ''
    restoreargs = '' if not forge else 'boolean force'
    put(native+'.common.util.BlockSnapshot', f'''package {native}.common.util;import net.minecraft.server.level.ServerLevel;import net.minecraft.core.BlockPos;import net.minecraft.world.level.block.state.BlockState;
public class BlockSnapshot{{public static int created,restored;public final ServerLevel world;public final BlockPos pos;public final BlockState original;
private BlockSnapshot(ServerLevel w,BlockPos p){{world=w;pos=p;original=w.getBlockState(p);}}public static BlockSnapshot create(Object d,ServerLevel w,BlockPos p{createargs}){{created++;return new BlockSnapshot(w,p);}}
public void restore({restoreargs}){{restored++;world.blocks.put(pos,original);}}}}''')
    hooks='ForgeEventFactory' if forge else 'EventHooks'
    put(native+'.event.'+hooks, f'''package {native}.event;import net.minecraft.core.Direction;import net.minecraft.world.entity.player.Player;import {native}.common.util.BlockSnapshot;
public class {hooks}{{public static boolean deny;public static int calls;public static Player actor;public static Direction face;
public static boolean onBlockPlace(Player a,BlockSnapshot snapshot,Direction d){{calls++;actor=a;face=d;return deny;}}}}''')
    # A loader ticket set is idempotent; false on a duplicate is *not* a permission denial.
    put('probe.NativeTickets', '''package probe;import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.world.level.ChunkPos;import net.minecraft.server.level.ServerLevel;
public class NativeTickets{public record Key(BlockPos owner,int x,int z,boolean ticking){}public static final Map<ServerLevel,Set<Key>> tickets=new IdentityHashMap<>();public static int forceCalls;public static Object id;
public static boolean force(ServerLevel w,Object controller,BlockPos owner,int x,int z,boolean add,boolean ticking){forceCalls++;id=controller;Set<Key>s=tickets.computeIfAbsent(w,k->new HashSet<>());Key key=new Key(owner,x,z,ticking);return add?s.add(key):s.remove(key);}
public static Set<Key> get(ServerLevel w){return tickets.getOrDefault(w,Set.of());}}
''')
    tickethelper = '''public static class TicketHelper {public final java.util.Map<net.minecraft.core.BlockPos,Object> tickets=new java.util.HashMap<>();public int removed;public java.util.Map<net.minecraft.core.BlockPos,Object> getBlockTickets(){return tickets;}public void removeAllTickets(net.minecraft.core.BlockPos p){tickets.remove(p);removed++;}}'''
    if forge:
        put(native+'.common.world.ForgeChunkManager', f'''package {native}.common.world;import java.util.function.BiConsumer;import net.minecraft.core.BlockPos;import net.minecraft.server.level.ServerLevel;
public class ForgeChunkManager{{public static int registrations;public static String modId;public static BiConsumer<ServerLevel,TicketHelper> callback;
public static void setForcedChunkLoadingCallback(String id,BiConsumer<ServerLevel,TicketHelper> c){{registrations++;modId=id;callback=c;}}
public static boolean forceChunk(ServerLevel l,String id,BlockPos p,int x,int z,boolean add,boolean tick){{return probe.NativeTickets.force(l,id,p,x,z,add,tick);}}{tickethelper}}}''')
    else:
        pack=native+'.common.world.chunk'
        put(pack+'.TicketHelper', 'package '+pack+';'+tickethelper.replace('public static class','public class'))
        put(pack+'.TicketController', f'''package {pack};import java.util.function.BiConsumer;import net.minecraft.resources.{identifier};import net.minecraft.core.BlockPos;import net.minecraft.server.level.ServerLevel;
public class TicketController{{public final {identifier} id;public final BiConsumer<ServerLevel,TicketHelper> callback;public TicketController({identifier} i,BiConsumer<ServerLevel,TicketHelper> c){{id=i;callback=c;}}
public boolean forceChunk(ServerLevel w,BlockPos p,int x,int z,boolean a,boolean t){{return probe.NativeTickets.force(w,id,p,x,z,a,t);}}}}''')
        put(pack+'.RegisterTicketControllersEvent', f'''package {pack};public class RegisterTicketControllersEvent{{public int calls;public TicketController registered;public void register(TicketController c){{calls++;registered=c;}}}}''')
    # Public API is a dependency here, not modified source. Strict typed doubles test forwarding.
    put('buildcraft.api.v2.OperationMode','package buildcraft.api.v2;public enum OperationMode{SIMULATE,EXECUTE}')
    for enum,vals in [('PermissionVerdict','ALLOW,PASS,DENY'),('WorldOperationKind','BREAK_BLOCK,PLACE_BLOCK,INTERACT_ENTITY')]:
        put('buildcraft.api.v2.permission.'+enum,f'package buildcraft.api.v2.permission;public enum {enum}{{{vals}}}')
    put('buildcraft.api.v2.permission.PermissionDecision','package buildcraft.api.v2.permission;public record PermissionDecision(PermissionVerdict verdict){}')
    put('buildcraft.api.v2.permission.AutomationActor', f'''package buildcraft.api.v2.permission;import java.util.UUID;import net.minecraft.resources.{identifier};public record AutomationActor(UUID ownerId,String ownerName,{identifier} source,boolean system){{public static AutomationActor machineOwner(UUID u,String n,{identifier} s){{return new AutomationActor(u,n,s,false);}}public static AutomationActor system({identifier} s){{return new AutomationActor(null,null,s,true);}}}}''')
    put('buildcraft.api.v2.permission.WorldOperationTarget','''package buildcraft.api.v2.permission;import net.minecraft.core.BlockPos;import java.util.UUID;public record WorldOperationTarget(BlockPos pos,UUID entity){public static WorldOperationTarget block(BlockPos p){return new WorldOperationTarget(p,null);}public static WorldOperationTarget entity(UUID id){return new WorldOperationTarget(null,id);}}''')
    put('buildcraft.api.v2.permission.WorldOperationContext', f'''package buildcraft.api.v2.permission;import net.minecraft.core.BlockPos;import net.minecraft.world.level.Level;import net.minecraft.resources.{identifier};import buildcraft.api.v2.OperationMode;public record WorldOperationContext(AutomationActor actor,Level level,BlockPos origin,WorldOperationTarget target,WorldOperationKind kind,OperationMode mode,{identifier} source){{}}''')
    put('buildcraft.api.v2.BuildCraftServices','package buildcraft.api.v2;public class BuildCraftServices{public static final Object PERMISSIONS=new Object();}')
    put('buildcraft.api.v2.BuildCraftApi', '''package buildcraft.api.v2;import buildcraft.api.v2.permission.*;
public class BuildCraftApi{public static PermissionVerdict verdict=PermissionVerdict.PASS;public static WorldOperationContext last;public static int calls;
public static Service service(Object key){if(key!=BuildCraftServices.PERMISSIONS)throw new AssertionError("wrong service");return new Service();}public static class Service{public PermissionDecision decide(WorldOperationContext c){calls++;last=c;return new PermissionDecision(verdict);}}}''')
    return stubs


SERVER_PROBE = r'''package probe;
import java.util.*;import com.mojang.authlib.GameProfile;import net.minecraft.core.*;import net.minecraft.server.level.*;import net.minecraft.world.*;import net.minecraft.world.item.*;
import net.minecraft.world.entity.Entity;import net.minecraft.world.level.*;import net.minecraft.world.level.block.entity.*;import net.minecraft.world.level.block.state.*;
import buildcraft.lib.platform.actor.*;import buildcraft.lib.platform.permission.*;import buildcraft.lib.platform.chunk.*;import buildcraft.lib.misc.*;import buildcraft.lib.chunkload.*;
import buildcraft.lib.BCLibConfig;import buildcraft.lib.BCLibConfig.*;import buildcraft.api.v2.*;import buildcraft.api.v2.permission.*;
public class Probe{
static int checks;static void ok(boolean v,String name){checks++;if(!v)throw new AssertionError(name);}static class Tile extends BlockEntity implements IChunkLoadingTile {
 Set<ChunkPos> requested=new HashSet<>();LoadType type=LoadType.HARD;Tile(Level w,BlockPos p){super(w,p);}public Set<ChunkPos> getChunksToLoad(){return requested;}public LoadType getLoadType(){return type;}}
public static void main(String[]args){boolean oldest=Boolean.parseBoolean(args[0]);
// Generic cache uses world identity even if a world class supplies value equality.
ActorCache<Object,String,Object> cache=new ActorCache<>();Object w1=new String("world"),w2=new String("world");int[] made={0};
Object a=cache.get(w1,"owner",(w,o)->{made[0]++;return new Object();});ok(cache.get(w1,new String("owner"),(w,o)->new Object())==a,"owner value identity");
ok(cache.get(w2,"owner",(w,o)->new Object())!=a,"world object identity");ok(cache.worldCount()==2&&made[0]==1,"cached factory once");cache.unload(w1);ok(cache.worldCount()==1,"unload only one world");cache.clear();ok(cache.worldCount()==0,"clear cache");
BCActors.stopServer();ServerLevel world=new ServerLevel(),other=new ServerLevel();UUID id=UUID.randomUUID();GameProfile owner=new GameProfile(id,"Owner");BlockPos origin=new BlockPos(10,22,30),target=new BlockPos(12,23,31);
ServerPlayer actor=BCActors.at(world,owner,origin);ok(actor.profile.equals(owner)&&actor.world==world,"actor owner and world");ok(actor.getX()==10&&actor.getY()==22&&actor.getZ()==30,"actor position");
ok(FakePlayerProvider.INSTANCE.getFakePlayer(world,new GameProfile(id,"Owner"),origin)==actor,"compat facade same cached actor");ok(BCActors.at(other,owner,origin)!=actor,"fake player is not moved between worlds");
ok(BCActors.at(world,new GameProfile(UUID.randomUUID(),"Other"),origin)!=actor,"owners isolated");
ok(BCActors.at(world,null,origin).profile.equals(FakePlayerProvider.NULL_PROFILE),"null owner fallback");ok(FakePlayerProvider.NULL_PROFILE.id().equals(UUID.nameUUIDFromBytes("buildcraft.core".getBytes(java.nio.charset.StandardCharsets.UTF_8))),"saved system UUID unchanged");
ServerPlayer online=new ServerPlayer(world,owner);world.getServer().getPlayerList().players.put(id,online);online.getAdvancements().assigned=actor;BCActors.at(world,owner,origin);
ok(oldest?online.getAdvancements().assigned==actor:online.getAdvancements().assigned==online,"version advancement repair preserved");
ItemStack prior=new ItemStack(9),tool=new ItemStack(100),inner=new ItemStack(30);actor.setItemInHand(InteractionHand.MAIN_HAND,prior);actor.setPos(1,2,3);
int value=BCActors.withTool(world,owner,target,tool,p->{ok(p==actor&&p.getItemInHand(InteractionHand.MAIN_HAND)==tool,"same mutable tool and actor");ok(p.getX()==12,"scoped outer position");tool.durability--;
 try{BCActors.withTool(world,owner,new BlockPos(99,99,99),inner,q->{ok(q.getItemInHand(InteractionHand.MAIN_HAND)==inner,"nested tool");throw new IllegalStateException("probe");});}catch(IllegalStateException expected){}
 ok(p.getItemInHand(InteractionHand.MAIN_HAND)==tool&&p.getX()==12,"nested failure restores outer context");return 42;});
ok(value==42&&tool.durability==99,"legitimate tool mutation survives");ok(actor.getItemInHand(InteractionHand.MAIN_HAND)==prior&&actor.getX()==1&&actor.getY()==2&&actor.getZ()==3,"outer scope restored");
try{BCActors.withTool(world,owner,target,tool,p->{throw new IllegalStateException("outer");});}catch(IllegalStateException expected){}ok(actor.getItemInHand(InteractionHand.MAIN_HAND)==prior&&actor.getX()==1,"outer failure restoration");
BCActors.unloadWorld(world);ok(BCActors.at(world,owner,origin)!=actor,"world unload evicts actors");
// API2 identity and intent forwarding; PASS stays allowed, never owner-only permission.
for(PermissionVerdict verdict:PermissionVerdict.values()){BuildCraftApi.verdict=verdict;int calls=BuildCraftApi.calls;
boolean allowed=AutomationPermissionUtil.mayBlock(world,origin,target,owner,BCPermissions.SOURCE_PUMP,WorldOperationKind.BREAK_BLOCK,OperationMode.SIMULATE);
ok(allowed==(verdict!=PermissionVerdict.DENY),"permission verdict "+verdict);ok(BuildCraftApi.calls==calls+1,"one permission decision");var context=BuildCraftApi.last;
ok(context.level()==world&&context.origin().equals(origin)&&context.target().pos().equals(target),"permission world/origin/target");ok(context.actor().ownerId().equals(id)&&context.actor().ownerName().equals("Owner"),"permission actor");ok(context.mode()==OperationMode.SIMULATE&&context.kind()==WorldOperationKind.BREAK_BLOCK&&context.source()==BCPermissions.SOURCE_PUMP,"operation and simulation preserved");}
BuildCraftApi.verdict=PermissionVerdict.PASS;Entity entity=new Entity();ok(BCPermissions.mayEntity(world,origin,entity,null,BCPermissions.SOURCE_ROBOT,WorldOperationKind.INTERACT_ENTITY,OperationMode.EXECUTE),"system execution may pass");ok(BuildCraftApi.last.target().entity().equals(entity.getUUID())&&BuildCraftApi.last.actor().system(),"entity/system identity");
ok(BCPermissions.actor(new GameProfile(FakePlayerProvider.NULL_PROFILE.id(),"renamed"),BCPermissions.SOURCE_BUILDER).system(),"system identity uses UUID not name or object");
// Native protection dispatch: a failed set produces no placement event; a denial rolls back.
var stone=new BlockState("stone");var newState=new BlockState("machine");world.blocks.put(target,stone);__BUS__.EVENT_BUS.calls=0;
ok(!PlatformWorldActions.canBreakBlock(world,target,null),"missing actor cannot break");ok(!PlatformWorldActions.canBreakBlock(world,new BlockPos(0,0,0),actor),"air does not emit break event");ok(__BUS__.EVENT_BUS.calls==0,"no spurious native break event");
__BUS__.EVENT_BUS.deny=true;ok(!PlatformWorldActions.canBreakBlock(world,target,actor),"native break denied");ok(__BUS__.EVENT_BUS.last.player==actor&&__BUS__.EVENT_BUS.last.pos.equals(target),"native sees exact actor and block");
__BUS__.EVENT_BUS.deny=false;ok(PlatformWorldActions.canBreakBlock(world,target,actor),"native break allowed");ok(world.getBlockState(target)==stone,"permission check never mutates world");
__HOOK__.calls=0;__HOOK__.deny=true;int restored=__SNAP__.restored;ok(!PlatformWorldActions.placeBlock(world,target,newState,actor,Direction.UP,3),"place cancel reported");ok(world.getBlockState(target)==stone&&__SNAP__.restored==restored+1,"snapshot restored once");ok(__HOOK__.actor==actor&&__HOOK__.face==Direction.UP,"place actor and clicked face");
world.refuseSet=true;int calls=__HOOK__.calls;ok(!PlatformWorldActions.placeBlock(world,target,newState,actor,Direction.DOWN,7),"setBlock failure");ok(__HOOK__.calls==calls,"no place event after failed set");world.refuseSet=false;__HOOK__.deny=false;
ok(PlatformWorldActions.placeBlock(world,target,newState,null,Direction.SOUTH,11),"fallback actor placement");ok(__HOOK__.actor instanceof ServerPlayer sp&&sp.profile.equals(FakePlayerProvider.NULL_PROFILE),"fallback native sees system profile");ok(world.getBlockState(target)==newState&&world.lastFlags==11,"placement state and flags");
Level client=new Level();calls=__HOOK__.calls;ok(PlatformWorldActions.placeBlock(client,target,newState,null,Direction.UP,2)&&__HOOK__.calls==calls,"client behavior unchanged without server event");
// Chunk ownership remains block position; native ticket persistence outlives world unload.
BCLibConfig.chunkLoadingType=ChunkLoaderType.ON;BCLibConfig.chunkLoadingLevel=ChunkLoaderLevel.STRICT_TILES;ServerLevel tickets=new ServerLevel();BlockPos ownerPos=new BlockPos(32,70,64);Tile tile=new Tile(tickets,ownerPos);
tile.requested.add(new ChunkPos(5,5));ok(ChunkLoaderManager.getChunksToLoad(tile).contains(new ChunkPos(ownerPos)),"owner chunk included");
ChunkLoaderManager.loadChunksForTile(tile);ok(NativeTickets.get(tickets).size()==2,"owner plus requested chunk");ok(NativeTickets.get(tickets).stream().allMatch(k->k.ticking()&&k.owner().equals(ownerPos)),"all tickets ticking with original owner key");
ChunkLoaderManager.loadChunksForTile(tile);ok(NativeTickets.get(tickets).size()==2,"duplicate acquire does not duplicate native ticket");
tile.requested.clear();tile.requested.add(new ChunkPos(6,6));ChunkLoaderManager.loadChunksForTile(tile);ok(NativeTickets.get(tickets).size()==2&&!NativeTickets.get(tickets).contains(new NativeTickets.Key(ownerPos,5,5,true)),"resize releases obsolete chunk");
BCLibConfig.chunkLoadingType=ChunkLoaderType.OFF;ChunkLoaderManager.loadChunksForTile(tile);ok(NativeTickets.get(tickets).isEmpty(),"config off releases tracked work area");
BCLibConfig.chunkLoadingType=ChunkLoaderType.AUTO;tickets.getServer().dedicated=true;ChunkLoaderManager.loadChunksForTile(tile);ok(NativeTickets.get(tickets).isEmpty(),"AUTO disabled dedicated");tickets.getServer().dedicated=false;ChunkLoaderManager.loadChunksForTile(tile);ok(NativeTickets.get(tickets).size()==2,"AUTO allows integrated server");
BCChunkTickets.unloadWorld(tickets);ok(NativeTickets.get(tickets).size()==2,"world unload retains persistent tickets");ChunkLoaderManager.releaseChunksFor(tile);ok(NativeTickets.get(tickets).isEmpty(),"release after mirror unload also covers tile requested chunks");
tile.type=IChunkLoadingTile.LoadType.SOFT;ChunkLoaderManager.loadChunksForTile(tile);ok(NativeTickets.get(tickets).isEmpty(),"strict tickets reject soft load");BCLibConfig.chunkLoadingLevel=ChunkLoaderLevel.SELF_TILES;ChunkLoaderManager.loadChunksForTile(tile);ok(NativeTickets.get(tickets).size()==2,"self tiles accepts soft load");
ChunkLoaderManager.releaseChunksFor(tile);tile.removed=true;int forceCalls=NativeTickets.forceCalls;ChunkLoaderManager.loadChunksForTile(tile);ok(NativeTickets.forceCalls==forceCalls,"removed tile cannot acquire");
__TICKET_CALLBACK__
BCActors.stopServer();System.out.println("Actor/permission/ticket boundary: "+checks+" assertions (native/API doubles)");}}
'''


def run_server(java: Path, work: Path, target: str) -> str:
    forge = target.endswith('-forge')
    native = 'net.minecraftforge' if forge else 'net.neoforged.neoforge'
    substitutions = {
        '__BUS__': native+'.common.'+('MinecraftForge' if forge else 'NeoForge'),
        '__HOOK__': native+'.event.'+('ForgeEventFactory' if forge else 'EventHooks'),
        '__SNAP__': native+'.common.util.BlockSnapshot',
    }
    if forge:
        callback = '''PlatformChunkTickets.init();PlatformChunkTickets.init();
ok(net.minecraftforge.common.world.ForgeChunkManager.registrations==1&&net.minecraftforge.common.world.ForgeChunkManager.modId.equals("buildcraftlib"),"one callback under saved mod ID");
var helper=new net.minecraftforge.common.world.ForgeChunkManager.TicketHelper();helper.tickets.put(ownerPos,new Object());
BCLibConfig.chunkLoadingType=ChunkLoaderType.ON;BCLibConfig.chunkLoadingLevel=ChunkLoaderLevel.STRICT_TILES;net.minecraftforge.common.world.ForgeChunkManager.callback.accept(tickets,helper);ok(helper.removed==0,"retain owner tickets on enabled restart");
BCLibConfig.chunkLoadingType=ChunkLoaderType.OFF;net.minecraftforge.common.world.ForgeChunkManager.callback.accept(tickets,helper);ok(helper.removed==1&&helper.tickets.isEmpty(),"remove disabled persisted owner");
ok(NativeTickets.id.equals("buildcraftlib"),"forceChunk persisted mod ID");'''
    else:
        callback = '''var event=new net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent();PlatformChunkTickets.registerTicketController(event);ok(event.calls==1&&event.registered.id.value().equals("buildcraftlib:machines"),"same controller identifier");
var helper=new net.neoforged.neoforge.common.world.chunk.TicketHelper();helper.tickets.put(ownerPos,new Object());BCLibConfig.chunkLoadingType=ChunkLoaderType.ON;BCLibConfig.chunkLoadingLevel=ChunkLoaderLevel.STRICT_TILES;
event.registered.callback.accept(tickets,helper);ok(helper.removed==0,"retain owner tickets on enabled restart");BCLibConfig.chunkLoadingType=ChunkLoaderType.OFF;event.registered.callback.accept(tickets,helper);ok(helper.removed==1&&helper.tickets.isEmpty(),"remove disabled persisted owner");ok(NativeTickets.id.equals(event.registered.id),"force uses registered controller ID");'''
    substitutions['__TICKET_CALLBACK__'] = callback
    probe=SERVER_PROBE
    for a,b in substitutions.items():probe=probe.replace(a,b)
    actual = [
        'buildcraft/lib/platform/actor/ActorCache.java', 'buildcraft/lib/platform/actor/BCActors.java',
        'buildcraft/lib/platform/actor/PlatformActors.java', 'buildcraft/lib/misc/FakePlayerProvider.java',
        'buildcraft/lib/platform/permission/BCPermissions.java', 'buildcraft/lib/platform/permission/PlatformWorldActions.java',
        'buildcraft/lib/misc/AutomationPermissionUtil.java', 'buildcraft/lib/platform/chunk/BCChunkTickets.java',
        'buildcraft/lib/platform/chunk/BCTicketOwners.java', 'buildcraft/lib/platform/chunk/PlatformChunkTickets.java',
        'buildcraft/lib/chunkload/ChunkLoaderManager.java', 'buildcraft/lib/chunkload/IChunkLoadingTile.java',
    ]
    return compile_probe(java,work,actual,server_stubs(target),probe,[str(target.startswith('1.19.2')).lower()])
