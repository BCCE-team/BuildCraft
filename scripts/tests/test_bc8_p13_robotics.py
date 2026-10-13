#!/usr/bin/env python3
"""Independent addon robot-board execution plus BC8 robotics production wiring."""
from __future__ import annotations
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
API = ROOT / 'source-shared/src/main/java'

STUBS = {
 'net/minecraft/resources/ResourceLocation.java': '''package net.minecraft.resources;
 public record ResourceLocation(String namespace,String path){
 public ResourceLocation(String full){this(full.contains(":")?full.split(":",2)[0]:"minecraft",full.contains(":")?full.split(":",2)[1]:full);}
 public static ResourceLocation tryParse(String s){return s==null||s.isBlank()||s.contains(" ")?null:new ResourceLocation(s);}
 public static ResourceLocation fromNamespaceAndPath(String ns,String path){return new ResourceLocation(ns,path);}
 public String getNamespace(){return namespace;} public String getPath(){return path;}
 @Override public String toString(){return namespace+":"+path;}}''',
 'net/minecraft/nbt/CompoundTag.java': '''package net.minecraft.nbt;
 public class CompoundTag {private final java.util.Map<String,Object> a=new java.util.HashMap<>();
 public String getString(String key){return (String)a.getOrDefault(key,"");} public void putString(String k,String v){a.put(k,v);}
 public CompoundTag getCompound(String k){return (CompoundTag)a.getOrDefault(k,new CompoundTag());}
 public boolean contains(String k){return a.containsKey(k);}public boolean isEmpty(){return a.isEmpty();}}''',
 'net/minecraft/network/chat/Component.java': '''package net.minecraft.network.chat;
 public record Component(String content){public static Component translatable(String key,Object...args){return new Component(key);}}''',
 'net/minecraft/world/entity/player/Player.java':'package net.minecraft.world.entity.player;public class Player{}',
 'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
 public class ItemStack {private final net.minecraft.nbt.CompoundTag tag=new net.minecraft.nbt.CompoundTag();
 public net.minecraft.nbt.CompoundTag getTag(){return tag;}}''',
 'buildcraft/lib/misc/ItemStackUtil.java': '''package buildcraft.lib.misc;
 public class ItemStackUtil{public static net.minecraft.nbt.CompoundTag getCustomData(net.minecraft.world.item.ItemStack s){return s.getTag();}}''',
 'buildcraft/lib/BCLibConfig.java':'package buildcraft.lib;public class BCLibConfig{public static boolean hidePowerValues;}',
 'buildcraft/lib/misc/LocaleUtil.java':'package buildcraft.lib.misc;public class LocaleUtil{public static String localize(String key){return key;}}',
 'buildcraft/lib/internal/mj/MjFormatting.java':'package buildcraft.lib.internal.mj;public class MjFormatting{public static String formatMicroMj(long value){return Long.toString(value);}}',
 'buildcraft/api/v2/energy/MjAmount.java':'package buildcraft.api.v2.energy;public class MjAmount{public static final long MICRO_MJ_PER_MJ=1000000L;}',
 'buildcraft/robotics/BCRobotics.java':'package buildcraft.robotics;public class BCRobotics{public static final String MODID="buildcraftrobotics";}',
 'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java':'package buildcraft.robotics.internal.legacy.robots;public class EntityRobotBase{}',
 'buildcraft/robotics/internal/legacy/robots/AIRobot.java':'''package buildcraft.robotics.internal.legacy.robots;
 public class AIRobot {public final EntityRobotBase robot;public AIRobot(EntityRobotBase robot){this.robot=robot;}
 public boolean canLoadFromNBT(){return false;}}''',
 'buildcraft/robotics/internal/legacy/boards/IRedstoneBoard.java':'''package buildcraft.robotics.internal.legacy.boards;
 public interface IRedstoneBoard<T>{RedstoneBoardRobotNBT getNBTHandler();void updateBoard(T container);}''',
 'buildcraft/robotics/internal/legacy/boards/RedstoneBoardNBT.java':'''package buildcraft.robotics.internal.legacy.boards;
 import net.minecraft.nbt.CompoundTag;import net.minecraft.world.item.ItemStack;
 import net.minecraft.world.entity.player.Player;import net.minecraft.network.chat.Component;import java.util.List;
 public abstract class RedstoneBoardNBT<T> {
 public abstract String getID();public abstract void addInformation(ItemStack s,Player p,List<Component> info,boolean advanced);
 public abstract IRedstoneBoard<T> create(CompoundTag tag,T obj);
 public void createBoard(CompoundTag tag){tag.putString("id",getID());}}''',
 'buildcraft/api/v2/robot/RobotHandle.java':'package buildcraft.api.v2.robot;public interface RobotHandle{long id();}',
 'buildcraft/api/v2/robot/RobotTask.java':'package buildcraft.api.v2.robot;public interface RobotTask{net.minecraft.resources.ResourceLocation typeId();}',
 'buildcraft/api/v2/registry/RegistryEntry.java':'package buildcraft.api.v2.registry;public record RegistryEntry<T>(net.minecraft.resources.ResourceLocation id,T value){}',
 'buildcraft/api/v2/registry/ApiRegistry.java':'''package buildcraft.api.v2.registry;
 import java.util.*;import net.minecraft.resources.ResourceLocation;
 public class ApiRegistry<T> {
 private final Map<ResourceLocation,T> a=new LinkedHashMap<>();
 public void register(ResourceLocation id,T value){a.put(id,value);}public T get(ResourceLocation id){return a.get(id);}
 public Collection<RegistryEntry<T>> entries(){return a.entrySet().stream().map(e->new RegistryEntry<T>(e.getKey(),e.getValue())).toList();}}''',
 'buildcraft/api/v2/registry/RegistryKey.java':'''package buildcraft.api.v2.registry;public record RegistryKey<T>(String key){}''',
 'buildcraft/api/v2/BuildCraftRegistries.java':'''package buildcraft.api.v2;
 public class BuildCraftRegistries{
 public static final buildcraft.api.v2.registry.RegistryKey<buildcraft.api.v2.robot.RobotBoardType> ROBOT_BOARD_TYPES=new buildcraft.api.v2.registry.RegistryKey<>("types");
 public static final buildcraft.api.v2.registry.RegistryKey<buildcraft.api.v2.robot.RobotBoardProgram> ROBOT_BOARD_PROGRAMS=new buildcraft.api.v2.registry.RegistryKey<>("programs");}''',
 'buildcraft/api/v2/BuildCraftApi.java':'''package buildcraft.api.v2;
 public class BuildCraftApi{
 public static final buildcraft.api.v2.registry.ApiRegistry<buildcraft.api.v2.robot.RobotBoardType> types=new buildcraft.api.v2.registry.ApiRegistry<>();
 public static final buildcraft.api.v2.registry.ApiRegistry<buildcraft.api.v2.robot.RobotBoardProgram> programs=new buildcraft.api.v2.registry.ApiRegistry<>();
 @SuppressWarnings("unchecked")public static <T> buildcraft.api.v2.registry.ApiRegistry<T> registry(buildcraft.api.v2.registry.RegistryKey<T> key){return (buildcraft.api.v2.registry.ApiRegistry<T>)(key.key().equals("types")?types:programs);}}''',
 'buildcraft/api/v2/content/ContentRegistrar.java':'''package buildcraft.api.v2.content;
 import buildcraft.api.v2.robot.*;import buildcraft.api.v2.BuildCraftApi;
 public class ContentRegistrar{
 public void robotTask(Object type){} public RobotBoardType robotBoard(RobotBoardType type){BuildCraftApi.types.register(type.id(),type);return type;}
 public RobotBoardProgram robotBoardProgram(RobotBoardProgram program){BuildCraftApi.programs.register(program.id(),program);return program;}}''',
 'dev/bcce/apifixture/RobotTaskConformanceAddon.java':'''package dev.bcce.apifixture;
 import buildcraft.api.v2.robot.*;import net.minecraft.resources.ResourceLocation;
 public class RobotTaskConformanceAddon implements RobotTask{
 public static final ResourceLocation ID=ResourceLocation.tryParse("api_fixture:inspect_area");
 public static final Object TYPE=new Object();public RobotTaskConformanceAddon(int progress){}
 public ResourceLocation typeId(){return ID;}}''',
 'buildcraft/robotics/internal/api2/ApiTaskAI.java':'''package buildcraft.robotics.internal.api2;
 import buildcraft.api.v2.robot.*;import buildcraft.robotics.internal.legacy.robots.*;
 public class ApiTaskAI extends AIRobot{
 public static RobotTask lastTask;public ApiTaskAI(EntityRobotBase r,RobotTask task){super(r);lastTask=task;}
 public static boolean canAssign(RobotTask task){return task!=null;}}''',
 'buildcraft/robotics/internal/api2/RobotServiceImpl.java':'''package buildcraft.robotics.internal.api2;
 import buildcraft.api.v2.robot.*;import buildcraft.robotics.internal.legacy.robots.*;
 public class RobotServiceImpl{public static RobotHandle view(EntityRobotBase robot){return ()->77;}}''',
 'buildcraft/lib/internal/debug/BCLog.java':'''package buildcraft.lib.internal.debug;
 public class BCLog{public static final Logger logger=new Logger();public static class Logger{public void warn(String s,Throwable t){}}}''',
}
BOARD_NAMES = ('Picker','Carrier','FluidCarrier','Lumberjack','Harvester','Miner','Planter','Farmer',
               'LeaveCutter','Butcher','Shovelman','Pump','Delivery','Knight','Bomber','Stripes','Builder')
for name in BOARD_NAMES:
 STUBS[f'buildcraft/robotics/boards/BoardRobot{name}.java'] = f'''package buildcraft.robotics.boards;
 import buildcraft.robotics.internal.legacy.boards.*;import buildcraft.robotics.internal.legacy.robots.*;
 public class BoardRobot{name} extends RedstoneBoardRobot{{public BoardRobot{name}(EntityRobotBase r){{super(r);}}
 public RedstoneBoardRobotNBT getNBTHandler(){{return null;}}}}'''

MAIN = '''package check;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.robot.*;
import buildcraft.robotics.internal.api2.ApiTaskAI;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import net.minecraft.resources.ResourceLocation;
import java.util.Set;
public class Check {
 static void ok(boolean b,String reason){if(!b)throw new AssertionError(reason);}
 record Task(ResourceLocation typeId) implements RobotTask{}
 public static void main(String[] a){
  ResourceLocation board=ResourceLocation.tryParse("fixture:expedition");
  ResourceLocation task=ResourceLocation.tryParse("fixture:expedition_task");
  BCRoboticsBoards.init();
  int builtins=BCRoboticsBoards.robotEntries().size();
  String code="";
  var fixture=new buildcraft.api.v2.content.ContentRegistrar();
  dev.bcce.apifixture.RobotBoardFixtureAddon.register(fixture);
  var addonBoard=BCRoboticsBoards.getById(dev.bcce.apifixture.RobotBoardFixtureAddon.BOARD_ID.toString());
  ok(addonBoard.energyCost()==3200,"fixture board not registered");
  ok(addonBoard.nbt().create(new EntityRobotBase()).createWorkAI() instanceof ApiTaskAI,"addon board task not created");
  ok(ApiTaskAI.lastTask.typeId().equals(dev.bcce.apifixture.RobotTaskConformanceAddon.ID),"addon task id wrong");
  BuildCraftApi.types.register(board,new RobotBoardType(board,2,Set.of(task)));
  BuildCraftApi.programs.register(board,new RobotBoardProgram(board,8192,"yellow",
    ResourceLocation.tryParse("fixture:textures/entity/robot.png"),r->{ok(r.id()==77,"wrong robot handle");return new Task(task);}));
  var found=BCRoboticsBoards.getById(board.toString());
  ok(found.energyCost()==8192,"MJ cost lost");
  ok(found.robotTextureLocation().toString().equals("fixture:textures/entity/robot.png"),"texture lost");
  ok(BCRoboticsBoards.robotEntries().size()==builtins+2,"late registry registration unavailable");
  ok(BCRoboticsBoards.getByKey("fixture.expedition")==found,"board key not linked");
  ok(buildcraft.robotics.internal.legacy.boards.RedstoneBoardRegistry.instance.getEnergyCost(found.nbt())==8192,"native programming cost missing");
  var ai=found.nbt().create(new EntityRobotBase()).createWorkAI();
  ok(ai instanceof ApiTaskAI,"API registered board not executed as task");
  ok(ApiTaskAI.lastTask.typeId().equals(task),"task type mismatched");
  String unknown="missingaddon:removed";
  ok(BCRoboticsBoards.getById(unknown).id().equals(unknown),"unknown board id destroyed");
  ok(BCRoboticsBoards.getById("broken id")==BCRoboticsBoards.EMPTY,"invalid id accepted");
  try {new RobotBoardProgram(board,0,"yellow",board,r->new Task(task));throw new AssertionError("free programming");}
  catch(IllegalArgumentException expected){}
  try {new RobotBoardProgram(board,1,"ultraviolet",board,r->new Task(task));throw new AssertionError("bad color");}
  catch(IllegalArgumentException expected){}
  ResourceLocation badBoard=ResourceLocation.tryParse("fixture:bad");
  BuildCraftApi.types.register(badBoard,new RobotBoardType(badBoard,1,Set.of(task)));
  BuildCraftApi.programs.register(badBoard,new RobotBoardProgram(badBoard,1,"blue",badBoard,r->new Task(board)));
  ok(BCRoboticsBoards.getById(badBoard.toString()).nbt().create(new EntityRobotBase()).createWorkAI()==null,
    "board ran task that was not allowed");
  System.out.println("BC8-P13 dynamic addon board, programming MJ, task factory and missing board preservation PASS");
 }
}'''

class P13RoboticsTests(unittest.TestCase):
 def test_actual_board_registry_and_task_runtime(self):
  if not shutil.which('javac') or not shutil.which('java'): self.skipTest('Java toolchain absent')
  with tempfile.TemporaryDirectory(prefix='bc8-p13-') as tmpname:
   tmp=Path(tmpname)
   for name,code in STUBS.items():
    f=tmp/name; f.parent.mkdir(parents=True,exist_ok=True);f.write_text(code)
   harness=tmp/'check/Check.java';harness.parent.mkdir(parents=True,exist_ok=True);harness.write_text(MAIN)
   shared=[
      API/'buildcraft/api/v2/robot/RobotBoardType.java',
      API/'buildcraft/api/v2/robot/RobotBoardProgram.java',
      API/'buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobot.java',
      API/'buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobotNBT.java',
      API/'buildcraft/robotics/internal/legacy/boards/RedstoneBoardRegistry.java',
   ]
   fixture=ROOT/'addon-fixture/src/main/java/dev/bcce/apifixture/RobotBoardFixtureAddon.java'
   addon_source=fixture.read_text()
   self.assertNotIn('buildcraft.robotics.',addon_source)
   self.assertNotIn('buildcraft.lib.',addon_source)
   for family in ('old','1.21.X'):
    board=ROOT/f'source-families/{family}/src/main/java/buildcraft/robotics/BCRoboticsBoards.java'
    args=['javac','-d',str(tmp/family),*(str(tmp/k) for k in STUBS),*(str(s) for s in shared),str(board),str(harness),str(fixture)]
    compiled=subprocess.run(args,capture_output=True,text=True,timeout=45)
    self.assertEqual(compiled.returncode,0,f'{family}: {compiled.stderr}')
    run=subprocess.run(['java','-cp',str(tmp/family),'check.Check'],capture_output=True,text=True,timeout=25)
    self.assertEqual(run.returncode,0,f'{family}: {run.stdout}\n{run.stderr}')

 def test_p13_production_wiring_and_no_internal_addon_surface(self):
  registrations=(API/'buildcraft/api/v2/BuildCraftRegistries.java').read_text()
  self.assertIn('ROBOT_BOARD_PROGRAMS = key(',registrations)
  self.assertIn('robotBoardProgram(RobotBoardProgram', (API/'buildcraft/api/v2/content/ContentRegistrar.java').read_text())
  for place in ('source-shared','source-families/26.X','source-family-platforms/26.X/neoforge'):
   runtime=ROOT/place/'src/main/java/buildcraft/lib/internal/api/v2/BuildCraftApiRuntime.java'
   self.assertIn('registerRegistry(BuildCraftRegistries.ROBOT_BOARD_PROGRAMS)',runtime.read_text())
  main=(API/'buildcraft/robotics/ai/AIRobotMain.java').read_text()
  self.assertIn('board.createWorkAI()',main)
  for family in ('old','1.21.X'):
   table=ROOT/f'source-{"platforms/forge" if family=="old" else "families/1.21.X"}/src/main/java/buildcraft/silicon/tile/TileProgrammingTable_Neptune.java'
   s=table.read_text()
   for expected in ('selectedPage * OPTION_COUNT + index','selectedPage * OPTION_COUNT + selectedOption',
                    'buffer.writeVarInt(selectedPage)', 'selectedPage = buffer.readVarInt()', 'selectedPage = 0;',
                    'public int pageCount()'):
    self.assertIn(expected,s)
  for file in ROOT.rglob('GuiProgrammingTable.java'):
   if 'build' not in file.parts:
    self.assertIn('container.sendSelectPage(',file.read_text(),str(file))
  for place in ('source-platforms/forge','source-platforms/neoforge',
                'source-family-platforms/26.X/neoforge','source-downports/26.X/26.1.2/neoforge',
                'source-downports/26.X/26.2/neoforge'):
   runtime=ROOT/place/'src/main/java/buildcraft/robotics/internal/api2/RobotServiceImpl.java'
   s=runtime.read_text()
   self.assertIn('lease.robotId() == robotId',s)
   self.assertIn('Robot dock port resolver failed:',s)
   self.assertIn('Robot event listener failed:',s)
  task=(API/'buildcraft/api/v2/robot/RobotTaskContext.java').read_text()
  for term in ('reserveRequest(', 'cancelRequest(', 'deliverRequest('):self.assertIn(term,task)

if __name__=='__main__':unittest.main()
