"""Executable Java contract check of canonical Marker Connector VolumeBox interaction.

The substitutes model ray/box hits and the saved-data editing/lock lifecycle; this is not Minecraft.
"""
from __future__ import annotations

import subprocess
import tempfile
from pathlib import Path

SOURCES={
'net/minecraft/core/BlockPos':'''package net.minecraft.core;
public record BlockPos(int x,int y,int z) {public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}}
''',
'net/minecraft/core/Direction':'''package net.minecraft.core;
public enum Direction {NORTH; public enum Axis {X,Y,Z;}}
''',
'net/minecraft/world/InteractionResult':'''package net.minecraft.world;
public enum InteractionResult {PASS,SUCCESS,FAIL;}
''',
'net/minecraft/world/phys/Vec3':'''package net.minecraft.world.phys;
public record Vec3(double x,double y,double z) {
 public Vec3 add(Vec3 v){return new Vec3(x+v.x,y+v.y,z+v.z);}public Vec3 scale(double a){return new Vec3(x*a,y*a,z*a);}
 public double distanceToSqr(Vec3 p){double dx=x-p.x,dy=y-p.y,dz=z-p.z;return dx*dx+dy*dy+dz*dz;}}
''',
'net/minecraft/world/phys/AABB':'''package net.minecraft.world.phys;
import net.minecraft.core.BlockPos;
import java.util.Optional;
public record AABB(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
 public AABB(BlockPos p){this(p.x(),p.y(),p.z(),p.x()+1,p.y()+1,p.z()+1);}
 public AABB inflate(double d){return new AABB(minX-d,minY-d,minZ-d,maxX+d,maxY+d,maxZ+d);}
 public Optional<Vec3> clip(Vec3 start,Vec3 end){
 if(start.y()<minY || start.y()>maxY || start.z()<minZ || start.z()>maxZ || end.x()<minX || start.x()>maxX)return Optional.empty();
 return Optional.of(new Vec3(Math.max(start.x(),minX),start.y(),start.z()));
 }}
''',
'net/minecraft/world/level/Level':'''package net.minecraft.world.level;
public class Level {public boolean isClientSide;public Level(boolean client){isClientSide=client;}}
''',
'net/minecraft/world/entity/player/Player':'''package net.minecraft.world.entity.player;
import net.minecraft.world.phys.Vec3;
public class Player {
 public boolean crouch;public Vec3 eye=new Vec3(.5,.5,.5),look=new Vec3(1,0,0);
 public boolean isCrouching(){return crouch;}public Vec3 getEyePosition(){return eye;}public Vec3 getLookAngle(){return look;}}
''',
'buildcraft/lib/misc/PositionUtil':'''package buildcraft.lib.misc;
import net.minecraft.core.BlockPos;
import java.util.*;
public class PositionUtil {public static List<BlockPos> getCorners(BlockPos a,BlockPos b){
 List<BlockPos> corners=new ArrayList<>();for(int x:new int[]{a.x(),b.x()})for(int y:new int[]{a.y(),b.y()})for(int z:new int[]{a.z(),b.z()})corners.add(new BlockPos(x,y,z));return corners;}}
''',
'buildcraft/lib/misc/VecUtil':'''package buildcraft.lib.misc;
import net.minecraft.core.*;
public class VecUtil {public static BlockPos replaceValue(BlockPos p,Direction.Axis axis,int v){return switch(axis){
 case X->new BlockPos(v,p.y(),p.z());case Y->new BlockPos(p.x(),v,p.z());case Z->new BlockPos(p.x(),p.y(),v);};}}
''',
'buildcraft/core/marker/volume/Lock':'''package buildcraft.core.marker.volume;
public final class Lock {public abstract static class Target {
 public static final class TargetResize extends Target {} public static final class TargetRemove extends Target {}
 public static final class TargetAddon extends Target {public final EnumAddonSlot slot;public TargetAddon(EnumAddonSlot s){slot=s;}}
 }}
''',
'buildcraft/core/marker/volume/Addon':'''package buildcraft.core.marker.volume;
import net.minecraft.world.entity.player.Player;
public class Addon {public static int dropped,opened;public void onRemoved(){dropped++;}
public void onPlayerRightClick(Player p){opened++;}}
''',
'buildcraft/core/marker/volume/VolumeBox':'''package buildcraft.core.marker.volume;
import java.util.*;import java.util.stream.Stream;
import net.minecraft.core.BlockPos;import net.minecraft.world.phys.AABB;import net.minecraft.world.entity.player.Player;
public class VolumeBox {
 public final Box box=new Box(new BlockPos(3,0,0),new BlockPos(4,0,0));
 public final Map<EnumAddonSlot,Addon> addons=new HashMap<>(); public final List<Lock.Target> locks=new ArrayList<>();
 public Player editing;public boolean confirmed,cancelled;public BlockPos held;
 public boolean isEditing(){return editing!=null;}
 public void setPlayer(Player p){editing=p;}
 public void setHeldDistOldMinOldMax(BlockPos opposite,double dist,BlockPos min,BlockPos max){held=opposite;}
 public void confirmEditing(){editing=null;confirmed=true;}
 public void cancelEditing(){editing=null;cancelled=true;}
 public Stream<Lock.Target> getLockTargetsStream(){return locks.stream();}
 public static final class Box {
 private final BlockPos min,max;
 public Box(BlockPos min,BlockPos max){this.min=min;this.max=max;}
 public BlockPos min(){return min;}public BlockPos max(){return max;}
 public AABB getBoundingBox(){return new AABB(min);}
 } }
''',
'buildcraft/core/marker/volume/WorldSavedDataVolumeBoxes':'''package buildcraft.core.marker.volume;
import java.util.*;import net.minecraft.world.entity.player.Player;import net.minecraft.world.level.Level;
public final class WorldSavedDataVolumeBoxes {
 public static final WorldSavedDataVolumeBoxes DATA=new WorldSavedDataVolumeBoxes();
 public final List<VolumeBox> volumeBoxes=new ArrayList<>();public int dirty;
 public static WorldSavedDataVolumeBoxes get(Level l){return DATA;}
 public VolumeBox getCurrentEditing(Player p){return volumeBoxes.stream().filter(box->box.editing==p).findFirst().orElse(null);}
 public void setDirty(){dirty++;}
}''',
'org/apache/commons/lang3/tuple/Pair':'''package org.apache.commons.lang3.tuple;
public record Pair<A,B>(A left,B right) {public A getLeft(){return left;}public B getRight(){return right;}
public static <A,B> Pair<A,B> of(A a,B b){return new Pair<>(a,b);}}
''',
'buildcraft/core/marker/volume/EnumAddonSlot':'''package buildcraft.core.marker.volume;
import java.util.*;import org.apache.commons.lang3.tuple.Pair;
import net.minecraft.world.entity.player.Player;
public enum EnumAddonSlot {CORNER;
public static VolumeBox target;
public static Pair<VolumeBox,EnumAddonSlot> getSelectingVolumeBoxAndSlot(Player p,List<VolumeBox> boxes){
 return target==null?Pair.of(null,null):Pair.of(target,CORNER);}}
''',
'buildcraft/core/marker/volume/Harness':'''package buildcraft.core.marker.volume;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
public final class Harness {
 static int tests;
 static void ok(boolean c){tests++;if(!c)throw new AssertionError("failed "+tests);}
 public static void main(String[] args){
 var level=new Level(false);var player=new Player();var data=WorldSavedDataVolumeBoxes.DATA;
 var box=new VolumeBox();data.volumeBoxes.add(box);int dirty=data.dirty;
 ok(VolumeBoxToolActions.use(new Level(true),player)==InteractionResult.PASS);
 ok(data.dirty==dirty);
 ok(VolumeBoxToolActions.use(level,player)==InteractionResult.SUCCESS);
 ok(box.isEditing());ok(box.held!=null);ok(data.dirty==dirty+1);
 ok(VolumeBoxToolActions.use(level,player)==InteractionResult.SUCCESS);
 ok(box.confirmed);ok(!box.isEditing());
 ok(VolumeBoxToolActions.use(level,player)==InteractionResult.SUCCESS);
 player.crouch=true;ok(VolumeBoxToolActions.use(level,player)==InteractionResult.SUCCESS);
 ok(box.cancelled);ok(!box.isEditing());ok(data.volumeBoxes.size()==1);
 player.crouch=false;EnumAddonSlot.target=box;
 box.addons.put(EnumAddonSlot.CORNER,new Addon());
 ok(VolumeBoxToolActions.use(level,player)==InteractionResult.SUCCESS);ok(Addon.opened==1);ok(!box.isEditing());
 // An active edit MUST win over addon targeting; otherwise the edit cannot be confirmed/cancelled.
 box.editing=player;
 ok(VolumeBoxToolActions.use(level,player)==InteractionResult.SUCCESS);ok(box.confirmed);ok(!box.isEditing());ok(Addon.opened==1);
 box.editing=player;player.crouch=true;
 ok(VolumeBoxToolActions.use(level,player)==InteractionResult.SUCCESS);ok(box.cancelled);ok(box.addons.size()==1);
 box.locks.add(new Lock.Target.TargetAddon(EnumAddonSlot.CORNER));
 ok(VolumeBoxToolActions.use(level,player)==InteractionResult.FAIL);ok(box.addons.size()==1);
 box.locks.clear();
 ok(VolumeBoxToolActions.use(level,player)==InteractionResult.SUCCESS);ok(box.addons.isEmpty());ok(Addon.dropped==1);
 // Remove blocked when locked, but resize remains permitted with TargetRemove alone.
 EnumAddonSlot.target=null;box.locks.add(new Lock.Target.TargetRemove());
 ok(VolumeBoxToolActions.isLocked(box));ok(VolumeBoxToolActions.use(level,player)==InteractionResult.FAIL);
 ok(data.volumeBoxes.size()==1);
 player.crouch=false;
 ok(VolumeBoxToolActions.use(level,player)==InteractionResult.SUCCESS);
 ok(box.isEditing());box.cancelEditing();
 box.locks.clear();box.locks.add(new Lock.Target.TargetResize());
 ok(VolumeBoxToolActions.isLocked(box));ok(VolumeBoxToolActions.use(level,player)==InteractionResult.FAIL);
 box.locks.clear();ok(!VolumeBoxToolActions.isLocked(box));
 player.crouch=true;box.addons.put(EnumAddonSlot.CORNER,new Addon());
 ok(VolumeBoxToolActions.use(level,player)==InteractionResult.SUCCESS);
 ok(data.volumeBoxes.isEmpty());ok(Addon.dropped==2);
 ok(VolumeBoxToolActions.use(level,player)==InteractionResult.FAIL);
 System.out.println(tests+" assertions PASS");
 }}'''
}


def execute(real_source: Path) -> str:
    with tempfile.TemporaryDirectory(prefix='volume-box-java-test-') as root:
        root=Path(root)
        sources=[]
        for relative,body in SOURCES.items():
            file=root/'src'/(relative+'.java')
            file.parent.mkdir(parents=True,exist_ok=True)
            file.write_text(body)
            sources.append(str(file))
        real=root/'src/buildcraft/core/marker/volume/VolumeBoxToolActions.java'
        real.write_bytes(real_source.read_bytes())
        sources.append(str(real))
        result=subprocess.run(['javac','--release','21','-encoding','UTF-8','-d',str(root/'classes'),*sources],capture_output=True,text=True,timeout=70)
        if result.returncode:raise AssertionError('Tool Java boundary failed to compile:\n'+result.stdout+'\n'+result.stderr)
        result=subprocess.run(['java','-cp',str(root/'classes'),'buildcraft.core.marker.volume.Harness'],capture_output=True,text=True,timeout=30)
        if result.returncode:raise AssertionError('Volume Box lifecycle incorrect:\n'+result.stdout+'\n'+result.stderr)
        return result.stdout.strip()
