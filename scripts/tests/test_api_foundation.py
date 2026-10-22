#!/usr/bin/env python3
"""Standalone API registration, world access, ownership and event contracts."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import os
import shutil
import subprocess
import tempfile
import unittest
import zipfile

FOUNDATION_JAVA = ROOT / "source-shared/src/main/java"
FOUNDATION_ADDON = ROOT / "addon-fixture/src/main/java/dev/bcce/apifixture/FoundationReloadFixtureAddon.java"

FOUNDATION_API = [
    "buildcraft/api/v2/registry/ApiRegistry.java",
    "buildcraft/api/v2/registry/RegistrationContext.java",
    "buildcraft/api/v2/registry/RegistryEntry.java",
    "buildcraft/api/v2/module/ModuleInfo.java",
    "buildcraft/api/v2/module/ModuleService.java",
    "buildcraft/api/v2/reload/DefinitionDecoder.java",
    "buildcraft/api/v2/reload/DefinitionEntry.java",
    "buildcraft/api/v2/reload/DefinitionProvenance.java",
    "buildcraft/api/v2/reload/DefinitionReloadService.java",
    "buildcraft/api/v2/reload/DefinitionSnapshot.java",
    "buildcraft/api/v2/reload/ReloadChange.java",
    "buildcraft/api/v2/reload/ReloadContributor.java",
    "buildcraft/api/v2/reload/ReloadDiagnostic.java",
    "buildcraft/api/v2/reload/ReloadGeneration.java",
    "buildcraft/api/v2/reload/ReloadObserver.java",
    "buildcraft/api/v2/reload/ReloadPublishResult.java",
    "buildcraft/api/v2/reload/ResolvedDefinition.java",
    "buildcraft/api/v2/reload/ScriptableDefinitionType.java",
    "buildcraft/api/v2/reload/ScriptedDefinition.java",
    "buildcraft/api/v2/reload/ScriptedDefinitionSnapshot.java",
]
FOUNDATION_IMPL = [
    "buildcraft/lib/internal/api/v2/registry/ApiRegistryImpl.java",
    "buildcraft/lib/internal/api/v2/reload/DefinitionReloadServiceImpl.java",
    "buildcraft/lib/internal/api/v2/ModuleServiceImpl.java",
]
FOUNDATION_STUBS = {
    "net/minecraft/resources/ResourceLocation.java": """package net.minecraft.resources;
public record ResourceLocation(String namespace, String path) {
    public static ResourceLocation tryParse(String value) {
        String[] a = value.split(":", 2);
        return a.length == 2 && !a[0].isBlank() && !a[1].isBlank()
            ? new ResourceLocation(a[0], a[1]) : null;
    }
    public String getNamespace() {return namespace;}
    public String getPath() {return path;}
    public String toString() {return namespace+":"+path;}
}""",
    "buildcraft/lib/internal/module/BCModules.java": """package buildcraft.lib.internal.module;
public enum BCModules {
    LIB("lib");
    public static final BCModules[] VALUES = values();
    public final String lowerCaseName;
    BCModules(String name) { lowerCaseName = name; }
    public boolean isLoaded() { return true; }
}""",
    "buildcraft/lib/internal/core/BuildCraftAPI.java": """package buildcraft.lib.internal.core;
public final class BuildCraftAPI { public static String getVersion() { return "2.1-test"; } }""",
}
FOUNDATION_HOST = """package buildcraft.lib.internal.api.v2;
import buildcraft.api.v2.reload.*;
import buildcraft.api.v2.registry.*;
import buildcraft.api.v2.module.*;
import buildcraft.lib.internal.api.v2.registry.ApiRegistryImpl;
import buildcraft.lib.internal.api.v2.reload.DefinitionReloadServiceImpl;
import dev.bcce.apifixture.FoundationReloadFixtureAddon;
import java.util.*;
import net.minecraft.resources.ResourceLocation;

