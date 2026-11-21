#!/usr/bin/env python3
"""Compile the actual Pump boards on all loaders and exercise central work eligibility."""
from __future__ import annotations
import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.source_preprocessor import preprocess_text
from scripts.source_config import load_properties, target_layout
from scripts.source_layout import resolve_effective_source

ROOT = Path(__file__).resolve().parents[2]
REL = 'src/main/java/buildcraft/robotics/boards/BoardRobotPump.java'
SHARED = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/ProcessorCandidateBoard.java'


def stubs(fluid: str, action: str) -> dict[str,str]:
    parts = fluid.replace('.', '/')
    top = dict()
    top['net/minecraft/core/BlockPos.java'] = '''package net.minecraft.core;
public record BlockPos(int x,int y,int z){ public long asLong(){return x*1000000L+z*1000L+y;} }
'''
    top['net/minecraft/world/level/material/FluidState.java'] = '''package net.minecraft.world.level.material;
public class FluidState{private final boolean empty;public FluidState(boolean empty){this.empty=empty;}
 public boolean isEmpty(){return empty;}}
'''
    top['net/minecraft/world/level/block/state/BlockState.java'] = '''package net.minecraft.world.level.block.state;
import net.minecraft.world.level.material.FluidState;
public class BlockState{private final boolean fluid;public BlockState(boolean f){fluid=f;}
 public FluidState getFluidState(){return new FluidState(!fluid);}}
'''
    top[parts+'.java'] = f'''package {fluid.rsplit('.',1)[0]};
public class {fluid.rsplit('.',1)[1]}{{
 public final int amount;public final int kind;
 public {fluid.rsplit('.',1)[1]}(int kind,int amount){{this.kind=kind;this.amount=amount;}}
 public boolean isEmpty(){{return amount<=0;}}
 public int getAmount(){{return amount;}}
}}'''
    package, suffix = action.rsplit('.',1)
    if suffix == 'FluidAction' and 'IFluidHandler.' in action:
        package = action.rsplit('.IFluidHandler.',1)[0]
        top[package.replace('.','/')+'/IFluidHandler.java'] = f'package {package}; public interface IFluidHandler {{ enum FluidAction {{SIMULATE,EXECUTE}} }}'
    else:
        top[action.replace('.','/')+'.java'] = f'package {package};public enum {suffix} {{SIMULATE,EXECUTE}}'
    top['net/minecraft/world/level/Level.java'] = f'''package net.minecraft.world.level;
import net.minecraft.core.BlockPos;import net.minecraft.world.level.block.state.BlockState;
import {fluid};import java.util.*;
public class Level {{public final Map<BlockPos,FluidStack> fluid=new HashMap<>();
 public boolean isLoaded(BlockPos pos){{return true;}}
 public BlockState getBlockState(BlockPos pos){{return new BlockState(fluid.containsKey(pos));}}
}}'''
    top['net/minecraft/nbt/CompoundTag.java'] = '''package net.minecraft.nbt;
import java.util.*;public class CompoundTag{public final Map<String,Object> map=new HashMap<>();
 public void put(String s,CompoundTag t){map.put(s,t);}
 public boolean contains(String s){return map.containsKey(s);}
 public CompoundTag getCompound(String s){return (CompoundTag)map.get(s);}}
'''
    top['buildcraft/lib/compat/NbtCompat.java'] = '''package buildcraft.lib.compat;
import net.minecraft.nbt.CompoundTag;
public class NbtCompat {public static CompoundTag getCompound(CompoundTag t,String name){return t.getCompound(name);}}
'''
    top['buildcraft/lib/internal/core/BlockIndex.java'] = '''package buildcraft.lib.internal.core;
import net.minecraft.core.BlockPos;import net.minecraft.nbt.CompoundTag;
public class BlockIndex {
 private final BlockPos at;public BlockIndex(BlockPos at){this.at=at;}
 public BlockIndex(CompoundTag tag){at=new BlockPos(1,2,3);}public BlockPos toBlockPos(){return at;}
 public void writeTo(CompoundTag tag){}
}'''
    top['buildcraft/lib/internal/core/IFluidFilter.java'] = f'''package buildcraft.lib.internal.core;
import {fluid};
public interface IFluidFilter{{boolean matches(FluidStack fluid);}}
'''
    top['buildcraft/lib/inventory/filter/PassThroughFluidFilter.java'] = f'''package buildcraft.lib.inventory.filter;
import {fluid};import buildcraft.lib.internal.core.IFluidFilter;
public class PassThroughFluidFilter implements IFluidFilter{{public boolean matches(FluidStack f){{return true;}}}}
'''
    top['buildcraft/lib/misc/BlockUtil.java'] = f'''package buildcraft.lib.misc;
import {fluid};import net.minecraft.core.BlockPos;import net.minecraft.world.level.Level;
public class BlockUtil{{ public static FluidStack drainBlock(Level l,BlockPos p,boolean real){{
 FluidStack f=l.fluid.getOrDefault(p,new FluidStack(0,0));if(real)l.fluid.remove(p);return f;
}}}}
'''
    top['buildcraft/robotics/internal/legacy/robots/ResourceIdBlock.java'] = '''package buildcraft.robotics.internal.legacy.robots;
public record ResourceIdBlock(net.minecraft.core.BlockPos at){
 public ResourceIdBlock(buildcraft.lib.internal.core.BlockIndex index){this(index.toBlockPos());}}
'''
    top['buildcraft/robotics/internal/legacy/robots/IRobotRegistry.java'] = '''package buildcraft.robotics.internal.legacy.robots;
import java.util.*;import net.minecraft.core.BlockPos;
public class IRobotRegistry {
 public final Map<BlockPos,Long> claims=new HashMap<>();
 public boolean isTaken(ResourceIdBlock resource){return claims.containsKey(resource.at());}
 public long robotIdTaking(ResourceIdBlock resource){return claims.getOrDefault(resource.at(),0L);}
 public void release(ResourceIdBlock resource){claims.remove(resource.at());}
}
'''
    top['buildcraft/robotics/internal/legacy/robots/AIRobot.java'] = '''package buildcraft.robotics.internal.legacy.robots;
public class AIRobot {
 public AIRobot(EntityRobotBase robot){}public boolean success(){return true;}
}
'''
    top['buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java'] = f'''package buildcraft.robotics.internal.legacy.robots;
import {fluid};import {action};import net.minecraft.world.level.Level;
public class EntityRobotBase {{
 public final Level level;public final IRobotRegistry registry=new IRobotRegistry();
 public int capacity=2000,used=0;
 public EntityRobotBase(Level level){{this.level=level;}}
 public FluidStack getFluidInTank(int slot){{return new FluidStack(1,used);}}
 public int getTankCapacity(int slot){{return capacity;}}
 public int fill(FluidStack fluid,FluidAction action){{return Math.min(Math.max(0,capacity-used),fluid.amount);}}
 public IRobotRegistry getRegistry(){{return registry;}}
 public Object getLinkedStation(){{return null;}}
 public long getRobotId(){{return 42L;}}
}}
'''
    top['buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobotNBT.java'] = '''package buildcraft.robotics.internal.legacy.boards;
public record RedstoneBoardRobotNBT(String id){public String getID(){return id;}}
'''
    top['buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobot.java'] = '''package buildcraft.robotics.internal.legacy.boards;
import buildcraft.robotics.internal.legacy.robots.*;import net.minecraft.nbt.CompoundTag;
public class RedstoneBoardRobot extends AIRobot {
 protected final EntityRobotBase robot;
 public RedstoneBoardRobot(EntityRobotBase robot){super(robot);this.robot=robot;}
 public RedstoneBoardRobotNBT getNBTHandler(){return new RedstoneBoardRobotNBT("pump");}
 public void update(){}public void delegateAIEnded(AIRobot ai){}public void end(){}
 public void startDelegateAI(AIRobot ai){}
 public boolean canLoadFromNBT(){return true;}
 public void writeSelfToNBT(CompoundTag nbt){}public void loadSelfFromNBT(CompoundTag nbt){}
}
'''
    top['buildcraft/robotics/BCRoboticsBoards.java'] = '''package buildcraft.robotics;
public class BCRoboticsBoards {
 public static Entry getByKey(String key){return new Entry();}
 public record Entry(){public buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT nbt(){
 return new buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT("buildcraft:pump");}}
}
'''
    top['buildcraft/robotics/statements/ActionRobotFilter.java'] = '''package buildcraft.robotics.statements;
import buildcraft.lib.internal.core.IFluidFilter;
public class ActionRobotFilter{
 public static IFluidFilter filter;
 public static IFluidFilter getGateFluidFilter(Object station){return filter;}}
'''
    top['buildcraft/robotics/scheduler/RobotProcessorWorkScheduler.java'] = '''package buildcraft.robotics.scheduler;
import net.minecraft.core.BlockPos;import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
public class RobotProcessorWorkScheduler{
 public static int completions;public static boolean lastSuccess;
 public static void executionFinished(EntityRobotBase robot,BlockPos pos,boolean success){completions++;lastSuccess=success;}
}
'''
    top['buildcraft/robotics/ai/AIRobotSearchAndGotoBlock.java'] = '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;import buildcraft.lib.internal.core.BlockIndex;
