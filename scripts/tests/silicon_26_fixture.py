"""Compile and exercise the 26.3 Silicon GUI display container against typed Java API doubles."""
from __future__ import annotations

import subprocess
import tempfile
from pathlib import Path

STUBS = {
    'net/minecraft/world/item/ItemStack': '''package net.minecraft.world.item;
public final class ItemStack {
 public static final ItemStack EMPTY = new ItemStack("", 0);
 public final String id;
 private int count;
 public ItemStack(String id, int count) {this.id=id;this.count=count;}
 public boolean isEmpty(){return count<=0 || id.isEmpty();}
 public int getCount(){return count;}
 public void setCount(int count){this.count=count;}
 public ItemStack copy(){return new ItemStack(id, count);}
}''',
    'net/minecraft/world/entity/player/Player': '''package net.minecraft.world.entity.player;public final class Player {}''',
    'net/minecraft/world/Container': '''package net.minecraft.world;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
public interface Container {
 int getContainerSize(); boolean isEmpty(); ItemStack getItem(int index);
 ItemStack removeItem(int index,int amount); ItemStack removeItemNoUpdate(int index);
 void setItem(int index,ItemStack item); void setChanged(); boolean stillValid(Player player);
 void clearContent(); default boolean canPlaceItem(int index, ItemStack item){return true;}
}''',
    'net/minecraft/world/inventory/Slot': '''package net.minecraft.world.inventory;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
public class Slot {
 public final Container container;public final int index,x,y;
 public Slot(Container container,int index,int x,int y){this.container=container;this.index=index;this.x=x;this.y=y;}
 public boolean mayPlace(ItemStack stack){return true;}
 public boolean mayPickup(Player player){return true;}
 public ItemStack remove(int amount){return container.removeItem(index,amount);}
 public ItemStack safeTake(int min,int max,Player player){return remove(max);}
 public ItemStack getItem(){return container.getItem(index);}
}''',
    'buildcraft/lib/gui/ItemProvider': '''package buildcraft.lib.gui;
import java.util.function.IntFunction;
import net.minecraft.world.item.ItemStack;
public final class ItemProvider {
 private final IntFunction<ItemStack> contents;private final int size;
 public ItemProvider(IntFunction<ItemStack> contents,int size){this.contents=contents;this.size=size;}
 public int getSlots(){return size;}
 public ItemStack getStackInSlot(int index){return contents.apply(index);}
}''',
    'buildcraft/lib/tile/item/ItemHandlerSimple': '''package buildcraft.lib.tile.item;
import net.minecraft.world.item.ItemStack;
public final class ItemHandlerSimple {
 private final ItemStack[] contents;
 public ItemHandlerSimple(int size){contents=new ItemStack[size];for(int i=0;i<size;i++)contents[i]=ItemStack.EMPTY;}
 public int getSlots(){return contents.length;}
 public ItemStack getStackInSlot(int index){return contents[index];}
 public void setStackInSlot(int index,ItemStack item){contents[index]=item;}
}''',
    'buildcraft/silicon/compat/SiliconDisplayProbe': '''package buildcraft.silicon.compat;
import buildcraft.lib.gui.ItemProvider;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
public final class SiliconDisplayProbe {
 static int count;
 static void ok(boolean pass){count++;if(!pass)throw new AssertionError("assertion "+count);}
 public static void main(String[] args){
  var state=new ItemStack("gate",3);
  var display=new SiliconDisplayContainer263(new ItemProvider(i -> i==1?state:ItemStack.EMPTY,3));
  ok(display.getContainerSize()==3);ok(!display.isEmpty());ok(display.getItem(0).isEmpty());
  ok(display.getItem(1).id.equals("gate"));ok(display.getItem(1).getCount()==3);
  var copy=display.getItem(1);copy.setCount(0);ok(state.getCount()==3);ok(display.getItem(1).getCount()==3);
  ok(display.getItem(-1).isEmpty());ok(display.getItem(3).isEmpty());
  var slot=display.slot(1,17,29);ok(slot.index==1);ok(slot.x==17);ok(slot.y==29);
  var player=new Player();ok(!slot.mayPickup(player));ok(!slot.mayPlace(new ItemStack("iron",1)));
  ok(slot.remove(1).isEmpty());ok(slot.safeTake(1,3,player).isEmpty());
  ok(state.getCount()==3);ok(!display.canPlaceItem(1,new ItemStack("iron",1)));
  display.setItem(1,new ItemStack("diamond",2));ok(state.getCount()==3);
  ok(display.removeItem(1,3).isEmpty());ok(display.removeItemNoUpdate(1).isEmpty());
  display.clearContent();ok(!display.isEmpty());ok(display.stillValid(player));
  boolean rejected=false;try{display.slot(3,0,0);}catch(IndexOutOfBoundsException expected){rejected=true;}ok(rejected);
  var backing=new ItemHandlerSimple(2);backing.setStackInSlot(0,new ItemStack("chip",5));
  var backingDisplay=new SiliconDisplayContainer263(backing);
  ok(backingDisplay.getContainerSize()==2);ok(!backingDisplay.isEmpty());
  ok(backingDisplay.getItem(0).getCount()==5);backing.getStackInSlot(0).setCount(2);
  ok(backingDisplay.getItem(0).getCount()==2);ok(backingDisplay.getItem(1).isEmpty());
  backing.setStackInSlot(0,ItemStack.EMPTY);ok(backingDisplay.isEmpty());
  System.out.println(count+" assertions PASS");
 }
}'''
}


def execute(java_file: Path) -> str:
    with tempfile.TemporaryDirectory(prefix='bc-silicon-26-java-') as tmp:
        root=Path(tmp)/'sources'
        classes=Path(tmp)/'classes'
        files=[]
        for relative,source in STUBS.items():
            path=root/(relative+'.java')
            path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text(source,encoding='utf-8')
            files.append(str(path))
        target=root/'buildcraft/silicon/compat/SiliconDisplayContainer263.java'
        target.parent.mkdir(parents=True,exist_ok=True)
        target.write_bytes(java_file.read_bytes())
        files.append(str(target))
        compiled=subprocess.run(['javac','--release','21','-encoding','UTF-8','-d',str(classes),*files],text=True,capture_output=True,timeout=60)
        if compiled.returncode:
            raise AssertionError('Silicon menu compile failed:\n'+compiled.stdout+compiled.stderr)
        launched=subprocess.run(['java','-ea','-cp',str(classes),'buildcraft.silicon.compat.SiliconDisplayProbe'],text=True,capture_output=True,timeout=30)
        if launched.returncode:
            raise AssertionError('Silicon menu probe failed:\n'+launched.stdout+launched.stderr)
        return launched.stdout.strip()
