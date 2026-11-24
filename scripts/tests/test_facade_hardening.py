#!/usr/bin/env python3
"""Check the real resolver, comparator and round-trip guard with offline API doubles.

The probes exercise Java logic, not Minecraft's codecs or registry lifecycle.
"""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_ids, target_layout
from source_layout import resolve_effective_source, _materialize_text_file

PKG = 'buildcraft/silicon/plug/'

def effective(target, logical, dest):
    props = load_properties()
    layout = target_layout(target, props)
    source = resolve_effective_source(layout, props, logical)
    assert source is not None, (target, logical)
    output = dest / logical
    downports = tuple(root for root in (layout.family_downport_root, layout.family_platform_downport_root) if root)
    _materialize_text_file(source, output, logical_relative=logical,
        minecraft=props[f'target.{target}.deps.minecraft'], family=layout.family, platform=layout.platform,
        preprocess=True, native_source=any(source.is_relative_to(root) for root in downports),
        native_passthrough=layout.platform == 'fabric' and source.is_relative_to(layout.family_platform_root))
    return output.read_text(encoding='utf-8')


def method(text, signature):
    start = text.index(signature)
    brace = text.index('{', start)
    level = 1
    end = brace + 1
    while level:
        if text[end] == '{': level += 1
        elif text[end] == '}': level -= 1
        end += 1
    return text[start:end]


