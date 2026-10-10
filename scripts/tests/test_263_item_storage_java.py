#!/usr/bin/env python3
"""Compile and execute 26.3 BCCE internal inventory compatibility wrappers with vanilla-shaped stubs."""
from __future__ import annotations

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
CLASSES=ROOT/'source-families/26.X/src/main/java/buildcraft/lib/compat/neoforge263/items'

ITEM_STACK=r'''package net.minecraft.world.item;
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
CONTAINER=r'''package net.minecraft.world;
import net.minecraft.world.item.ItemStack;
public interface Container {
    int getContainerSize(); ItemStack getItem(int slot);
    ItemStack removeItem(int slot, int count);
    void setItem(int slot, ItemStack stack); void setChanged();
    default int getMaxStackSize(ItemStack stack){return stack.getMaxStackSize();}
    default boolean canPlaceItem(int slot,ItemStack stack){return true;}
}
'''
HARNESS=r'''import buildcraft.lib.compat.neoforge263.items.wrapper.InvWrapper;
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
                'net/minecraft/world/item/ItemStack.java':ITEM_STACK,
                'net/minecraft/world/Container.java':CONTAINER,
                'Validate263ItemStorage.java':HARNESS,
            }
            for path,content in stubfiles.items():
                dst=root/path;dst.parent.mkdir(parents=True,exist_ok=True);dst.write_text(content)
            own=[CLASSES/'IItemHandler.java',CLASSES/'IItemHandlerModifiable.java',
                CLASSES/'wrapper/InvWrapper.java',CLASSES/'wrapper/CombinedInvWrapper.java']
            cmd=['javac','-d',str(root/'classes')]+[str(x) for x in own]+[str(root/x) for x in stubfiles]
            build=subprocess.run(cmd,capture_output=True,text=True,timeout=25)
            self.assertEqual(0,build.returncode,build.stderr)
            run=subprocess.run(['java','-cp',str(root/'classes'),'Validate263ItemStorage'],capture_output=True,text=True,timeout=15)
            self.assertEqual(0,run.returncode,run.stderr)
            self.assertIn('assertions PASS',run.stdout)
            print(run.stdout.strip(),flush=True)

if __name__=='__main__': unittest.main()