public final class FoundationHostTest {
    static ResourceLocation id(String value) { return FoundationReloadFixtureAddon.id(value); }
    static DefinitionProvenance p(String owner, int priority) {
        return new DefinitionProvenance(owner, owner+".json", priority);
    }
    static ScriptedDefinition val(String id, String text, int priority) {
        return ScriptedDefinition.value(id(id), Map.of("value", text), p("foundation", priority));
    }
    static void check(boolean yes, String message) { if(!yes) throw new AssertionError(message); }
    static void denied(Runnable r) {
        try { r.run(); } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException ex) { return; }
        throw new AssertionError("Expected rejection");
    }
    public static void main(String[] args) {
        // Freeze preflight must preserve mutable state when a forward alias is unresolved.
        ApiRegistryImpl<String> registry = new ApiRegistryImpl<>();
        registry.registerAlias(id("foundation:forward"), id("foundation:missing"), ()->"foundation");
        denied(registry::freeze);
        check(!registry.frozen(), "A failed freeze mutated the registry");
        registry.register(id("foundation:missing"), "ok", ()->"foundation");
        registry.registerAlias(id("foundation:alias"), id("foundation:forward"), ()->"foundation");
        denied(() -> registry.registerAlias(id("foundation:missing"), id("foundation:alias"), ()->"foundation"));
        denied(() -> registry.registerAlias(id("foundation:forward"), id("foundation:missing"), ()->"foundation"));
        registry.validateFreeze();
        registry.freeze();
        check("ok".equals(registry.get(id("foundation:alias"))), "Alias not resolved");
        denied(() -> registry.register(id("foundation:late"), "bad"));
        check(registry.entries().size()==1, "Mutable data leak from entries");

        DefinitionReloadServiceImpl reloads = new DefinitionReloadServiceImpl();
        ModuleServiceImpl modules = new ModuleServiceImpl();
        FoundationReloadFixtureAddon.register(reloads, modules);
        modules.validateFreeze();
        check(modules.loaded(id("foundation:core")), "External module was not discoverable");
        ModuleServiceImpl missing = new ModuleServiceImpl();
        missing.registerExternal(new ModuleInfo(id("foundation:missing_dep"), "1", true),
            List.of(id("other:missing")), ()->"foundation");
        denied(missing::freeze);
        missing.registerExternal(new ModuleInfo(id("other:missing"), "1", true),
            List.of(), ()->"other");
        missing.freeze();
        denied(() -> modules.registerExternal(new ModuleInfo(id("buildcraft:lib"), "bad", true),
            List.of(), ()->"buildcraft"));
        modules.freeze();
        reloads.freeze();
        denied(() -> reloads.registerType(new ScriptableDefinitionType<>(id("foundation:late"), String.class,
            o -> "late"), ()->"foundation"));

        var type=FoundationReloadFixtureAddon.RULES;
        var first=reloads.reload(type,List.of(val("foundation:target","base",0),
            ScriptedDefinition.alias(id("foundation:alias"),id("foundation:target"),p("foundation",0)),
            ScriptedDefinition.disabled(id("foundation:off"), "removed",p("foundation",0))));
        check(first.published(), "Initial publication failed " + first.diagnostics());
        ScriptedDefinitionSnapshot<String> previous=reloads.snapshot(type);
        check(previous.get(id("foundation:injected")).isPresent(),"Code reload contributor missing");
        check("base".equals(previous.get(id("foundation:alias")).orElseThrow()),"Alias read");
        check(previous.get(id("foundation:off")).isEmpty(),"Disabled entry leaked");
        check(previous.disabled().get(id("foundation:off")).reason().equals("removed"), "Disabled reason lost");

        var changed=reloads.reload(type,List.of(val("foundation:target","low",0),
            val("foundation:target","winner",20)));
        check(changed.published(),"Priority reload failed");
        check("winner".equals(reloads.snapshot(type).get(id("foundation:target")).orElseThrow()),"Priority resolution");
        check(reloads.snapshot(type).definitions().resolved(id("foundation:target")).orElseThrow()
            .overridden().size()==1,"Lost override provenance");
        check("base".equals(previous.get(id("foundation:target")).orElseThrow()),"Old snapshot mutated");
        var committed=reloads.snapshot(type);
        check(FoundationReloadFixtureAddon.before==2 && FoundationReloadFixtureAddon.after==2,"Observer ordering");
        var scripted=reloads.reload(type,List.of(ScriptedDefinition.value(id("foundation:typed"),
            Map.of("type","foundation:uppercase","value","chosen"),p("foundation",0))));
        check(scripted.published(),"Named decoder did not publish");
        check("CHOSEN".equals(reloads.snapshot(type).get(id("foundation:typed")).orElseThrow()),"Named decoder wrong");
        var post=reloads.reload(type,List.of(val("foundation:after_fail","committed",0)));
        check(post.published() && !post.diagnostics().isEmpty(),"After publish warning must not roll back data");
        check("committed".equals(reloads.snapshot(type).get(id("foundation:after_fail")).orElseThrow()),"After publish lost data");
        committed=reloads.snapshot(type);

        check(!reloads.reload(type,List.of(val("foundation:target","left",2),
            val("foundation:target","right",2))).published(),"Conflicting priorities accepted");
        check(!reloads.reload(type,List.of(ScriptedDefinition.alias(id("foundation:bad"),
            id("foundation:missing"),p("foundation",0)))).published(),"Dangling alias accepted");
        check(!reloads.reload(type,List.of(ScriptedDefinition.alias(id("foundation:a"),
            id("foundation:b"),p("foundation",0)),ScriptedDefinition.alias(id("foundation:b"),
            id("foundation:a"),p("foundation",0)))).published(),"Alias cycle accepted");
        check(!reloads.reload(type,List.of(ScriptedDefinition.value(id("foundation:target"),
            Map.of("absent", "value"),p("foundation",0)))).published(),"Broken decoder accepted");
        check(!reloads.reload(type,List.of(val("foundation:veto","stop",0))).published(),"Veto was ignored");
        check(reloads.snapshot(type)==committed,"Failed reload changed published snapshot");
        check(FoundationReloadFixtureAddon.before==5 && FoundationReloadFixtureAddon.after==4,"Veto callback incorrect");

        var nested=new LinkedHashMap<String,Object>();
        var items=new ArrayList<Object>(List.of("old")); nested.put("list",items);
        var source=ScriptedDefinition.value(id("foundation:safe"),nested,p("foundation",0));
        items.set(0,"mutated");
        check(((List<?>)source.data().get("list")).get(0).equals("old"),"Nested input was not copied");
        denied(() -> source.data().put("hack","yes"));
        System.out.println("API foundation PASS: api-only addon, modules, registry freeze, aliases, reload, veto, rollback");
    }
}
"""


def foundation_run(*args: str) -> None:
    result = subprocess.run(args, capture_output=True, text=True, timeout=100)
    if result.returncode:
        raise AssertionError(" ".join(args[:5])+"\n"+result.stdout+"\n"+result.stderr[-16000:])


def foundation_write(root: Path, path: str, data: str) -> Path:
    target = root/path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(data, encoding="utf-8")
    return target


class FoundationConformanceTest(unittest.TestCase):
    def test_addon_jar_bootstrap_and_reload(self) -> None:
        if not all(shutil.which(it) for it in ("javac", "java", "jar")):
            self.skipTest("JDK required")
        with tempfile.TemporaryDirectory(prefix="bcce-foundation-") as td:
            temp=Path(td)
            stub={name:foundation_write(temp/"stub",name,src) for name,src in FOUNDATION_STUBS.items()}
            api_classes=temp/"api-classes"
            api_classes.mkdir()
            foundation_run("javac","--release","17","-encoding","UTF-8","-sourcepath","", "-d",str(api_classes),
                str(stub["net/minecraft/resources/ResourceLocation.java"]),
                *[str(FOUNDATION_JAVA/path) for path in FOUNDATION_API])
            api_jar=temp/"api.jar"
            foundation_run("jar","--create","--file",str(api_jar),"-C",str(api_classes),".")
            addon_classes=temp/"addon-classes"
            addon_classes.mkdir()
            foundation_run("javac","--release","17","-encoding","UTF-8","-sourcepath","", "-cp",str(api_jar),
                "-d",str(addon_classes), str(FOUNDATION_ADDON))
            addon_jar=temp/"addon.jar"
            foundation_run("jar","--create","--file",str(addon_jar),"-C",str(addon_classes),".")
            with __import__("zipfile").ZipFile(addon_jar) as zip_file:
                assert all(not member.startswith(("buildcraft/", "net/minecraft/")) for member in zip_file.namelist())
            host=foundation_write(temp,"host/buildcraft/lib/internal/api/v2/FoundationHostTest.java",FOUNDATION_HOST)
            host_classes=temp/"host-classes"
            host_classes.mkdir()
            foundation_run("javac","--release","17","-encoding","UTF-8","-sourcepath","", "-cp",os.pathsep.join((str(api_jar),str(addon_jar))),
                "-d",str(host_classes), *[str(FOUNDATION_JAVA/path) for path in FOUNDATION_IMPL],
                str(stub["buildcraft/lib/internal/module/BCModules.java"]),
                str(stub["buildcraft/lib/internal/core/BuildCraftAPI.java"]), str(host))
            foundation_run("java","-cp",os.pathsep.join((str(api_jar),str(addon_jar),str(host_classes))),
                "buildcraft.lib.internal.api.v2.FoundationHostTest")


    def test_all_seven_targets_select_registered_runtime(self) -> None:
        # Check the actual precedence of canonical, downport and native 26.X layers.
        import sys
        sys.path.insert(0, str(ROOT / "scripts"))
        from source_layout import load_properties, resolve_effective_source, target_layout

        props = load_properties()
        targets = (
            "1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge", "1.21.11-neoforge",
            "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge",
        )
        paths = (
            "buildcraft/api/v2/reload/DefinitionReloadService.java",
            "buildcraft/lib/internal/api/v2/reload/DefinitionReloadServiceImpl.java",
            "buildcraft/lib/internal/api/v2/registry/ApiRegistryImpl.java",
            "buildcraft/lib/internal/api/v2/ModuleServiceImpl.java",
            "buildcraft/lib/internal/api/v2/BuildCraftApiRuntime.java",
        )
        for target in targets:
            with self.subTest(target=target):
                layout = target_layout(target, props)
                resolved = {}
                for suffix in paths:
                    selected = resolve_effective_source(layout, props, "src/main/java/" + suffix)
                    self.assertIsNotNone(selected, f"{target} missing {suffix}")
                    resolved[suffix] = selected.read_text(encoding="utf-8")
                runtime = resolved[paths[-1]]
                self.assertIn("BuildCraftServices.DEFINITION_RELOAD, definitionReload", runtime)
                self.assertIn("definitionReload.freeze()", runtime)
                self.assertIn("registry.validateFreeze()", runtime)
                self.assertIn("modules.validateFreeze()", runtime)
                self.assertIn("registerExternal", resolved[paths[-2]])
                self.assertIn("validateFreeze()", resolved[paths[-3]])
                self.assertIn("registerContributor", resolved[paths[0]])


WORLD_SRC = ROOT / "source-shared/src/main/java"

WORLD_PUBLIC = [
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
WORLD_BACKEND = [
    "buildcraft/lib/internal/api/v2/WorldAccessServiceImpl.java",
    "buildcraft/lib/internal/api/v2/WorldEventServiceImpl.java",
]

WORLD_STUBS = {
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

WORLD_ADDON = """package addon;
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

