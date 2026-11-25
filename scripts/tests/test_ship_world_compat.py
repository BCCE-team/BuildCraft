"""Optional ship-space GUI and automation compatibility contract tests.

The Java doubles model a translated, 90-degree rotated plot. These tests do not
start Minecraft, Create, Sable or VS2; they exercise the exact optional API
signatures and BCCE's reach/projection boundary without loading those mods.
"""
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_layout, target_ids
from source_layout import resolve_effective_source
from source_preprocessor import preprocess_text

PROPS = load_properties()
TARGETS = ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge')
PREFIX = 'src/main/java/buildcraft/'

def effective(target, name):
    layout = target_layout(target, PROPS)
    path = resolve_effective_source(layout, PROPS, PREFIX + name + '.java')
    assert path is not None, (target, name)
    return preprocess_text(path.read_text(), minecraft=PROPS[f'target.{target}.deps.minecraft'],
                           family=layout.family, platform=layout.platform, source=str(path))

COMMON = {
    'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public record BlockPos(int x, int y, int z) {
 public int getX(){return x;} public int getY(){return y;} public int getZ(){return z;}
}''',
    'net/minecraft/world/phys/Vec3.java': '''package net.minecraft.world.phys;
public class Vec3 { public final double x,y,z; public Vec3(double x,double y,double z){this.x=x;this.y=y;this.z=z;} }''',
    'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
import net.minecraft.world.phys.Vec3;
public class Level {
 public double tx=10, tz=20; public boolean broken=false;
 public boolean ship(int x,int z){return x>=1000000 && x<1000160;}
 public Vec3 project(Vec3 v){if(broken)throw new IllegalStateException("bad pose");
  if(!ship((int)Math.floor(v.x),(int)Math.floor(v.z)))return v;
  return new Vec3(tx-(v.z-2000000), v.y+30, tz+(v.x-1000000));}
 public double distance(Vec3 a,Vec3 b){a=project(a);b=project(b);return (a.x-b.x)*(a.x-b.x)+(a.y-b.y)*(a.y-b.y)+(a.z-b.z)*(a.z-b.z);}
}''',
    'buildcraft/lib/internal/debug/BCLog.java': '''package buildcraft.lib.internal.debug;
public class BCLog { public static final Log logger=new Log(); public static class Log { public void warn(String s,Object... args){} } }''',
    'net/minecraft/world/entity/Entity.java': '''package net.minecraft.world.entity;
import net.minecraft.world.level.Level; import net.minecraft.world.phys.Vec3;
public class Entity { public Level level; public Vec3 p; public Entity(Level l,Vec3 p){level=l;this.p=p;} public Vec3 position(){return p;} }''',
    'net/minecraft/world/entity/player/Player.java': '''package net.minecraft.world.entity.player;
import net.minecraft.world.entity.Entity; import net.minecraft.world.level.Level; import net.minecraft.world.phys.Vec3;
public class Player extends Entity { public Player(Level l,Vec3 p){super(l,p);} LEVEL_METHOD }''',
    'Probe.java': '''import buildcraft.lib.misc.ShipWorldAccess;
import net.minecraft.core.BlockPos; import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3; import net.minecraft.world.entity.player.Player;
public class Probe {
 static int checks; static void check(boolean v){checks++; if(!v)throw new AssertionError("check "+checks);}
 public static void main(String[] args){
  boolean physics=Boolean.parseBoolean(args[0]); Level level=new Level();
  BlockPos local=new BlockPos(1000002,40,2000003), ordinary=new BlockPos(0,0,0);
  Player p=new Player(level,new Vec3(6.5,70.5,22.5));
  check(ShipWorldAccess.isMounted(level,local)==physics);check(!ShipWorldAccess.isMounted(level,ordinary));
  check(ShipWorldAccess.withinReach(p,level,local,64)==physics);
  check(ShipWorldAccess.withinReach(p,local,64)==physics);
  if(physics){
   BlockPos pos=ShipWorldAccess.worldBlockPos(level,local);check(pos.equals(new BlockPos(6,70,22)));
   level.tx=-20;check(!ShipWorldAccess.withinReach(p,local,64));
   level.tx=10;p.p=new Vec3(14.5,70.5,22.5);check(ShipWorldAccess.withinReach(p,local,64));
   p.p=new Vec3(14.5001,70.5,22.5);check(!ShipWorldAccess.withinReach(p,local,64));
   p.p=new Vec3(1000002.5,40.5,2000003.5);check(ShipWorldAccess.withinReach(p,local,64));
   check(ShipWorldAccess.distanceSquared(level,p.p,new Vec3(6.5,70.5,22.5))==0);
   level.broken=true;check(!ShipWorldAccess.withinReach(p,local,64));
   check(ShipWorldAccess.worldBlockPos(level,local)==null);level.broken=false;
  }else check(ShipWorldAccess.worldBlockPos(level,local).equals(local));
  p.p=new Vec3(.5,.5,.5);check(ShipWorldAccess.withinReach(p,ordinary,64));
  check(!ShipWorldAccess.withinReach(p,new Level(),ordinary,64));
  check(!ShipWorldAccess.withinReach(p,ordinary,Double.NaN));
  check(!ShipWorldAccess.withinReach(p,ordinary,-1));
  check(!ShipWorldAccess.withinReach(null,ordinary,64));
  check(ShipWorldAccess.worldPoint(level,new Vec3(Double.NaN,0,0))==null);
  p.p=new Vec3(1000,1000,1000);check(!ShipWorldAccess.withinReach(p,ordinary,64));
  System.out.println("Ship coordinate checks: "+checks);
 }
}''',
}
VS = {'org/valkyrienskies/mod/common/VSGameUtilsKt.java': '''package org.valkyrienskies.mod.common;
import net.minecraft.world.level.Level; import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity; import net.minecraft.world.phys.Vec3;
public class VSGameUtilsKt {
 public static Object getShipManagingPos(Level l,BlockPos p){return l.ship(p.x(),p.z())?l:null;}
 public static Vec3 toWorldCoordinates(Level l,Vec3 p){return l.project(p);}
 public static double squaredDistanceToInclShips(Entity e,double x,double y,double z){return e.level.distance(e.position(),new Vec3(x,y,z));}
}'''}
SABLE = {'dev/ryanhcode/sable/Sable.java': '''package dev.ryanhcode.sable;
import net.minecraft.world.level.Level; import net.minecraft.world.phys.Vec3;
public class Sable {
 public static final Helper HELPER=new Helper();
 public static class Helper {
  public Object getContaining(Level l,int x,int z){return l.ship(x<<4,z<<4)?l:null;}
  public Vec3 projectOutOfSubLevel(Level l,Vec3 p){return l.project(p);}
  public double distanceSquaredWithSubLevels(Level l,double x,double y,double z,double a,double b,double c){return l.distance(new Vec3(x,y,z),new Vec3(a,b,c));}
 }
}'''}

