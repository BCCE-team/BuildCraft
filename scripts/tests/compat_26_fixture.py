"""Compile and execute the actual 26.3 compatibility capability transformer with typed API doubles."""
from __future__ import annotations

from pathlib import Path
import subprocess
import tempfile

STUBS={
'net/minecraft/core/Direction':'''package net.minecraft.core;
public enum Direction {NORTH,SOUTH,EAST,WEST,UP,DOWN}''',
'net/minecraft/core/BlockPos':'''package net.minecraft.core;
public record BlockPos(int x,int y,int z) {}''',
'net/neoforged/neoforge/capabilities/BlockCapability':'''package net.neoforged.neoforge.capabilities;
public final class BlockCapability<T,S> {public final String name;public BlockCapability(String name){this.name=name;}}''',
'net/neoforged/neoforge/transfer/fluid/FluidResource':'''package net.neoforged.neoforge.transfer.fluid;public record FluidResource(String id) {}''',
'net/neoforged/neoforge/transfer/ResourceHandler':'''package net.neoforged.neoforge.transfer;
public interface ResourceHandler<T> { int size();}''',
'net/neoforged/neoforge/capabilities/Capabilities':'''package net.neoforged.neoforge.capabilities;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
public final class Capabilities {public static final class Fluid {
 public static final BlockCapability<ResourceHandler<FluidResource>,Direction> BLOCK=new BlockCapability<>("fluid");}}''',
'net/minecraft/world/level/Level':'''package net.minecraft.world.level;
import net.minecraft.core.*;
import net.neoforged.neoforge.capabilities.*;
import java.util.*;
public class Level {public int lookups;
private final Map<BlockCapability<?,Direction>,Object> data=new HashMap<>();
public <T> void store(BlockCapability<T,Direction> cap,T value){data.put(cap,value);}
@SuppressWarnings("unchecked") public <T> T getCapability(BlockCapability<T,Direction> cap,BlockPos pos,Direction side){lookups++;return (T)data.get(cap);}
}''',
'net/minecraft/world/level/block/entity/BlockEntity':'''package net.minecraft.world.level.block.entity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
public class BlockEntity {
private final Level level;private boolean removed;private final BlockPos position=new BlockPos(4,6,8);
public BlockEntity(Level level){this.level=level;}
public Level getLevel(){return level;}
public BlockPos getBlockPos(){return position;}
public boolean isRemoved(){return removed;}
public void remove(){removed=true;}
}''',
'buildcraft/lib/misc/CapUtil':'''package buildcraft.lib.misc;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.BlockCapability;
public final class CapUtil {
public static final BlockCapability<Object,Direction> CAP_FLUIDS=new BlockCapability<>("old_fluid");
public static final BlockCapability<Object,Direction> CAP_ITEMS=new BlockCapability<>("old_item");
public static final BlockCapability<Object,Direction> CAP_FE=new BlockCapability<>("old_fe");
public static Object getFluidHandler(Level level,BlockPos pos,Direction side){return level.getCapability(CAP_FLUIDS,pos,side);}
public static Object getItemHandler(Level level,BlockPos pos,Direction side){return level.getCapability(CAP_ITEMS,pos,side);}
public static Object getEnergyStorage(Level level,BlockPos pos,Direction side){return level.getCapability(CAP_FE,pos,side);}
}''',
'buildcraft/compat/Harness':'''package buildcraft.compat;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.entity.*;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.capabilities.*;
import buildcraft.lib.misc.CapUtil;
import java.util.*;
public class Harness {
static int checks=0;static void yes(boolean cond){checks++;if(!cond)throw new AssertionError("check "+checks);}
static ResourceHandler<FluidResource> fluid(int size){return ()->size;}
static final class Machine extends BlockEntity {Machine(Level l){super(l);}}
static final class OtherMachine extends BlockEntity {OtherMachine(Level l){super(l);}}
public static void main(String[]args){
 var singleton=CompatCapTransfromer.INSTANCE;
 var level=new Level();var robot=new Machine(level);var other=new OtherMachine(level);
 var nativeHandler=fluid(5);
 level.store(Capabilities.Fluid.BLOCK,nativeHandler);
 yes(singleton.transfromFluidCap(robot,Direction.NORTH)==nativeHandler);
 yes(singleton.transfromFluidCap(other,Direction.SOUTH)==nativeHandler);
 yes(singleton.getCap(robot,Capabilities.Fluid.BLOCK,Direction.EAST).orElse(null)==nativeHandler);
 yes(singleton.getCap(other,Capabilities.Fluid.BLOCK,Direction.SOUTH).orElse(null)==nativeHandler);
 yes(singleton.transfromFluidCap(null,Direction.SOUTH)==null);
 robot.remove();yes(singleton.transfromFluidCap(robot,Direction.SOUTH)==null);
 yes(singleton.getCap(new BlockEntity(null),Capabilities.Fluid.BLOCK,Direction.SOUTH).isEmpty());
 // Typed class transformer gets first chance, but may return null to allow fallback.
 var local=fluid(2);
 singleton.registryFluidCapTransform(Machine.class,(obj,face)->local);
 var alive=new Machine(level);
 yes(singleton.transfromFluidCap(alive,Direction.NORTH)==local);
 yes(singleton.getCap(alive,Capabilities.Fluid.BLOCK,Direction.NORTH).orElse(null)==local);
 // Fallback applies to unregistered types, and retains the capability's resource identity.
 var fallback=fluid(3);
 singleton.registerFluidCapFallback((obj,face)->obj instanceof OtherMachine?fallback:null);
 yes(singleton.transfromFluidCap(other,Direction.DOWN)==fallback);
 yes(singleton.getCap(other,Capabilities.Fluid.BLOCK,Direction.DOWN).orElse(null)==fallback);
 yes(singleton.getCap(alive,Capabilities.Fluid.BLOCK,Direction.SOUTH).orElse(null)==local);
 yes(singleton.getCap(other,Capabilities.Fluid.BLOCK,Direction.SOUTH).orElse(null)!=nativeHandler);
 var legacyItem=new Object();var legacyFluid=new Object();var legacyEnergy=new Object();
 level.store(CapUtil.CAP_ITEMS,legacyItem);level.store(CapUtil.CAP_FLUIDS,legacyFluid);
 level.store(CapUtil.CAP_FE,legacyEnergy);
 yes(singleton.getCap(other,CapUtil.CAP_ITEMS,Direction.NORTH).orElse(null)==legacyItem);
 yes(singleton.getCap(other,CapUtil.CAP_FLUIDS,Direction.NORTH).orElse(null)==legacyFluid);
 yes(singleton.getCap(other,CapUtil.CAP_FE,Direction.NORTH).orElse(null)==legacyEnergy);
 var random=new BlockCapability<Object,Direction>("random");var value=new Object();level.store(random,value);
 yes(singleton.getCap(other,random,Direction.NORTH).orElse(null)==value);
 yes(level.lookups>=8);
 System.out.println(checks+" assertions PASS");
}}
'''
}

def execute(java_file: Path) -> str:
    with tempfile.TemporaryDirectory(prefix='bc-compat-native-') as tmp:
        root=Path(tmp)/'src'
        sources=[]
        for key,text in STUBS.items():
            dst=root/(key+'.java')
            dst.parent.mkdir(parents=True,exist_ok=True)
            dst.write_text(text,encoding='utf-8')
            sources.append(str(dst))
        impl=root/'buildcraft/compat/CompatCapTransfromer.java'
        impl.write_bytes(java_file.read_bytes())
        sources.append(str(impl))
        compiled=subprocess.run(['javac','--release','21','-encoding','UTF-8','-d',str(Path(tmp)/'classes'),*sources],text=True,capture_output=True,timeout=60)
        if compiled.returncode:raise AssertionError('compat capability failed compilation:\n'+compiled.stderr+'\n'+compiled.stdout)
        run=subprocess.run(['java','-cp',str(Path(tmp)/'classes'),'buildcraft.compat.Harness'],text=True,capture_output=True,timeout=30)
        if run.returncode:raise AssertionError('compat capability failed behavior:\n'+run.stderr+'\n'+run.stdout)
        return run.stdout.strip()
