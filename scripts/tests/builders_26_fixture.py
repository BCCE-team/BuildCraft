"""Compile and test native 26.3 Builder menu and schematic resource bridges against typed API doubles."""
from __future__ import annotations

import subprocess
import tempfile
from pathlib import Path

STUBS = {
'net/minecraft/world/item/ItemStack': '''package net.minecraft.world.item;
public class ItemStack {
 public static final ItemStack EMPTY = new ItemStack("",0);
 public final String item;
 private int count;
 public ItemStack(String item,int count){this.item=item;this.count=count;}
 public boolean isEmpty(){return item.isEmpty() || count<=0;}
 public int getCount(){return count;}
 public void setCount(int count){this.count=count;}
 public ItemStack copy(){return new ItemStack(item,count);}
 public void limitSize(int max){if(count>max)count=max;}
 public ItemStack split(int amount){int taken=Math.min(Math.max(amount,0),count);count-=taken;return new ItemStack(item,taken);}
 public static boolean matches(ItemStack a,ItemStack b){return (a.isEmpty()&&b.isEmpty()) || (a.item.equals(b.item)&&a.count==b.count);}
 public String toString(){return item+":"+count;}
}''',
'net/minecraft/world/entity/player/Player': 'package net.minecraft.world.entity.player; public class Player {}',
'net/minecraft/world/Container': '''package net.minecraft.world;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
public interface Container {
 int getContainerSize();boolean isEmpty();ItemStack getItem(int index);
 ItemStack removeItem(int index,int count);ItemStack removeItemNoUpdate(int index);
 void setItem(int index,ItemStack item);void setChanged();boolean stillValid(Player player);
 void clearContent();default boolean canPlaceItem(int index,ItemStack item){return true;}
 default int getMaxStackSize(){return 64;}
}''',
'buildcraft/lib/tile/item/ItemHandlerSimple': '''package buildcraft.lib.tile.item;
import net.minecraft.world.item.ItemStack;
public class ItemHandlerSimple {
 private ItemStack stored;private final String accepts;
 public int changes;
 public ItemHandlerSimple(String accepts,ItemStack initial){this.accepts=accepts;stored=initial.copy();}
 public ItemStack getStackInSlot(int index){return stored;}
 public boolean canSet(int index,ItemStack stack){return stack.isEmpty() || accepts.equals("*") || stack.item.equals(accepts);}
 public void setStackInSlot(int index,ItemStack stack){if(!canSet(index,stack))throw new AssertionError("wrong item"); stored=stack.copy();changes++;}
}''',
'buildcraft/lib/gui/ItemProvider': '''package buildcraft.lib.gui;
import net.minecraft.world.item.ItemStack;
import java.util.function.IntFunction;
public class ItemProvider {
 private final IntFunction<ItemStack> fn;private final int count;
 public ItemProvider(IntFunction<ItemStack> fn,int count){this.fn=fn;this.count=count;}
 public int getSlots(){return count;}
 public ItemStack getStackInSlot(int index){return fn.apply(index);}
}''',
'net/neoforged/neoforge/fluids/FluidStack': '''package net.neoforged.neoforge.fluids;
public record FluidStack(String name,int amount) {
 public boolean isEmpty(){return amount<=0 || name.isEmpty();}
}''',
'net/neoforged/neoforge/transfer/fluid/FluidResource': '''package net.neoforged.neoforge.transfer.fluid;
import net.neoforged.neoforge.fluids.FluidStack;
public record FluidResource(String name) {
 public boolean isEmpty(){return name.isEmpty();}
 public FluidStack toStack(int amount){return new FluidStack(name,amount);}
}''',
'net/neoforged/neoforge/transfer/item/ItemResource': '''package net.neoforged.neoforge.transfer.item;
import net.minecraft.world.item.ItemStack;
public record ItemResource(String name) {
 public static ItemResource of(ItemStack stack){return new ItemResource(stack.item);}
}''',
'net/neoforged/neoforge/transfer/item/ItemStacksResourceHandler': '''package net.neoforged.neoforge.transfer.item;
public final class ItemStacksResourceHandler {
 private ItemResource current=new ItemResource("");private int count;
 public ItemStacksResourceHandler(int size) {if(size!=1)throw new IllegalArgumentException();}
 public void set(int index,ItemResource item,int count){this.current=item;this.count=count;}
 public ItemResource resource(){return current;}
 public int amount(){return count;}
}''',
'net/neoforged/neoforge/transfer/transaction/Transaction': '''package net.neoforged.neoforge.transfer.transaction;
import java.util.*;
public final class Transaction implements AutoCloseable {
 private final List<Runnable> rollback=new ArrayList<>();private boolean committed;
 public static Transaction openRoot(){return new Transaction();}
 public void snapshot(Runnable undo){rollback.add(undo);}
 public void commit(){committed=true;}
 public void close(){if(!committed)for(int i=rollback.size()-1;i>=0;i--)rollback.get(i).run();}
}''',
'net/neoforged/neoforge/transfer/ResourceHandler': '''package net.neoforged.neoforge.transfer;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
public interface ResourceHandler<T> {
 int size();T getResource(int index);int getAmountAsInt(int index);
 int extract(int index,T resource,int amount,Transaction transaction);
}''',
'net/neoforged/neoforge/capabilities/Capabilities': '''package net.neoforged.neoforge.capabilities;
public final class Capabilities {public static final class Fluid {public static final Object ITEM=new Object();}}''',
'net/neoforged/neoforge/transfer/access/ItemAccess': '''package net.neoforged.neoforge.transfer.access;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.*;
import net.neoforged.neoforge.transfer.transaction.Transaction;
public final class ItemAccess {
 private final ItemStacksResourceHandler inventory;
 private ItemAccess(ItemStacksResourceHandler inventory){this.inventory=inventory;}
 public static ItemAccess forHandlerIndexStrict(ItemStacksResourceHandler handler,int index){return new ItemAccess(handler);}
 public ItemAccess oneByOne(){return this;}
 public ResourceHandler<FluidResource> getCapability(Object cap){
  String item=inventory.resource().name();
  if(cap!=Capabilities.Fluid.ITEM || !item.contains("tank") && !item.contains("bucket")) return null;
  final String fluid=item.contains("lava")?"lava":"water";
  return new ResourceHandler<>() {
   private int available=item.contains("half")?500:1000;
   public int size(){return 1;}
   public FluidResource getResource(int index){return new FluidResource(fluid);}
   public int getAmountAsInt(int index){return available;}
   public int extract(int index,FluidResource resource,int amount,Transaction tx){
    if(item.contains("sealed") || amount<=0 || !resource.name().equals(fluid))return 0;
    int moved=Math.min(available,amount);
    if(moved<=0)return 0;
    int old=available;var before=inventory.resource();int beforeAmount=inventory.amount();
    tx.snapshot(()->{available=old;inventory.set(0,before,beforeAmount);});
    available-=moved;
    if(available==0)inventory.set(0,new ItemResource("bucket"),1);
    return moved;
   }
  };
 }
}''',
'buildcraft/builders/compat/Harness': '''package buildcraft.builders.compat;
import java.util.*;
import net.minecraft.world.item.ItemStack;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.lib.gui.ItemProvider;
public final class Harness {
 private static int checks;
 private static void ok(boolean b){checks++;if(!b)throw new AssertionError("failed assertion "+checks);}
 private static void eq(int a,int b){ok(a==b);}
 private static void has(ItemStack a,String name,int amount){ok(a.item.equals(name));eq(a.getCount(),amount);}
 public static void main(String[] args){
   var snapshot=new ItemHandlerSimple("blueprint",new ItemStack("blueprint",1));
   var from=new ItemHandlerSimple("from",ItemStack.EMPTY);
   var to=new ItemHandlerSimple("to",new ItemStack("to",1));
   var inv=new BuildersItemContainer263(snapshot,from,to);
   eq(inv.getContainerSize(),3);
   ok(!inv.isEmpty());
   has(inv.getItem(0),"blueprint",1);
   ok(inv.getItem(-1).isEmpty());
   ok(!inv.canPlaceItem(0,new ItemStack("invalid",1)));
   ok(inv.canPlaceItem(1,new ItemStack("from",1)));
   int before=snapshot.changes;
   // A vanilla Slot mutates the returned stack and then calls Container#setChanged.
   inv.getItem(0).split(1);
   eq(snapshot.changes,before);
   inv.setChanged();
   ok(snapshot.getStackInSlot(0).isEmpty());
   eq(snapshot.changes,before+1);
   eq(from.changes,0);
   inv.setItem(1,new ItemStack("from",5));
   has(from.getStackInSlot(0),"from",1);
   inv.setItem(1,new ItemStack("invalid",1));
   has(from.getStackInSlot(0),"from",1);
   eq(inv.getMaxStackSize(),1);
   has(inv.removeItem(1,1),"from",1);
   ok(from.getStackInSlot(0).isEmpty());
   from.setStackInSlot(0,new ItemStack("from",1));
   has(inv.getItem(1),"from",1);
   has(inv.removeItemNoUpdate(2),"to",1);
   ok(to.getStackInSlot(0).isEmpty());
   inv.clearContent();
   ok(snapshot.getStackInSlot(0).isEmpty());
   ok(from.getStackInSlot(0).isEmpty());
   ok(to.getStackInSlot(0).isEmpty());
   ok(inv.isEmpty());
   var list=new ItemStack[]{new ItemStack("stone",7), new ItemStack("iron",3)};
   var display=new BuildersDisplayContainer263(new ItemProvider(i->list[i],2));
   eq(display.getContainerSize(),2);
   has(display.getItem(0),"stone",7);
   display.getItem(0).split(2);
   has(list[0],"stone",7);
   display.setItem(0,new ItemStack("hacked",4));
   has(display.getItem(0),"stone",7);
   ok(display.removeItem(0,2).isEmpty());
   ok(display.removeItemNoUpdate(1).isEmpty());
   ok(!display.canPlaceItem(0,new ItemStack("hacked",1)));
   list[0]=ItemStack.EMPTY;
   ok(display.getItem(0).isEmpty());
   list[1]=ItemStack.EMPTY;
   ok(display.isEmpty());
   var fluids=BuilderFluidContainers263.drainContained(List.of(
     new ItemStack("water_bucket",1), new ItemStack("lava_half_tank",1),
     new ItemStack("water_sealed_tank",1), new ItemStack("stone",1), ItemStack.EMPTY));
   eq(fluids.size(),2);
   ok(fluids.get(0).name().equals("water"));
   eq(fluids.get(0).amount(),1000);
   ok(fluids.get(1).name().equals("lava"));
   eq(fluids.get(1).amount(),500);
   eq(BuilderFluidContainers263.drainContained(List.of(ItemStack.EMPTY,new ItemStack("water_sealed_tank",1))).size(),0);
   System.out.println(checks+" assertions PASS");
 }
}''',
}


def execute(java_sources: list[Path]) -> str:
    with tempfile.TemporaryDirectory(prefix='bc-builders-26-api-') as temp:
        root=Path(temp)/'src';files=[]
        for relative,source in STUBS.items():
            path=root/(relative+'.java');path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text(source,encoding='utf-8');files.append(str(path))
        for source in java_sources:
            path=root/'buildcraft/builders/compat'/source.name
            path.write_bytes(source.read_bytes());files.append(str(path))
        classes=Path(temp)/'classes'
        result=subprocess.run(['javac','--release','21','-encoding','UTF-8','-d',str(classes),*files],capture_output=True,text=True,timeout=60)
        if result.returncode:
            raise AssertionError('26.3 Builders API probe compilation failed:\n'+result.stdout+'\n'+result.stderr)
        result=subprocess.run(['java','-cp',str(classes),'buildcraft.builders.compat.Harness'],capture_output=True,text=True,timeout=30)
        if result.returncode:
            raise AssertionError('26.3 Builders API probe failed:\n'+result.stdout+'\n'+result.stderr)
        return result.stdout.strip()