WORLD_HOST = """package integration;
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
   System.out.println("API world contracts PASS world access lease, chunk guards, owner, ports, permissions, event priority/veto/freeze");
 }
}
"""


def world_invoke(*args: str, cwd=None):
    p=subprocess.run(args,cwd=cwd,capture_output=True,text=True,timeout=100)
    if p.returncode:
        raise AssertionError("COMMAND: "+" ".join(args[:5])+"\n"+p.stdout+"\n"+p.stderr[-18000:])
    return p.stdout


def world_write(folder: Path, name: str, text: str) -> Path:
    path=folder/name
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(text,encoding="utf-8")
    return path


class WorldConformanceTest(unittest.TestCase):
    def test_world_and_events_with_independent_addon_jar(self):
        if not all(shutil.which(binary) for binary in ("javac","java","jar")):
            self.skipTest("JDK required")
        with tempfile.TemporaryDirectory(prefix="bcce-world-") as tmp:
            root=Path(tmp)
            stubs=[world_write(root/"stub",name,source) for name,source in WORLD_STUBS.items()]
            api=root/"api";api.mkdir()
            world_invoke("javac","--release","17","-encoding","UTF-8","-sourcepath","",
                "-d",str(api),*[str(path) for path in stubs],*[str(WORLD_SRC/path) for path in WORLD_PUBLIC])
            api_jar=root/"api.jar"
            world_invoke("jar","--create","--file",str(api_jar),"-C",str(api),".")
            addon=world_write(root,"addon/addon/WorldAddon.java",WORLD_ADDON)
            addon_classes=root/"addon-classes"; addon_classes.mkdir()
            world_invoke("javac","--release","17","-encoding","UTF-8","-sourcepath","",
                "-cp",str(api_jar),"-d",str(addon_classes),str(addon))
            addon_jar=root/"addon.jar"
            world_invoke("jar","--create","--file",str(addon_jar),"-C",str(addon_classes),".")
            with zipfile.ZipFile(addon_jar) as entries:
                self.assertTrue(all(not n.startswith(("buildcraft/","net/minecraft/")) for n in entries.namelist()))
            host=world_write(root,"host/integration/Host.java",WORLD_HOST)
            host_classes=root/"host-classes";host_classes.mkdir()
            world_invoke("javac","--release","17","-encoding","UTF-8","-sourcepath","",
                "-cp",os.pathsep.join((str(api_jar),str(addon_jar))),"-d",str(host_classes),
                str(host),*[str(WORLD_SRC/path) for path in WORLD_BACKEND])
            result=world_invoke("java","-cp",os.pathsep.join((str(api_jar),str(addon_jar),str(host_classes))),"integration.Host")
            self.assertIn("API world contracts PASS",result)

    def test_target_source_hooks_and_no_loader_specific_api(self):
        pipes=list(ROOT.glob("source-*/**/buildcraft/transport/pipe/Pipe.java"))
        active=[p for p in pipes if "worldAccess()" in p.read_text(encoding="utf-8")]
        self.assertEqual(5,len(active),[str(p) for p in active])
        for p in active:
            s=p.read_text(encoding="utf-8")
            self.assertIn("apiRuntime.callbackEpoch()",s)
            self.assertIn("BCPermissions.actor",s)
            self.assertNotIn("@Override\n    /** A tick-scoped world facade",s, "Duplicate @Override on worldAccess: " + str(p))
        self.assertIn("inCallback",(WORLD_SRC/"buildcraft/transport/api2/PipeComponentRuntime.java").read_text())
        tiles=list(ROOT.glob("source-*/**/buildcraft/transport/tile/TilePipeHolder.java"))
        updated=[p for p in tiles if "WorldEventKind.PIPE_PLACED" in p.read_text()]
        self.assertEqual(4,len(updated),[str(p) for p in updated])
        interaction=(WORLD_SRC/"buildcraft/lib/internal/api/v2/BlockInteractionServiceImpl.java").read_text()
        self.assertIn("WorldEventKind.BLOCK_INTERACT",interaction)
        from source_lookup import resolve_target_source
        worldrules = [resolve_target_source(target, "src/main/java/buildcraft/lib/internal/api/v2/WorldRuleServiceImpl.java")
                      for target in ("1.20.1-forge", "1.21.11-neoforge", "26.3-neoforge")]
        self.assertTrue(all("WorldEventKind.BEFORE_BLOCK_PLACE" in path.read_text()
                            for path in worldrules), [str(path) for path in worldrules])
        for source in list((WORLD_SRC/"buildcraft/api/v2/world").glob("*.java"))+list((WORLD_SRC/"buildcraft/api/v2/event").glob("*.java")):
            contents=source.read_text()
            self.assertNotIn("import net.minecraftforge",contents)
            self.assertNotIn("import net.neoforged",contents)
            self.assertNotIn("import net.fabricmc",contents)

if __name__ == "__main__":
    unittest.main()