import net.minecraft.core.BlockPos;import net.minecraft.world.level.Level;
public class AIRobotSearchAndGotoBlock extends AIRobot{
 public interface Filter{boolean matches(Level level,BlockPos p);}
 public AIRobotSearchAndGotoBlock(EntityRobotBase robot,boolean random,Filter f){super(robot);}
 public BlockIndex getBlockFound(){return new BlockIndex(new BlockPos(1,2,3));}
}
'''
    for clazz in ('AIRobotGotoSleep','AIRobotGotoStationAndUnloadFluids','AIRobotPumpBlock'):
        top[f'buildcraft/robotics/ai/{clazz}.java'] = f'''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;import buildcraft.lib.internal.core.BlockIndex;
public class {clazz} extends AIRobot{{
 public {clazz}(EntityRobotBase r){{super(r);}}
 public {clazz}(EntityRobotBase r,BlockIndex i){{super(r);}}
}}
'''
    return top

SCENARIO = '''package fixture;
import net.minecraft.world.level.Level;import net.minecraft.core.BlockPos;
import buildcraft.robotics.boards.BoardRobotPump;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.scheduler.RobotProcessorWorkScheduler;
import buildcraft.robotics.ai.AIRobotPumpBlock;
import buildcraft.robotics.ai.AIRobotSearchAndGotoBlock;
import buildcraft.robotics.statements.ActionRobotFilter;
import buildcraft.lib.internal.core.IFluidFilter;
import FLUID;
public class PumpFixture {
 static void ck(boolean v,String msg){if(!v)throw new AssertionError(msg);}
 public static void main(String[]args){
  Level world=new Level();BlockPos source=new BlockPos(1,2,3);
  world.fluid.put(source,new FluidStack(1,1000));
  EntityRobotBase robot=new EntityRobotBase(world);BoardRobotPump board=new BoardRobotPump(robot);
  ck(board.processorSupportsQueue(),"Not queued");
  ck(board.processorSharedScanPredicate(),"Scanner should share fluid palette");
  ck(board.processorPaletteMightContain(world.getBlockState(source)),"No fluid in palette");
  ck(!board.processorPaletteMightContain(world.getBlockState(new BlockPos(2,2,2))),"Empty blocks scanned");
  ck(board.processorDiscoveryCandidate(world,source),"Fluid source not discovered");
  ck(board.processorTargetEligible(world,source),"Available source not eligible");
  robot.registry.claims.put(source,42L);
  ck(board.processorPlanningEligible(world,source),"Self owned active fluid claim dropped");
  robot.registry.claims.put(source,77L);
  ck(!board.processorTargetEligible(world,source),"Foreign claimed source offered");
  robot.registry.claims.clear();robot.used=1500;
  ck(!board.processorTargetEligible(world,source),"Tank overflow ignored");
  robot.used=0;
  ActionRobotFilter.filter=fluid -> fluid.kind==2;
  ck(!board.processorTargetEligible(world,source),"Gate filter ignored");
  ActionRobotFilter.filter=null;
  board.delegateAIEnded(new AIRobotSearchAndGotoBlock(robot,false,(l,p)->true));
  board.delegateAIEnded(new AIRobotPumpBlock(robot));
  ck(RobotProcessorWorkScheduler.completions==1 && RobotProcessorWorkScheduler.lastSuccess,
   "Actual pump did not report completion");
  ck(robot.registry.claims.isEmpty(),"Claim not released on finish");
  System.out.println("PASS: catalog, fluid palette, gate, tank, own claims, executor completion");
 }
}'''


class PumpProfessionTests(unittest.TestCase):
    def test_real_pump_boards_five_targets(self):
        matrix = [('1.19.2-forge', 'net.minecraftforge.fluids.FluidStack',
                   'net.minecraftforge.fluids.capability.IFluidHandler.FluidAction'),
                  ('1.20.1-forge', 'net.minecraftforge.fluids.FluidStack',
                   'net.minecraftforge.fluids.capability.IFluidHandler.FluidAction'),
                  ('1.21.11-neoforge', 'net.neoforged.neoforge.fluids.FluidStack',
                   'net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction'),
                  ('26.3-neoforge', 'net.neoforged.neoforge.fluids.FluidStack',
                   'buildcraft.transport.internal.pipe.FluidAction'),
                  ('26.3-fabric', 'buildcraft.lib.fluid.FluidStack',
                   'buildcraft.transport.internal.pipe.FluidAction')]
        props = load_properties()
        for name, fluid, action in matrix:
            with self.subTest(target=name), tempfile.TemporaryDirectory(prefix='bc-pump-stage21-') as tmp:
                out = Path(tmp)
                layout = target_layout(name, props)
                path = resolve_effective_source(layout, props, REL)
                self.assertIsNotNone(path, name)
                rendered = preprocess_text(path.read_text(),
                      minecraft=props[f'target.{name}.deps.minecraft'], family=layout.family,
                      platform=layout.platform, source=str(path))
                sources = []
                for rel, content in stubs(fluid, action).items():
                    f = out / rel
                    f.parent.mkdir(parents=True, exist_ok=True)
                    f.write_text(content)
                    sources.append(str(f))
                f = out / 'buildcraft/robotics/boards/BoardRobotPump.java'
                f.parent.mkdir(parents=True,exist_ok=True)
                f.write_text(rendered)
                sources.append(str(f))
                f = out / 'buildcraft/robotics/scheduler/ProcessorCandidateBoard.java'
                f.parent.mkdir(parents=True,exist_ok=True)
                f.write_text(SHARED.read_text())
                sources.append(str(f))
                f = out / 'fixture/PumpFixture.java'
                f.parent.mkdir(parents=True,exist_ok=True)
                f.write_text(SCENARIO.replace('FLUID',fluid))
                sources.append(str(f))
                comp = subprocess.run(['javac','--release','17','-d',str(out/'classes'),*sources],
                          capture_output=True,text=True,timeout=45)
                self.assertEqual(comp.returncode,0,comp.stdout+comp.stderr)
                run = subprocess.run(['java','-cp',str(out/'classes'),'fixture.PumpFixture'],
                          capture_output=True,text=True,timeout=15)
                self.assertEqual(run.returncode,0,run.stdout+run.stderr)


if __name__ == '__main__': unittest.main()
