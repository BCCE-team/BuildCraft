#!/usr/bin/env python3
"""BC8-P02: compile an API-only addon JAR, then run genuine world/event backends.

The Minecraft and platform classes here are tiny host-side test doubles. This is
not a substitute for Forge/NeoForge GameTests of real chunk/permission integrations.
"""
from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "source-shared/src/main/java"

PUBLIC = [
    "buildcraft/api/v2/OperationMode.java",
    *["buildcraft/api/v2/permission/" + f + ".java" for f in (
        "ActorType", "AutomationActor", "PermissionDecision", "PermissionVerdict",
        "WorldOperationTarget", "WorldOperationKind", "WorldOperationContext", "PermissionService")],
    "buildcraft/api/v2/item/ItemPort.java",
    "buildcraft/api/v2/item/ItemTransferPolicy.java",
    "buildcraft/api/v2/item/ItemTransferResult.java",
    *["buildcraft/api/v2/platform/" + f + ".java" for f in (
        "PlatformServices", "ItemTransfer", "FluidTransfer", "EnergyTransfer", "ExternalEnergyPort")],
    "buildcraft/api/v2/world/WorldAccessContext.java",
    "buildcraft/api/v2/world/WorldAccessService.java",
    *["buildcraft/api/v2/event/" + f + ".java" for f in (
        "WorldEventContext", "WorldEventKind", "WorldEventDecision", "WorldEventListener",
        "WorldEventSubscription", "WorldEventService")],
]
BACKEND = [
    "buildcraft/lib/internal/api/v2/WorldAccessServiceImpl.java",
    "buildcraft/lib/internal/api/v2/WorldEventServiceImpl.java",
]