STUBS = {
    'net/minecraft/world/level/block/state/properties/Property.java': '''package net.minecraft.world.level.block.state.properties;public record Property<T extends Comparable<T>>(String name) {}''',
    'net/minecraft/world/item/Item.java': '''package net.minecraft.world.item;public record Item(String id) {}''',
    'net/minecraft/world/item/Items.java': '''package net.minecraft.world.item;public class Items{public static final Item AIR=new Item("air");}''',
    'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;public record ItemStack(Item item,int count) {}''',
    'net/minecraft/world/level/block/Block.java': '''package net.minecraft.world.level.block;
import java.util.*;import net.minecraft.world.item.*;import net.minecraft.world.level.block.state.*;import net.minecraft.world.level.block.state.properties.*;
public class Block {public final String id;public final Property<Integer> p=new Property<>("variant");public final BlockState defaultState;
public Block(String id){this.id=id;defaultState=new BlockState(this,0);} public Block getStateDefinition(){return this;}
public Collection<Property<?>> getProperties(){return List.of(p);} public BlockState defaultBlockState(){return defaultState;}
public Item asItem(){return id.equals("minecraft:air")?Items.AIR:new Item(id);} public String getDescriptionId(){return "same.translation";}}''',
    'net/minecraft/world/level/block/Blocks.java': '''package net.minecraft.world.level.block;public class Blocks{public static final Block AIR=new Block("minecraft:air");}''',
    'net/minecraft/world/level/block/state/BlockState.java': '''package net.minecraft.world.level.block.state;
import java.util.*;import net.minecraft.world.level.block.*;import net.minecraft.world.level.block.state.properties.*;
public record BlockState(Block getBlock,int variant){public boolean isAir(){return getBlock==Blocks.AIR;}public Collection<Property<?>> getProperties(){return getBlock.getProperties();}
@SuppressWarnings("unchecked") public <T extends Comparable<T>> T getValue(Property<T> p){return (T)Integer.valueOf(variant);}}''',
    'net/minecraft/core/registries/BuiltInRegistries.java': '''package net.minecraft.core.registries;import net.minecraft.world.level.block.Block;public class BuiltInRegistries{public static final BuiltInRegistries BLOCK=new BuiltInRegistries(); public String getKey(Block b){return b.id;}}''',
    'net/minecraftforge/registries/ForgeRegistries.java': '''package net.minecraftforge.registries;import net.minecraft.world.level.block.Block;public class ForgeRegistries{public static final ForgeRegistries BLOCKS=new ForgeRegistries(); public String getKey(Block b){return b.id;}}''',
    'net/minecraft/network/FriendlyByteBuf.java': '''package net.minecraft.network;public class FriendlyByteBuf{public Object data;public int remaining;public int readableBytes(){return remaining;}public void clear(){data=null;remaining=0;}}''',
    'com/google/common/collect/ImmutableSet.java': '''package com.google.common.collect;import java.util.*;public class ImmutableSet<T> extends HashSet<T>{public static <T> ImmutableSet<T> of(){return new ImmutableSet<>();}public static <T> ImmutableSet<T> copyOf(Collection<? extends T> c){ImmutableSet<T> s=new ImmutableSet<>();s.addAll(c);return s;}}''',
    PKG+'StackUtil.java': '''package buildcraft.silicon.plug;import net.minecraft.world.item.*;public class StackUtil{public static final ItemStack EMPTY=new ItemStack(Items.AIR,0);}''',
    PKG+'FacadeBlockStateInfo.java': '''package buildcraft.silicon.plug;import net.minecraft.world.level.block.state.*;import net.minecraft.world.item.*;import net.minecraft.world.level.block.state.properties.*;import com.google.common.collect.ImmutableSet;
public class FacadeBlockStateInfo{public final BlockState state; public final ItemStack requiredStack; public final ImmutableSet<Property<?>> varyingProperties;
public FacadeBlockStateInfo(BlockState s,ItemStack i,ImmutableSet<Property<?>> p){state=s;requiredStack=i;varyingProperties=p;}public FacadePhasedState createPhased(Object ignored){return new FacadePhasedState(this);}}''',
    PKG+'FacadePhasedState.java': '''package buildcraft.silicon.plug;import net.minecraft.network.FriendlyByteBuf;import net.minecraft.world.level.block.*;import net.minecraft.world.level.block.state.*;
public class FacadePhasedState{static boolean corruptNbt,corruptNet,trailing;final FacadeBlockStateInfo stateInfo;FacadePhasedState(FacadeBlockStateInfo i){stateInfo=i;}
BlockState writeToNbt(){return stateInfo.state;}static FacadePhasedState readFromNbt(BlockState s){return new FacadePhasedState(FacadeStateManager.resolvePersistedState(corruptNbt?Blocks.AIR.defaultBlockState():s));}
void writeToBuffer(FriendlyByteBuf b){b.data=stateInfo.state;b.remaining=1;}static FacadePhasedState readFromBuffer(FriendlyByteBuf b){b.remaining=trailing?1:0;return new FacadePhasedState(FacadeStateManager.resolvePersistedState(corruptNet?Blocks.AIR.defaultBlockState():(BlockState)b.data));}}''',
}
IMPORTS = '''package buildcraft.silicon.plug;import java.util.*;import net.minecraft.world.level.block.*;import net.minecraft.world.level.block.state.*;import net.minecraft.world.level.block.state.properties.*;import net.minecraft.world.item.*;import com.google.common.collect.ImmutableSet;'''
PROBE = IMPORTS + '''import net.minecraft.network.FriendlyByteBuf;
public class HardeningProbe {
 static int checks; static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
 static void reject(FacadeBlockStateInfo i,FriendlyByteBuf b){boolean rejected=false;try{FacadeStateRoundTrip.verify(i,b);}catch(IllegalStateException expected){rejected=true;}check(rejected);check(b.readableBytes()==0&&b.data==null);}
 public static void main(String[] args){Block a=new Block("alpha:stone"),b=new Block("beta:stone");BlockState s=a.defaultBlockState(),t=new BlockState(a,1),u=b.defaultBlockState();var order=BlockUtil.blockStateComparator();
 check(order.compare(s,u)<0&&order.compare(u,s)>0);check(order.compare(s,t)<0&&order.compare(t,s)>0);check(order.compare(s,new BlockState(a,0))==0);
 TreeMap<BlockState,Integer> map=new TreeMap<>(order);map.put(s,1);map.put(t,2);map.put(u,3);check(map.size()==3);
 var missing=FacadeStateManager.resolvePersistedState(t);check(missing.state==t);check(missing.varyingProperties.contains(a.p));check(FacadeStateManager.getIndexedStates().isEmpty());
 check(FacadeStateManager.resolvePersistedState(Blocks.AIR.defaultBlockState())==FacadeStateManager.getDefaultState());
 FacadeStateManager.validFacadeStates.put(t,missing);check(FacadeStateManager.resolvePersistedState(t)==missing);check(FacadeStateManager.getIndexedStates().size()==1);
 var buf=new FriendlyByteBuf();FacadeStateRoundTrip.verify(missing,buf);check(buf.data==null&&buf.readableBytes()==0);
 FacadePhasedState.corruptNbt=true;reject(missing,buf);FacadePhasedState.corruptNbt=false;
 FacadePhasedState.corruptNet=true;reject(missing,buf);FacadePhasedState.corruptNet=false;
 FacadePhasedState.trailing=true;reject(missing,buf);FacadePhasedState.trailing=false;
 FacadeStateManager.validFacadeStates.clear();reject(missing,buf);
 System.out.println("PASS "+checks+" facade hardening checks");}}
'''

class FacadeHardeningTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'Java SDK required')
    def test_actual_resolver_comparator_and_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            for target in ('1.19.2-forge','26.1.2-neoforge','26.3-fabric'):
                with self.subTest(target=target):
                    root=tmp/target
                    manager=effective(target,'src/main/java/'+PKG+'FacadeStateManager.java',root/'effective')
                    util=effective(target,'src/main/java/buildcraft/lib/misc/BlockUtil.java',root/'effective')
                    sources={**STUBS,PKG+'HardeningProbe.java':PROBE}
                    sources[PKG+'BlockUtil.java']=IMPORTS+'public class BlockUtil{'+method(util,'public static Comparator<BlockState> blockStateComparator()')+method(util,'public static <T extends Comparable<T>> int compareProperty(')+'}'
                    sources[PKG+'FacadeStateManager.java']=IMPORTS+'public class FacadeStateManager{static FacadeBlockStateInfo defaultState,previewState;static final SortedMap<BlockState,FacadeBlockStateInfo> validFacadeStates=new TreeMap<>(BlockUtil.blockStateComparator());'+''.join(method(manager,'public static synchronized '+signature) for signature in ('FacadeBlockStateInfo getDefaultState()','List<FacadeBlockStateInfo> getIndexedStates()','FacadeBlockStateInfo resolvePersistedState(BlockState state)'))+'}'
                    sources[PKG+'FacadeStateRoundTrip.java']=(ROOT/'source-shared/src/main/java'/PKG/'FacadeStateRoundTrip.java').read_text()
                    for name,code in sources.items():
                        path=root/'src'/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(code,encoding='utf-8')
                    subprocess.run(['javac','--release','17','-d',str(root/'classes'),*map(str,(root/'src').rglob('*.java'))],check=True)
                    subprocess.run(['java','-cp',str(root/'classes'),'buildcraft.silicon.plug.HardeningProbe'],check=True)

    def test_all_targets_use_hardening(self):
        for target in target_ids(load_properties()):
            with self.subTest(target=target),tempfile.TemporaryDirectory() as tmp:
                tmp=Path(tmp)
                for path,tokens in {
                    PKG+'FacadeStateManager.java':('FacadeStateRoundTrip.verify(info,','Map<ItemStackKey, Set<Map<String, String>>> addedSignatures','resolvePersistedState(BlockState state)','canPlaceState(BlockState state)','if (a == defaultBlockState) return -1;'),
                    PKG+'FacadePhasedState.java':('resolvePersistedState(',),
                    'buildcraft/silicon/item/ItemPluggableFacade.java':('creativeCache.appendAll','createCachedSingle','canPlaceState('),
                    'buildcraft/silicon/recipe/FacadeAssemblyRecipes.java':('createCachedSingle(',),
                    'buildcraft/compat/jei/BuildCraftJeiPlugin.java':('getIndexedStates()',),
                    'buildcraft/lib/misc/BlockUtil.java':('getStateDefinition().getProperties()','idA.compareTo(idB)'),
                }.items():
                    code=effective(target,'src/main/java/'+path,tmp)
                    for token in tokens:self.assertIn(token,code)
                if target!='1.19.2-forge':
                    for logical,value in [('creative_mode_tab','buildcraft:facades'),('items' if target=='1.20.1-forge' else 'item','buildcraftsilicon:plug/facade')]:
                        if target=='1.20.1-forge' and logical=='creative_mode_tab':
                            tag=(ROOT/'resource-src/old/1.20.1/data/c/tags/creative_mode_tab/hidden_from_recipe_viewers.json').read_text(encoding='utf-8')
                        else:
                            tag=effective(target,'src/main/resources/data/c/tags/'+logical+'/hidden_from_recipe_viewers.json',tmp)
                        self.assertIn(value,json.loads(tag)['values'])

if __name__=='__main__': unittest.main()
