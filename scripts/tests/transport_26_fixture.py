"""Compile and exercise the real 26.3 transport native transfer bridges against typed API doubles."""
from __future__ import annotations

from pathlib import Path
import subprocess
import tempfile

STUBS = {
    'net/neoforged/neoforge/fluids/FluidStack': '''package net.neoforged.neoforge.fluids;
public final class FluidStack {
 public static final FluidStack EMPTY = new FluidStack("empty",0);
 private final String name; private final int amount;
 public FluidStack(String name,int amount) {this.name=name;this.amount=amount;}
 public String name() {return name;}
 public boolean isEmpty() {return amount<=0||name.equals("empty");}
 public int getAmount() {return amount;}
 public FluidStack copyWithAmount(int size) {return size==0 ? EMPTY : new FluidStack(name,size);}
}''',
    'net/neoforged/neoforge/transfer/fluid/FluidResource': '''package net.neoforged.neoforge.transfer.fluid;
import net.neoforged.neoforge.fluids.FluidStack;
public record FluidResource(String name) {
 public static final FluidResource EMPTY = new FluidResource("empty");
 public static FluidResource of(FluidStack s) {return s==null||s.isEmpty()?EMPTY:new FluidResource(s.name());}
 public boolean isEmpty() {return name.equals("empty");}
 public FluidStack toStack(int n) {return isEmpty()||n<=0?FluidStack.EMPTY:new FluidStack(name,n);}
}''',
    'net/neoforged/neoforge/transfer/transaction/TransactionContext': '''package net.neoforged.neoforge.transfer.transaction; public interface TransactionContext {}''',
    'net/neoforged/neoforge/transfer/transaction/Transaction': '''package net.neoforged.neoforge.transfer.transaction;
import java.util.*;
public final class Transaction implements AutoCloseable,TransactionContext {
 private static Transaction active;
 private final Transaction parent; private final List<Runnable> undo=new ArrayList<>();
 private boolean commit=false;
 private Transaction(Transaction parent) {this.parent=parent;active=this;}
 public static Transaction openRoot() {if(active!=null)throw new IllegalStateException("nested root"); return new Transaction(null);}
 public static Transaction open(TransactionContext ctx) {if(ctx==null)return openRoot(); if(ctx!=active)throw new IllegalStateException("incorrect parent"); return new Transaction((Transaction)ctx);}
 public static Transaction current() {return active;}
 public static void snapshot(Runnable undo) {if(active!=null)active.undo.add(undo);}
 public void commit() {commit=true;}
 public void close() {active=parent;if(!commit){for(int i=undo.size()-1;i>=0;i--)undo.get(i).run();} else if(parent!=null)parent.undo.addAll(undo);}
}''',
    'net/neoforged/neoforge/transfer/ResourceHandler': '''package net.neoforged.neoforge.transfer;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
public interface ResourceHandler<T> {
 int size(); T getResource(int index); long getAmountAsLong(int index); long getCapacityAsLong(int index,T resource);
 boolean isValid(int index,T resource); int insert(int index,T resource,int amount,TransactionContext ctx);
 int extract(int index,T resource,int amount,TransactionContext ctx);
 default int getAmountAsInt(int index) {return (int)getAmountAsLong(index);}
 default int getCapacityAsInt(int index,T resource) {return (int)getCapacityAsLong(index,resource);}
 default int insert(T res,int amount,TransactionContext ctx){int inserted=0;for(int i=0;i<size();i++)inserted+=insert(i,res,amount-inserted,ctx);return inserted;}
}''',
    'net/neoforged/neoforge/transfer/energy/EnergyHandler': '''package net.neoforged.neoforge.transfer.energy;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
public interface EnergyHandler {long getAmountAsLong(); long getCapacityAsLong(); int insert(int amount,TransactionContext tx); int extract(int amount,TransactionContext tx);
 default int getAmountAsInt(){return (int)getAmountAsLong();}
 default int getCapacityAsInt(){return (int)getCapacityAsLong();}}
''',
    'buildcraft/lib/compat/transfer/TransferJournal': '''package buildcraft.lib.compat.transfer;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
public final class TransferJournal {public static TransactionContext current(){return Transaction.current();}}
''',
    'buildcraft/lib/platform/storage/FluidStorage': '''package buildcraft.lib.platform.storage;
public interface FluidStorage<F> {int getTanks();F getFluidInTank(int index);int getTankCapacity(int index);boolean isFluidValid(int index,F fluid);int fill(F fluid,boolean sim);F drain(F fluid,boolean sim);F drain(int amount,boolean sim);}
''',
    'buildcraft/lib/platform/storage/FilteredFluidStorage': '''package buildcraft.lib.platform.storage;
import java.util.function.Predicate;
public interface FilteredFluidStorage<F> extends FluidStorage<F> {F drain(Predicate<F> filter,int amount,boolean sim);}
''',
    'buildcraft/lib/platform/storage/EnergyStorage': '''package buildcraft.lib.platform.storage;
public interface EnergyStorage {int receiveEnergy(int amount,boolean sim);int extractEnergy(int amount,boolean sim);int getEnergyStored();int getMaxEnergyStored();boolean canReceive();boolean canExtract();}
''',
    'buildcraft/transport/compat/Harness': '''package buildcraft.transport.compat;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.*;
import net.neoforged.neoforge.transfer.energy.*;
import net.neoforged.neoforge.transfer.fluid.*;
import net.neoforged.neoforge.transfer.transaction.*;
public final class Harness {
 private static int checks;
 private static void ok(boolean c){checks++;if(!c)throw new AssertionError("failed #"+checks);}
 private static void eq(int a,int b){ok(a==b);}
 public static final class Tank implements ResourceHandler<FluidResource> {
  private final String allowed; private final int capacity; private int amount;
  Tank(String allowed,int capacity,int initial){this.allowed=allowed;this.capacity=capacity;amount=initial;}
  public int size(){return 1;}
  public FluidResource getResource(int i){return amount<=0?FluidResource.EMPTY:new FluidResource(allowed);}
  public long getAmountAsLong(int i){return amount;}
  public long getCapacityAsLong(int i,FluidResource r){return r.isEmpty()||isValid(i,r)?capacity:0;}
  public boolean isValid(int i,FluidResource r){return !r.isEmpty()&&r.name().equals(allowed);}
  public int insert(int i,FluidResource r,int n,TransactionContext tx){if(!isValid(i,r))return 0;int changed=Math.max(0,Math.min(n,capacity-amount));int before=amount;Transaction.snapshot(()->amount=before);amount+=changed;return changed;}
  public int extract(int i,FluidResource r,int n,TransactionContext tx){if(!isValid(i,r))return 0;int changed=Math.max(0,Math.min(n,amount));int before=amount;Transaction.snapshot(()->amount=before);amount-=changed;return changed;}
 }
 public static final class Battery implements EnergyHandler {
  private final int capacity; private int amount;
  Battery(int capacity,int initial){this.capacity=capacity;amount=initial;}
  public long getAmountAsLong(){return amount;}
  public long getCapacityAsLong(){return capacity;}
  public int insert(int n,TransactionContext tx){int changed=Math.max(0,Math.min(n,capacity-amount));int before=amount;Transaction.snapshot(()->amount=before);amount+=changed;return changed;}
  public int extract(int n,TransactionContext tx){int changed=Math.max(0,Math.min(n,amount));int before=amount;Transaction.snapshot(()->amount=before);amount-=changed;return changed;}
 }
 public static void main(String[] args){
   var nativeTank=new Tank("oil",100,30);var adapter=new NativeFluidStorage263(nativeTank);
   eq(adapter.getTanks(),1);eq(adapter.getTankCapacity(0),100);eq(adapter.getFluidInTank(0).getAmount(),30);
   ok(adapter.isFluidValid(0,new FluidStack("oil",5)));
   ok(!adapter.isFluidValid(0,new FluidStack("water",5)));
   eq(adapter.fill(new FluidStack("oil",80),true),70);eq(adapter.getFluidInTank(0).getAmount(),30);
   eq(adapter.fill(new FluidStack("oil",80),false),70);eq(adapter.getFluidInTank(0).getAmount(),100);
   eq(adapter.fill(new FluidStack("water",5),false),0);eq(adapter.fill(FluidStack.EMPTY,false),0);
   eq(adapter.drain(new FluidStack("water",30),false).getAmount(),0);
   eq(adapter.drain(new FluidStack("oil",20),true).getAmount(),20);eq(adapter.getFluidInTank(0).getAmount(),100);
   eq(adapter.drain(new FluidStack("oil",20),false).getAmount(),20);eq(adapter.getFluidInTank(0).getAmount(),80);
   eq(adapter.drain(s->s.name().equals("water"),60,false).getAmount(),0);
   eq(adapter.drain(s->s.name().equals("oil"),15,true).getAmount(),15);eq(adapter.getFluidInTank(0).getAmount(),80);
   eq(adapter.drain(1000,false).getAmount(),80);eq(adapter.getFluidInTank(0).getAmount(),0);
   try(Transaction root=Transaction.openRoot()){
      eq(adapter.fill(new FluidStack("oil",60),false),60);
      eq(adapter.getFluidInTank(0).getAmount(),60);
   }
   eq(adapter.getFluidInTank(0).getAmount(),0);
   try(Transaction root=Transaction.openRoot()){
      eq(adapter.fill(new FluidStack("oil",80),false),80);
      eq(adapter.getFluidInTank(0).getAmount(),80);
      root.commit();
   }
   eq(adapter.getFluidInTank(0).getAmount(),80);
   try(Transaction root=Transaction.openRoot()){
      eq(adapter.drain(30,false).getAmount(),30);
      eq(adapter.getFluidInTank(0).getAmount(),50);
   }
   eq(adapter.getFluidInTank(0).getAmount(),80);
   var battery = new Battery(500,100); var e=new NativeEnergyStorage263(battery);
   eq(e.getEnergyStored(),100);eq(e.getMaxEnergyStored(),500);ok(e.canExtract());ok(e.canReceive());
   eq(e.receiveEnergy(450,true),400);eq(e.getEnergyStored(),100);
   eq(e.receiveEnergy(450,false),400);eq(e.getEnergyStored(),500);
   eq(e.extractEnergy(1000,true),500);eq(e.getEnergyStored(),500);
   eq(e.extractEnergy(250,false),250);eq(e.getEnergyStored(),250);
   eq(e.extractEnergy(0,false),0);eq(e.receiveEnergy(-5,false),0);
   try(Transaction root=Transaction.openRoot()){
      eq(e.extractEnergy(50,false),50);eq(e.getEnergyStored(),200);
      eq(e.receiveEnergy(30,false),30);eq(e.getEnergyStored(),230);
   }
   eq(e.getEnergyStored(),250);
   try(Transaction root=Transaction.openRoot()){
      eq(e.extractEnergy(10,false),10);root.commit();
   }
   eq(e.getEnergyStored(),240);
   System.out.println(checks+" assertions PASS");
 }
}
''',
}

def execute(fluid_java: Path, energy_java: Path) -> str:
    with tempfile.TemporaryDirectory(prefix='transport-26-compiled-') as td:
        root=Path(td)/'src'; root.mkdir()
        names=[]
        for name,body in STUBS.items():
            file=root/(name+'.java');file.parent.mkdir(parents=True,exist_ok=True)
            file.write_text(body,encoding='utf-8'); names.append(str(file))
        for file in (fluid_java,energy_java):
            dst=root/'buildcraft/transport/compat'/file.name
            dst.write_bytes(file.read_bytes());names.append(str(dst))
        output=Path(td)/'classes'
        proc=subprocess.run(['javac','-encoding','UTF-8','-d',str(output),*names],capture_output=True,text=True,timeout=60)
        if proc.returncode: raise AssertionError('Native adapters failed to compile:\n'+proc.stdout+'\n'+proc.stderr)
        proc=subprocess.run(['java','-cp',str(output),'buildcraft.transport.compat.Harness'],capture_output=True,text=True,timeout=30)
        if proc.returncode: raise AssertionError('Native adapter transaction contract failure:\n'+proc.stdout+'\n'+proc.stderr)
        return proc.stdout.strip()
