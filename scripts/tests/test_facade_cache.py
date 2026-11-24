#!/usr/bin/env python3
"""Exercise the actual persistent/creative facade cache with small registry stand-ins."""
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_layout, target_ids
from source_layout import resolve_effective_source, _materialize_text_file

PKG = 'buildcraft/silicon/plug/'
BASE = ROOT / 'source-shared/src/main/java'
STUBS = {
    'buildcraft/lib/internal/debug/BCLog.java': '''package buildcraft.lib.internal.debug;
public class BCLog {public static void explicitEvent(String s,Object... args){} public static final Log logger=new Log(); public static class Log {
public void info(String s,Object... args){} public void warn(String s,Object... args){} }}''',
    'buildcraft/lib/net/BuildCraftTarget.java': '''package buildcraft.lib.net;
public class BuildCraftTarget {public static final String MINECRAFT_VERSION="test",LOADER="test",MOD_VERSION="8.0.31",GIT_COMMIT_HASH="test";}''',
    'buildcraft/lib/internal/api/v2/BuildCraftApiRuntime.java': '''package buildcraft.lib.internal.api.v2;
import java.util.*;import net.minecraft.world.level.block.Block;
public class BuildCraftApiRuntime {public static final BuildCraftApiRuntime INSTANCE=new BuildCraftApiRuntime();
public final Set<Block> dynamic=new HashSet<>(); public BuildCraftApiRuntime facadeRules(){return this;}
public boolean hasRuleFor(Block b){return dynamic.contains(b);}}''',
    'net/minecraft/tags/BlockTags.java': '''package net.minecraft.tags;public class BlockTags{public static final String FLOWERS="flowers",LEAVES="leaves";}''',
    'net/minecraft/world/item/Item.java': '''package net.minecraft.world.item;public record Item(String id){}''',
    'net/minecraft/world/item/Items.java': '''package net.minecraft.world.item;public class Items{public static final Item AIR=new Item("minecraft:air");}''',
    'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;public class ItemStack{public final Item item; public int count; public ItemStack(Item i,int n){item=i;count=n;}public ItemStack copy(){return new ItemStack(item,count);}}''',
    'net/minecraft/world/level/block/state/properties/Property.java': '''package net.minecraft.world.level.block.state.properties;
import java.util.*;import java.util.stream.*;
public class Property<T extends Comparable<T>> {final String name; final List<T> values; public Property(String n,List<T> v){name=n;values=v;}
public String getName(){return name;} public String getName(T v){return v.toString();} public Optional<T> getValue(String s){return values.stream().filter(v->getName(v).equals(s)).findFirst();}
public record Value<T extends Comparable<T>>(T value){} public Stream<Value<T>> getAllValues(){return values.stream().map(Value::new);} }''',
    'net/minecraft/world/level/block/Block.java': '''package net.minecraft.world.level.block;
import java.util.*;import net.minecraft.world.item.*;import net.minecraft.world.level.block.state.*;import net.minecraft.world.level.block.state.properties.*;
public class Block {public final String id;public boolean flowers=false, leaves=false, valid=true, fail=false;public Item item;
public final Property<String> p; public Block(String id,String... values){this.id=id;item=new Item(id);p=new Property<>("variant",List.of(values));}
public Item asItem(){return item;}public BlockState defaultBlockState(){return new BlockState(this,p.getAllValues().findFirst().orElseThrow().value());}
public Block getStateDefinition(){return this;} public Collection<Property<?>> getProperties(){return List.of(p);} }''',
    'net/minecraft/world/level/block/state/BlockState.java': '''package net.minecraft.world.level.block.state;
import java.util.*;import net.minecraft.world.level.block.*;import net.minecraft.world.level.block.state.properties.*;
public record BlockState(Block block,String value){public boolean is(String s){return s.equals("flowers")?block.flowers:block.leaves;}
public Collection<Property<?>> getProperties(){return block.getProperties();}public <T extends Comparable<T>> BlockState setValue(Property<T> p,T v){return new BlockState(block,v.toString());}}''',
    'buildcraft/lib/misc/BlockUtil.java': '''package buildcraft.lib.misc;import java.util.*;import net.minecraft.world.level.block.state.BlockState;
public class BlockUtil {public static Map<String,String> getPropertiesStringMap(BlockState s){return Map.of("variant",s.value());}}''',
    'com/google/common/collect/ImmutableSet.java': '''package com.google.common.collect;import java.util.*;
public class ImmutableSet<T> extends HashSet<T>{public static <T> Builder<T> builder(){return new Builder<>();}
public static class Builder<T>{final ImmutableSet<T> s=new ImmutableSet<>();public Builder<T> add(T x){s.add(x);return this;}public ImmutableSet<T> build(){return s;}}}''',
    PKG+'FacadeBlockStateInfo.java': '''package buildcraft.silicon.plug;import com.google.common.collect.ImmutableSet;import net.minecraft.world.item.*;import net.minecraft.world.level.block.state.*;import net.minecraft.world.level.block.state.properties.*;
public class FacadeBlockStateInfo {public final BlockState state;public final ItemStack requiredStack;public final ImmutableSet<Property<?>> varyingProperties;
public FacadeBlockStateInfo(BlockState s,ItemStack i,ImmutableSet<Property<?>> p){state=s;requiredStack=i;varyingProperties=p;}}''',
    PKG+'FacadeStateManager.java': '''package buildcraft.silicon.plug;public class FacadeStateManager{static long gen;public static long indexGeneration(){return gen;}}''',
    PKG+'FacadeStateValidator.java': 'package buildcraft.silicon.plug;class FacadeStateValidator{}',
    PKG+'FacadeStateRoundTrip.java': 'package buildcraft.silicon.plug;class FacadeStateRoundTrip{}',
    PKG+'FacadePhasedState.java': 'package buildcraft.silicon.plug;class FacadePhasedState{}',
    PKG+'FacadeInstance.java': '''package buildcraft.silicon.plug;public record FacadeInstance(FacadeBlockStateInfo info,boolean flipped){public static FacadeInstance createSingle(FacadeBlockStateInfo i,boolean f){return new FacadeInstance(i,f);}}''',
}
PROBE = '''package buildcraft.silicon.plug;
import java.util.*;import java.nio.file.*;import java.nio.file.attribute.FileTime;import com.google.common.collect.ImmutableSet;
import net.minecraft.world.level.block.*;import net.minecraft.world.level.block.state.*;import net.minecraft.world.level.block.state.properties.*;import net.minecraft.world.item.*;
import buildcraft.lib.internal.api.v2.BuildCraftApiRuntime;
public class CacheProbe {
 static int checks;static Path root,dir;static Map<String,String> versions=new TreeMap<>();static List<Block> blocks=new ArrayList<>();
 static List<String> scanned=new ArrayList<>(), restored=new ArrayList<>();static List<FacadeBlockStateInfo> infos=new ArrayList<>();
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m+" scans="+scanned+" restored="+restored);}
 static boolean valid(BlockState s){return s.block().valid&&!s.block().flowers;}
 static FacadeBlockStateInfo info(Block b,String value){return new FacadeBlockStateInfo(new BlockState(b,value),new ItemStack(b.item,1),ImmutableSet.<Property<?>>builder().add(b.p).build());}
 static void run(boolean commit){scanned.clear();restored.clear();infos.clear();Runnable save=FacadePersistentCache.index(root,versions,blocks,b->b.id,i->i.id(),(b,c)->{scanned.add(b.id);if(b.fail){c.failed=true;return;}if(valid(b.defaultBlockState())){b.p.getAllValues().forEach(v->{var i=info(b,v.value());c.add(i);infos.add(i);});}}, list->{for(var i:list){restored.add(i.state.block().id+":"+i.state.value());infos.add(i);}},CacheProbe::valid,false);if(commit)save.run();}
 static Path section(String n){return dir.resolve("section-"+n+".bin");}
 public static void main(String[] args)throws Exception{root=Path.of(args[0]);dir=root.resolve(".cache/buildcraft/facades/test-test");
 Block a=new Block("alpha:stone","normal","lit"),b=new Block("beta:stone","normal"),bad=new Block("beta:plant","normal");bad.valid=false;
 blocks.addAll(List.of(a,b,bad));versions.putAll(Map.of("alpha","1","beta","1","buildcraftlib","8.0.31","minecraft","test"));
 run(false);check(scanned.size()==3&&!Files.exists(dir),"defer writes until complete");
 run(true);check(scanned.size()==3&&infos.size()==3,"cold scan");check(Files.readString(dir.resolve("mods.txt")).contains("alpha=1"),"manifest");
 Files.setLastModifiedTime(section("beta"),FileTime.fromMillis(100000));
 run(true);check(scanned.isEmpty()&&restored.size()==3,"warm cache and negative result");check(Files.getLastModifiedTime(section("beta")).toMillis()==100000,"unchanged section not rewritten");
 check(infos.stream().filter(i->i.state.block()==a).allMatch(i->i.varyingProperties.contains(a.p)),"varying props restored");
 versions.put("alpha","2");run(true);check(scanned.equals(List.of(a.id)),"only updated namespace scanned");check(Files.getLastModifiedTime(section("beta")).toMillis()==100000,"unrelated file untouched");
 Block c=new Block("gamma:brick","normal");blocks.add(c);versions.put("gamma","1");run(true);check(scanned.equals(List.of(c.id)),"only added namespace scanned");
 blocks.remove(c);versions.remove("gamma");run(true);check(scanned.isEmpty()&&!Files.exists(section("gamma")),"removed namespace pruned");
 byte[] bytes=Files.readAllBytes(section("alpha"));bytes[bytes.length-1]^=1;Files.write(section("alpha"),bytes);run(true);check(scanned.equals(List.of(a.id)),"corrupt section recovered independently");
 BuildCraftApiRuntime.INSTANCE.dynamic.add(a);run(true);check(scanned.equals(List.of(a.id)),"dynamic rule bypass");run(true);check(scanned.equals(List.of(a.id)),"dynamic rule never persisted");BuildCraftApiRuntime.INSTANCE.dynamic.clear();run(true);check(scanned.equals(List.of(a.id)),"rule removed rescan");
 a.valid=false;run(true);check(scanned.equals(List.of(a.id)),"restored geometry revalidated");a.valid=true;a.flowers=true;run(true);check(scanned.equals(List.of(a.id)),"tags invalidate section");a.flowers=false;run(true);check(scanned.equals(List.of(a.id)),"tag removal invalidates negative result");
 Block a2=new Block(a.id,"normal","lit","new");blocks.set(0,a2);run(true);check(scanned.equals(List.of(a.id)),"state schema change invalidation");
 a2.fail=true;versions.put("alpha","3");run(true);check(scanned.equals(List.of(a.id)),"failed scan");a2.fail=false;run(true);check(scanned.equals(List.of(a.id)),"failed result not cached");
 System.setProperty("buildcraft.facades.disableCache","true");run(true);check(scanned.size()==3,"manual bypass");System.clearProperty("buildcraft.facades.disableCache");
 var creative=new FacadeCreativeCache();var info=info(a2,"normal");List<ItemStack> out=new ArrayList<>();int[] builds={0};java.util.function.Function<FacadeInstance,ItemStack> factory=i->{builds[0]++;return new ItemStack(i.info().requiredStack.item,1);};
 creative.append(info,factory,out::add);out.get(0).count=99;out.clear();creative.append(info,factory,out::add);check(builds[0]==2&&out.get(0).count==1,"cached stack copies");FacadeStateManager.gen++;creative.append(info,factory,out::add);check(builds[0]==4,"creative generation invalidation");
 int[] tabBuilds={0};java.util.function.Consumer<java.util.function.Consumer<ItemStack>> builder=output->{tabBuilds[0]++;creative.append(info,factory,output);};
 out.clear();creative.appendAll(builder,out::add);out.get(0).count=99;out.clear();creative.appendAll(builder,out::add);check(tabBuilds[0]==1&&out.size()==2&&out.get(0).count==1,"whole creative tab snapshot and copy isolation");
 FacadeStateManager.gen++;out.clear();creative.appendAll(builder,out::add);check(tabBuilds[0]==2&&out.size()==2,"whole creative snapshot invalidates with generation");
 out.get(0).count=6;check(creative.get(info,false,factory).count==1,"recipe copies cannot mutate creative template");
 System.out.println("PASS "+checks+" facade cache checks");}
}'''


class FacadeCacheTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'Java SDK required')
    def test_incremental_cache(self):
        with tempfile.TemporaryDirectory(prefix='bcce-cache-probe-') as tmp:
            tmp = Path(tmp)
            sources = tmp / 'src'
            for path, code in {**STUBS, PKG+'CacheProbe.java': PROBE}.items():
                p = sources / path
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(code, encoding='utf-8')
            for cls in ('FacadeCacheStorage', 'FacadePersistentCache', 'FacadeCreativeCache'):
                shutil.copy(BASE / PKG / (cls + '.java'), sources / PKG)
            subprocess.run(['javac', '--release', '17', '-d', str(tmp/'classes'), *map(str, sources.rglob('*.java'))], check=True)
            subprocess.run(['java', '-cp', str(tmp/'classes'), 'buildcraft.silicon.plug.CacheProbe', str(tmp/'game')], check=True)

    def test_every_target_wires_cache(self):
        props = load_properties()
        for target in target_ids(props):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as tmp:
                layout = target_layout(target, props)
                for logical, tokens in {
                    'src/main/java/'+PKG+'FacadeStateManager.java': ('FacadePersistentCache.index(', 'FacadeStateManager::registerCached', 'capture.add(info)', 'testingBuffer.release()', 'indexGeneration++'),
                    'src/main/java/buildcraft/silicon/item/ItemPluggableFacade.java': ('new FacadeCreativeCache()', 'creativeCache.append('),
                    'src/main/java/buildcraft/lib/internal/api/v2/FacadeRuleRegistryImpl.java': ('hasRuleFor(Block block)', 'mappedBlocks.add(state.getBlock())'),
                }.items():
                    source = resolve_effective_source(layout, props, logical)
                    out = Path(tmp) / logical
                    _materialize_text_file(source, out, logical_relative=logical,
                        minecraft=props[f'target.{target}.deps.minecraft'], family=layout.family,
                        platform=layout.platform, preprocess=True)
                    text = out.read_text(encoding='utf-8')
                    for token in tokens:
                        self.assertIn(token, text)

if __name__ == '__main__':
    unittest.main()
