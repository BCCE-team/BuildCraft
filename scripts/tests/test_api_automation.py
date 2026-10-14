#!/usr/bin/env python3
"""Statements, Gate integration and scoped action dispatch."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import os
import subprocess
import tempfile
import unittest

STATEMENTS_SRC = ROOT / "source-shared/src/main/java/buildcraft/api/v2"

STATEMENTS_STUBS = {
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
STATEMENTS_PUBLIC = [
 "OperationMode.java", "context/ContextKey.java", "context/ExtensionContext.java",
 *["statement/"+n+".java" for n in (
  "StatementScope", "StatementContexts", "StatementValidation", "StatementContributor",
  "StatementCollector", "StatementContext", "StatementParameters", "StatementService",
  "ActionType", "TriggerType", "ActionExecutor", "TriggerEvaluator", "StatementResult",
  "ParameterSchema", "ParameterSpec", "ParameterValue"
 )]
]

STATEMENTS_ADDON = """package addon;
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
STATEMENTS_HOST = """package host;
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


def statements_put(root, name, data):
    dest=root/name
    dest.parent.mkdir(parents=True,exist_ok=True)
    dest.write_text(data,encoding="utf-8")
    return dest


class StatementConformanceTest(unittest.TestCase):
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
        with tempfile.TemporaryDirectory(prefix="statement-contract-") as td:
            temp=Path(td)
            public=temp/"public"; addon=temp/"addon"; runtime=temp/"runtime"
            for path in (public,addon,runtime):path.mkdir()
            stubs=[statements_put(temp/"stub",name,content) for name,content in STATEMENTS_STUBS.items()]
            api=[STATEMENTS_SRC/name for name in STATEMENTS_PUBLIC]
            cmd=["javac","-d",str(public),*[str(s) for s in stubs],*[str(s) for s in api]]
            subprocess.run(cmd,check=True,capture_output=True,text=True)
            addon_source=statements_put(temp/"addon-src","addon/TestAddon.java",STATEMENTS_ADDON)
            subprocess.run(["javac","-cp",str(public),"-d",str(addon),str(addon_source)],check=True,capture_output=True,text=True)
            jar=temp/"StatementAddon.jar"
            subprocess.run(["jar","cf",str(jar),"-C",str(addon),"."],check=True,capture_output=True,text=True)
            host=statements_put(temp/"host-src","host/Run.java",STATEMENTS_HOST)
            backend=ROOT/"source-shared/src/main/java/buildcraft/lib/internal/api/v2/StatementServiceImpl.java"
            cp=os.pathsep.join((str(public),str(jar)))
            subprocess.run(["javac","-cp",cp,"-d",str(runtime),str(backend),str(host)],check=True,capture_output=True,text=True)
            subprocess.run(["java","-cp",os.pathsep.join((cp,str(runtime))),"host.Run"],check=True,capture_output=True,text=True)


GATE_EXTRA = {
 "buildcraft/lib/internal/core/render/ISprite.java": "package buildcraft.lib.internal.core.render; public interface ISprite {}",
 "net/minecraft/world/item/ItemStack.java": "package net.minecraft.world.item;public class ItemStack {public static final ItemStack EMPTY=new ItemStack();}",
 "net/minecraft/network/chat/Component.java": """package net.minecraft.network.chat;
 public record Component(String text){public static Component literal(String s){return new Component(s);}}""",
 "net/minecraft/nbt/CompoundTag.java": """package net.minecraft.nbt;
 import java.util.*;
 public class CompoundTag {
  private Map<String,Object> data=new HashMap<>();
  public void putString(String k,String v){data.put(k,v);}
  public String getString(String k){return (String)data.getOrDefault(k,"");}
  public void putByteArray(String k,byte[] v){data.put(k,v);}
  public byte[] getByteArray(String k){return (byte[])data.getOrDefault(k,new byte[0]);}
  public String toString(){return "{}";}
 }""",
 "net/minecraft/nbt/TagParser.java": """package net.minecraft.nbt;
 import com.mojang.brigadier.exceptions.CommandSyntaxException;
 public class TagParser { public static CompoundTag parseTag(String s)throws CommandSyntaxException{return new CompoundTag();}}""",
 "com/mojang/brigadier/exceptions/CommandSyntaxException.java": "package com.mojang.brigadier.exceptions;public class CommandSyntaxException extends Exception {}",
 "buildcraft/lib/internal/statement/IStatement.java": """package buildcraft.lib.internal.statement;
 import net.minecraft.network.chat.Component;
 import buildcraft.lib.internal.core.render.ISprite;
 public interface IStatement {
  String getUniqueTag();int maxParameters();int minParameters();
  IStatementParameter createParameter(int i);IStatement rotateLeft();IStatement[] getPossible();
  Component getDescription(); ISprite getSprite();
 }""",
 "buildcraft/lib/internal/statement/IStatementParameter.java": """package buildcraft.lib.internal.statement;
 import buildcraft.lib.internal.core.render.ISprite;
 import net.minecraft.network.chat.Component;
 import net.minecraft.world.item.ItemStack;
 import net.minecraft.nbt.CompoundTag;
 public interface IStatementParameter {
  String getUniqueTag(); ItemStack getItemStack();Component getDescription();ISprite getSprite();
  IStatementParameter onClick(IStatementContainer c,IStatement s,ItemStack stack,StatementMouseClick mouse);
  void writeToNbt(CompoundTag nbt);IStatementParameter rotateLeft();
  IStatementParameter[] getPossible(IStatementContainer source);
 }""",
 "buildcraft/lib/internal/statement/StatementMouseClick.java": "package buildcraft.lib.internal.statement;public class StatementMouseClick {}",
 "buildcraft/lib/internal/statement/IStatementContainer.java": """package buildcraft.lib.internal.statement;
 import net.minecraft.core.Direction;
 import net.minecraft.world.level.block.entity.BlockEntity;
 public interface IStatementContainer {
  BlockEntity getNeighbourTile(Direction side);
 }""",
 "buildcraft/lib/internal/statement/ITrigger.java": "package buildcraft.lib.internal.statement;public interface ITrigger extends IStatement {}",
 "buildcraft/lib/internal/statement/IAction.java": "package buildcraft.lib.internal.statement;public interface IAction extends IStatement {}",
 "buildcraft/lib/internal/statement/ITriggerInternal.java": """package buildcraft.lib.internal.statement;
 public interface ITriggerInternal extends ITrigger {boolean isTriggerActive(IStatementContainer c,IStatementParameter[] args);}""",
 "buildcraft/lib/internal/statement/IActionInternal.java": """package buildcraft.lib.internal.statement;
 public interface IActionInternal extends IAction {void actionActivate(IStatementContainer c,IStatementParameter[] args);}""",
 "buildcraft/lib/internal/statement/ITriggerInternalSided.java": """package buildcraft.lib.internal.statement;
 import net.minecraft.core.Direction;
 public interface ITriggerInternalSided extends ITrigger {boolean isTriggerActive(Direction s,IStatementContainer c,IStatementParameter[] args);}""",
 "buildcraft/lib/internal/statement/IActionInternalSided.java": """package buildcraft.lib.internal.statement;
 import net.minecraft.core.Direction;
 public interface IActionInternalSided extends IAction {void actionActivate(Direction s,IStatementContainer c,IStatementParameter[] args);}""",
 "buildcraft/lib/internal/statement/ITriggerExternal.java": """package buildcraft.lib.internal.statement;
 import net.minecraft.core.Direction;
 import net.minecraft.world.level.block.entity.BlockEntity;
 public interface ITriggerExternal extends ITrigger {boolean isTriggerActive(BlockEntity t,Direction s,IStatementContainer c,IStatementParameter[] args);}""",
 "buildcraft/lib/internal/statement/IActionExternal.java": """package buildcraft.lib.internal.statement;
 import net.minecraft.core.Direction;
 import net.minecraft.world.level.block.entity.BlockEntity;
 public interface IActionExternal extends IAction {void actionActivate(BlockEntity t,Direction s,IStatementContainer c,IStatementParameter[] args);}""",
 "buildcraft/lib/internal/statement/StatementManager.java": """package buildcraft.lib.internal.statement;
 import java.util.*;
 import net.minecraft.nbt.CompoundTag;
 import net.minecraft.network.FriendlyByteBuf;
 public class StatementManager {
  public static final Map<String,IStatement> statements=new HashMap<>();
  public interface IParameterReader {IStatementParameter readFromNbt(CompoundTag nbt);}
  public interface IParamReaderBuf {IStatementParameter readFromBuf(FriendlyByteBuf buffer)throws java.io.IOException;}
  public static void registerParameter(String id,IParameterReader reader) {}
  public static void registerParameter(String id,IParamReaderBuf reader) {}
 }""",
 "net/minecraft/network/FriendlyByteBuf.java": """package net.minecraft.network;
 import net.minecraft.nbt.CompoundTag;
 public class FriendlyByteBuf {public CompoundTag readNbt(){return new CompoundTag();}}""",
 "buildcraft/lib/statement/StatementTypeParam.java": """package buildcraft.lib.statement;
 import buildcraft.lib.internal.statement.*;
 import net.minecraft.nbt.CompoundTag;
 public class StatementTypeParam {
  public static final StatementTypeParam INSTANCE=new StatementTypeParam();
  public CompoundTag writeToNbt(IStatementParameter param){return new CompoundTag();}
  public IStatementParameter readFromNbt(CompoundTag tag){return null;}
 }""",
 "buildcraft/lib/statement/TriggerWrapper.java": """package buildcraft.lib.statement;
 import buildcraft.lib.internal.statement.*;
 import net.minecraft.core.Direction;
 public abstract class TriggerWrapper implements ITrigger {
  public static TriggerWrapper wrap(IStatement s,Direction side){return null;}
 }""",
 "buildcraft/lib/statement/ActionWrapper.java": """package buildcraft.lib.statement;
 import buildcraft.lib.internal.statement.*;
 import net.minecraft.core.Direction;
 public abstract class ActionWrapper implements IAction {
  public static ActionWrapper wrap(IStatement s,Direction side){return null;}
 }""",
 "buildcraft/api/v2/registry/ApiRegistry.java": """package buildcraft.api.v2.registry;
 import java.util.*;
 import net.minecraft.resources.ResourceLocation;
 public final class ApiRegistry<T> {
  private final Map<ResourceLocation,T> map=new LinkedHashMap<>();
  public T get(ResourceLocation id){return map.get(id);}
  public void register(ResourceLocation id,T value){map.put(id,value);}
  public void register(ResourceLocation id,T value,java.util.function.Supplier<String> owner){register(id,value);}
  public Collection<T> values(){return map.values();}
 }""",
 "buildcraft/api/v2/statement/ParameterType.java": """package buildcraft.api.v2.statement;
 import net.minecraft.resources.ResourceLocation;
 import buildcraft.api.v2.persistence.*;
 import java.util.*;
 public record ParameterType<T>(ResourceLocation id) {
  public ParameterType(ResourceLocation id,ApiCodec<T,OpaqueData> ignored){this(id);}
  public List<T> suggestions(StatementContext c){return List.of();}
  public ApiCodec<T,OpaqueData> codec(){return new ApiCodec<>(){
    public CodecResult<T> decode(OpaqueData d){return CodecResult.failure("stub");}
    public CodecResult<OpaqueData> encode(T t){return CodecResult.failure("stub");}
  };}
 }""",
}

GATE_HOST = """package host;
import addon.TestAddon;
import buildcraft.api.v2.*;
import buildcraft.api.v2.statement.*;
import buildcraft.api.v2.context.*;
import buildcraft.api.v2.permission.*;
import buildcraft.lib.internal.statement.*;
import buildcraft.lib.internal.statement.api2.StatementApi2Bridge;
import buildcraft.api.v2.gate.GateView;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.*;
public class RunBridge {
 static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
 static class Gate implements IStatementContainer,GateView {
  public BlockEntity getNeighbourTile(Direction side){return new BlockEntity();}
  public BlockPos position(){return new BlockPos(1,2,3);}
 }
 public static void main(String[] args){
  var trigger=TestAddon.sided();
  var action=TestAddon.external();
  BuildCraftApi.registry(BuildCraftRegistries.TRIGGER_TYPES).register(TestAddon.SIDE,trigger);
  BuildCraftApi.registry(BuildCraftRegistries.ACTION_TYPES).register(TestAddon.EXT,action);
  BuildCraftApi.registry(BuildCraftRegistries.STATEMENT_CONTRIBUTORS).register(
    new ResourceLocation("addon:contrib"),TestAddon.contributor());
  Gate gate=new Gate(); BlockEntity neighbour=new BlockEntity();
  var sided=StatementApi2Bridge.nativeInternalSidedTriggers(gate,Direction.NORTH);
  check(sided.size()==1,"sided statement advertised");
  var external=StatementApi2Bridge.nativeExternalActions(Direction.NORTH,neighbour);
  check(external.size()==1,"external action advertised");
  check(StatementApi2Bridge.nativeInternalTriggers(gate).isEmpty(),"scoped not internal");
  check(StatementApi2Bridge.nativeInternalActions(gate).isEmpty(),"external not internal");
  var parameters=new StatementParameters(Map.of(TestAddon.SLOT,
    new ParameterValue<>(new ParameterType<Integer>(TestAddon.TYPE),7)));
  IStatement nativeStatement=StatementApi2Bridge.ensureNativeAdapter(TestAddon.SIDE);
  IStatementParameter[] legacy=StatementApi2Bridge.toLegacyParameters(nativeStatement,parameters);
  check(sided.get(0).isTriggerActive(Direction.NORTH,gate,legacy),"sided runtime callback");
  check(sided.get(0).isTriggerActive(Direction.SOUTH,gate,legacy),"both valid sides");
  int before=TestAddon.calls;
  external.get(0).actionActivate(neighbour,Direction.WEST,gate,new IStatementParameter[0]);
  check(TestAddon.calls==before+1,"external runtime action with neighbour");
  var gateContext=StatementApi2Bridge.context(gate,Direction.WEST,OperationMode.EXECUTE);
  check(gateContext.views().get(StatementContexts.GATE).isPresent(),"public gate view");
  check(gateContext.views().get(StatementContexts.GATE_POSITION).orElseThrow().x()==1,"gate position");
  check(!StatementApi2Bridge.validateSlot(new StatementSlot(StatementKind.TRIGGER,TestAddon.SIDE,
      Direction.WEST,StatementParameters.EMPTY)),"reject missing arguments");
  check(StatementApi2Bridge.validateSlot(new StatementSlot(StatementKind.TRIGGER,TestAddon.SIDE,
      Direction.WEST,parameters)),"accept valid typed args");
 }
}"""

class GateBridgeConformanceTest(unittest.TestCase):
    def test_native_scoped_bridge_against_addon_jar(self):
        with tempfile.TemporaryDirectory(prefix="gate-bridge-") as td:
            base=Path(td);public=base/"public";addon=base/"addon";runtime=base/"runtime"
            for folder in (public,addon,runtime):folder.mkdir()
            stubs=dict(STATEMENTS_STUBS);stubs.update(GATE_EXTRA)
            sources=[statements_put(base/"stub",name,body) for name,body in stubs.items()]
            api=[STATEMENTS_SRC/name for name in STATEMENTS_PUBLIC]
            api.extend([STATEMENTS_SRC/"persistence/ApiCodec.java",STATEMENTS_SRC/"persistence/CodecResult.java",STATEMENTS_SRC/"persistence/OpaqueData.java",STATEMENTS_SRC/"statement/StatementKind.java",STATEMENTS_SRC/"statement/StatementSlot.java"])
            subprocess.run(["javac","-d",str(public),*[str(p) for p in sources],*[str(p) for p in api]],check=True,capture_output=True,text=True)
            addon_source=statements_put(base/"addon-src","addon/TestAddon.java",STATEMENTS_ADDON)
            subprocess.run(["javac","-cp",str(public),"-d",str(addon),str(addon_source)],check=True,capture_output=True,text=True)
            jar=base/"Addon.jar"
            subprocess.run(["jar","cf",str(jar),"-C",str(addon),"."],check=True,capture_output=True,text=True)
            host=statements_put(base/"host-src","host/RunBridge.java",GATE_HOST)
            bridge=ROOT/"source-shared/src/main/java/buildcraft/lib/internal/statement/api2/StatementApi2Bridge.java"
            cp=os.pathsep.join((str(public),str(jar)))
            try:
                subprocess.run(["javac","-cp",cp,"-d",str(runtime),str(bridge),str(host)],check=True,capture_output=True,text=True)
            except subprocess.CalledProcessError as error:
                self.fail(error.stderr)
            try:
                subprocess.run(["java","-cp",os.pathsep.join((cp,str(runtime))),"host.RunBridge"],check=True,capture_output=True,text=True)
            except subprocess.CalledProcessError as error:
                self.fail(error.stderr)

if __name__ == "__main__":
    unittest.main()