STUBS = {
    "net/minecraft/resources/ResourceLocation.java": """package net.minecraft.resources;
import java.util.Objects;
public final class ResourceLocation {
 private final String id;
 private ResourceLocation(String id){this.id=id;}
 public static ResourceLocation tryParse(String id){return id==null||!id.contains(":")?null:new ResourceLocation(id);}
 public String toString(){return id;}
 public boolean equals(Object other){return other instanceof ResourceLocation r && Objects.equals(id,r.id);}
 public int hashCode(){return id.hashCode();}
}
""",
    "net/minecraft/resources/ResourceKey.java": """package net.minecraft.resources;
public final class ResourceKey<T> {
 private final ResourceLocation location;
 public ResourceKey(ResourceLocation id){location=id;}
 public ResourceLocation location(){return location;}
}
""",
    "net/minecraft/core/BlockPos.java": """package net.minecraft.core;
public record BlockPos(int x, int y, int z) { public BlockPos immutable(){return this;} }
""",
    "net/minecraft/core/Direction.java": """package net.minecraft.core; public enum Direction { NORTH,SOUTH,EAST,WEST,UP,DOWN; }
""",
    "net/minecraft/world/level/block/state/BlockState.java": """package net.minecraft.world.level.block.state;
public record BlockState(String name) { }
""",
    "net/minecraft/server/MinecraftServer.java": """package net.minecraft.server;
public final class MinecraftServer {
 public boolean sameThread=true;
 public boolean isSameThread(){return sameThread;}
}
""",
    "net/minecraft/world/level/Level.java": """package net.minecraft.world.level;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.state.BlockState;
public class Level {
 public boolean client;
 public long time=135;
 public final MinecraftServer server = new MinecraftServer();
 public final Set<BlockPos> loaded = new HashSet<>();
 public final Map<BlockPos,BlockState> states = new HashMap<>();
 public Level(boolean client){this.client=client;}
 public boolean isClientSide(){return client;}
 public MinecraftServer getServer(){return server;}
 public boolean hasChunkAt(BlockPos pos){return loaded.contains(pos);}
 public BlockState getBlockState(BlockPos pos){
   if (!hasChunkAt(pos)) throw new AssertionError("World read would force a chunk load");
   return states.getOrDefault(pos,new BlockState("air"));
 }
 public long getGameTime(){return time;}
 public ResourceKey<Level> dimension(){return new ResourceKey<>(ResourceLocation.tryParse("test:dimension"));}
}
""",
    "net/minecraft/world/item/ItemStack.java": """package net.minecraft.world.item;
public class ItemStack {
 public static final ItemStack EMPTY = new ItemStack(0);
 private int count;
 public ItemStack(int count){this.count=count;}
 public int getCount(){return count;}
 public void setCount(int next){count=next;}
 public ItemStack copy(){return new ItemStack(count);}
 public boolean isEmpty(){return count==0;}
}
""",
    "buildcraft/api/v2/item/ItemMatcher.java": """package buildcraft.api.v2.item;
@FunctionalInterface public interface ItemMatcher { boolean matches(net.minecraft.world.item.ItemStack stack); }
""",
    "buildcraft/api/v2/fluid/FluidAmount.java": """package buildcraft.api.v2.fluid;
public record FluidAmount(long quantity) { }
""",
    "buildcraft/api/v2/fluid/FluidVolume.java": """package buildcraft.api.v2.fluid;
public record FluidVolume(FluidAmount amount) { }
""",
    "buildcraft/api/v2/fluid/FluidMatcher.java": """package buildcraft.api.v2.fluid;
public interface FluidMatcher { }
""",
    "buildcraft/api/v2/fluid/FluidTransferResult.java": """package buildcraft.api.v2.fluid;
public record FluidTransferResult(FluidAmount amount) {
 public static FluidTransferResult nothing(FluidAmount amount){return new FluidTransferResult(new FluidAmount(0));}
}
""",
    "buildcraft/api/v2/fluid/FluidPort.java": """package buildcraft.api.v2.fluid;
import buildcraft.api.v2.OperationMode;
public interface FluidPort {
 FluidTransferResult insert(FluidVolume volume, OperationMode mode);
 FluidTransferResult extract(FluidMatcher matcher, FluidAmount max, OperationMode mode);
}
""",
    "buildcraft/api/v2/pipe/PipeView.java": """package buildcraft.api.v2.pipe;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Direction;
public interface PipeView {
 ResourceLocation typeId(); Set<Direction> connectedSides();
}
""",
    "buildcraft/api/v2/pipe/PipeService.java": """package buildcraft.api.v2.pipe;
import java.util.Optional;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
public interface PipeService { Optional<PipeView> pipe(Level level,BlockPos pos); }
""",
    "buildcraft/api/v2/service/ServiceKey.java": """package buildcraft.api.v2.service;
public record ServiceKey<T>(String id) { }
""",
    "buildcraft/api/v2/BuildCraftServices.java": """package buildcraft.api.v2;
import buildcraft.api.v2.service.ServiceKey;
import buildcraft.api.v2.permission.PermissionService;
import buildcraft.api.v2.platform.PlatformServices;
import buildcraft.api.v2.pipe.PipeService;
public final class BuildCraftServices {
 public static final ServiceKey<PermissionService> PERMISSIONS=new ServiceKey<>("permissions");
 public static final ServiceKey<PlatformServices> PLATFORM=new ServiceKey<>("platform");
 public static final ServiceKey<PipeService> PIPES=new ServiceKey<>("pipes");
}
""",
    "buildcraft/api/v2/BuildCraftApi.java": """package buildcraft.api.v2;
import buildcraft.api.v2.service.ServiceKey;
import java.util.*;
public final class BuildCraftApi {
 private static final Map<ServiceKey<?>,Object> services=new HashMap<>();
 public static <T> void install(ServiceKey<T> key,T value){services.put(key,value);}
 public static <T> T service(ServiceKey<T> key){return (T)services.get(key);}
 public static final class Runtime {
   public <T> Optional<T> service(ServiceKey<T> key){return Optional.ofNullable(BuildCraftApi.service(key));}
 }
 public static Runtime runtime(){return new Runtime();}
}
""",
}

