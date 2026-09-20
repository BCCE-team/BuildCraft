"""Actual-Java probes for the internal storage/event/registry/config boundaries.

Native API doubles model only the used signatures and observable contracts. These
checks do not replace a Gradle compile against Forge/NeoForge or an in-game test.
"""
from __future__ import annotations
from pathlib import Path
import shutil
import subprocess

PACKAGE = 'buildcraft/lib/platform/'


def run(java_root: Path, work: Path, target: str) -> str:
    if not shutil.which('javac') or not shutil.which('java'):
        raise RuntimeError('Java 21 javac and java are required; probes were not run')
    current = target.startswith('1.21.11')
    forge = target.endswith('-forge')
    prefix = 'net.minecraftforge' if forge else 'net.neoforged.neoforge'
    buspkg = 'net.minecraftforge.eventbus.api' if forge else 'net.neoforged.bus.api'
    spec = 'ForgeConfigSpec' if forge else 'ModConfigSpec'
    cfgpkg = 'net.minecraftforge.fml.event.config' if forge else 'net.neoforged.fml.event.config'
    envpkg = 'net.minecraftforge.fml.loading' if forge else 'net.neoforged.fml.loading'
    distpkg = 'net.minecraftforge.api.distmarker' if forge else 'net.neoforged.api.distmarker'
    ident = 'Identifier' if current else 'ResourceLocation'
    stubs: dict[str, str] = {}

    def put(path: str, text: str):
        stubs[path.replace('.', '/')+'.java'] = text

    put('javax.annotation.Nullable', 'package javax.annotation; public @interface Nullable {}')
    put('javax.annotation.Nonnull', 'package javax.annotation; public @interface Nonnull {}')
    put('net.minecraft.core.Direction', '''package net.minecraft.core;
public enum Direction {DOWN,UP,NORTH,SOUTH,WEST,EAST}
''')
    put('buildcraft.lib.internal.core.EnumPipePart', '''package buildcraft.lib.internal.core;
import net.minecraft.core.Direction;
public enum EnumPipePart {CENTER(null),DOWN(Direction.DOWN),UP(Direction.UP),NORTH(Direction.NORTH),SOUTH(Direction.SOUTH),WEST(Direction.WEST),EAST(Direction.EAST);
public static final EnumPipePart[] VALUES=values();public static final EnumPipePart[] HORIZONTALS={NORTH,SOUTH,WEST,EAST};public final Direction face;EnumPipePart(Direction face){this.face=face;}
public static EnumPipePart fromFacing(Direction side){if(side==null)return CENTER;return switch(side){case DOWN->DOWN;case UP->UP;case NORTH->NORTH;case SOUTH->SOUTH;case WEST->WEST;case EAST->EAST;};}}
''')
    put('net.minecraft.world.item.ItemStack', '''package net.minecraft.world.item;
public class ItemStack {public static final ItemStack EMPTY=new ItemStack("",0);public final String id;private int count;public String components="";
public ItemStack(String id,int count){this.id=id;this.count=count;}public int getCount(){return count;}public boolean isEmpty(){return count<=0||id.isEmpty();}public void setCount(int c){count=c;}
public ItemStack copy(){ItemStack s=new ItemStack(id,count);s.components=components;return s;}public ItemStack copyWithCount(int c){ItemStack s=copy();s.count=c;return s;}}
''')
    put(prefix+'.items.IItemHandler',f'''package {prefix}.items;import net.minecraft.world.item.ItemStack;
public interface IItemHandler {{int getSlots();ItemStack getStackInSlot(int s);ItemStack insertItem(int s,ItemStack v,boolean simulate);ItemStack extractItem(int s,int n,boolean simulate);int getSlotLimit(int s);boolean isItemValid(int s,ItemStack v);}}''')
    put(prefix+'.items.IItemHandlerModifiable',f'''package {prefix}.items;import net.minecraft.world.item.ItemStack;public interface IItemHandlerModifiable extends IItemHandler {{void setStackInSlot(int s,ItemStack v);}}''')
    put('buildcraft.lib.internal.inventory.IItemHandlerFiltered',f'''package buildcraft.lib.internal.inventory;
import {prefix}.items.IItemHandler;import buildcraft.lib.platform.storage.FilteredItemStorage;
public interface IItemHandlerFiltered extends IItemHandler, FilteredItemStorage {{}}''')
    put(prefix+'.energy.IEnergyStorage',f'''package {prefix}.energy;public interface IEnergyStorage {{int receiveEnergy(int n,boolean s);int extractEnergy(int n,boolean s);int getEnergyStored();int getMaxEnergyStored();boolean canExtract();boolean canReceive();}}''')
    put(prefix+'.fluids.FluidStack',f'''package {prefix}.fluids;public class FluidStack {{public static final FluidStack EMPTY=new FluidStack("",0);public final String fluid;public final int amount;public String components="";public FluidStack(String f,int a){{fluid=f;amount=a;}}public boolean isEmpty(){{return amount<=0;}}}}''')
    put(prefix+'.fluids.capability.IFluidHandler',f'''package {prefix}.fluids.capability;import {prefix}.fluids.FluidStack;
public interface IFluidHandler {{enum FluidAction {{SIMULATE,EXECUTE;public boolean simulate(){{return this==SIMULATE;}}public boolean execute(){{return this==EXECUTE;}}}}
int getTanks();FluidStack getFluidInTank(int t);int getTankCapacity(int t);boolean isFluidValid(int t,FluidStack f);int fill(FluidStack f,FluidAction a);FluidStack drain(FluidStack f,FluidAction a);FluidStack drain(int n,FluidAction a);}}''')
    put('buildcraft.lib.internal.core.IFluidFilter',f'''package buildcraft.lib.internal.core;import {prefix}.fluids.FluidStack;public interface IFluidFilter {{boolean matches(FluidStack f);}}''')
    put('buildcraft.lib.internal.core.IFluidHandlerAdv',f'''package buildcraft.lib.internal.core;import {prefix}.fluids.FluidStack;import {prefix}.fluids.capability.IFluidHandler;public interface IFluidHandlerAdv extends IFluidHandler {{FluidStack drain(IFluidFilter f,int n,FluidAction a);}}''')
    # Vanilla keys and typed registry bindings.
    put('net.minecraft.resources.'+ident, f'''package net.minecraft.resources;
public final class {ident} {{private final String id;public {ident}(String id){{this.id=id;}}public {ident}(String ns,String p){{id=ns+":"+p;}}
public static {ident} parse(String id){{return new {ident}(id);}}public static {ident} fromNamespaceAndPath(String ns,String p){{return new {ident}(ns,p);}}public String toString(){{return id;}}}}
''')
    put('net.minecraft.core.Registry','package net.minecraft.core;public interface Registry<T> {}')
    put('net.minecraft.resources.ResourceKey',f'''package net.minecraft.resources;import net.minecraft.core.Registry;
public record ResourceKey<T>({ident} id) {{public {ident} location(){{return id;}}public {ident} identifier(){{return id;}}public static <T> ResourceKey<Registry<T>> createRegistryKey({ident} id){{return new ResourceKey<>(id);}}}}
''')
    put(buspkg+'.IEventBus',f'''package {buspkg};import java.util.function.Consumer;public interface IEventBus {{<T> void addListener(Consumer<T> listener);}}''')
    put('probe.Bus',f'''package probe;import java.util.*;import java.util.function.*;
public final class Bus implements {buspkg}.IEventBus {{public final List<Consumer<Object>> listeners=new ArrayList<>();public final List<Runnable> registers=new ArrayList<>();
@SuppressWarnings("unchecked")public <T> void addListener(Consumer<T> listener){{listeners.add((Consumer<Object>)(Object)listener);}}
/** Native event buses dispatch by registered event type. Doubles reject mismatching lambda argument bridges. */
public void post(Object event){{for(var listener:List.copyOf(listeners)){{try{{listener.accept(event);}}catch(ClassCastException mismatch){{if(!mismatch.getStackTrace()[0].getClassName().equals(getClass().getName()))throw mismatch;}}}}}}
public void registerAll(){{for(var r:List.copyOf(registers))r.run();}}}}
''')
    # The CCE stack starts in Bus.post for a lambda's erased check, not in the callback body.
    holder='RegistryObject<I>' if forge else 'DeferredHolder<T,I>'
    holder_class='RegistryObject<T>' if forge else 'DeferredHolder<B,T extends B>'
    holder_name='RegistryObject' if forge else 'DeferredHolder'
    put(prefix+'.registries.'+holder_name,f'''package {prefix}.registries;import java.util.function.Supplier;
public class {holder_class} implements Supplier<T> {{private T value;public void accept(T v){{value=v;}}public T get(){{if(value==null)throw new IllegalStateException("native holder unbound");return value;}}public boolean isPresent(){{return value!=null;}}public boolean isBound(){{return value!=null;}}}}
''')
    put(prefix+'.registries.DeferredRegister',f'''package {prefix}.registries;import java.util.*;import java.util.function.*;import net.minecraft.resources.*;import net.minecraft.core.Registry;
public class DeferredRegister<T> {{private final Map<String,Runnable> entries=new LinkedHashMap<>();private boolean frozen;public static final List<String> FIRED=new ArrayList<>();private final String ns;
private DeferredRegister(String ns){{this.ns=ns;}}public static <T> DeferredRegister<T> create(ResourceKey<? extends Registry<T>> k,String ns){{return new DeferredRegister<>(ns);}}
public <I extends T> {holder} register(String path,Supplier<? extends I> factory){{if(frozen)throw new IllegalStateException("frozen native register");if(entries.containsKey(path))throw new IllegalArgumentException("duplicate");
{holder} h=new {holder_name}<>();entries.put(path,()->{{FIRED.add(ns+":"+path);h.accept(factory.get());}});return h;}}
public void register({buspkg}.IEventBus bus){{((probe.Bus)bus).registers.add(()->{{frozen=true;for(var run:entries.values())run.run();}});}}}}
''')
    # A schema recording loader double. Values are live objects, not snapshots.
    put(prefix+'.common.'+spec, f'''package {prefix}.common;import java.util.*;import java.util.function.*;
public class {spec} {{public final Map<String,Value<?>> values;public final List<String> steps;
private {spec}(Map<String,Value<?>> v,List<String>s){{values=v;steps=List.copyOf(s);}}
public static class Value<T> implements Supplier<T>{{private T value;public final Object min,max;public final boolean restart;Value(T v,Object min,Object max,boolean r){{value=v;this.min=min;this.max=max;restart=r;}}public T get(){{return value;}}public void set(T v){{if(v instanceof Number n){{double d=n.doubleValue();if(min instanceof Number lo&&d<lo.doubleValue())throw new IllegalArgumentException("below minimum");if(max instanceof Number hi&&d>hi.doubleValue())throw new IllegalArgumentException("above maximum");}}value=v;}}}}
public static class Builder {{private final Map<String,Value<?>> values=new LinkedHashMap<>();private final List<String> steps=new ArrayList<>();private final List<String> paths=new ArrayList<>();private boolean restart;
public Builder push(String p){{paths.add(p);steps.add("push:"+p);return this;}}public Builder pop(){{paths.remove(paths.size()-1);steps.add("pop");return this;}}
public Builder comment(String...s){{steps.add("comment:"+String.join("|",s));return this;}}public Builder worldRestart(){{restart=true;steps.add("restart");return this;}}
private <T> Value<T> defineValue(String k,T value,Object min,Object max){{String path=(paths.isEmpty()?"":String.join(".",paths)+".")+k;Value<T> v=new Value<>(value,min,max,restart);restart=false;if(values.put(path,v)!=null)throw new IllegalArgumentException("duplicate native key");steps.add("define:"+path+":"+value);return v;}}
public Value<Boolean> define(String k,boolean v){{return defineValue(k,v,null,null);}}public Value<String> define(String k,String v){{return defineValue(k,v,null,null);}}
public Value<Integer> defineInRange(String k,int v,int min,int max){{return defineValue(k,v,min,max);}}public Value<Double> defineInRange(String k,double v,double min,double max){{return defineValue(k,v,min,max);}}
@SafeVarargs public final <E extends Enum<E>> Value<E> defineEnum(String k,E v,E...a){{return defineValue(k,v,null,null);}}
public {spec} build(){{if(!paths.isEmpty())throw new IllegalStateException("unclosed config path");return new {spec}(values,steps);}}}}
}}
''')
    put(cfgpkg+'.ModConfigEvent',f'''package {cfgpkg};public class ModConfigEvent {{public record Config(String getModId){{}}private final Config config;ModConfigEvent(String id){{config=new Config(id);}}public Config getConfig(){{return config;}}public static final class Loading extends ModConfigEvent {{public Loading(String id){{super(id);}}}}public static final class Reloading extends ModConfigEvent {{public Reloading(String id){{super(id);}}}}}}''')
    # Common native event data shapes.
    put(distpkg+'.Dist',f'package {distpkg};public enum Dist {{CLIENT,DEDICATED_SERVER}}')
    put(envpkg+'.FMLEnvironment',f'package {envpkg};public class FMLEnvironment {{public static {distpkg}.Dist dist={distpkg}.Dist.CLIENT;public static {distpkg}.Dist getDist(){{return dist;}}}}')
    put('net.minecraft.world.level.LevelAccessor','package net.minecraft.world.level;public interface LevelAccessor {}')
    put('net.minecraft.world.level.Level','package net.minecraft.world.level;public class Level implements LevelAccessor {public boolean isClientSide;public boolean isClientSide(){return isClientSide;}}')
    put('net.minecraft.server.level.ServerLevel','package net.minecraft.server.level;public class ServerLevel extends net.minecraft.world.level.Level {}')
    put('net.minecraft.world.entity.Entity','package net.minecraft.world.entity;public class Entity {}')
    put('net.minecraft.world.entity.player.Player','package net.minecraft.world.entity.player;public class Player extends net.minecraft.world.entity.Entity {}')
    put('net.minecraft.server.level.ServerPlayer','package net.minecraft.server.level;public class ServerPlayer extends net.minecraft.world.entity.player.Player {private final ServerLevel level;public ServerPlayer(ServerLevel level){this.level=level;}public ServerLevel getLevel(){return level;} }')
    put('net.minecraft.world.level.ChunkPos','package net.minecraft.world.level;public record ChunkPos(int x,int z) {}')
    put(prefix+'.common.'+('MinecraftForge' if forge else 'NeoForge'),f"package {prefix}.common;public class {'MinecraftForge' if forge else 'NeoForge'} {{public static final probe.Bus EVENT_BUS=new probe.Bus();}}")
    put(prefix+'.event.entity.EntityJoinLevelEvent',f'package {prefix}.event.entity;public record EntityJoinLevelEvent(net.minecraft.world.entity.Entity getEntity) {{}}')
    put(prefix+'.event.level.LevelEvent',f'''package {prefix}.event.level;public class LevelEvent {{public record Unload(net.minecraft.world.level.LevelAccessor getLevel){{}}}}''')
    put(prefix+'.event.level.ChunkEvent',f'''package {prefix}.event.level;public class ChunkEvent {{public record Chunk(net.minecraft.world.level.ChunkPos getPos){{}}public record Unload(net.minecraft.world.level.LevelAccessor getLevel,Chunk getChunk){{}}}}''')
    put(prefix+'.event.level.ChunkWatchEvent',f'''package {prefix}.event.level;import net.minecraft.server.level.*;public class ChunkWatchEvent {{private final ServerPlayer p;private final ServerLevel l;public ChunkWatchEvent(ServerPlayer p,ServerLevel l){{this.p=p;this.l=l;}}public ServerPlayer getPlayer(){{return p;}}public ServerLevel getLevel(){{return l;}}
public static class Watch extends ChunkWatchEvent{{public Watch(ServerPlayer p,ServerLevel l){{super(p,l);}}}}public static class UnWatch extends ChunkWatchEvent{{public UnWatch(ServerPlayer p,ServerLevel l){{super(p,l);}}}}}}
''')
    put(prefix+'.client.event.ClientPlayerNetworkEvent',f'package {prefix}.client.event;public class ClientPlayerNetworkEvent {{public static class LoggingIn{{}}public static class LoggingOut{{}}}}')
    if forge:
        put('net.minecraftforge.fml.LogicalSide','package net.minecraftforge.fml;public enum LogicalSide {CLIENT,SERVER}')
        put(prefix+'.event.TickEvent',f'''package {prefix}.event;import net.minecraft.world.level.Level;import net.minecraft.world.entity.player.Player;import net.minecraftforge.fml.LogicalSide;
public class TickEvent {{public enum Phase{{START,END}}public final Phase phase;TickEvent(Phase p){{phase=p;}}public static class ServerTickEvent extends TickEvent {{public ServerTickEvent(Phase p){{super(p);}}}}public static class ClientTickEvent extends TickEvent {{public ClientTickEvent(Phase p){{super(p);}}}}
public static class LevelTickEvent extends TickEvent {{public final Level level;public final LogicalSide side;public LevelTickEvent(Level l,Phase p,LogicalSide s){{super(p);level=l;side=s;}}}}
public static class PlayerTickEvent extends TickEvent {{public final Player player;public PlayerTickEvent(Player p,Phase phase){{super(phase);player=p;}}}}}}
''')
    else:
        for name,arg,extra in [('ServerTickEvent','',''),('LevelTickEvent','net.minecraft.world.level.Level level','public net.minecraft.world.level.Level getLevel(){return level;}'),('PlayerTickEvent','net.minecraft.world.entity.player.Player player','public net.minecraft.world.entity.player.Player getEntity(){return player;}')]:
            init='this.'+arg.split()[-1]+'='+arg.split()[-1]+';' if arg else ''
            field='private final '+arg+';' if arg else ''
            superarg=arg.split()[-1] if arg else ''
            put(prefix+'.event.tick.'+name,f'package {prefix}.event.tick;public class {name} {{{field}private {name}({arg}){{{init}}}{extra}public static class Pre extends {name} {{public Pre({arg}){{super({superarg});}}}}public static class Post extends {name}{{public Post({arg}){{super({superarg});}}}}}}')
        put(prefix+'.client.event.ClientTickEvent',f'package {prefix}.client.event;public class ClientTickEvent {{public static class Pre{{}}public static class Post{{}}}}')
    put('net.minecraft.network.FriendlyByteBuf','package net.minecraft.network;public class FriendlyByteBuf {}')
    put('net.minecraft.network.RegistryFriendlyByteBuf','package net.minecraft.network;public class RegistryFriendlyByteBuf extends FriendlyByteBuf {}')
    put('net.minecraft.world.entity.player.Inventory','package net.minecraft.world.entity.player;public class Inventory {}')
    put('net.minecraft.world.inventory.AbstractContainerMenu','package net.minecraft.world.inventory;public class AbstractContainerMenu {}')
    put('net.minecraft.world.inventory.MenuType','package net.minecraft.world.inventory;public class MenuType<T extends AbstractContainerMenu> {public final buildcraft.lib.platform.registry.BCMenuFactory<T> factory;public MenuType(buildcraft.lib.platform.registry.BCMenuFactory<T> f){factory=f;}}')
    put('buildcraft.lib.gui.MenuBC_Neptune','package buildcraft.lib.gui;public class MenuBC_Neptune extends net.minecraft.world.inventory.AbstractContainerMenu {}')
    factorybuf = 'FriendlyByteBuf' if forge else 'RegistryFriendlyByteBuf'
    put(prefix+'.network.IContainerFactory',f'package {prefix}.network;public interface IContainerFactory<T extends net.minecraft.world.inventory.AbstractContainerMenu> {{T create(int id,net.minecraft.world.entity.player.Inventory inv,net.minecraft.network.{factorybuf} data);}}')
    menuhook = 'IForgeMenuType' if forge else 'IMenuTypeExtension'
    put(prefix+'.common.extensions.'+menuhook,f'package {prefix}.common.extensions;public interface {menuhook} {{static <T extends net.minecraft.world.inventory.AbstractContainerMenu> net.minecraft.world.inventory.MenuType<T> create({prefix}.network.IContainerFactory<T> factory){{return new net.minecraft.world.inventory.MenuType<>((id,inv,data)->factory.create(id,inv,(net.minecraft.network.{factorybuf})data));}}}}')
    # Actual code and the probe compile under all four target selections.
    actual = [PACKAGE+x+'.java' for x in (
        'storage/ItemStorage','storage/MutableItemStorage','storage/FilteredItemStorage','storage/EnergyStorage','storage/FluidStorage','storage/FilteredFluidStorage','storage/StorageMap','storage/StorageAdapters',
        'registry/BCMenuFactory','registry/PlatformMenus','registry/BCRegistryEntry','registry/BCDeferredRegister','registry/BCRegistryBinder','registry/RegistryNames','registry/RegistryBinding',
        'config/BCConfigSpec','config/ConfigBinding','events/BCEvents','events/PlatformEvents','events/PlatformClientEvents')]
    actual += ['buildcraft/lib/net/BCNetworkSide.java', 'buildcraft/lib/gui/BCContainerFactory.java']
    source=work/'src';classes=work/'classes'
    if source.exists():shutil.rmtree(source)
    if classes.exists():shutil.rmtree(classes)
    for rel in actual:
        dest=source/rel;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(java_root/rel,dest)
    for rel,text in stubs.items():
        dest=source/rel;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(text)
    probe=PROBE.replace('__NATIVE__',prefix).replace('__CONFIG__',cfgpkg).replace('__SPEC__',spec).replace('__BUS__','MinecraftForge' if forge else 'NeoForge').replace('__ID__',ident)
    event_code=FORGE_EVENTS if forge else NEO_EVENTS
    event_code=event_code.replace('__WATCH__','ChunkWatchEvent' if target=='1.19.2-forge' else 'ChunkWatchEvent.Watch')
    probe=probe.replace('__EVENTS__',event_code).replace('__TARGET__',target).replace('__NATIVE__',prefix).replace('__WATCH_CLASS__','Watch')
    (source/'probe/Probe.java').write_text(probe)
    classes.mkdir(parents=True,exist_ok=True)
    for command in [ ['javac','--release','17' if forge else '21','-encoding','UTF-8','-d',str(classes),*map(str,sorted(source.rglob('*.java')))], ['java','-ea','-cp',str(classes),'probe.Probe'] ]:
        proc=subprocess.run(command,capture_output=True,text=True,timeout=90)
        if proc.returncode:raise AssertionError(f'{target} {command[0]} failed:\n{proc.stdout}\n{proc.stderr}')
    return proc.stdout.strip()