class ShipWorldCompatTests(unittest.TestCase):
    def test_coordinate_bridge_with_and_without_optional_mods(self):
        for target in TARGETS:
            for engine in ({}, SABLE if target.endswith('neoforge') else VS):
                with self.subTest(target=target, engine=bool(engine)), tempfile.TemporaryDirectory() as tmp:
                    root=Path(tmp); sources=dict(COMMON); sources.update(engine)
                    player_path = 'net/minecraft/world/entity/player/Player.java'
                    sources[player_path] = sources[player_path].replace('LEVEL_METHOD', '' if target.startswith('1.19') else 'public Level level(){return level;}')
                    sources['buildcraft/lib/misc/ShipWorldAccess.java']=effective(target,'lib/misc/ShipWorldAccess')
                    for path, content in sources.items():
                        file=root/path; file.parent.mkdir(parents=True,exist_ok=True);file.write_text(content)
                    subprocess.run(['javac','--release','17','-d',str(root),*[str(root/x) for x in sources]],check=True)
                    subprocess.run(['java','-cp',str(root),'Probe',str(bool(engine)).lower()],check=True)

    def test_all_target_gui_and_packet_contracts(self):
        for target in target_ids(PROPS):
            with self.subTest(target=target):
                tile=effective(target,'lib/tile/TileBC_Neptune')
                self.assertIn('ShipWorldAccess.withinReach(player, level, worldPosition, 64.0D)',tile)
                self.assertIn('level.getBlockEntity(worldPosition) != this',tile)
                self.assertIn('isPhysicsOperationPaused()',tile)
                self.assertIn('ShipWorldAccess.withinReach',effective(target,'lib/gui/BCMenuUtil'))
                self.assertIn('ShipWorldAccess.withinReach',effective(target,'lib/net/MessageUpdateTile'))
                self.assertIn('ShipWorldAccess.withinReach',effective(target,'lib/net/MessageDebugRequest'))
                self.assertIn('ShipWorldAccess.withinReach',effective(target,'lib/platform/storage/ItemStorageContainer'))
                if target in TARGETS:
                    self.assertIn('ShipWorldAccess.isMounted',effective(target,'lib/cache/NeighbourTileCache'))
                self.assertIn('if (isPhysicsOperationPaused()) return;',effective(target,'robotics/tile/TileRobotProcessor'))

    def test_laser_tables_and_external_work_guarded(self):
        for target in TARGETS:
            with self.subTest(target=target):
                base=effective(target,'silicon/tile/TileLaserTableBase')
                self.assertIn('if (isPhysicsOperationPaused()) return 0;',base)
                self.assertIn('return !isPhysicsOperationPaused();',base)
                for name in ['TileAssemblyTable','TileAdvancedCraftingTable','TileIntegrationTable','TileChargingTable','TileProgrammingTable_Neptune','TileLaser']:
                    text=effective(target,'silicon/tile/'+name)
                    self.assertRegex(text,r'public void update\(\) \{\s*(?://[^\n]*\n\s*)?if \(isPhysicsOperationPaused\(\)\)')
                for name in ['builders/tile/TileQuarry','builders/tile/TileBuilder','builders/tile/TileFiller','builders/tile/TileArchitectTable','factory/tile/TileMiner','factory/tile/TilePump','factory/tile/TileFloodGate']:
                    self.assertRegex(effective(target,name),r'public void update\(\) \{\s*if \(isPhysicsOperationPaused\(\)\) return;')
                self.assertIn('isOnPhysicalStructure()) isRedstonePowered',effective(target,'lib/engine/TileEngineBase_BC8'))
                self.assertIn('gui.buildcraftlib.physics.paused',effective(target,'lib/gui/GuiBC8'))
                self.assertIn('worldBlockPos',effective(target,'robotics/gui/GuiZonePlanner'))
                self.assertIn('worldBlockPos',effective(target,'robotics/zone/MessageZoneMapRequest'))

if __name__=='__main__': unittest.main()