ADDON = """package addon;
import buildcraft.api.v2.world.*;
import buildcraft.api.v2.event.*;
import buildcraft.api.v2.permission.*;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
public final class WorldAddon {
 public static void install(WorldEventService events,List<String> calls) {
  events.register(ResourceLocation.tryParse("addon:first"),5,e->{calls.add("first:"+e.kind());return WorldEventDecision.ALLOW;});
  events.register(ResourceLocation.tryParse("addon:deny"),1,e->{calls.add("deny:"+e.kind());return WorldEventDecision.DENY;});
 }
 public static WorldAccessContext borrow(WorldAccessService access,
      net.minecraft.world.level.Level level,net.minecraft.core.BlockPos pos,
      AutomationActor actor,java.util.function.BooleanSupplier valid) {
  return access.scoped(level,pos,actor,valid);
 }
}
"""

HOST = """package integration;
import addon.WorldAddon;
import buildcraft.api.v2.*;
import buildcraft.api.v2.event.*;
import buildcraft.api.v2.world.*;
import buildcraft.api.v2.permission.*;
import buildcraft.api.v2.platform.*;
import buildcraft.api.v2.item.*;
import buildcraft.lib.internal.api.v2.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
public final class Host {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static ResourceLocation id(String s){return ResourceLocation.tryParse(s);}
 static WorldEventContext event(WorldEventKind kind,Level level,BlockPos pos,AutomationActor actor){
   return new WorldEventContext(kind,level,pos,actor,Optional.of(new BlockState("pipe")),
       Optional.of(id("addon:pipe")),OperationMode.EXECUTE);
 }
 public static void main(String[] args){
   var source=new BlockPos(0,64,0);
   var target=new BlockPos(1,64,0);
   var level=new Level(false);
   level.loaded.add(source);level.loaded.add(target);
   level.states.put(target,new BlockState("chest"));
   AutomationActor actor=AutomationActor.machineOwner(UUID.randomUUID(),"Steve",id("addon:router"));
   AtomicBoolean valid=new AtomicBoolean(true);
   AtomicBoolean denied=new AtomicBoolean(false);
   AtomicInteger transfers=new AtomicInteger();
   BuildCraftApi.install(BuildCraftServices.PERMISSIONS,ctx -> denied.get()
       ? PermissionDecision.deny(id("addon:claims"),"private") : PermissionDecision.pass());
   ItemPort nativeInventory=new ItemPort(){
     public ItemTransferResult insert(ItemStack stack, OperationMode mode){
       if(mode==OperationMode.EXECUTE)transfers.addAndGet(stack.getCount());
       return ItemTransferResult.ofInsertion(stack,stack.getCount());
     }
     public ItemTransferResult extract(ItemMatcher matcher,int max,OperationMode mode){
       return ItemTransferResult.ofExtraction(max,new ItemStack(max));
     }
   };
   BuildCraftApi.install(BuildCraftServices.PLATFORM,new PlatformServices(){
     public Optional<ItemTransfer> itemTransfer(){return Optional.of((world,pos,side)->Optional.of(nativeInventory));}
     public Optional<FluidTransfer> fluidTransfer(){return Optional.empty();}
     public Optional<EnergyTransfer> energyTransfer(){return Optional.empty();}
   });
   BuildCraftApi.install(BuildCraftServices.PIPES,(world,pos)->Optional.of(new buildcraft.api.v2.pipe.PipeView(){
      public ResourceLocation typeId(){return id("addon:pipe");}
      public Set<Direction> connectedSides(){return Set.of(Direction.WEST);}
   }));
   WorldAccessService service=new WorldAccessServiceImpl();
   WorldAccessContext world=WorldAddon.borrow(service,level,source,actor,valid::get);
   check(world.actor().equals(actor),"owner identity lost");
   check(world.isServerSide() && world.gameTime()==135,"world time/server state");
   check(world.isLoaded(target) && world.blockState(target).orElseThrow().name().equals("chest"),"loaded reads");
   check(world.pipeTypeId(target).orElseThrow().equals(id("addon:pipe")),"pipe type id");
   check(world.pipeConnections(target).orElseThrow().contains(Direction.WEST),"pipe sides");
   check(!world.isLoaded(new BlockPos(800,64,800)) && world.blockState(new BlockPos(800,64,800)).isEmpty(),"unloaded reads");
   var port=world.itemPort(target,Direction.WEST).orElseThrow();
   check(port.insert(new ItemStack(3),OperationMode.SIMULATE).transferredCount()==3 && transfers.get()==0,"simulate changed state");
   check(port.insert(new ItemStack(2),OperationMode.EXECUTE).transferredCount()==2 && transfers.get()==2,"real transfer");
   denied.set(true);
   check(port.insert(new ItemStack(4),OperationMode.EXECUTE).transferredCount()==0 && transfers.get()==2,"claim bypass via cached port");
   check(world.permission(target,WorldOperationKind.INVENTORY_ACCESS,OperationMode.EXECUTE).verdict()==PermissionVerdict.DENY,"permission enforcement");
   denied.set(false);
   level.loaded.remove(target);
   check(port.insert(new ItemStack(4),OperationMode.EXECUTE).transferredCount()==0,"unloaded cached port");
   level.loaded.add(target);
   valid.set(false);
   check(!world.isLoaded(target) && world.blockState(target).isEmpty(),"saved world access after callback");
   check(port.insert(new ItemStack(4),OperationMode.EXECUTE).transferredCount()==0,"saved item port after callback");
   valid.set(true);
   level.server.sameThread=false;
   check(port.extract(stack->true,2,OperationMode.EXECUTE).transferredCount()==0,"off-thread cached port");
   level.server.sameThread=true;
   var client=new Level(true);client.loaded.add(source);client.loaded.add(target);
   check(!service.open(client,source,actor).isLoaded(target),"client port access");
   var events=new WorldEventServiceImpl();
   var calls=new ArrayList<String>();
   WorldAddon.install(events,calls);
   check(events.listeners().size()==2,"subscriber registry");
   check(events.dispatch(event(WorldEventKind.BLOCK_INTERACT,level,target,actor))==WorldEventDecision.DENY,"cancellable veto");
   check(calls.equals(List.of("first:BLOCK_INTERACT","deny:BLOCK_INTERACT")),"priority ordering"+calls);
   calls.clear();
   check(events.dispatch(event(WorldEventKind.PIPE_PLACED,level,target,actor))==WorldEventDecision.ALLOW,"post event is not cancellable");
   check(calls.size()==2,"post listener chain");
   events.freeze();
   try{events.register(id("addon:late"),0,e->WorldEventDecision.PASS);throw new AssertionError("late listener accepted");}
   catch(IllegalStateException expected){}
   var crash=new WorldEventServiceImpl();
   crash.register(id("addon:crash"),10,e->{throw new IllegalStateException("broken");});
   check(crash.dispatch(event(WorldEventKind.BEFORE_BLOCK_PLACE,level,target,actor))==WorldEventDecision.DENY,
     "broken permission listener must fail closed");
   check(crash.dispatch(event(WorldEventKind.BLOCK_PLACED,level,target,actor))==WorldEventDecision.PASS,
     "broken post observer must not retroactively cancel placement");
   check(events.dispatch(event(WorldEventKind.BLOCK_INTERACT,client,target,actor))==WorldEventDecision.DENY,
     "client event not denied");
   System.out.println("BC8-P02 PASS world access lease, chunk guards, owner, ports, permissions, event priority/veto/freeze");
 }
}
"""