FORGE_EVENTS='''
events.post(new __NATIVE__.event.TickEvent.ServerTickEvent(__NATIVE__.event.TickEvent.Phase.START));eq(ticks[0],0,"end callback does not run on start");
events.post(new __NATIVE__.event.TickEvent.ServerTickEvent(__NATIVE__.event.TickEvent.Phase.END));eq(ticks[0],1,"end callback once");
events.post(new __NATIVE__.event.TickEvent.LevelTickEvent(world,__NATIVE__.event.TickEvent.Phase.END,net.minecraftforge.fml.LogicalSide.SERVER));
events.post(new __NATIVE__.event.TickEvent.PlayerTickEvent(player,__NATIVE__.event.TickEvent.Phase.END));
events.post(new __NATIVE__.event.TickEvent.ClientTickEvent(__NATIVE__.event.TickEvent.Phase.END));
'''
NEO_EVENTS='''
events.post(new __NATIVE__.event.tick.ServerTickEvent.Pre());eq(ticks[0],0,"post callback does not run on pre");
events.post(new __NATIVE__.event.tick.ServerTickEvent.Post());eq(ticks[0],1,"post callback once");
events.post(new __NATIVE__.event.tick.LevelTickEvent.Post(world));
events.post(new __NATIVE__.event.tick.PlayerTickEvent.Post(player));
events.post(new __NATIVE__.client.event.ClientTickEvent.Post());
'''
PROBE=r'''package probe;
import java.util.*;import java.util.function.*;
import buildcraft.lib.platform.storage.*;import buildcraft.lib.platform.registry.*;import buildcraft.lib.platform.config.*;import buildcraft.lib.platform.events.*;
import buildcraft.lib.net.BCNetworkSide;import buildcraft.lib.internal.core.*;import net.minecraft.world.item.ItemStack;import net.minecraft.resources.__ID__;
import __NATIVE__.items.*;import __NATIVE__.energy.*;import __NATIVE__.fluids.*;import __NATIVE__.fluids.capability.*;import __NATIVE__.fluids.capability.IFluidHandler.FluidAction;
import __NATIVE__.registries.DeferredRegister;import __NATIVE__.event.level.*;
public final class Probe {
static int checks;static void ok(boolean value,String m){checks++;if(!value)throw new AssertionError(m);}static void eq(int a,int b,String m){ok(a==b,m+" "+a+" != "+b);}
static void denied(Runnable action,String m){try{action.run();throw new AssertionError(m);}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
static class Items implements IItemHandlerModifiable {ItemStack stack=ItemStack.EMPTY;boolean simulateSeen;
 public int getSlots(){return 2;}public ItemStack getStackInSlot(int s){return s==0?stack:ItemStack.EMPTY;}
 public int getSlotLimit(int s){return s==0?8:0;}public boolean isItemValid(int s,ItemStack i){return s==0&&i.id.equals("iron");}
 public ItemStack insertItem(int s,ItemStack v,boolean simulate){simulateSeen=simulate;if(!isItemValid(s,v))return v;int n=Math.min(v.getCount(),Math.max(0,8-stack.getCount()));if(!simulate)stack=v.copyWithCount(stack.getCount()+n);return v.copyWithCount(v.getCount()-n);}
 public ItemStack extractItem(int s,int n,boolean simulate){simulateSeen=simulate;if(s!=0||n<=0)return ItemStack.EMPTY;int take=Math.min(n,stack.getCount());ItemStack out=stack.copyWithCount(take);if(!simulate)stack=stack.copyWithCount(stack.getCount()-take);return out;}
 public void setStackInSlot(int s,ItemStack v){stack=v;}
}
static class Energy implements IEnergyStorage {int stored=4;public int receiveEnergy(int n,boolean sim){int v=Math.max(0,Math.min(10-stored,n));if(!sim)stored+=v;return v;}public int extractEnergy(int n,boolean sim){int v=Math.max(0,Math.min(n,stored));if(!sim)stored-=v;return v;}
public int getEnergyStored(){return stored;}public int getMaxEnergyStored(){return 10;}public boolean canReceive(){return true;}public boolean canExtract(){return false;}}
static class Fluids implements IFluidHandlerAdv {int amount=6;boolean simulateSeen;FluidStack last;int filtered;
public int getTanks(){return 1;}public FluidStack getFluidInTank(int t){return new FluidStack("oil",amount);}public int getTankCapacity(int t){return 10;}public boolean isFluidValid(int t,FluidStack f){return f.fluid.equals("oil");}
public int fill(FluidStack f,FluidAction a){last=f;simulateSeen=a.simulate();int n=isFluidValid(0,f)?Math.min(f.amount,10-amount):0;if(a.execute())amount+=n;return n;}
public FluidStack drain(FluidStack f,FluidAction a){last=f;return f.fluid.equals("oil")?drain(f.amount,a):FluidStack.EMPTY;}
public FluidStack drain(int n,FluidAction a){simulateSeen=a.simulate();int take=Math.max(0,Math.min(n,amount));if(a.execute())amount-=take;return new FluidStack("oil",take);}
public FluidStack drain(IFluidFilter f,int n,FluidAction a){filtered++;return f.matches(getFluidInTank(0))?drain(n,a):FluidStack.EMPTY;}}
enum Mode { OFF,ON }
@SuppressWarnings("unchecked")public static void main(String[]args){
 Items nativeItems=new Items();ItemStorage items=StorageAdapters.fromNativeItems(nativeItems);ItemStack iron=new ItemStack("iron",12);iron.components="custom=1";
 ok(items instanceof MutableItemStorage,"mutable export only for native mutable handler");ok(StorageAdapters.toNativeItems(items)==nativeItems,"item native identity roundtrip");eq(items.getSlots(),2,"slots");eq(items.getSlotLimit(1),0,"restricted slot limit");
 eq(items.insertItem(0,iron,true).getCount(),4,"simulated remainder");eq(nativeItems.stack.getCount(),0,"simulation immutable");eq(iron.getCount(),12,"input stack immutable");ok(nativeItems.simulateSeen,"simulate forwarded");
 eq(items.insertItem(0,ItemStack.EMPTY,false).getCount(),0,"empty item insert is safe");eq(items.getStackInSlot(0).getCount(),0,"empty item insert does not mutate");
 eq(items.insertItem(1,iron,false).getCount(),12,"restricted side-view slot never bypassed");eq(items.insertItem(0,iron,false).getCount(),4,"partial remainder");eq(items.getStackInSlot(0).getCount(),8,"actual content");ok(items.getStackInSlot(0).components.equals("custom=1"),"components preserved");
 eq(items.extractItem(0,0,false).getCount(),0,"empty item extract is safe");eq(items.getStackInSlot(0).getCount(),8,"empty item extract does not mutate");
 eq(items.extractItem(0,3,true).getCount(),3,"simulated extract");eq(items.getStackInSlot(0).getCount(),8,"simulated extract unchanged");eq(items.extractItem(0,Integer.MAX_VALUE,false).getCount(),8,"overflow-sized extraction is bounded");eq(items.getStackInSlot(0).getCount(),0,"extracted once");
 ((MutableItemStorage)items).setStackInSlot(0,iron);ok(nativeItems.stack==iron,"restoration retains setter identity");
 ItemStorage immutable=new ItemStorage(){public int getSlots(){return 0;}public ItemStack getStackInSlot(int i){return ItemStack.EMPTY;}public ItemStack insertItem(int i,ItemStack s,boolean b){return s;}public ItemStack extractItem(int i,int n,boolean b){return ItemStack.EMPTY;}public int getSlotLimit(int i){return 0;}public boolean isItemValid(int i,ItemStack s){return false;}};
 IItemHandler exported=StorageAdapters.toNativeItems(immutable);ok(!(exported instanceof IItemHandlerModifiable),"no fabricated direct mutation");ok(StorageAdapters.fromNativeItems(exported)==immutable,"item internal identity roundtrip");
 ok(StorageAdapters.fromNativeItems((IItemHandler)null)==null,"absent item capability remains absent");
 StorageMap storageMap=new StorageMap();storageMap.addItems(items,EnumPipePart.NORTH);ok(storageMap.items(net.minecraft.core.Direction.NORTH)==items,"sided item catalogue identity");ok(storageMap.items(net.minecraft.core.Direction.SOUTH)==null,"sided item catalogue isolation");
 Energy energyNative=new Energy();EnergyStorage energy=StorageAdapters.fromNativeEnergy(energyNative);ok(StorageAdapters.toNativeEnergy(energy)==energyNative,"energy roundtrip");eq(energy.receiveEnergy(0,false),0,"empty energy receive is safe");eq(energy.receiveEnergy(-1,false),0,"negative energy receive is safe");eq(energy.receiveEnergy(Integer.MAX_VALUE,true),6,"overflow-sized receive is capacity bounded");eq(energy.getEnergyStored(),4,"energy simulate");eq(energy.receiveEnergy(3,false),3,"energy actual");eq(energy.getEnergyStored(),7,"energy amount");ok(!energy.canExtract()&&energy.canReceive(),"native energy restrictions kept");eq(energy.extractEnergy(0,false),0,"empty energy extract is safe");eq(energy.extractEnergy(Integer.MAX_VALUE,true),7,"overflow-sized extract is bounded");eq(energyNative.stored,7,"energy extraction sim unchanged");
 storageMap.addEnergy(side->side==net.minecraft.core.Direction.UP?energy:null,EnumPipePart.UP,EnumPipePart.DOWN);ok(storageMap.energy(net.minecraft.core.Direction.UP)==energy,"dynamic sided energy view");ok(storageMap.energy(net.minecraft.core.Direction.DOWN)==null,"dynamic sided energy rejection");
 Fluids nativeFluids=new Fluids();FluidStorage<FluidStack> fluids=StorageAdapters.fromNativeFluids(nativeFluids);ok(StorageAdapters.toNativeFluids(fluids)==nativeFluids,"fluid native identity for rollback");ok(fluids instanceof FilteredFluidStorage,"filtered drain capability retained");
 storageMap.addFluids(fluids,EnumPipePart.CENTER);ok(storageMap.fluids(null)==fluids,"null side maps to center");
 FluidStack oil=new FluidStack("oil",9);oil.components="pressure=4";eq(fluids.fill(new FluidStack("oil",0),false),0,"empty fluid fill is safe");eq(nativeFluids.amount,6,"empty fluid fill does not mutate");eq(fluids.fill(oil,true),4,"fluid partial simulated fill");eq(nativeFluids.amount,6,"fluid simulation unchanged");ok(nativeFluids.last==oil,"fluid payload not flattened or copied");ok(nativeFluids.simulateSeen,"fluid action simulation mapped");eq(fluids.fill(oil,false),4,"fluid actual capacity");eq(nativeFluids.amount,10,"fluid fill once");eq(fluids.drain(0,false).amount,0,"empty fluid drain is safe");eq(nativeFluids.amount,10,"empty fluid drain does not mutate");eq(fluids.drain(3,true).amount,3,"fluid sim drain");eq(nativeFluids.amount,10,"fluid drain simulation unchanged");eq(fluids.drain(Integer.MAX_VALUE,true).amount,10,"overflow-sized fluid drain is bounded");eq(nativeFluids.amount,10,"overflow-sized simulated fluid drain does not mutate");
 eq(((FilteredFluidStorage<FluidStack>)fluids).drain(f->f.fluid.equals("oil"),4,false).amount,4,"filtered actual drain");eq(nativeFluids.filtered,1,"filtered fast path once");eq(nativeFluids.amount,6,"filtered amount");eq(fluids.drain(new FluidStack("water",10),false).amount,0,"wrong fluid rejected");eq(fluids.getTanks(),1,"fluid tank count");eq(fluids.getTankCapacity(0),10,"fluid capacity");
 // Lazy registration, order, identity, presence, late definitions and duplicate rejection.
 BCDeferredRegister<Number> catalog=BCDeferredRegister.create("minecraft:item","probe");int[] creations={0};
 BCRegistryEntry<Integer> first=catalog.register("first",()->{creations[0]++;return 42;});BCRegistryEntry<Integer> second=catalog.register("second",id->{ok(id.toString().equals("probe:second"),"factory ID");return first.get()+1;});
 eq(creations[0],0,"catalog does not run factories");ok(!first.isPresent(),"unbound entry not present");denied(first::get,"early get must fail");Bus registryBus=new Bus();catalog.register(RegistryBinding.on(registryBus));eq(creations[0],0,"binding does not run factories");ok(!first.isBound(),"native registration not yet fired");
 BCRegistryEntry<Integer> late=catalog.register("late",()->44);denied(()->catalog.register("first",()->0),"duplicate descriptor");denied(()->catalog.register(RegistryBinding.on(registryBus)),"duplicate bus bind");
 registryBus.registerAll();eq(first.get(),42,"first value");eq(second.get(),43,"dependent factory");eq(late.get(),44,"late-before-event factory");eq(creations[0],1,"factory exactly once");ok(first.isPresent()&&first.isBound(),"native binding visible");ok(DeferredRegister.FIRED.equals(List.of("probe:first","probe:second","probe:late")),"original definition order preserved");denied(()->catalog.register("too_late",()->45),"native freeze not bypassed");eq(catalog.entries().size(),3,"rejected late registration leaves no phantom descriptor");
 // Menu wrapper is compiled as real code against the native factory's buffer signature.
 var inventory=new net.minecraft.world.entity.player.Inventory();var extra=new net.minecraft.network.RegistryFriendlyByteBuf();final int[] menus={0};
 var menuType=buildcraft.lib.gui.BCContainerFactory.create((id,inv,data)->{eq(id,73,"menu window id");ok(inv==inventory&&data==extra,"menu inventory/buffer identity");menus[0]++;return new buildcraft.lib.gui.MenuBC_Neptune();});
 eq(menus[0],0,"menu factory not eagerly evaluated");ok(menuType.factory.create(73,inventory,extra)!=null,"native menu type invokes descriptor");eq(menus[0],1,"menu creation once");
 // Native config binds once; gameplay stores live value handles, not copied settings.
 BCConfigSpec.Builder builder=new BCConfigSpec.Builder();builder.push("general");String[] comments={"line1","line2"};builder.comment(comments);comments[0]="mutated";
 var flag=builder.define("flag",false);var count=builder.worldRestart().defineInRange("count",4,1,64);var ratio=builder.defineInRange("ratio",0.1D,0.0001D,0.2D);var mode=builder.defineEnum("mode",Mode.OFF,Mode.values());var string=builder.define("name","oil");builder.pop();BCConfigSpec schema=builder.build();
 denied(flag::get,"read before binding");denied(builder::build,"builder consumed once");var nativeSpec=ConfigBinding.bind(schema);ok(ConfigBinding.bind(schema)==nativeSpec,"same registered config instance");ok(!flag.get(),"bool default");eq(count.get(),4,"count default");ok(ratio.get()==0.1D&&mode.get()==Mode.OFF&&string.get().equals("oil"),"typed values");
 ok(nativeSpec.steps.get(1).equals("comment:line1|line2"),"schema snapshots comments");ok(nativeSpec.values.get("general.count").restart,"worldRestart metadata");ok(nativeSpec.values.get("general.count").min.equals(1)&&nativeSpec.values.get("general.count").max.equals(64),"integer bounds");ok(nativeSpec.values.get("general.ratio").min.equals(0.0001D),"double bounds");
 var countNative=(__NATIVE__.common.__SPEC__.Value<Integer>)nativeSpec.values.get("general.count");countNative.set(12);eq(count.get(),12,"reload read through live native value");denied(()->countNative.set(999),"invalid range rejected by native binding");eq(count.get(),12,"invalid value does not replace last valid config");
 Bus configBus=new Bus();List<String> loads=new ArrayList<>();ConfigBinding.listen(configBus,id->loads.add("load:"+id),id->loads.add("reload:"+id));configBus.post(new __CONFIG__.ModConfigEvent.Loading("buildcraftcore"));configBus.post(new __CONFIG__.ModConfigEvent.Reloading("buildcraftenergy"));ok(loads.equals(List.of("load:buildcraftcore","reload:buildcraftenergy")),"config event types and mod IDs");
 // Event normalization: phases, payload identity, client isolation, and exactly one dispatch.
 Bus events=__NATIVE__.common.__BUS__.EVENT_BUS;net.minecraft.server.level.ServerLevel world=new net.minecraft.server.level.ServerLevel();net.minecraft.server.level.ServerPlayer player=new net.minecraft.server.level.ServerPlayer(world);
 int[] ticks=new int[5];PlatformEvents.serverTick(BCEvents.Phase.END,e->{ok(e.phase()==BCEvents.Phase.END,"server phase");ticks[0]++;});
 PlatformEvents.levelTick(BCEvents.Phase.END,e->{ok(e.level()==world&&e.side()==BCNetworkSide.SERVER,"world and side identity");ticks[1]++;});PlatformEvents.playerTick(BCEvents.Phase.END,e->{ok(e.player()==player,"player identity");ticks[2]++;});
 PlatformClientEvents.tick(BCEvents.Phase.END,e->ticks[3]++);PlatformEvents.chunkWatch(e->{ok(e.getPlayer()==player&&e.getLevel()==world,"chunk watch identity");ticks[4]++;});
 __EVENTS__
 eq(ticks[1],1,"level callback once");eq(ticks[2],1,"player callback once");eq(ticks[3],1,"client callback once");events.post(new ChunkWatchEvent.__WATCH_CLASS__(player,world));eq(ticks[4],1,"chunk callback once");
 final int[] life={0,0,0,0,0};PlatformEvents.entityJoin(e->{ok(e.getEntity()==player,"entity payload");life[0]++;});PlatformEvents.levelUnload(e->{ok(e.getLevel()==world,"world unload payload");life[1]++;});PlatformEvents.chunkUnload(e->{ok(e.pos().x()==-2&&e.pos().z()==5,"negative chunk payload");life[2]++;});PlatformClientEvents.login(()->life[3]++);PlatformClientEvents.logout(()->life[4]++);
 events.post(new __NATIVE__.event.entity.EntityJoinLevelEvent(player));events.post(new LevelEvent.Unload(world));events.post(new ChunkEvent.Unload(world,new ChunkEvent.Chunk(new net.minecraft.world.level.ChunkPos(-2,5))));events.post(new __NATIVE__.client.event.ClientPlayerNetworkEvent.LoggingIn());events.post(new __NATIVE__.client.event.ClientPlayerNetworkEvent.LoggingOut());
 for(int value:life)eq(value,1,"lifecycle callback exactly once");
 System.out.println("__TARGET__: "+checks+" internal-platform assertions (native API doubles)");
}}
'''
