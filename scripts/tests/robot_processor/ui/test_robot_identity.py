"""Exercise the real identity and home-link helpers with isolated Minecraft stubs."""
from pathlib import Path
import subprocess
import tempfile
import unittest
import sys

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_lookup import read_materialized_java

PREFIX = 'src/main/java/buildcraft/robotics/'
TARGETS = ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge', '1.21.11-neoforge',
           '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge', '26.3-fabric')
HELPERS = ('internal/legacy/robots/RobotIdentityLedger.java', 'internal/legacy/robots/RobotHomeLinks.java',
           'item/RobotItemIdentity.java')
STUBS = {
    'com/mojang/authlib/GameProfile.java': '''package com.mojang.authlib;
import java.util.UUID; public record GameProfile(UUID id,String name) {
 public UUID getId(){return id;} public String getName(){return name;}
}''',
    'buildcraft/lib/misc/FakePlayerProvider.java': '''package buildcraft.lib.misc;
import com.mojang.authlib.GameProfile; import java.util.UUID;
public class FakePlayerProvider {public static final GameProfile NULL_PROFILE=new GameProfile(new UUID(0,0),"[BC]");}''',
    'net/minecraft/nbt/CompoundTag.java': '''package net.minecraft.nbt;
import java.util.*; public class CompoundTag {
 final Map<String,Object> map=new HashMap<>();
 public void put(String k,CompoundTag v){map.put(k,v.copy());}
 public void putLong(String k,long v){map.put(k,v);}
 public void putString(String k,String v){map.put(k,v);}
 public boolean contains(String k){return map.containsKey(k);}
 public long getLong(String k){return map.get(k) instanceof Long l?l:0;}
 public String getString(String k){return map.get(k) instanceof String s?s:"";}
 public CompoundTag getCompound(String k){return map.get(k) instanceof CompoundTag t?t.copy():new CompoundTag();}
 public CompoundTag copy(){var t=new CompoundTag();t.map.putAll(map);return t;}
}''',
    'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
import net.minecraft.nbt.CompoundTag;
public class ItemStack {private CompoundTag tag; public int count=1;
 public CompoundTag getTag(){return tag;} public void setTag(CompoundTag t){tag=t.copy();}
 public int getCount(){return count;} public ItemStack copy(){var s=new ItemStack();s.count=count;if(tag!=null)s.setTag(tag);return s;}
}''',
    'net/minecraft/world/entity/player/Player.java': '''package net.minecraft.world.entity.player;
import com.mojang.authlib.GameProfile;import java.util.UUID;
public class Player {public final GameProfile profile; public boolean op;
 public Player(UUID id){profile=new GameProfile(id,"Player");} public GameProfile getGameProfile(){return profile;}
 public UUID getUUID(){return profile.id();} public boolean hasPermissions(int level){return op;}
}''',
    'net/minecraft/server/level/ServerPlayer.java': '''package net.minecraft.server.level;
import java.util.UUID; public class ServerPlayer extends net.minecraft.world.entity.player.Player {
 public ServerPlayer(UUID id){super(id);}
}''',
    'buildcraft/api/v2/robot/RobotEventKind.java': '''package buildcraft.api.v2.robot;
public enum RobotEventKind {INTERACT}''',
    'buildcraft/robotics/internal/api2/RobotEventSupport.java': '''package buildcraft.robotics.internal.api2;
import buildcraft.api.v2.robot.RobotEventKind;import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import net.minecraft.world.entity.player.Player;import net.minecraft.world.item.ItemStack;
public class RobotEventSupport {public static boolean deny;
 public static boolean denied(RobotEventKind k,EntityRobotBase r,Player p,ItemStack i){return deny;}
}''',
    'buildcraft/robotics/internal/api2/RobotAutomationSupport.java': '''package buildcraft.robotics.internal.api2;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;import com.mojang.authlib.GameProfile;
public class RobotAutomationSupport {public static GameProfile owner(EntityRobotBase robot){return robot.owner;}}''',
    'buildcraft/robotics/internal/legacy/robots/IRobotRegistry.java': '''package buildcraft.robotics.internal.legacy.robots;
import java.util.*;public class IRobotRegistry {
 public final RobotIdentityLedger ledger=new RobotIdentityLedger();
 public final Map<Long,EntityRobotBase> live=new HashMap<>();public final List<DockingStation> stations=new ArrayList<>();
 public int released; public RobotIdentityLedger robotIdentities(){return ledger;}
 public EntityRobotBase getLoadedRobot(long id){return live.get(id);}public void registryMarkDirty(){}
 public Collection<DockingStation> getStations(){return List.copyOf(stations);}
 public void release(DockingStation s,long id){released++;}
}''',
    'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java': '''package buildcraft.robotics.internal.legacy.robots;
import com.mojang.authlib.GameProfile;public class EntityRobotBase {
 public long id; public GameProfile owner;public IRobotRegistry registry;public DockingStation dock;
 public long getRobotId(){return id;} public IRobotRegistry getRegistry(){return registry;}
 public DockingStation getDockingStation(){return dock;}public void undock(){dock=null;}
}''',
    'buildcraft/robotics/internal/legacy/robots/DockingStation.java': '''package buildcraft.robotics.internal.legacy.robots;
public class DockingStation {public long id;public boolean main; public DockingStation(long i,boolean m){id=i;main=m;}
 public boolean isMainStation(){return main;}public long robotIdTaking(){return id;}
 public void unsafeRelease(EntityRobotBase r){if(id==r.id){id=0;main=false;}}
}''',
    'buildcraft/robotics/entity/EntityRobot.java': '''package buildcraft.robotics.entity;
import buildcraft.robotics.internal.legacy.robots.*;import com.mojang.authlib.GameProfile;
public class EntityRobot extends EntityRobotBase {public EntityRobot(IRobotRegistry r){registry=r;}
 public void setOwner(GameProfile p){owner=p;}}''',
    'CheckIdentity.java': '''import java.util.*;
import buildcraft.robotics.item.RobotItemIdentity;import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.internal.legacy.robots.*;import buildcraft.robotics.internal.api2.RobotEventSupport;
import net.minecraft.server.level.ServerPlayer;import net.minecraft.world.item.ItemStack;
public class CheckIdentity {
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  var owner=new ServerPlayer(UUID.randomUUID());var stranger=new ServerPlayer(UUID.randomUUID());
  var reg=new IRobotRegistry();var robot=new EntityRobot(reg);robot.id=42;robot.owner=owner.profile;
  reg.live.put(42L,robot);reg.ledger.assign(42,"old-disk");
  check(RobotItemIdentity.canManage(robot,owner,new ItemStack()));
  check(!RobotItemIdentity.canManage(robot,stranger,new ItemStack()));
  RobotEventSupport.deny=true;check(!RobotItemIdentity.canManage(robot,owner,new ItemStack()));RobotEventSupport.deny=false;
  var home=new DockingStation(42,true);var duplicate=new DockingStation(42,true);
  var charger=new DockingStation(42,false);var other=new DockingStation(7,true);
  reg.stations.addAll(List.of(home,duplicate,charger,other));robot.dock=duplicate;
  RobotHomeLinks.keepOnly(robot,home);
  check(home.id==42&&duplicate.id==0&&charger.id==42&&other.id==7&&robot.dock==null&&reg.released==1);
  var stack=RobotItemIdentity.pack(robot,new ItemStack());var copy=stack.copy();
  check(reg.ledger.isPacked(42)&&reg.ledger.controls(42,"old-disk"));
  check(RobotItemIdentity.preparePlacement(stack,new EntityRobot(reg),owner)==-1); // still live
  reg.live.clear();
  // Save/reload both independent registries; the actual proof is persisted verbatim.
  var restored=new IRobotRegistry();reg.ledger.snapshot().forEach(restored.ledger::restore);
  reg.ledger.controllerSnapshot().forEach(restored.ledger::restoreController);
  var placed=new EntityRobot(restored);
  check(RobotItemIdentity.preparePlacement(stack,placed,stranger)==-1);
  check(RobotItemIdentity.preparePlacement(stack,new EntityRobot(new IRobotRegistry()),owner)==-1);
  stranger.op=true;check(RobotItemIdentity.preparePlacement(stack,placed,stranger)==42);
  check(placed.owner.id().equals(owner.getUUID()));
  check(RobotItemIdentity.claimPlacement(stack,restored,42));
  check(!RobotItemIdentity.claimPlacement(copy,restored,42));
  RobotItemIdentity.cancelPlacement(stack,restored,42);
  check(RobotItemIdentity.preparePlacement(stack,placed,owner)==42);
  check(RobotItemIdentity.claimPlacement(stack,restored,42));
  check(RobotItemIdentity.preparePlacement(copy,new EntityRobot(restored),owner)==-1);
  check(restored.ledger.controls(42,"old-disk")); // pickup/placement never unlinks HDD
  restored.ledger.assign(42,"new-disk");
  check(!restored.ledger.adopt(42,"old-disk")&&restored.ledger.controls(42,"new-disk"));
  check(!restored.ledger.release(42,"old-disk")&&restored.ledger.release(42,"new-disk"));
  check(!restored.ledger.adopt(42,"old-disk")); // explicit unlink tombstone
  check(RobotItemIdentity.preparePlacement(new ItemStack(),placed,owner)==0);
 }
}''',
}

class RobotIdentityTests(unittest.TestCase):
    def test_actual_identity_permissions_transfer_and_home_cleanup(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            files=dict(STUBS)
            for name in HELPERS:
                files['buildcraft/robotics/'+name]=read_materialized_java('1.19.2-forge', PREFIX+name)
            paths=[]
            for name,text in files.items():
                path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text);paths.append(str(path))
            subprocess.run(['javac','--release','17','-d',tmp,*paths],check=True,capture_output=True,text=True)
            subprocess.run(['java','-cp',tmp,'CheckIdentity'],check=True)

    def test_all_eight_effective_sources(self):
        for target in TARGETS:
            with self.subTest(target=target):
                def read(path): return read_materialized_java(target,PREFIX+path)
                item=read('item/ItemRobot.java');entity=read('entity/EntityRobot.java')
                registry=read('SimpleRobotRegistryProvider.java');station=read('internal/legacy/robots/DockingStation.java')
                self.assertIn('RobotItemIdentity.pack(this, asItemStack())',entity)
                self.assertIn('RobotItemIdentity.hasOwnershipAccess(this, player)',entity)
                self.assertIn('registry.release(previous, robotId)',entity)
                self.assertIn('RobotHomeLinks.keepOnly(this, linkedStation)',entity)
                self.assertIn('RobotHomeLinks.keepOnly(robot, this)',station)
                self.assertLess(item.index('RobotEventSupport.denied(RobotEventKind.PLACE'),item.index('RobotItemIdentity.claimPlacement('))
                self.assertLess(item.index('RobotItemIdentity.claimPlacement('),item.index('robot.setUniqueRobotId(savedId'))
                self.assertIn('RobotItemIdentity.cancelPlacement(currentItem, registry, savedId)',item)
                self.assertIn('if (savedId > 0 || !player.isCreative())',item)
                self.assertIn('if (level.addFreshEntity(robot))',item)
                for key in ('packedRobots','robotControllers'):
                    self.assertGreaterEqual(registry.count('"'+key+'"'),2)
                self.assertIn('packedIdentities.restoreController(',registry)
                self.assertIn('RobotItemIdentity.canManage(robot, player, stack)',read('item/ItemRobotController.java'))
                tile=read('tile/TileRobotProcessor.java')
                self.assertIn('data.putString("diskIdentity", diskIdentity)',tile)
                self.assertIn('reconcileControllerLinks(true)',tile)
                self.assertIn('identities.controls(id, diskIdentity)',tile)
                self.assertIn('RobotItemIdentity.canManage(releasedRobot, player,',read('plug/RobotStationPluggable.java'))

if __name__=='__main__': unittest.main()
