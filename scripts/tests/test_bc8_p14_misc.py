#!/usr/bin/env python3
"""Compile an independent API2 misc addon against actual public contracts and native service bridges."""
import pathlib, tempfile, subprocess, shutil, unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
BASE=ROOT/'source-shared/src/main/java'
STUBS={
 'net/minecraft/resources/ResourceLocation.java': '''package net.minecraft.resources;
 public record ResourceLocation(String value){public static ResourceLocation tryParse(String v){return new ResourceLocation(v);}public String toString(){return value;}
 public String getNamespace(){return value.contains(":")?value.split(":",2)[0]:"minecraft";}
 public String getPath(){return value.contains(":")?value.split(":",2)[1]:value;}}
''',
 'net/minecraft/world/item/Item.java': 'package net.minecraft.world.item;public record Item(String id){}',
 'net/minecraft/world/item/Items.java': '''package net.minecraft.world.item;
 public class Items{public static final Item IRON_INGOT=new Item("iron");
 public static final Item GOLD_INGOT=new Item("gold");
 public static final Item PAPER=new Item("paper");
 public static final Item MAP=new Item("map");
 public static final Item STICK=new Item("stick");}
 ''',
 'net/minecraft/world/item/DyeColor.java': '''package net.minecraft.world.item;public enum DyeColor{RED,BLUE,GREEN}
''',
 'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
 import java.util.stream.*;import net.minecraft.tags.TagKey;
 public class ItemStack{private String name;private Object item;
 public ItemStack(String n){name=n;item=new Item(n);} public ItemStack(String n,Object i){name=n;item=i;}
 public ItemStack(Item item){this(item.id(),item);} public boolean is(Item i){return item==i;}
 public String name(){return name;}public void setName(String n){name=n;}
 public ItemStack copy(){return new ItemStack(name,item);} public boolean isEmpty(){return name.isEmpty();}
 public Object getItem(){return item;}public void setCount(int n){}public Stream<TagKey> getTags(){return Stream.empty();}}
''',
 'net/minecraft/tags/TagKey.java': '''package net.minecraft.tags;import net.minecraft.resources.ResourceLocation;public record TagKey(ResourceLocation location){}
''',
 'net/minecraft/world/level/block/state/BlockState.java': '''package net.minecraft.world.level.block.state;public record BlockState(String value){}
''',
 'net/minecraft/core/Direction.java': '''package net.minecraft.core;public enum Direction{UP,DOWN,NORTH,SOUTH,EAST,WEST}
''',
 'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;public record BlockPos(int x,int y,int z){}
''',
 'net/minecraft/core/NonNullList.java': '''package net.minecraft.core;public class NonNullList<T> extends java.util.ArrayList<T>{public static <T> NonNullList<T> create(){return new NonNullList<T>();}}
''',
 'net/minecraft/world/level/BlockGetter.java': '''package net.minecraft.world.level;public interface BlockGetter{}
''',
 'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;import net.minecraft.core.BlockPos;import net.minecraft.world.level.block.state.BlockState;
 public class Level implements BlockGetter{public BlockState getBlockState(BlockPos p){return new BlockState("crop");}}
''',
 'net/minecraft/world/entity/player/Player.java': '''package net.minecraft.world.entity.player;public class Player{}
''',
 'net/minecraft/world/InteractionHand.java': '''package net.minecraft.world;public enum InteractionHand{MAIN_HAND,OFF_HAND}
''',
 'net/minecraft/world/phys/HitResult.java': '''package net.minecraft.world.phys;public class HitResult{}
''',
 'buildcraft/api/v2/area/BlockBox.java': '''package buildcraft.api.v2.area;public record BlockBox(int x){}
''',
 'buildcraft/api/v2/area/Path.java': '''package buildcraft.api.v2.area;public record Path(int x){}
''',
 'buildcraft/api/v2/area/Zone.java': '''package buildcraft.api.v2.area;public record Zone(int x){}
''',
 'buildcraft/api/v2/registry/RegistryKey.java': '''package buildcraft.api.v2.registry;public record RegistryKey<T>(String name){}
''',
 'buildcraft/api/v2/registry/RegistryEntry.java': '''package buildcraft.api.v2.registry;import net.minecraft.resources.ResourceLocation;public record RegistryEntry<T>(ResourceLocation id,T value){}
''',
 'buildcraft/api/v2/registry/ApiRegistry.java': '''package buildcraft.api.v2.registry;import java.util.*;import net.minecraft.resources.ResourceLocation;
 public class ApiRegistry<T>{private final Map<ResourceLocation,T> v=new LinkedHashMap<>();private boolean frozen;
 public void register(ResourceLocation id,T value){if(frozen||v.putIfAbsent(id,value)!=null)throw new IllegalStateException("frozen/duplicate");}
 public T get(ResourceLocation id){return v.get(id);}public Collection<T> values(){return v.values();}
 public Collection<RegistryEntry<T>> entries(){return v.entrySet().stream().map(e->new RegistryEntry<T>(e.getKey(),e.getValue())).toList();}
 public void freeze(){frozen=true;}}
''',
 'buildcraft/api/v2/BuildCraftRegistries.java': '''package buildcraft.api.v2;import buildcraft.api.v2.registry.*;import buildcraft.api.v2.list.*;import buildcraft.api.v2.facade.*;
 import buildcraft.api.v2.map.*;import buildcraft.api.v2.tool.*;
 public class BuildCraftRegistries{
 public static final RegistryKey<ItemList> NAMED_ITEM_LISTS=new RegistryKey<>("lists");
 public static final RegistryKey<FacadeMaterialAdapter> FACADE_MATERIAL_ADAPTERS=new RegistryKey<>("facades");
 public static final RegistryKey<MapLocationAdapter> MAP_LOCATION_ADAPTERS=new RegistryKey<>("maps");
 public static final RegistryKey<WrenchAdapter> WRENCH_ADAPTERS=new RegistryKey<>("wrenches");}
''',
 'buildcraft/api/v2/BuildCraftApi.java': '''package buildcraft.api.v2;import java.util.*;import buildcraft.api.v2.registry.*;
 public class BuildCraftApi{
 static final Map<RegistryKey<?>,ApiRegistry<?>> REG=new HashMap<>();
 @SuppressWarnings("unchecked")public static <T> ApiRegistry<T> registry(RegistryKey<T> k){return (ApiRegistry<T>)REG.computeIfAbsent(k,x->new ApiRegistry<>());}
 public static buildcraft.api.v2.tool.WrenchService service(String key){return null;}}
''',
 'buildcraft/lib/BCLibConfig.java':'package buildcraft.lib;public class BCLibConfig{public static boolean useWrenchTag=false;}',
 'buildcraft/lib/internal/tool/IToolWrench.java': '''package buildcraft.lib.internal.tool;
 import net.minecraft.world.entity.player.Player;import net.minecraft.world.InteractionHand;import net.minecraft.world.item.ItemStack;import net.minecraft.world.phys.HitResult;
 public interface IToolWrench{void wrenchUsed(Player p,InteractionHand h,ItemStack s,HitResult hit);}
''',
 'buildcraft/api/v2/BuildCraftServices.java': '''package buildcraft.api.v2;import buildcraft.api.v2.tool.WrenchService;
 public class BuildCraftServices{public static final String WRENCHES="wrenches";}
''',
}
FILES=[
 'buildcraft/api/v2/OperationMode.java','buildcraft/api/v2/item/ItemMatcher.java',
 'buildcraft/api/v2/list/ItemList.java','buildcraft/api/v2/list/ItemListEntry.java','buildcraft/api/v2/list/ListMatchMode.java',
 'buildcraft/api/v2/list/ItemListService.java','buildcraft/api/v2/list/ItemListAdapter.java',
 'buildcraft/api/v2/facade/FacadeMaterial.java','buildcraft/api/v2/facade/FacadePhase.java',
 'buildcraft/api/v2/facade/FacadeMaterialAdapter.java','buildcraft/api/v2/facade/FacadeService.java','buildcraft/api/v2/facade/FacadeStateResult.java',
 'buildcraft/api/v2/map/MapLocationKind.java','buildcraft/api/v2/map/MapLocationView.java',
 'buildcraft/api/v2/map/MapLocationAdapter.java','buildcraft/api/v2/map/MapLocationService.java',
 'buildcraft/api/v2/tool/WrenchUseContext.java','buildcraft/api/v2/tool/WrenchAdapter.java','buildcraft/api/v2/tool/WrenchService.java',
 'buildcraft/api/v2/crops/CropAdapter.java','buildcraft/api/v2/crops/CropRegistration.java','buildcraft/api/v2/crops/CropService.java',
 'buildcraft/lib/internal/api/v2/ItemListServiceImpl.java','buildcraft/lib/internal/api/v2/FacadeServiceImpl.java',
 'buildcraft/lib/internal/api/v2/MapLocationServiceImpl.java','buildcraft/lib/internal/api/v2/WrenchServiceImpl.java',
 'buildcraft/lib/misc/WrenchUtil.java',
]
MAIN = '''package check;
 import buildcraft.api.v2.*;import buildcraft.api.v2.registry.*;import buildcraft.api.v2.list.*;
 import buildcraft.api.v2.facade.*;import buildcraft.api.v2.map.*;import buildcraft.api.v2.tool.*;
 import buildcraft.api.v2.crops.*;import buildcraft.lib.internal.api.v2.*;
 import net.minecraft.resources.*;import net.minecraft.world.item.*;import net.minecraft.world.level.*;
 import net.minecraft.world.level.block.state.*;import net.minecraft.core.*;import net.minecraft.world.entity.player.*;
 import dev.bcce.apifixture.MiscParityFixtureAddon;import java.util.*;
 public class P14Check {
 static void ok(boolean yes,String message){if(!yes)throw new AssertionError(message);}
 public static void main(String[] args) throws Exception {
  MiscParityFixtureAddon.register();
  ItemListService list = new ItemListServiceImpl();
  ok(list.ids().contains(MiscParityFixtureAddon.FILTER),"named list not registered");
  ok(list.matches(MiscParityFixtureAddon.FILTER,new ItemStack(Items.IRON_INGOT)),"list did not match");
  ok(!list.matches(MiscParityFixtureAddon.FILTER,new ItemStack(Items.GOLD_INGOT)),"list false positive");
  BuildCraftApi.registry(BuildCraftRegistries.NAMED_ITEM_LISTS).freeze();
  try{list.register(ResourceLocation.tryParse("fixture:later"),new ItemList(List.of(),ListMatchMode.ANY));throw new AssertionError("late registration");}
  catch(IllegalStateException expected){}
  FacadeService facade = new FacadeServiceImpl();
  var wood=new BlockState("wood");
  ok(facade.createPhased(List.of(new FacadePhase(wood,DyeColor.RED),new FacadePhase(wood,DyeColor.BLUE)),true)
   .orElseThrow().is(Items.PAPER),"phased item factory not invoked");
  ok(facade.createPhased(List.of(new FacadePhase(wood,DyeColor.RED),new FacadePhase(wood,DyeColor.RED)),false).isEmpty(),"duplicate phase accepted");
  ok(facade.createPhased(List.of(),false).isEmpty(),"empty phase accepted");
  MapLocationService map=new MapLocationServiceImpl();
  var stack=new ItemStack(Items.MAP);
  ok(map.read(stack).orElseThrow().label().equals("fixture"),"map addon not resolved");
  BuildCraftApi.registry(BuildCraftRegistries.MAP_LOCATION_ADAPTERS).register(
    ResourceLocation.tryParse("fixture:malicious"),new MapLocationAdapter(){
    public boolean supports(ItemStack s){return s.name().startsWith("custommap:");}
    public Optional<MapLocationView> read(ItemStack s){return Optional.of(MapLocationView.clean(s.name()));}
    public boolean write(ItemStack s,MapLocationView v,OperationMode m){s.setName("custommap:"+v.label());return true;}
    public boolean clear(ItemStack s,OperationMode m){s.setName("custommap:cleared");return true;}});
  stack=new ItemStack("custommap:initial");
  ok(map.read(stack).orElseThrow().label().equals("custommap:initial"),"map read lost");
  ok(map.write(stack,MapLocationView.point("new",new BlockPos(1,2,3),Direction.UP),OperationMode.SIMULATE),"map simulate rejected");
  ok(stack.name().equals("custommap:initial"),"map simulate changed item");
  ok(map.write(stack,MapLocationView.point("new",new BlockPos(1,2,3),Direction.UP),OperationMode.EXECUTE),"map write failed");
  ok(stack.name().equals("custommap:new"),"map execute did not change item");
  ok(map.clear(stack,OperationMode.SIMULATE) && stack.name().equals("custommap:new"),"map clear simulation changed item");
  WrenchServiceImplProbe.check();
  buildcraft.lib.misc.WrenchUtil.wrenchUsed(new Player(),net.minecraft.world.InteractionHand.MAIN_HAND,
   new ItemStack(Items.STICK),new net.minecraft.world.phys.HitResult());
  ok(MiscParityFixtureAddon.WrenchObservations.useCount==1,"native wrench callback not dispatched");
  CropAdapter fails=new CropAdapter(){public boolean isSeed(ItemStack s){throw new IllegalStateException();}
   public boolean canSustainPlant(Level l,ItemStack s,BlockPos p){return false;}
   public boolean plant(Level l,Player a,ItemStack s,BlockPos p){return false;}
   public boolean isMature(net.minecraft.world.level.BlockGetter l,BlockState s,BlockPos p){throw new IllegalStateException();}
   public boolean harvest(Level l,BlockPos p,NonNullList<ItemStack> d,Player a){return false;}};
  CropAdapter correct=new CropAdapter(){public boolean isSeed(ItemStack s){return s.name().equals("seed");}
   public boolean canSustainPlant(Level l,ItemStack s,BlockPos p){return true;}
   public boolean plant(Level l,Player a,ItemStack s,BlockPos p){return true;}
   public boolean isMature(net.minecraft.world.level.BlockGetter l,BlockState s,BlockPos p){return true;}
   public boolean harvest(Level l,BlockPos p,NonNullList<ItemStack> d,Player a){d.add(new ItemStack("wheat"));return true;}};
  CropService crops=new CropService(){public void register(ResourceLocation id,int pr,CropAdapter a){}
   public List<CropRegistration> adapters(){return List.of(new CropRegistration(ResourceLocation.tryParse("test:bad"),2,fails),
      new CropRegistration(ResourceLocation.tryParse("test:good"),1,correct));}};
  ok(crops.isSeed(new ItemStack("seed")),"crop fallback broken");
  var drops=NonNullList.<ItemStack>create();
  ok(crops.harvest(new Level(),new BlockPos(1,2,3),drops,new Player()),"crop harvest fallback broken");
  ok(drops.size()==1 && drops.get(0).name().equals("wheat"),"crop drops wrong");
  System.out.println("P14 native API2 list/facade/map/wrench/crops conformance OK");
 }
}
'''
WRENCH_CHECK = '''package check;
 import buildcraft.api.v2.*;import buildcraft.api.v2.tool.*;import buildcraft.lib.internal.api.v2.*;
 import net.minecraft.world.item.ItemStack;
 public class WrenchServiceImplProbe {
  public static void check() throws Exception {
   var impl=Class.forName("buildcraft.lib.internal.api.v2.WrenchServiceImpl").getDeclaredConstructor();
   impl.setAccessible(true);
   WrenchService w=(WrenchService)impl.newInstance();
   if(!w.isWrench(new ItemStack(net.minecraft.world.item.Items.STICK)))throw new AssertionError("wrench adapter not resolved");
   if(w.isWrench(new ItemStack(net.minecraft.world.item.Items.GOLD_INGOT)))throw new AssertionError("wrench false positive");
  }
}
'''
class P14MiscTest(unittest.TestCase):
 def test_native_links_selected_for_all_seven_targets(self):
  import sys
  sys.path.insert(0,str(ROOT/'scripts'))
  from source_layout import load_properties,target_ids,target_layout,resolve_effective_source
  props=load_properties()
  paths={
   'buildcraft/lib/internal/api/v2/BuildCraftApiRuntime.java':(
      'registerRegistry(BuildCraftRegistries.NAMED_ITEM_LISTS)',
      'registerRegistry(BuildCraftRegistries.WRENCH_ADAPTERS)'),
   'buildcraft/lib/internal/api/v2/ItemListServiceImpl.java':('NAMED_ITEM_LISTS',),
   'buildcraft/lib/internal/api/v2/FacadeServiceImpl.java':('createPhased(',),
   'buildcraft/lib/internal/api/v2/MapLocationServiceImpl.java':('MAP_LOCATION_ADAPTERS',),
   'buildcraft/lib/internal/api/v2/BlockInteractionServiceImpl.java':('catch (RuntimeException ignored)',),
   'buildcraft/lib/internal/api/v2/WrenchServiceImpl.java':('WRENCH_ADAPTERS',),
   'buildcraft/silicon/plug/FacadeStateManager.java':('createPhased(', 'PLUG_FACADE_ITEM'),
   'buildcraft/lib/misc/WrenchUtil.java':('adapter.onUsed(context)',),
  }
  for target in target_ids(props):
   layout=target_layout(target,props)
   for name,tokens in paths.items():
    path=resolve_effective_source(layout,props,pathlib.Path('src/main/java')/name)
    self.assertIsNotNone(path,(target,name))
    text=path.read_text()
    for token in tokens:self.assertIn(token,text,(target,name))
 def test_api2_public_addon_against_real_native_services(self):
  if not shutil.which('javac') or not shutil.which('java'):self.skipTest('JDK not available')
  addon=ROOT/'addon-fixture/src/main/java/dev/bcce/apifixture/MiscParityFixtureAddon.java'
  self.assertNotIn('buildcraft.lib.',addon.read_text());self.assertNotIn('buildcraft.transport.',addon.read_text())
  with tempfile.TemporaryDirectory(prefix='p14-parity-') as td:
   d=pathlib.Path(td);sources=[]
   for name,src in STUBS.items():
    dst=d/name;dst.parent.mkdir(parents=True,exist_ok=True);dst.write_text(src);sources.append(str(dst))
   for rel in FILES:sources.append(str(BASE/rel))
   sources.append(str(addon))
   main=d/'check/P14Check.java';main.parent.mkdir(parents=True,exist_ok=True);main.write_text(MAIN);sources.append(str(main))
   wrench=d/'check/WrenchServiceImplProbe.java';wrench.write_text(WRENCH_CHECK);sources.append(str(wrench))
   out=d/'classes';out.mkdir()
   proc=subprocess.run(['javac','-encoding','UTF-8','-d',str(out),*sources],capture_output=True,text=True,timeout=100)
   self.assertEqual(proc.returncode,0,proc.stdout+'\n'+proc.stderr)
   run=subprocess.run(['java','-cp',str(out),'check.P14Check'],capture_output=True,text=True,timeout=30)
   self.assertEqual(run.returncode,0,run.stdout+'\n'+run.stderr)
   self.assertIn('conformance OK',run.stdout)
if __name__=='__main__':unittest.main()