def invoke(*args: str, cwd=None):
    p=subprocess.run(args,cwd=cwd,capture_output=True,text=True,timeout=100)
    if p.returncode:
        raise AssertionError("COMMAND: "+" ".join(args[:5])+"\n"+p.stdout+"\n"+p.stderr[-18000:])
    return p.stdout


def write(folder: Path, name: str, text: str) -> Path:
    path=folder/name
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(text,encoding="utf-8")
    return path


class Bc8P02WorldConformance(unittest.TestCase):
    def test_world_and_events_with_independent_addon_jar(self):
        if not all(shutil.which(binary) for binary in ("javac","java","jar")):
            self.skipTest("JDK required")
        with tempfile.TemporaryDirectory(prefix="bcce-p02-") as tmp:
            root=Path(tmp)
            stubs=[write(root/"stub",name,source) for name,source in STUBS.items()]
            api=root/"api";api.mkdir()
            invoke("javac","--release","17","-encoding","UTF-8","-sourcepath","",
                "-d",str(api),*[str(path) for path in stubs],*[str(SRC/path) for path in PUBLIC])
            api_jar=root/"api.jar"
            invoke("jar","--create","--file",str(api_jar),"-C",str(api),".")
            addon=write(root,"addon/addon/WorldAddon.java",ADDON)
            addon_classes=root/"addon-classes"; addon_classes.mkdir()
            invoke("javac","--release","17","-encoding","UTF-8","-sourcepath","",
                "-cp",str(api_jar),"-d",str(addon_classes),str(addon))
            addon_jar=root/"addon.jar"
            invoke("jar","--create","--file",str(addon_jar),"-C",str(addon_classes),".")
            with zipfile.ZipFile(addon_jar) as entries:
                self.assertTrue(all(not n.startswith(("buildcraft/","net/minecraft/")) for n in entries.namelist()))
            host=write(root,"host/integration/Host.java",HOST)
            host_classes=root/"host-classes";host_classes.mkdir()
            invoke("javac","--release","17","-encoding","UTF-8","-sourcepath","",
                "-cp",os.pathsep.join((str(api_jar),str(addon_jar))),"-d",str(host_classes),
                str(host),*[str(SRC/path) for path in BACKEND])
            result=invoke("java","-cp",os.pathsep.join((str(api_jar),str(addon_jar),str(host_classes))),"integration.Host")
            self.assertIn("BC8-P02 PASS",result)

    def test_target_source_hooks_and_no_loader_specific_api(self):
        pipes=list(ROOT.glob("source-*/**/buildcraft/transport/pipe/Pipe.java"))
        active=[p for p in pipes if "worldAccess()" in p.read_text(encoding="utf-8")]
        self.assertEqual(5,len(active),[str(p) for p in active])
        for p in active:
            s=p.read_text(encoding="utf-8")
            self.assertIn("apiRuntime.callbackEpoch()",s)
            self.assertIn("BCPermissions.actor",s)
            self.assertNotIn("@Override\n    /** A tick-scoped world facade",s, "Duplicate @Override on worldAccess: " + str(p))
        self.assertIn("inCallback",(SRC/"buildcraft/transport/api2/PipeComponentRuntime.java").read_text())
        tiles=list(ROOT.glob("source-*/**/buildcraft/transport/tile/TilePipeHolder.java"))
        updated=[p for p in tiles if "WorldEventKind.PIPE_PLACED" in p.read_text()]
        self.assertEqual(4,len(updated),[str(p) for p in updated])
        interaction=(SRC/"buildcraft/lib/internal/api/v2/BlockInteractionServiceImpl.java").read_text()
        self.assertIn("WorldEventKind.BLOCK_INTERACT",interaction)
        worldrules=list(ROOT.glob("source-*/**/buildcraft/lib/internal/api/v2/WorldRuleServiceImpl.java"))
        self.assertEqual(3,sum("WorldEventKind.BEFORE_BLOCK_PLACE" in p.read_text() for p in worldrules))
        for source in list((SRC/"buildcraft/api/v2/world").glob("*.java"))+list((SRC/"buildcraft/api/v2/event").glob("*.java")):
            contents=source.read_text()
            self.assertNotIn("import net.minecraftforge",contents)
            self.assertNotIn("import net.neoforged",contents)
            self.assertNotIn("import net.fabricmc",contents)

if __name__=="__main__": unittest.main()
