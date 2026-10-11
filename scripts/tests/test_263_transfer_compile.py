#!/usr/bin/env python3
"""26.3 API-removal regression: isolated NeoForge 26.3 transfer bridges and oil Feature codec.

These tests verify source selection and resource compatibility, not full Minecraft compilation.
"""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_layout import resolve_effective_source, target_layout, load_properties
from source_layout import _materialize_text_file
from transforms.neoforge263 import relocate_removed_neoforge_apis

REMOVED = (
    'net.neoforged.neoforge.items.IItemHandler',
    'net.neoforged.neoforge.fluids.capability.IFluidHandler',
    'net.neoforged.neoforge.energy.IEnergyStorage',
    'net.neoforged.neoforge.fluids.FluidUtil',
    'net.neoforged.neoforge.fluids.IFluidTank',
    'net.neoforged.neoforge.items.wrapper.InvWrapper',
)

class Compile263Regression(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import tempfile
        cls.tmp = tempfile.TemporaryDirectory(prefix='bcce-263-api-')
        cls.props = load_properties()
        cls.sources = {}
        selected = (
            'lib/compat/transfer/TransferInterop.java',
            'lib/platform/storage/PlatformStorage.java',
            'lib/misc/CapUtil.java',
            'lib/fluid/Tank.java',
            'energy/BCEnergyWorldGen.java',
            'energy/generation/features/OilGenFeature.java',
            'energy/generation/features/OilFeatureConfiguration.java',
            'builders/snapshot/FakeWorld.java',
        )
        for ver in ('26.2','26.3'):
            layout=target_layout(ver+'-neoforge',cls.props)
            downs=tuple(r for r in (layout.family_downport_root,layout.family_platform_downport_root) if r)
            for rel in selected:
                logical='src/main/java/buildcraft/'+rel
                source=resolve_effective_source(layout,cls.props,logical)
                assert source is not None,logical
                output=Path(cls.tmp.name)/ver/logical
                _materialize_text_file(source,output,logical_relative=logical,
                  minecraft=ver,family=layout.family,platform=layout.platform,
                  preprocess=True,native_source=any(source.is_relative_to(d) for d in downs))
                cls.sources[ver,rel] = output.read_text(encoding='utf-8')

    @classmethod
    def tearDownClass(cls): cls.tmp.cleanup()

    def test_api_relocations_only_on_263(self):
        sample='\n'.join('import '+s+';' for s in REMOVED)
        self.assertEqual(sample, relocate_removed_neoforge_apis(sample,minecraft='26.2',relative='Any.java'))
        new=relocate_removed_neoforge_apis(sample,minecraft='26.3',relative='Any.java')
        for old in REMOVED: self.assertNotIn(old,new)
        self.assertIn('buildcraft.lib.compat.neoforge263.',new)

    def test_262_previous_api_preserved(self):
        for rel in ('lib/compat/transfer/TransferInterop.java','lib/misc/CapUtil.java'):
            self.assertIn('net.neoforged.neoforge.items.IItemHandler;',self.sources['26.2',rel])
            self.assertNotIn('buildcraft.lib.compat.neoforge263.',self.sources['26.2',rel])

    def test_263_transfer_bridge_not_legacy_loader_api(self):
        for rel in ('lib/compat/transfer/TransferInterop.java','lib/misc/CapUtil.java',
                    'lib/platform/storage/PlatformStorage.java','lib/fluid/Tank.java'):
            src=self.sources['26.3',rel]
            for old in REMOVED: self.assertNotIn(old,src,rel)
        self.assertIn('TransferInterop.importEnergy(handler)',
                      self.sources['26.3','lib/platform/storage/PlatformStorage.java'])
        self.assertIn('TransferInterop.importFluids(modern)',
                      self.sources['26.3','lib/misc/CapUtil.java'])

    def test_263_only_java_contracts(self):
        base=ROOT/'source-families/26.X/src/main/java/buildcraft/lib/compat/neoforge263'
        classes=('items/IItemHandler.java','items/IItemHandlerModifiable.java',
           'items/SlotItemHandler.java','items/ItemHandlerCopySlot.java',
           'items/wrapper/CombinedInvWrapper.java','items/wrapper/InvWrapper.java',
           'fluids/IFluidHandler.java','fluids/IFluidHandlerItem.java',
           'fluids/IFluidTank.java','fluids/FluidUtil.java','energy/IEnergyStorage.java')
        for cls in classes:
            path=base/cls
            self.assertTrue(path.is_file(),cls)
            self.assertTrue(path.read_text().startswith('//? source if >=26.3'),cls)

    def test_263_worldgen_uses_feature_type_and_map_codec(self):
        wg=self.sources['26.3','energy/BCEnergyWorldGen.java']
        self.assertIn('"minecraft:worldgen/feature_type"',wg)
        self.assertIn('BCDeferredRegister<MapCodec<? extends Feature>>',wg)
        feature=self.sources['26.3','energy/generation/features/OilGenFeature.java']
        self.assertIn('implements Feature',feature)
        self.assertIn('MapCodec<OilGenFeature>',feature)
        self.assertNotIn('FeaturePlaceContext',feature)
        config=self.sources['26.3','energy/generation/features/OilFeatureConfiguration.java']
        self.assertIn('MapCodec<OilFeatureConfiguration> CODEC = RecordCodecBuilder.mapCodec',config)
        self.assertNotIn('implements FeatureConfiguration',config)
        old=self.sources['26.2','energy/generation/features/OilGenFeature.java']
        self.assertIn('extends Feature<OilFeatureConfiguration>',old)

    def test_263_fake_world_removed_262_hooks(self):
        old=self.sources['26.2','builders/snapshot/FakeWorld.java']
        new=self.sources['26.3','builders/snapshot/FakeWorld.java']
        self.assertIn('FuelValues fuelValues()',old)
        self.assertNotIn('FuelValues fuelValues()',new)
        self.assertNotIn('PotionBrewing',new)

    def test_263_oil_placement_inline(self):
        src=ROOT/'resource-src/26.X/26.3/data/buildcraftenergy/worldgen/placed_feature/oil_placed_feature.json'
        value=json.loads(src.read_text(encoding='utf-8'))
        self.assertEqual('buildcraftenergy:worldgen.feature.oil',value['feature']['type'])
        self.assertIn('oilStructureSetting',value['feature'])
        self.assertEqual('buildcraftenergy:oil', value['feature']['oilStructureSetting']['genOilState'])
        self.assertNotIsInstance(value['feature']['oilStructureSetting']['genOilState'], dict)
        self.assertNotIn('config',value['feature'])
        self.assertIn('placement',value)

    def test_263_neoforge_dependency_range_tracks_pinned_version(self):
        dep = self.props['target.26.3-neoforge.deps.neoforge']
        version_range = self.props['target.26.3-neoforge.neoforge.version_range']
        self.assertTrue(dep.startswith('26.3.'), dep)
        self.assertEqual(f'[{dep},)', version_range)
        self.assertNotEqual(dep, self.props['target.26.2-neoforge.deps.neoforge'])



# ---- 26.3 inventory adapter Java contract ----



import shutil
import subprocess
import tempfile

ITEM_STORAGE_263_ROOT=Path(__file__).resolve().parents[2]
ITEM_STORAGE_263_CLASSES=ITEM_STORAGE_263_ROOT/'source-families/26.X/src/main/java/buildcraft/lib/compat/neoforge263/items'

ITEM_STORAGE_263_ITEM_STACK=r'''package net.minecraft.world.item;
public final class ItemStack {
    public static final ItemStack EMPTY = new ItemStack("", 0);
    private String id; private int count;
    public ItemStack(String id,int count){this.id=id;this.count=count;}
    public boolean isEmpty(){return count<=0 || id.isEmpty();}
    public int getCount(){return count;}
    public int getMaxStackSize(){return 64;}
    public void grow(int count){this.count += count;}
    public ItemStack copy(){return new ItemStack(id,count);}
    public ItemStack copyWithCount(int c){return new ItemStack(id,c);}
    public static boolean isSameItemSameComponents(ItemStack a,ItemStack b){return a.id.equals(b.id);}
    public String toString(){return id+"="+count;}
}
'''
ITEM_STORAGE_263_CONTAINER=r'''package net.minecraft.world;
import net.minecraft.world.item.ItemStack;
public interface Container {
    int getContainerSize(); ItemStack getItem(int slot);
    ItemStack removeItem(int slot, int count);
    void setItem(int slot, ItemStack stack); void setChanged();
    default int getMaxStackSize(ItemStack stack){return stack.getMaxStackSize();}
    default boolean canPlaceItem(int slot,ItemStack stack){return true;}
}
'''
ITEM_STORAGE_263_HARNESS=r'''import buildcraft.lib.compat.neoforge263.items.wrapper.InvWrapper;
import buildcraft.lib.compat.neoforge263.items.wrapper.CombinedInvWrapper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
public class Validate263ItemStorage {
  static int checked=0;
  static void is(boolean b){checked++;if(!b) throw new AssertionError("check "+checked);}
  static ItemStack s(String name,int count){return new ItemStack(name,count);}
  static class Inventory implements Container {
    final ItemStack[] slots={ItemStack.EMPTY,ItemStack.EMPTY}; boolean forbid=false;int changes=0;
    public int getContainerSize(){return slots.length;}
    public ItemStack getItem(int n){return slots[n];}
    public ItemStack removeItem(int n,int count){int taken=Math.min(slots[n].getCount(),count);
      ItemStack result=slots[n].copyWithCount(taken);slots[n]=slots[n].copyWithCount(slots[n].getCount()-taken);return result;}
    public void setItem(int n,ItemStack value){slots[n]=value;changes++;}
    public void setChanged(){changes++;}
    public boolean canPlaceItem(int n,ItemStack stack){return !forbid;}
  }
  public static void main(String[] a){
    Inventory first=new Inventory(); InvWrapper h=new InvWrapper(first);
    is(h.getSlots()==2);is(h.getStackInSlot(0).isEmpty());
    is(h.insertItem(0,s("iron",22),true).isEmpty()); is(h.getStackInSlot(0).isEmpty());
    is(h.insertItem(0,s("iron",22),false).isEmpty());is(h.getStackInSlot(0).getCount()==22);
    is(h.extractItem(0,5,true).getCount()==5);is(h.getStackInSlot(0).getCount()==22);
    is(h.extractItem(0,5,false).getCount()==5);is(h.getStackInSlot(0).getCount()==17);
    is(h.insertItem(0,s("gold",1),false).getCount()==1); is(h.getStackInSlot(0).getCount()==17);
    is(h.insertItem(0,s("iron",50),false).getCount()==3);is(h.getStackInSlot(0).getCount()==64);
    is(h.getSlotLimit(0)==64);is(h.extractItem(0,99,false).getCount()==64);
    is(h.getStackInSlot(0).isEmpty());
    first.forbid=true;is(!h.isItemValid(0,s("gold",1)));is(h.insertItem(0,s("gold",1),false).getCount()==1);
    first.forbid=false;
    h.setStackInSlot(0,s("copper",8));is(h.getStackInSlot(0).getCount()==8);
    Inventory second=new Inventory(); InvWrapper right=new InvWrapper(second);
    CombinedInvWrapper both=new CombinedInvWrapper(h,right);
    is(both.getSlots()==4);is(both.getStackInSlot(0).getCount()==8);
    both.setStackInSlot(2,s("steel",4));is(right.getStackInSlot(0).getCount()==4);
    is(both.getStackInSlot(2).getCount()==4);
    is(both.extractItem(2,2,false).getCount()==2);is(right.getStackInSlot(0).getCount()==2);
    is(both.insertItem(3,s("copper",3),false).isEmpty());
    is(second.getItem(1).getCount()==3);is(first.changes>0 && second.changes>0);
    System.out.println("26.3 inventory bridge: "+checked+" assertions PASS");
  }
}
'''

class JavaItemBridge263(unittest.TestCase):
    def test_inventory_wrapper_real_java(self):
        if not shutil.which('javac') or not shutil.which('java'): self.skipTest('JDK missing')
        with tempfile.TemporaryDirectory(prefix='bcce-263-items-') as temp:
            root=Path(temp)
            stubfiles={
                'net/minecraft/world/item/ItemStack.java':ITEM_STORAGE_263_ITEM_STACK,
                'net/minecraft/world/Container.java':ITEM_STORAGE_263_CONTAINER,
                'Validate263ItemStorage.java':ITEM_STORAGE_263_HARNESS,
            }
            for path,content in stubfiles.items():
                dst=root/path;dst.parent.mkdir(parents=True,exist_ok=True);dst.write_text(content)
            own=[ITEM_STORAGE_263_CLASSES/'IItemHandler.java',ITEM_STORAGE_263_CLASSES/'IItemHandlerModifiable.java',
                ITEM_STORAGE_263_CLASSES/'wrapper/InvWrapper.java',ITEM_STORAGE_263_CLASSES/'wrapper/CombinedInvWrapper.java']
            cmd=['javac','-d',str(root/'classes')]+[str(x) for x in own]+[str(root/x) for x in stubfiles]
            build=subprocess.run(cmd,capture_output=True,text=True,timeout=25)
            self.assertEqual(0,build.returncode,build.stderr)
            run=subprocess.run(['java','-cp',str(root/'classes'),'Validate263ItemStorage'],capture_output=True,text=True,timeout=15)
            self.assertEqual(0,run.returncode,run.stderr)
            self.assertIn('assertions PASS',run.stdout)
            print(run.stdout.strip(),flush=True)


if __name__ == '__main__':
    unittest.main()
