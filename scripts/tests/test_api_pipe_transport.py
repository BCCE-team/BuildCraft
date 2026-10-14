#!/usr/bin/env python3
"""Custom pipe flows, behaviours and item transit ownership."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import importlib.util
import shutil
import subprocess
import tempfile
import unittest
import pathlib

FLOW_SRC = ROOT / "source-shared/src/main/java"
FLOW_ORIGINAL = Path(__file__).with_name("test_pipe_component_runtime.py")
flow_spec = importlib.util.spec_from_file_location("pipe_component_fixture", FLOW_ORIGINAL)
flow_base = importlib.util.module_from_spec(flow_spec)
flow_spec.loader.exec_module(flow_base)

FLOW_API = [p for p in flow_base.SOURCES if '/lib/' not in p and '/transport/api2/' not in p]
FLOW_API += ['source-shared/src/main/java/buildcraft/api/v2/pipe/' + n + '.java' for n in (
    'PipeRuntimeComponent', 'PipeFlowComponent', 'PipeBehaviourComponent',
    'PipeFlowDefinition', 'PipeBehaviourDefinition', 'PipeType', 'PipeMedium',
    'ItemTransportProfile', 'FluidTransportProfile', 'PowerTransportProfile',
    'ExternalEnergyTransportProfile', 'PipeActivationResult',
)]
FLOW_API += ['source-shared/src/main/java/buildcraft/api/v2/' + n + '.java' for n in (
    'OperationMode', 'item/ItemTransferResult', 'item/ItemTransferPolicy',
    'item/ItemMatcher', 'item/ItemPort', 'pipe/ItemInjectionRequest',
    'pipe/ItemTransitData', 'pipe/ItemPipePort',
)]
FLOW_BACKEND = [p for p in flow_base.SOURCES if '/lib/' in p or '/transport/api2/' in p]
FLOW_BACKEND += ['source-shared/src/main/java/buildcraft/transport/api2/' + n + '.java'
            for n in ('PipeFlowBridge', 'ApiPipeFlow')]
FLOW_STUBS = dict(flow_base.STUBS)
for name in (
    'buildcraft/api/v2/pipe/PipeType.java',
    'buildcraft/api/v2/pipe/PipeRuntimeComponent.java',
    'buildcraft/api/v2/pipe/PipeFlowDefinition.java',
    'buildcraft/api/v2/pipe/PipeBehaviourDefinition.java',
):
    FLOW_STUBS.pop(name, None)
FLOW_STUBS.pop('buildcraft/api/v2/pipe/ItemPipePort.java', None)
FLOW_STUBS['net/minecraft/world/item/ItemStack.java'] = '''package net.minecraft.world.item;
public class ItemStack {
 public static final ItemStack EMPTY = new ItemStack("", 0);
 public final String id; private int count;
 public ItemStack(String id,int count){this.id=id;this.count=count;}
 public boolean isEmpty(){return count<=0;}
 public ItemStack copy(){return new ItemStack(id,count);}
 public int getCount(){return count;}
 public void setCount(int n){count=n;}
 public void shrink(int n){count=Math.max(0,count-n);}
}'''
FLOW_STUBS['net/minecraft/world/item/DyeColor.java'] = 'package net.minecraft.world.item; public enum DyeColor { RED, BLUE }'
FLOW_STUBS['net/minecraft/core/Direction.java'] = 'package net.minecraft.core; public enum Direction { NORTH,SOUTH,EAST,WEST,UP,DOWN }'
FLOW_STUBS['net/minecraft/world/entity/Entity.java'] = 'package net.minecraft.world.entity; public class Entity { }'
FLOW_STUBS['net/minecraft/world/level/block/entity/BlockEntity.java'] = 'package net.minecraft.world.level.block.entity; public class BlockEntity {}'
FLOW_STUBS['buildcraft/api/v2/energy/MjAmount.java'] = '''package buildcraft.api.v2.energy;
public record MjAmount(long microMj) implements Comparable<MjAmount> {
  public static final long MICRO_MJ_PER_MJ=1000000L;
  public static MjAmount ofMicro(long n){return new MjAmount(n);}
  public boolean isZero(){return microMj==0;}
  public int compareTo(MjAmount o){return Long.compare(microMj,o.microMj);}
}'''
FLOW_STUBS['buildcraft/api/v2/fluid/FluidAmount.java'] = '''package buildcraft.api.v2.fluid;
public record FluidAmount(long milliBuckets) {
 public static FluidAmount of(long n){return new FluidAmount(n);}
}'''

FLOW_STUBS['buildcraft/api/v2/fluid/FluidPort.java'] = 'package buildcraft.api.v2.fluid; public interface FluidPort {}'
FLOW_STUBS['buildcraft/api/v2/energy/MjPort.java'] = 'package buildcraft.api.v2.energy; public interface MjPort {}'
FLOW_STUBS['buildcraft/api/v2/platform/ExternalEnergyPort.java'] = 'package buildcraft.api.v2.platform; public interface ExternalEnergyPort {}'
FLOW_STUBS['buildcraft/api/v2/pipe/PipeConnectionContext.java'] = 'package buildcraft.api.v2.pipe; public record PipeConnectionContext() {}'
FLOW_STUBS['buildcraft/api/v2/pipe/PipeConnectionComponent.java'] = '''package buildcraft.api.v2.pipe;
public interface PipeConnectionComponent extends PipeComponent {
  PipeConnectionDecision connection(PipeConnectionContext context);
}'''
FLOW_STUBS['buildcraft/api/v2/pipe/PipeConnectionDecision.java'] = '''package buildcraft.api.v2.pipe;
public enum PipeConnectionDecision { PASS,ALLOW,DENY }'''
FLOW_STUBS['buildcraft/api/v2/pipe/PipeActivationComponent.java'] = '''package buildcraft.api.v2.pipe;
public interface PipeActivationComponent extends PipeComponent {
  PipeActivationResult activate(PipeActivationContext context);
}'''
FLOW_STUBS['buildcraft/api/v2/pipe/PipeActivationContext.java'] = 'package buildcraft.api.v2.pipe; public record PipeActivationContext() {}'
FLOW_STUBS['buildcraft/api/v2/BuildCraftRegistries.java'] = '''package buildcraft.api.v2;
import buildcraft.api.v2.pipe.*;
public class BuildCraftRegistries {
 public static final Key<PipeComponentType<?>> PIPE_COMPONENT_TYPES=new Key<>();
 public static final Key<PipeFlowDefinition<?>> PIPE_FLOW_TYPES=new Key<>();
 public static final Key<PipeBehaviourDefinition<?>> PIPE_BEHAVIOUR_TYPES=new Key<>();
 public static final Key<PipeSyncChannel<?>> PIPE_SYNC_CHANNELS=new Key<>();
 public static final class Key<T> { }
}'''
FLOW_STUBS['buildcraft/api/v2/BuildCraftApi.java'] = '''package buildcraft.api.v2;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
public class BuildCraftApi {
 private static final Map<BuildCraftRegistries.Key<?>,Registry<?>> REG=new HashMap<>();
 @SuppressWarnings("unchecked") public static <T> Registry<T> registry(BuildCraftRegistries.Key<T> key){
   return (Registry<T>)REG.computeIfAbsent(key,k->new Registry<>());
 }
 public static class Registry<T> {
   private final Map<ResourceLocation,T> entries=new LinkedHashMap<>();
   public T get(ResourceLocation id){return entries.get(id);}
   public Collection<T> values(){return entries.values();}
   public void put(ResourceLocation id,T value){if(entries.putIfAbsent(id,value)!=null)throw new IllegalStateException();}
 }
}'''
FLOW_STUBS['buildcraft/transport/internal/pipe/PipeFlowType.java'] = '''package buildcraft.transport.internal.pipe;
import net.minecraft.nbt.CompoundTag;
public final class PipeFlowType {
 public interface Creator { PipeFlow create(IPipe pipe); }
 public interface Loader { PipeFlow load(IPipe pipe, CompoundTag nbt); }
 public final Creator creator; public final Loader loader;
 public PipeFlowType(Creator creator, Loader loader){this.creator=creator;this.loader=loader;}
}'''
FLOW_STUBS['buildcraft/transport/internal/pipe/PipeFlow.java'] = '''package buildcraft.transport.internal.pipe;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
public abstract class PipeFlow {
 public final IPipe pipe;
 protected PipeFlow(IPipe pipe){this.pipe=pipe;}
 public abstract boolean canConnect(Direction side,PipeFlow other);
 public abstract boolean canConnect(Direction side,BlockEntity other);
}'''
FLOW_STUBS['buildcraft/transport/internal/pipe/IPipe.java'] = '''package buildcraft.transport.internal.pipe;
public interface IPipe { PipeDefinition getDefinition(); }'''
FLOW_STUBS['buildcraft/transport/internal/pipe/PipeDefinition.java'] = '''package buildcraft.transport.internal.pipe;
import buildcraft.api.v2.pipe.PipeType;
public class PipeDefinition {
 private final PipeType type;
 public PipeDefinition(PipeType t){type=t;}
 public PipeType getApiType(){return type;}
}'''
FLOW_STUBS['net/minecraft/nbt/CompoundTag.java'] = 'package net.minecraft.nbt; public class CompoundTag {}'
FLOW_STUBS['buildcraft/transport/pipe/Pipe.java'] = '''package buildcraft.transport.pipe;
import java.util.Optional;
import buildcraft.api.v2.pipe.*;
import buildcraft.transport.internal.pipe.*;
import net.minecraft.core.Direction;
public class Pipe implements IPipe {
 private final PipeDefinition definition; private ItemPipePort port;
 private boolean active = true;
 public Pipe(PipeType type,ItemPipePort port){definition=new PipeDefinition(type);this.port=port;}
 public PipeDefinition getDefinition(){return definition;}
 public boolean isServerSide(){return true;}
 public boolean isActive(){return active;}
 public void setActive(boolean value){active=value;}
 public Optional<ItemPipePort> itemPipePort(Direction from){return active?Optional.ofNullable(port):Optional.empty();}
}'''
FLOW_STUBS['buildcraft/transport/internal/pipe/IFlowItems.java'] = '''package buildcraft.transport.internal.pipe;
import buildcraft.lib.internal.core.IStackFilter;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
public interface IFlowItems {
 boolean canInjectItems(Direction from);
 ItemStack injectItem(ItemStack stack,boolean doAdd,Direction from,DyeColor color,double speed);
 int tryExtractItems(int count,Direction from,DyeColor colour,IStackFilter filter,FluidAction action);
 void insertItemsForce(ItemStack stack,Direction from,DyeColor colour,double speed);
 void sendPhantomItem(ItemStack stack,Direction from,Direction to,DyeColor colour);
}'''
FLOW_STUBS['buildcraft/lib/internal/core/IStackFilter.java'] = 'package buildcraft.lib.internal.core; public interface IStackFilter { }'
FLOW_STUBS['buildcraft/lib/misc/StackUtil.java'] = '''package buildcraft.lib.misc;
import net.minecraft.world.item.ItemStack;
public class StackUtil {
 public static boolean canMerge(ItemStack left,ItemStack right){return left.id.equals(right.id);}
}'''
FLOW_STUBS['net/minecraft/world/level/block/entity/BlockEntity.java'] = 'package net.minecraft.world.level.block.entity; public class BlockEntity {}'
FLOW_STUBS['net/minecraft/world/item/ItemStack.java'] = FLOW_STUBS['net/minecraft/world/item/ItemStack.java']
for name in ('forge','neoforge'):
    domain = 'net.minecraftforge' if name=='forge' else 'net.neoforged.neoforge'
    FLOW_STUBS[domain.replace('.', '/')+'/fluids/capability/IFluidHandler.java'] = (
        'package '+domain+'.fluids.capability; public interface IFluidHandler { enum FluidAction {SIMULATE,EXECUTE} }')


FLOW_ADDON_TEMPLATE = r'''package addon;
import buildcraft.api.v2.persistence.*;
import buildcraft.api.v2.pipe.*;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
public final class CLASSNAME {
 public static final ResourceLocation COMPONENT=ResourceLocation.tryParse("addon:COMPID");
 public static final ResourceLocation FLOW=ResourceLocation.tryParse("addon:FLOWID");
 public static final ResourceLocation FORMAT=ResourceLocation.tryParse("addon:binary_counter");
 public static final ResourceLocation CHANNEL=ResourceLocation.tryParse("addon:CHANNELID");
 public static final ApiCodec<Integer,OpaqueData> INT=new ApiCodec<>() {
   public CodecResult<OpaqueData> encode(Integer value){return CodecResult.success(new OpaqueData(FORMAT,new byte[]{(byte)value.intValue()}));}
   public CodecResult<Integer> decode(OpaqueData data){return data.size()==1 && FORMAT.equals(data.format())
     ? CodecResult.success((int)data.bytes()[0]&255): CodecResult.failure("Invalid flow payload");}
 };
 public static final class Flow implements PipeFlowComponent {
   public int ticks; public int after;
   public Flow(int ticks){this.ticks=ticks;}
   public ResourceLocation typeId(){return COMPONENT;}
   public void tick(PipeMutationContext context){ticks++;context.markChanged();}
   public void postAttachmentTick(PipeMutationContext context){after++;}
 }
 public static final ApiCodec<Flow,OpaqueData> CODEC=new ApiCodec<>() {
   public CodecResult<OpaqueData> encode(Flow f){return INT.encode(f.ticks);}
   public CodecResult<Flow> decode(OpaqueData saved){return INT.decode(saved).map(Flow::new);}
 };
 public static final PipeSyncChannel<Integer> SYNC=new PipeSyncChannel<>(CHANNEL,INT,32);
 public static final PipeComponentType<Flow> COMPONENT_TYPE=new PipeComponentType<>(COMPONENT,
   pipe->new Flow(0),PersistentType.<Flow,OpaqueData>builder(COMPONENT,1,CODEC).build(),
   List.of(new PipeComponentSync<>(SYNC,f->f.ticks,(f,n)->f.ticks=n,PipeComponentSync.Policy.WHEN_CHANGED)));
 public static final PipeFlowDefinition<Flow> DEFINITION=new PipeFlowDefinition<>(FLOW,COMPONENT_TYPE);
 public static PipeType pipe(){return PIPEBUILDER;}
}'''
FLOW_BEHAVIOUR = r'''package addon;
import buildcraft.api.v2.persistence.*;
import buildcraft.api.v2.pipe.*;
import net.minecraft.resources.ResourceLocation;
public final class BehaviourAddon {
 public static final ResourceLocation ID=ResourceLocation.tryParse("addon:behaviour_state");
 public static final ResourceLocation TYPE=ResourceLocation.tryParse("addon:behaviour");
 public static final class Behaviour implements PipeBehaviourComponent {
   public int ticks;
   public Behaviour(int n){ticks=n;}
   public ResourceLocation typeId(){return ID;}
   public void tick(PipeMutationContext ctx){ticks++;}
 }
 public static final ApiCodec<Behaviour,OpaqueData> CODEC=new ApiCodec<>(){
   public CodecResult<OpaqueData> encode(Behaviour b){return CodecResult.success(
      new OpaqueData(TYPE,new byte[]{(byte)b.ticks}));}
   public CodecResult<Behaviour> decode(OpaqueData d){return CodecResult.success(new Behaviour(d.bytes()[0]&255));}
 };
 public static final PipeComponentType<Behaviour> COMPONENT=new PipeComponentType<>(ID,
    p->new Behaviour(0),PersistentType.<Behaviour,OpaqueData>builder(ID,1,CODEC).build());
 public static final PipeBehaviourDefinition<Behaviour> DEFINITION=new PipeBehaviourDefinition<>(TYPE,COMPONENT);
}'''
FLOW_HOST = r'''package host;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import buildcraft.api.v2.*;
import buildcraft.api.v2.pipe.*;
import buildcraft.transport.api2.*;
import buildcraft.transport.internal.pipe.*;
import addon.ItemAddon;
import addon.SignalAddon;
import addon.BehaviourAddon;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import buildcraft.api.v2.item.*;
public class Main {
 static void ok(boolean v,String description){if(!v)throw new AssertionError(description);}
 static final class Context implements PipeMutationContext {
   int changed;
   public String position(){return "test";}
   public void markChanged(){changed++;}
   public void requestSync(ResourceLocation id){}
 }
 static final class TargetPort implements ItemPipePort {
   int executes;
   public ItemTransferResult insert(ItemStack stack,OperationMode mode){
     return inject(new ItemInjectionRequest(stack,net.minecraft.core.Direction.NORTH,ItemTransitData.DEFAULT),mode);
   }
   public ItemTransferResult extract(ItemMatcher filter,int count,OperationMode mode){
     return ItemTransferResult.nothing(count);
   }
   public ItemTransferResult inject(ItemInjectionRequest request,OperationMode mode){
     if (mode==OperationMode.EXECUTE) executes++;
     return ItemTransferResult.ofInsertion(request.stack(),Math.min(request.stack().getCount(),3));
   }
 }
 static void register(PipeFlowDefinition<?> d){
   BuildCraftApi.registry(BuildCraftRegistries.PIPE_FLOW_TYPES).put(d.id(),d);
   BuildCraftApi.registry(BuildCraftRegistries.PIPE_COMPONENT_TYPES).put(d.componentType().id(),d.componentType());
 }
 static PipeComponentRuntime runtime(PipeType type,Context ctx,byte[] bytes){return new PipeComponentRuntime(ctx,type,false,bytes);}
 public static void main(String[] ignored)throws Exception {
   register(ItemAddon.DEFINITION);register(SignalAddon.DEFINITION);
   BuildCraftApi.registry(BuildCraftRegistries.PIPE_BEHAVIOUR_TYPES).put(BehaviourAddon.DEFINITION.id(),BehaviourAddon.DEFINITION);
   BuildCraftApi.registry(BuildCraftRegistries.PIPE_COMPONENT_TYPES).put(BehaviourAddon.COMPONENT.id(),BehaviourAddon.COMPONENT);
   BuildCraftApi.registry(BuildCraftRegistries.PIPE_SYNC_CHANNELS).put(ItemAddon.SYNC.id(),ItemAddon.SYNC);
   BuildCraftApi.registry(BuildCraftRegistries.PIPE_SYNC_CHANNELS).put(SignalAddon.SYNC.id(),SignalAddon.SYNC);
   PipeType item=ItemAddon.pipe();PipeType signal=SignalAddon.pipe();
   ok(item.media().contains(PipeMedium.ITEM),"item medium");
   ok(signal.media().isEmpty() && signal.customMedia().size()==1,"custom medium without enum");
   ok(signal.behaviourTypeId().orElseThrow().equals(BehaviourAddon.TYPE),"custom behaviour");
   Context context=new Context();PipeComponentRuntime started=runtime(item,context,null);
   started.onLoad();started.tick();started.tick();started.postAttachmentTick();
   ItemAddon.Flow startedFlow=(ItemAddon.Flow)started.components().get(0);
   ok(startedFlow.ticks==2 && startedFlow.after==1 && context.changed==2,"live flow tick/post attachment");
   byte[] saved=started.save();
   PipeComponentRuntime restored=runtime(item,new Context(),saved);restored.onLoad();
   ItemAddon.Flow restoredFlow=(ItemAddon.Flow)restored.components().get(0);
   ok(restoredFlow.ticks==2,"flow factory/load through persistence");
   restored.tick();ok(restoredFlow.ticks==3,"flow resumed");
   FriendlyByteBuf frame=new FriendlyByteBuf(); restored.writeFullSync(frame);
   PipeComponentRuntime client=new PipeComponentRuntime(new Context(),item,true,null);
   client.readSync(new FriendlyByteBuf(frame.toByteArray()));
   ItemAddon.Flow copy=(ItemAddon.Flow)client.components().get(0);
   ok(copy.ticks==3,"client snapshot sync");
   client.tick();ok(copy.ticks==3,"custom flow never ticks on client");
   Context signalContext=new Context();PipeComponentRuntime signalHost=runtime(signal,signalContext,null);
   signalHost.onLoad();signalHost.tick();
   ok(signalHost.components().size()==2,"custom flow and behaviour created");
   ok(((SignalAddon.Flow)signalHost.components().get(0)).ticks==1,"nonstandard flow live");
   ok(((BehaviourAddon.Behaviour)signalHost.components().get(1)).ticks==1,"independent behaviour live");
   PipeComponentRuntime reloaded=runtime(signal,new Context(),signalHost.save());reloaded.onLoad();
   ok(((SignalAddon.Flow)reloaded.components().get(0)).ticks==1,"custom medium restored");
   ok(((BehaviourAddon.Behaviour)reloaded.components().get(1)).ticks==1,"custom behaviour restored");
   PipeFlowType builtin=new PipeFlowType(p->null,(p,n)->null);
   PipeFlowType mapped=PipeFlowBridge.resolve(item,builtin);
   PipeFlowType mappedAgain=PipeFlowBridge.resolve(item,builtin);
   ok(mapped==mappedAgain && mapped!=builtin,"custom materialization stable");
   IPipe pipe=()->new PipeDefinition(item);
   IPipe other=()->new PipeDefinition(item);
   PipeFlow a=mapped.creator.create(pipe),b=mapped.loader.load(other,new net.minecraft.nbt.CompoundTag());
   ok(a instanceof ApiPipeFlow && a.canConnect(net.minecraft.core.Direction.NORTH,b),"custom flow bridge");
   ok(!a.canConnect(net.minecraft.core.Direction.NORTH,new net.minecraft.world.level.block.entity.BlockEntity()),
      "custom pipe must not connect to arbitrary block entities");
   TargetPort port=new TargetPort();
   buildcraft.transport.pipe.Pipe real=new buildcraft.transport.pipe.Pipe(item,port);
   IFlowItems compatible=(IFlowItems)mapped.creator.create(real);
   ok(compatible.canInjectItems(net.minecraft.core.Direction.NORTH),"legacy item injection detected");
   ItemStack offered=new ItemStack("iron",5);
   ItemStack simulated=compatible.injectItem(offered,false,net.minecraft.core.Direction.NORTH,null,.5);
   ok(simulated.getCount()==2 && port.executes==0 && offered.getCount()==5,"simulation does not mutate");
   ItemStack executed=compatible.injectItem(offered,true,net.minecraft.core.Direction.NORTH,null,.5);
   ok(executed.getCount()==2 && port.executes==1 && offered.getCount()==5,"legacy handoff to public port");
   real.setActive(false);
   ok(!compatible.canInjectItems(net.minecraft.core.Direction.NORTH) &&
     compatible.injectItem(offered,true,net.minecraft.core.Direction.NORTH,null,.5).getCount()==5,
     "unloaded pipe fails closed");
   System.out.println("PASS: two independent addon JARs, custom media, behaviour, persist, sync, adapter");
 }
}'''


def flow_write(root: Path, name: str, contents: str) -> Path:
    p = root / name
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(contents, encoding="utf-8")
    return p


def flow_compile_java(output: Path, inputs: list[Path], cp: tuple[Path, ...] = ()) -> None:
    output.mkdir(parents=True, exist_ok=True)
    cmd = ["javac", "--release", "17", "-encoding", "UTF-8", "-d", str(output)]
    if cp:
        cmd += ["-cp", __import__("os").pathsep.join(map(str, cp))]
    cmd += [str(s) for s in inputs]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=100)
    if result.returncode != 0:
        raise AssertionError(result.stdout + "\n" + result.stderr[-18000:])


def flow_jar_classes(output: Path, classes: Path) -> None:
    subprocess.run(["jar", "cf", str(output), "-C", str(classes), "."], check=True,
                   capture_output=True, timeout=25)


class PipeFlowConformanceTest(unittest.TestCase):
    def test_native_263_item_flow_abi(self):
        """The 26.3 IFlowItems ABI uses its own FluidAction, not the NeoForge API."""
        import sys
        sys.path.insert(0, str(ROOT / "scripts"))
        from source_layout import load_properties, target_layout, resolve_effective_source, _materialize_text_file

        properties = load_properties()
        rel = "src/main/java/buildcraft/transport/api2/ApiItemPipeFlow.java"
        for target in ("1.21.11-neoforge", "26.2-neoforge", "26.3-neoforge"):
            layout = target_layout(target, properties)
            source = resolve_effective_source(layout, properties, rel)
            self.assertIsNotNone(source)
            with tempfile.TemporaryDirectory(prefix="bc-pipes-abi-") as tmp:
                output = Path(tmp) / "ApiItemPipeFlow.java"
                _materialize_text_file(source, output, logical_relative=rel,
                    minecraft=properties[f"target.{target}.deps.minecraft"],
                    family=layout.family, platform=layout.platform, preprocess=True)
                code = output.read_text(encoding="utf-8")
                if target == "26.3-neoforge":
                    self.assertIn("import buildcraft.transport.internal.pipe.FluidAction;", code)
                    self.assertIn("net.minecraft.resources.Identifier flowId", code)
                    self.assertNotIn("net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction", code)
                else:
                    self.assertIn("import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;", code)

    def test_independent_addon_flows(self):
        self.assertTrue(shutil.which("javac") and shutil.which("jar") and shutil.which("java"))
        for platform in ('forge', 'neoforge'):
            with self.subTest(platform=platform):
                self._check_platform(platform)

    def _check_platform(self, platform: str):
        with tempfile.TemporaryDirectory(prefix="bc-pipes-addon-") as tmp:
            directory = Path(tmp)
            public_stubs = []
            internal_stubs = []
            for name, content in FLOW_STUBS.items():
                if name == 'buildcraft/transport/internal/pipe/IFlowItems.java' and platform == 'neoforge':
                    content = content.replace('net.minecraftforge.fluids', 'net.neoforged.neoforge.fluids')
                path = flow_write(directory / 'stubs', name, content)
                if name.startswith('buildcraft/transport/') or name.startswith('buildcraft/lib/'):
                    internal_stubs.append(path)
                else:
                    public_stubs.append(path)
            api_jar = directory / 'api.jar'
            flow_compile_java(directory/'api-classes', [ROOT / x for x in FLOW_API] + public_stubs)
            flow_jar_classes(api_jar, directory/'api-classes')

            extra = flow_write(directory/'behaviour-src','addon/BehaviourAddon.java', FLOW_BEHAVIOUR)
            flow_compile_java(directory/'behaviour-classes',[extra],(api_jar,))
            behaviour_jar = directory/'behaviour.jar'
            flow_jar_classes(behaviour_jar,directory/'behaviour-classes')
            addon_jars = [behaviour_jar]
            for cls, component, flow, channel, build in (
                ('ItemAddon', 'item_flow_state', 'item_flow', 'item_channel',
                 'PipeType.builder(ResourceLocation.tryParse("addon:custom_item")).medium(PipeMedium.ITEM).flow(FLOW).build()'),
                ('SignalAddon', 'signal_flow_state', 'signal_flow', 'signal_channel',
                 'PipeType.builder(ResourceLocation.tryParse("addon:custom_signal"))'
                 '.customMedium(ResourceLocation.tryParse("addon:packets")).flow(FLOW)'
                 '.behaviour(BehaviourAddon.TYPE).build()'),
            ):
                addon = FLOW_ADDON_TEMPLATE.replace('CLASSNAME', cls).replace('COMPID',component).replace('FLOWID',flow)
                addon = addon.replace('CHANNELID',channel).replace('PIPEBUILDER',build)
                out = directory/f'{cls}-classes'
                file = flow_write(directory/f'{cls}-src', f'addon/{cls}.java', addon)
                cp = (api_jar, *addon_jars)  # no backend classes
                flow_compile_java(out, [file], cp)
                dest = directory/f'{cls}.jar'
                flow_jar_classes(dest,out)
                addon_jars.append(dest)
            host = flow_write(directory/'host-src', 'host/Main.java', FLOW_HOST)
            adapter = ROOT / f'source-platforms/{platform}/src/main/java/buildcraft/transport/api2/ApiItemPipeFlow.java'
            flow_compile_java(directory/'host-classes', [ROOT/x for x in FLOW_BACKEND] + [adapter] + internal_stubs + [host],
                         (api_jar,*addon_jars))
            cp = __import__('os').pathsep.join(map(str, (api_jar,*addon_jars,directory/'host-classes')))
            result = subprocess.run(['java','-cp',cp,'host.Main'],capture_output=True,text=True,timeout=40)
            self.assertEqual(result.returncode,0,result.stdout+'\n'+result.stderr[-8000:])
            self.assertIn('PASS: two independent addon JARs',result.stdout)


TRANSIT_FLOW = pathlib.Path('src/main/java/buildcraft/transport/pipe/flow/PipeFlowItems.java')
TRANSIT_FLOW_VARIANTS = [
    ROOT / 'source-platforms/forge' / TRANSIT_FLOW,
    ROOT / 'source-platforms/neoforge' / TRANSIT_FLOW,
    ROOT / 'source-family-platforms/1.21.X/neoforge' / TRANSIT_FLOW,
    ROOT / 'source-family-platforms/26.X/neoforge' / TRANSIT_FLOW,
    ROOT / 'source-downports/26.X/26.1.2/neoforge' / TRANSIT_FLOW,
    ROOT / 'source-downports/26.X/26.2/neoforge' / TRANSIT_FLOW,
]


class PipeTransitOwnershipTest(unittest.TestCase):
    def test_native_flow_variants(self):
        phases = ('SIDE_CHECK', 'SPLIT', 'FIND_DEST', 'MODIFY_SPEED')
        for path in TRANSIT_FLOW_VARIANTS:
            with self.subTest(path=path):
                source = path.read_text(encoding='utf-8')
                for phase in phases:
                    self.assertIn('fireTransit(ItemTransitPhase.' + phase, source)
                self.assertIn('PipeTransitHandoff.begin(item.apiTransitId, item.apiTransitPayload)', source)
                self.assertIn('PipeTransitHandoff.claim()', source)
                self.assertIn('UUID.randomUUID()', source)
                self.assertNotIn('findDest.items.size() == 1 ? item.apiTransitId : null', source)

    def test_nested_transfer_ownership(self):
        java = ROOT / 'source-shared/src/main/java/buildcraft/transport/api2/PipeTransitHandoff.java'
        with tempfile.TemporaryDirectory() as tmp:
            test = pathlib.Path(tmp) / 'Probe.java'
            test.write_text('''import java.util.UUID;
import buildcraft.transport.api2.PipeTransitHandoff;
import buildcraft.api.v2.pipe.ItemTransitPayload;
import java.util.Map;
public class Probe {
  public static void main(String[] args) {
    UUID first=UUID.randomUUID(), second=UUID.randomUUID();
    if (PipeTransitHandoff.take()!=null) throw new AssertionError("unscoped");
    try (var a=PipeTransitHandoff.begin(first)) {
      try (var b=PipeTransitHandoff.begin(second)) {
        if (!second.equals(PipeTransitHandoff.take())) throw new AssertionError("nested");
        if (PipeTransitHandoff.take()!=null) throw new AssertionError("reused");
      }
      if (!first.equals(PipeTransitHandoff.take())) throw new AssertionError("outer");
      if (PipeTransitHandoff.take()!=null) throw new AssertionError("outer reused");
    }
    if (PipeTransitHandoff.take()!=null) throw new AssertionError("leaked");
    byte[] external={1,2,3};
    ItemTransitPayload payload=new ItemTransitPayload(Map.of("logistics:route", external));
    external[0]=8;
    if (payload.get("logistics:route").orElseThrow()[0]!=1) throw new AssertionError("mutable input");
    payload.get("logistics:route").orElseThrow()[0]=9;
    if (payload.get("logistics:route").orElseThrow()[0]!=1) throw new AssertionError("mutable output");
    try (var scope=PipeTransitHandoff.begin(first,payload)) {
      var handoff=PipeTransitHandoff.claim();
      if (!first.equals(handoff.id()) || !payload.equals(handoff.payload())) throw new AssertionError("payload lost");
      if (PipeTransitHandoff.claim().id()!=null) throw new AssertionError("payload claimed twice");
    }
    try { new ItemTransitPayload(Map.of("other:big",new byte[ItemTransitPayload.MAX_ENTRY_BYTES+1]));
      throw new AssertionError("unbounded payload");
    } catch (IllegalArgumentException expected) { }
    try { payload.with("not-namespaced",new byte[]{1});
      throw new AssertionError("invalid namespace");
    } catch (IllegalArgumentException expected) { }
  }
}''', encoding='utf-8')
            subprocess.run(['javac', '-d', tmp, str(ROOT / 'source-shared/src/main/java/buildcraft/api/v2/pipe/ItemTransitPayload.java'), str(java), str(test)], check=True, capture_output=True)
            subprocess.run(['java', '-cp', tmp, 'Probe'], check=True, capture_output=True)

if __name__ == "__main__":
    unittest.main()
