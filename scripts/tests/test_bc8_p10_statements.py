#!/usr/bin/env python3
"""BC8-P10: public statement placement, typed argument validation and live dispatch."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "source-shared/src/main/java/buildcraft/api/v2"

STUBS = {
 "net/minecraft/resources/ResourceLocation.java": """package net.minecraft.resources;
 public record ResourceLocation(String name) {
  public static ResourceLocation tryParse(String id){return id==null?null:new ResourceLocation(id);}
  public String getNamespace(){return name.split(":")[0];}
 }""",
 "net/minecraft/core/Direction.java": "package net.minecraft.core; public enum Direction {NORTH,SOUTH,EAST,WEST,UP,DOWN}",
 "net/minecraft/core/BlockPos.java": "package net.minecraft.core; public record BlockPos(int x,int y,int z) {}",
 "net/minecraft/world/level/block/entity/BlockEntity.java": "package net.minecraft.world.level.block.entity; public class BlockEntity {}",
 "buildcraft/api/v2/permission/AutomationActor.java": """package buildcraft.api.v2.permission;
 public class AutomationActor {public static AutomationActor unknown(){return new AutomationActor();}}""",
 "buildcraft/api/v2/gate/GateView.java": """package buildcraft.api.v2.gate;
 import net.minecraft.core.BlockPos;
 public interface GateView {BlockPos position();}""",
 "buildcraft/api/v2/statement/ParameterType.java": """package buildcraft.api.v2.statement;
 import net.minecraft.resources.ResourceLocation;
 public record ParameterType<T>(ResourceLocation id) {}""",
 "buildcraft/api/v2/BuildCraftRegistries.java": """package buildcraft.api.v2;
 import buildcraft.api.v2.statement.*;
 public final class BuildCraftRegistries {
  public record Key<T>(){}
  public static final Key<ParameterType<?>> PARAMETER_TYPES=new Key<>();
  public static final Key<TriggerType> TRIGGER_TYPES=new Key<>();
  public static final Key<ActionType> ACTION_TYPES=new Key<>();
  public static final Key<StatementContributor> STATEMENT_CONTRIBUTORS=new Key<>();
 }""",
 "buildcraft/api/v2/registry/ApiRegistry.java": """package buildcraft.api.v2.registry;
 import java.util.*;
 import net.minecraft.resources.ResourceLocation;
 public final class ApiRegistry<T> {
  private final Map<ResourceLocation,T> map=new LinkedHashMap<>();
  public T get(ResourceLocation id){return map.get(id);}
  public void register(ResourceLocation id,T value){map.put(id,value);}
  public Collection<T> values(){return map.values();}
 }""",
 "buildcraft/api/v2/BuildCraftApi.java": """package buildcraft.api.v2;
 import java.util.*;
 import buildcraft.api.v2.registry.ApiRegistry;
 public final class BuildCraftApi {
  private static final Map<Object,ApiRegistry<?>> MAP=new IdentityHashMap<>();
  @SuppressWarnings("unchecked")
  public static <T> ApiRegistry<T> registry(BuildCraftRegistries.Key<T> key){
   return (ApiRegistry<T>)MAP.computeIfAbsent(key,k->new ApiRegistry<>());
  }
 }"""
}
PUBLIC = [
 "OperationMode.java", "context/ContextKey.java", "context/ExtensionContext.java",
 *["statement/"+n+".java" for n in (
  "StatementScope", "StatementContexts", "StatementValidation", "StatementContributor",
  "StatementCollector", "StatementContext", "StatementParameters", "StatementService",
  "ActionType", "TriggerType", "ActionExecutor", "TriggerEvaluator", "StatementResult",
  "ParameterSchema", "ParameterSpec", "ParameterValue"
 )]
]

ADDON = """package addon;
import buildcraft.api.v2.statement.*;
import buildcraft.api.v2.*;
import buildcraft.api.v2.context.*;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
public final class TestAddon {
 public static int calls;
 public static final ResourceLocation SIDE=new ResourceLocation("addon:sided");
 public static final ResourceLocation EXT=new ResourceLocation("addon:external");
 public static final ResourceLocation OWN=new ResourceLocation("addon:old");
 public static final ResourceLocation SLOT=new ResourceLocation("addon:item");
 public static final ResourceLocation TYPE=new ResourceLocation("addon:int");
 public static TriggerType sided(){return new TriggerType(SIDE,new ParameterSchema(List.of(new ParameterSpec(SLOT,TYPE,true))),
  (context,params)->{calls++; return context.optionalSide().isPresent() && params.get(SLOT,new ParameterType<Integer>(TYPE)).orElse(0)==7;});}
 public static ActionType external(){return new ActionType(EXT,ParameterSchema.EMPTY,(context,params)->{
  calls++;return context.views().get(StatementContexts.EXTERNAL_TARGET).isPresent()?StatementResult.success():
   new StatementResult(StatementResult.Status.FAILED,"missing target");});}
 public static StatementContributor contributor(){return new StatementContributor(){
  public void contribute(StatementContext context,StatementCollector collector){
   collector.addTrigger(SIDE);collector.addAction(EXT);
  }
  public StatementScope scope(ResourceLocation id){
   return SIDE.equals(id)?StatementScope.INTERNAL_SIDED:EXT.equals(id)?StatementScope.EXTERNAL:StatementScope.INTERNAL;
  }
 };}
 public static StatementContributor original(){return (ctx,out)->out.addTrigger(OWN);}
}"""
HOST = """package host;
import addon.TestAddon;
import buildcraft.api.v2.*;
import buildcraft.api.v2.registry.*;
import buildcraft.api.v2.statement.*;
import buildcraft.api.v2.context.*;
import buildcraft.api.v2.permission.*;
import buildcraft.lib.internal.api.v2.StatementServiceImpl;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.*;
public final class Run {
 static void check(boolean v,String s){if(!v)throw new AssertionError(s);}
 public static void main(String[] args){
  BuildCraftApi.registry(BuildCraftRegistries.TRIGGER_TYPES).register(TestAddon.SIDE,TestAddon.sided());
  BuildCraftApi.registry(BuildCraftRegistries.TRIGGER_TYPES).register(TestAddon.OWN,
   new TriggerType(TestAddon.OWN,ParameterSchema.EMPTY,(c,p)->true));
  BuildCraftApi.registry(BuildCraftRegistries.ACTION_TYPES).register(TestAddon.EXT,TestAddon.external());
  BuildCraftApi.registry(BuildCraftRegistries.STATEMENT_CONTRIBUTORS).register(
   new ResourceLocation("addon:scoped"),TestAddon.contributor());
  BuildCraftApi.registry(BuildCraftRegistries.STATEMENT_CONTRIBUTORS).register(
   new ResourceLocation("addon:old"),TestAddon.original());
  StatementServiceImpl api=StatementServiceImpl.INSTANCE;
  StatementContext scoped=new StatementContext(ExtensionContext.empty(),AutomationActor.unknown(),
    OperationMode.EXECUTE,Direction.NORTH);
  check(api.availableTriggers(scoped,StatementScope.INTERNAL_SIDED).contains(TestAddon.SIDE),"sided discovery");
  check(!api.availableTriggers(scoped,StatementScope.INTERNAL).contains(TestAddon.SIDE),"sided not internal");
  check(api.availableTriggers(scoped,StatementScope.INTERNAL).contains(TestAddon.OWN),"old contributor preserved");
  check(api.availableActions(scoped,StatementScope.EXTERNAL).contains(TestAddon.EXT),"external action discovery");
  StatementParameters valid=new StatementParameters(Map.of(TestAddon.SLOT,
    new ParameterValue<>(new ParameterType<Integer>(TestAddon.TYPE),7)));
  StatementParameters missing=StatementParameters.EMPTY;
  StatementParameters wrong=new StatementParameters(Map.of(TestAddon.SLOT,
    new ParameterValue<>(new ParameterType<Integer>(new ResourceLocation("addon:wrong")),7)));
  StatementParameters unknown=new StatementParameters(Map.of(new ResourceLocation("addon:unknown"),
    new ParameterValue<>(new ParameterType<Integer>(TestAddon.TYPE),7)));
  check(!api.evaluateTrigger(TestAddon.SIDE,scoped,missing),"required argument");
  check(!api.evaluateTrigger(TestAddon.SIDE,scoped,wrong),"type checking");
  check(!api.evaluateTrigger(TestAddon.SIDE,scoped,unknown),"unknown arguments");
  check(TestAddon.calls==0,"invalid never dispatched");
  check(api.evaluateTrigger(TestAddon.SIDE,scoped,valid),"valid scoped trigger");
  check(!api.evaluateTrigger(TestAddon.SIDE,new StatementContext(ExtensionContext.empty(),
   AutomationActor.unknown(),OperationMode.EXECUTE,null),valid),"side propagated");
  int calls=TestAddon.calls;
  check(api.executeAction(TestAddon.EXT,new StatementContext(ExtensionContext.empty(),
   AutomationActor.unknown(),OperationMode.SIMULATE,Direction.NORTH),missing).status()==StatementResult.Status.SUCCESS,
   "action simulation");
  check(calls==TestAddon.calls,"simulation is read-only");
  check(api.executeAction(TestAddon.EXT,scoped,missing).status()==StatementResult.Status.FAILED,"no target fails");
  StatementContext external=new StatementContext(new ExtensionContext(){
   @SuppressWarnings("unchecked")public <T> Optional<T> get(ContextKey<T> key){
    return key.equals(StatementContexts.EXTERNAL_TARGET)?Optional.of((T)new BlockEntity()):Optional.empty();
   }
  },AutomationActor.unknown(),OperationMode.EXECUTE,Direction.NORTH);
  check(api.executeAction(TestAddon.EXT,external,missing).status()==StatementResult.Status.SUCCESS,"external target");
  check(StatementScope.values().length==3,"three scopes");
 }
}"""


def put(root, name, data):
    dest=root/name
    dest.parent.mkdir(parents=True,exist_ok=True)
    dest.write_text(data,encoding="utf-8")
    return dest


class StatementsP10Tests(unittest.TestCase):
    def test_scoped_production_wiring(self):
        for family in ("old", "1.21.X", "26.X", "26.X/neoforge"):
            location=("source-family-platforms" if family.endswith("/neoforge") else "source-families")
            manager=(ROOT/location/f"{family}/src/main/java/buildcraft/lib/internal/statement/StatementManager.java").read_text()
            for hook in ("nativeExternalTriggers", "nativeExternalActions", "nativeInternalSidedTriggers", "nativeInternalSidedActions"):
                self.assertIn("StatementApi2Bridge."+hook,manager, (family,hook))
        for where in ("source-shared", "source-families/26.X", "source-family-platforms/26.X/neoforge"):
            bridge=(ROOT/where/"src/main/java/buildcraft/lib/internal/statement/api2/StatementApi2Bridge.java").read_text()
            for cls in ("NativeExternalTriggerAdapter", "NativeExternalActionAdapter", "NativeSidedTriggerAdapter", "NativeSidedActionAdapter"):
                self.assertIn(cls,bridge)
            self.assertIn("StatementContexts.EXTERNAL_TARGET",bridge)
        for where in ("source-shared", "source-families/1.21.X"):
            gate=(ROOT/where/"src/main/java/buildcraft/silicon/gate/GateLogic.java").read_text()
            self.assertEqual(gate.count("StatementApi2Bridge.validateSlot("),2)

    def test_independent_addon_jar(self):
        with tempfile.TemporaryDirectory(prefix="p10-statements-") as td:
            temp=Path(td)
            public=temp/"public"; addon=temp/"addon"; runtime=temp/"runtime"
            for path in (public,addon,runtime):path.mkdir()
            stubs=[put(temp/"stub",name,content) for name,content in STUBS.items()]
            api=[SRC/name for name in PUBLIC]
            cmd=["javac","-d",str(public),*[str(s) for s in stubs],*[str(s) for s in api]]
            subprocess.run(cmd,check=True,capture_output=True,text=True)
            addon_source=put(temp/"addon-src","addon/TestAddon.java",ADDON)
            subprocess.run(["javac","-cp",str(public),"-d",str(addon),str(addon_source)],check=True,capture_output=True,text=True)
            jar=temp/"P10Addon.jar"
            subprocess.run(["jar","cf",str(jar),"-C",str(addon),"."],check=True,capture_output=True,text=True)
            host=put(temp/"host-src","host/Run.java",HOST)
            backend=ROOT/"source-shared/src/main/java/buildcraft/lib/internal/api/v2/StatementServiceImpl.java"
            cp=os.pathsep.join((str(public),str(jar)))
            subprocess.run(["javac","-cp",cp,"-d",str(runtime),str(backend),str(host)],check=True,capture_output=True,text=True)
            subprocess.run(["java","-cp",os.pathsep.join((cp,str(runtime))),"host.Run"],check=True,capture_output=True,text=True)

if __name__=="__main__":unittest.main()
