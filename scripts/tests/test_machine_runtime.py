#!/usr/bin/env python3
"""Compile production machine host and a public-API-only consumer, then exercise lifecycle and wire persistence."""
from __future__ import annotations

import shutil
import sys
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
API = 'source-shared/src/main/java/'
SOURCES = [
    *('buildcraft/api/v2/persistence/'+name+'.java' for name in (
      'ApiCodec','CodecResult','EncodedPayload','KnownPayload','OpaqueData','PayloadCopier',
      'PayloadResolution','PersistentType','SchemaMigration','UnknownPayload','UnknownPayloadReason')),
    'buildcraft/api/v2/registry/RegistrationContext.java',
    'buildcraft/lib/internal/api/v2/persistence/PersistenceRegistryBuilder.java',
    'buildcraft/lib/internal/api/v2/persistence/PersistenceRegistrySnapshot.java',
    'buildcraft/lib/internal/api/v2/runtime/RuntimeExtensions.java',
    'buildcraft/api/v2/OperationMode.java',
    'buildcraft/api/v2/energy/MjAmount.java',
    'buildcraft/api/v2/energy/MjTransferResult.java',
    'buildcraft/api/v2/energy/MjTransferPolicy.java',
    'buildcraft/api/v2/energy/MjPort.java',
    'buildcraft/api/v2/item/ItemTransferResult.java',
    'buildcraft/api/v2/machine/MachineProperty.java',
    'buildcraft/api/v2/machine/MachineType.java',
    'buildcraft/api/v2/machine/WorkState.java',
    'buildcraft/api/v2/machine/WorkStatus.java',
    *('buildcraft/api/v2/machine/'+name+'.java' for name in (
        'MachineComponent','MachineComponentType','MachineComponentFactory','MachineComponentPresentation',
        'MachineTickComponent','MachineActionComponent','MachineComponentLifecycle','MachineWorkComponent','HeatPortProvider',
        'MachineWorldAccess','MachineHostOwner','MachineHost','MachineHostProvider',
        'MachineView','MachineControl','MachineControlMode','MachineExecutionContext','MachineService')),
    'buildcraft/lib/internal/api/v2/MachineRuntimeHost.java',
    'buildcraft/lib/internal/api/v2/MachineServiceImpl.java',
]
STUBS = {
 'net/minecraft/resources/ResourceLocation.java': '''package net.minecraft.resources;
public record ResourceLocation(String namespace, String path) {
 public static ResourceLocation tryParse(String text) {
    if(text==null || !text.contains(":"))return null;
    String[] a=text.split(":",2);return new ResourceLocation(a[0],a[1]);
 }
 public String toString(){return namespace+":"+path;}
}''',
 'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public record BlockPos(int x,int y,int z) { }
''',
 'net/minecraft/core/Direction.java': 'package net.minecraft.core; public enum Direction { DOWN,UP,NORTH,SOUTH,WEST,EAST }',
 'net/minecraft/world/level/block/state/BlockState.java': 'package net.minecraft.world.level.block.state; public class BlockState {}',
 'net/minecraft/world/entity/player/Player.java': 'package net.minecraft.world.entity.player; public class Player {}',
 'net/minecraft/world/item/Item.java': 'package net.minecraft.world.item; public class Item {}',
 'net/minecraft/world/item/Items.java': 'package net.minecraft.world.item; public class Items { public static final Item IRON_INGOT=new Item(); }',
 'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
public class ItemStack {
 public static final ItemStack EMPTY=new ItemStack(null,0);
 public final Item item; private int count;
 public ItemStack(Item item,int count) {this.item=item;this.count=count;}
 public boolean isEmpty(){return item==null || count==0;}
 public ItemStack copy(){return new ItemStack(item,count);}
 public int getCount(){return count;}
 public void setCount(int n){count=n;}
}''',
 'net/minecraft/world/level/block/entity/BlockEntity.java': '''package net.minecraft.world.level.block.entity;
public class BlockEntity {}''',
 'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
public class Level {
 public boolean loaded=true;
 public final Map<BlockPos,BlockEntity> blocks=new HashMap<>();
 public boolean hasChunkAt(BlockPos pos){return loaded;}
 public BlockState getBlockState(BlockPos pos){return new BlockState();}
 public BlockEntity getBlockEntity(BlockPos pos){return blocks.get(pos);}
}''',
 'buildcraft/api/v2/item/ItemPort.java': '''package buildcraft.api.v2.item;
import net.minecraft.world.item.ItemStack;
import buildcraft.api.v2.OperationMode;
public interface ItemPort { ItemTransferResult insert(ItemStack offered, OperationMode mode); }''',
 'buildcraft/api/v2/fluid/FluidPort.java':'package buildcraft.api.v2.fluid; public interface FluidPort {}',
 'buildcraft/api/v2/platform/ExternalEnergyPort.java':'package buildcraft.api.v2.platform; public interface ExternalEnergyPort {}',
 'buildcraft/api/v2/machine/HeatPort.java':'package buildcraft.api.v2.machine; public interface HeatPort {}',
 'buildcraft/lib/internal/tiles/IHasWork.java': 'package buildcraft.lib.internal.tiles; public interface IHasWork {boolean hasWork();}',
 'buildcraft/lib/platform/capability/PlatformCapabilities.java': """package buildcraft.lib.platform.capability;
 import buildcraft.lib.internal.tiles.IHasWork;
 import buildcraft.api.v2.machine.MachineHostProvider;
 import net.minecraft.world.level.block.entity.BlockEntity;
 import net.minecraft.core.Direction;
 public final class PlatformCapabilities {
  public static IHasWork hasWork(BlockEntity entity,Direction side) {
   if(entity instanceof MachineHostProvider provider)return ()->provider.machineHost().hasWork();
   return entity instanceof IHasWork work?work:null;
  }
 }""",

 'buildcraft/api/v2/machine/MachineAreaView.java':'package buildcraft.api.v2.machine; public interface MachineAreaView {}',
 'buildcraft/api/v2/recipe/MachineRecipeService.java':'package buildcraft.api.v2.recipe; public interface MachineRecipeService {}',
 'buildcraft/api/v2/fuels/EnergyFluidService.java':'package buildcraft.api.v2.fuels; public interface EnergyFluidService {}',
 'buildcraft/api/v2/registry/RegistryKey.java':'package buildcraft.api.v2.registry; public record RegistryKey<T>(String id) {}',
 'buildcraft/api/v2/registry/ApiRegistry.java': '''package buildcraft.api.v2.registry;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
public class ApiRegistry<T> {
 private final Map<ResourceLocation,T> data=new LinkedHashMap<>();
 public T get(ResourceLocation id){return data.get(id);}
 public void register(ResourceLocation id,T value,RegistrationContext ctx){data.put(id,value);}
}''',
 'buildcraft/api/v2/BuildCraftRegistries.java': '''package buildcraft.api.v2;
import buildcraft.api.v2.machine.*;
import buildcraft.api.v2.registry.*;
public class BuildCraftRegistries {
 public static final RegistryKey<MachineType> MACHINE_TYPES=new RegistryKey<>("machines");
 public static final RegistryKey<MachineComponentType<?>> MACHINE_COMPONENT_TYPES=new RegistryKey<>("components");
}''',
 'buildcraft/api/v2/BuildCraftServices.java': '''package buildcraft.api.v2;
public class BuildCraftServices {
 public static final Object MACHINE_RECIPES=new Object(), ENERGY_FLUIDS=new Object();
}''',
 'buildcraft/lib/internal/debug/BCLog.java': '''package buildcraft.lib.internal.debug;
public class BCLog { public static final Logger logger = new Logger();
public static class Logger { public void warn(String msg,Object...args){} } }''',
 'buildcraft/api/v2/BuildCraftApi.java': '''package buildcraft.api.v2;
import buildcraft.api.v2.registry.*;
import java.util.*;
public class BuildCraftApi {
 private static final Map<RegistryKey<?>,ApiRegistry<?>> registries=new HashMap<>();
 @SuppressWarnings("unchecked") public static <T> ApiRegistry<T> registry(RegistryKey<T> id){
    return (ApiRegistry<T>)registries.computeIfAbsent(id, x->new ApiRegistry<>());
 }
 @SuppressWarnings("unchecked") public static <T> T service(Object id){return null;}
}''',
}
MAIN = r'''package buildcraft.lib.internal.api.v2;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import buildcraft.api.v2.*;
import buildcraft.api.v2.energy.*;
import buildcraft.api.v2.item.*;
import buildcraft.api.v2.machine.*;
import buildcraft.api.v2.persistence.*;
import buildcraft.api.v2.registry.*;
import dev.bcce.apifixture.CustomMachineFixtureAddon;
import net.minecraft.resources.*;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.item.*;

public class MachineRuntimeFixture {
 static void ok(boolean result,String reason){if(!result) throw new AssertionError(reason);}
 static void fails(Runnable cb){try{cb.run();}catch(IllegalStateException | IllegalArgumentException expected){return;}throw new AssertionError("Expected rejection");}
 static final class FakePower implements MjPort {
   long amount=8_000_000L;
   @Override public MjTransferResult extract(MjAmount asked,OperationMode mode){
      long used=Math.min(asked.microMj(), amount);
      if(mode==OperationMode.EXECUTE)amount-=used;
      return MjTransferResult.of(asked,MjAmount.ofMicro(used));
   }
   @Override public MjTransferResult insert(MjAmount asked,OperationMode mode){return MjTransferResult.none(asked);}
   @Override public MjAmount stored(){return MjAmount.ofMicro(amount);}
   @Override public MjAmount capacity(){return MjAmount.ofMicro(8_000_000L);}
 }
 static final class FakeItems implements ItemPort {
   int count;
   public ItemTransferResult insert(ItemStack offered,OperationMode mode){
      int accepted=count<64?offered.getCount():0;
      if(mode==OperationMode.EXECUTE)count+=accepted;
      return ItemTransferResult.ofInsertion(offered,accepted);
   }
 }
 static final MachineServiceImpl SERVICE=new MachineServiceImpl();
 static final Player ACTOR=new Player();
 static final class Owner extends BlockEntity implements MachineHostOwner,MachineHostProvider {
   final Level level; final boolean client; final BlockPos pos=new BlockPos(1,2,3);
   final FakePower energy=new FakePower(); final FakeItems inventory=new FakeItems();
   final MachineHost host; int notifications, writes; boolean allow, modified;
   Owner(boolean client){ this.client=client; level=new Level();level.blocks.put(pos,this);
       host=SERVICE.createHost(CustomMachineFixtureAddon.MACHINE,this);
   }
   public Level level(){return level;}
   public BlockPos position(){return pos;}
   public boolean clientSide(){return client;}
   public void machineChanged(){notifications++;}
   public Optional<MjPort> mjPort(Direction side){return side==Direction.UP?Optional.of(energy):Optional.empty();}
   public Optional<ItemPort> itemPort(Direction side){return side==Direction.DOWN?Optional.of(inventory):Optional.empty();}
   public boolean canPlayerUse(Player p){return allow && p==ACTOR;}
   public Optional<MachineWorldAccess> worldAccess(){return Optional.of((p,s)->{modified=true;return true;});}
   public MachineHost machineHost(){return host;}
 }
 static byte[] saveBlockEntity(Owner owner) throws IOException {
   // Addon-owned inventory and MJ storage are serialized beside the BuildCraft host payload.
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
   out.writeLong(owner.energy.amount);out.writeInt(owner.inventory.count);
   byte[] components=owner.host.saveState();out.writeInt(components.length);out.write(components);
   return bytes.toByteArray();
 }
 static void loadBlockEntity(Owner owner,byte[] data) throws IOException {
   DataInputStream in=new DataInputStream(new ByteArrayInputStream(data));
   owner.energy.amount=in.readLong();owner.inventory.count=in.readInt();
   int n=in.readInt();byte[] components=new byte[n];in.readFully(components);
   owner.host.restoreState(components);
 }
 static ResourceLocation id(String key){return ResourceLocation.tryParse(key);}
 static byte[] migrated(int completed) throws IOException {
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
   out.writeInt(0x42434d48);out.writeByte(1);out.writeUTF(CustomMachineFixtureAddon.TYPE.toString());
   out.writeByte(0);out.writeByte(0);out.writeDouble(0);out.writeUTF("");
   out.writeShort(2);
   out.writeUTF(CustomMachineFixtureAddon.OLD_COMPONENT.toString());out.writeInt(0);
   out.writeUTF(CustomMachineFixtureAddon.FORMAT.toString());out.writeInt(4);out.writeInt(completed);
   out.writeUTF("other:missing");out.writeInt(7);out.writeUTF("other:format");out.writeInt(3);out.write(new byte[]{4,5,6});
   return bytes.toByteArray();
 }
 static boolean contains(byte[] b, String needle){return new String(b, java.nio.charset.StandardCharsets.ISO_8859_1).contains(needle);}
 public static void main(String[] args) throws Exception {
   var registries=BuildCraftApi.registry(BuildCraftRegistries.MACHINE_COMPONENT_TYPES);
   registries.register(CustomMachineFixtureAddon.PRESS.id(),CustomMachineFixtureAddon.PRESS,()->"fixture");
   BuildCraftApi.registry(BuildCraftRegistries.MACHINE_TYPES).register(CustomMachineFixtureAddon.TYPE,CustomMachineFixtureAddon.MACHINE,()->"fixture");
   new MachineComponentType<>(id("fixture:legacy"),null); // Existing constructor still links.
   Owner server=new Owner(false);server.host.onLoad();
   ok(SERVICE.machine(server.level,server.pos).orElseThrow()==server.host,"MachineService provider lookup");
   ok(server.host.machineComponents().size()==1,"No executable component");
   server.host.tick();server.host.tick();
   ok(server.inventory.count==2 && server.energy.amount==6_000_000L,"Tick must transfer real resources");
   ok(server.host.workStatus().state()==WorkState.RUNNING,"Work status missing");
   ok(server.host.hasWork(),"MachineHost work view missing");
   ok(SERVICE.hasWork(server.level,server.pos,Direction.NORTH).orElseThrow(),"Native work bridge missing");
   ok(server.host.component(CustomMachineFixtureAddon.COMPONENT,CustomMachineFixtureAddon.Press.class).orElseThrow().completed()==2,"Factory-created component not ticked");
   ok(server.notifications>0,"Changed state did not notify BE owner");
   byte[] saved=saveBlockEntity(server);ok(saved.length>20,"No persistent data");
   Owner restored=new Owner(false);loadBlockEntity(restored,saved);restored.host.onLoad();
   ok(restored.inventory.count==2 && restored.energy.amount==6_000_000L,"Native resource state lost on reload");
   ok(restored.host.component(CustomMachineFixtureAddon.COMPONENT,CustomMachineFixtureAddon.Press.class).orElseThrow().completed()==2,"Restored value changed");
   restored.host.tick();ok(restored.inventory.count==3 && restored.energy.amount==5_000_000L,"Restored machine cannot continue work");
   Owner client=new Owner(true);client.host.applySync(restored.host.fullSync());
   ok(client.host.presentation(CustomMachineFixtureAddon.COMPONENT,CustomMachineFixtureAddon.CounterView.class).orElseThrow().completed()==3,"Client typed presentation missing");
   ok(client.host.workStatus().state()==WorkState.RUNNING,"Client working state missing");
   ok(client.host.hasWork(),"Client work indicator missing from synced status");
   restored.host.tick();byte[] delta=restored.host.changedSync();ok(delta.length>0,"No dirty sync");
   client.host.applySync(delta);
   ok(client.host.presentation(CustomMachineFixtureAddon.COMPONENT,CustomMachineFixtureAddon.CounterView.class).orElseThrow().completed()==4,"Delta value incorrect");
   client.host.applySync(delta);ok(client.host.presentation(CustomMachineFixtureAddon.COMPONENT,CustomMachineFixtureAddon.CounterView.class).orElseThrow().completed()==4,"Duplicate packet replayed");
   ok(restored.host.performAction(ACTOR,CustomMachineFixtureAddon.RESET,new byte[0])==false,"Unauthorized GUI action accepted");
   restored.allow=true;
   ok(!restored.host.performAction(ACTOR,CustomMachineFixtureAddon.RESET,new byte[4097]),"Oversized GUI action accepted");
   ok(restored.host.performAction(ACTOR,CustomMachineFixtureAddon.RESET,new byte[0]),"GUI action not dispatched");
   ok(restored.host.component(CustomMachineFixtureAddon.COMPONENT,CustomMachineFixtureAddon.Press.class).orElseThrow().completed()==0,"GUI action ignored");
   ok(restored.host.setMode(MachineControlMode.OFF,OperationMode.SIMULATE),"Cannot simulate control");
   ok(restored.host.mode()==MachineControlMode.ON,"SIMULATE changed mode");
   ok(restored.host.setMode(MachineControlMode.OFF,OperationMode.EXECUTE),"Control mutation rejected");
   restored.host.tick();ok(restored.host.component(CustomMachineFixtureAddon.COMPONENT,CustomMachineFixtureAddon.Press.class).orElseThrow().completed()==0,"OFF executed component");
   ok(restored.host.workStatus().state()==WorkState.PAUSED,"OFF did not pause status");
   ok(!restored.host.hasWork(),"Disabled machine incorrectly reports work");
   client.host.applySync(restored.host.changedSync());ok(client.host.mode()==MachineControlMode.OFF,"Control did not sync");
   ok(restored.host.setMode(MachineControlMode.ON,OperationMode.EXECUTE),"Cannot resume");
   restored.host.tick();ok(restored.host.component(CustomMachineFixtureAddon.COMPONENT,CustomMachineFixtureAddon.Press.class).orElseThrow().completed()==1,"Resume did not tick");
   ok(!restored.host.setBlock(new BlockPos(10,10,10),new BlockState()) || restored.modified,"Server block mutation inconsistent");
   restored.modified=false;restored.level.loaded=false;
   ok(!restored.host.setBlock(new BlockPos(10,10,10),new BlockState()) && !restored.modified,"Unloaded chunk changed");
   ok(SERVICE.machine(restored.level,restored.pos).isEmpty(),"Lookup force-loaded chunk");
   ok(SERVICE.hasWork(restored.level,restored.pos,Direction.NORTH).isEmpty(),"Work lookup force-loaded chunk");
   restored.level.loaded=true;
   Owner migrated=new Owner(false);migrated.host.restoreState(migrated(5));migrated.host.onLoad();
   ok(migrated.host.component(CustomMachineFixtureAddon.COMPONENT,CustomMachineFixtureAddon.Press.class).orElseThrow().completed()==6,"Alias migration failed");
   ok(contains(migrated.host.saveState(),"other:missing"),"Unknown addon component was lost");
   Owner corrupt=new Owner(false);byte[] garbage={3,2,1,0};corrupt.host.restoreState(garbage);corrupt.host.tick();
   ok(Arrays.equals(garbage,corrupt.host.saveState()) && corrupt.host.workStatus().state()==WorkState.ERROR,"Corrupt state overwritten");
   List<ItemStack> drops=new ArrayList<>();server.host.addDrops(drops);server.host.removed();server.host.removed();server.host.invalidate();server.host.invalidate();
   fails(server.host::tick);
   System.out.println("Machine host, loaded/unloaded, native ports, save/migrate, GUI, delta sync, gates and failure tests OK");
 }
}'''

class MachineHostTests(unittest.TestCase):
    def test_production_host_and_api_only_addon(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('JDK unavailable')
        with tempfile.TemporaryDirectory(prefix='bcce-machine7-') as scratch:
            root=Path(scratch)
            for name,text in STUBS.items():
                path=root/name
                path.parent.mkdir(parents=True,exist_ok=True)
                path.write_text(text)
            main=root/'buildcraft/lib/internal/api/v2/MachineRuntimeFixture.java'
            main.parent.mkdir(parents=True,exist_ok=True)
            main.write_text(MAIN)
            sources=[str(root/name) for name in STUBS]
            sources.extend(str(ROOT/API/path) for path in SOURCES)
            addon=ROOT/'addon-fixture/src/main/java/dev/bcce/apifixture/CustomMachineFixtureAddon.java'
            self.assertNotIn('buildcraft.lib.internal',addon.read_text())
            sources.extend([str(addon),str(main)])
            compiled=subprocess.run(['javac','-d',str(root/'classes'),*sources],capture_output=True,text=True,timeout=75)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            executed=subprocess.run(['java','-cp',str(root/'classes'),'buildcraft.lib.internal.api.v2.MachineRuntimeFixture'],capture_output=True,text=True,timeout=30)
            self.assertEqual(executed.returncode,0,executed.stderr+'\n'+executed.stdout)

    def test_promoted_26x_sources_resolve_to_effective_owner(self):
        scripts_dir = str(ROOT / 'scripts')
        if scripts_dir not in sys.path:
            sys.path.insert(0, scripts_dir)
        from source_lookup import resolve_source_path, resolve_target_source
        from source_layout import effective_source_files, load_properties, target_layout, resolve_effective_source

        logical = 'src/main/java/buildcraft/lib/internal/api/v2/MachineServiceImpl.java'
        former = 'source-family-platforms/26.X/neoforge/' + logical
        resolved = resolve_source_path(former)
        self.assertEqual(ROOT / 'source-families/26.X' / logical, resolved)
        self.assertIn('MachineHostProvider', resolved.read_text())

        props = load_properties()
        for target in ('26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge'):
            layout = target_layout(target, props)
            selected = effective_source_files(layout, props, 'src/main/java/buildcraft/lib')
            for name in (logical, 'src/main/java/buildcraft/lib/compat/ItemNameKeys121111.java'):
                with self.subTest(target=target, logical=name):
                    self.assertEqual(selected.get(name), resolve_effective_source(layout, props, name))
            self.assertEqual(selected[logical], resolve_target_source(target, logical))

    def test_gate_bindings_and_multitarget_sources(self):
        for family in ('old','1.21.X'):
            files=ROOT/f'source-families/{family}/src/main/java/buildcraft/core/statements'
            self.assertIn('MachineControlMode.valueOf(mode.name())',(files/'ActionMachineControl.java').read_text())
            self.assertIn('BuildCraftServices.MACHINES',(files/'TriggerMachine.java').read_text())
        scripts_dir = str(ROOT / 'scripts')
        if scripts_dir not in sys.path:
            sys.path.insert(0, scripts_dir)
        from source_lookup import resolve_target_source
        impls=[ROOT/'source-shared/src/main/java/buildcraft/lib/internal/api/v2/MachineServiceImpl.java']
        impls.extend(resolve_target_source(target, 'src/main/java/buildcraft/lib/internal/api/v2/MachineServiceImpl.java')
                     for target in ('26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge'))
        for impl in impls:
            src=impl.read_text()
            self.assertIn('MachineHostProvider',src)
            self.assertIn('createHost(',src)
            self.assertIn('hasChunkAt(pos)',src)

if __name__=='__main__':
    unittest.main()
