#!/usr/bin/env python3
"""BC8-P10 executes scoped statements through the actual internal Gate bridge."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_bc8_p10_statements import ROOT, SRC, STUBS, PUBLIC, put, ADDON

EXTRA = {
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
# Extension above reuses P10 public addon, including its independent JAR.
HOST = """package host;
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

class BridgeP10Tests(unittest.TestCase):
    def test_native_scoped_bridge_against_addon_jar(self):
        with tempfile.TemporaryDirectory(prefix="p10-bridge-") as td:
            base=Path(td);public=base/"public";addon=base/"addon";runtime=base/"runtime"
            for folder in (public,addon,runtime):folder.mkdir()
            stubs=dict(STUBS);stubs.update(EXTRA)
            sources=[put(base/"stub",name,body) for name,body in stubs.items()]
            api=[SRC/name for name in PUBLIC]
            api.extend([SRC/"persistence/ApiCodec.java",SRC/"persistence/CodecResult.java",SRC/"persistence/OpaqueData.java",SRC/"statement/StatementKind.java",SRC/"statement/StatementSlot.java"])
            subprocess.run(["javac","-d",str(public),*[str(p) for p in sources],*[str(p) for p in api]],check=True,capture_output=True,text=True)
            addon_source=put(base/"addon-src","addon/TestAddon.java",ADDON)
            subprocess.run(["javac","-cp",str(public),"-d",str(addon),str(addon_source)],check=True,capture_output=True,text=True)
            jar=base/"Addon.jar"
            subprocess.run(["jar","cf",str(jar),"-C",str(addon),"."],check=True,capture_output=True,text=True)
            host=put(base/"host-src","host/RunBridge.java",HOST)
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

if __name__ == "__main__":unittest.main()
