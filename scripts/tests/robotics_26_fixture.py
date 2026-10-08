"""Compile the actual 26.3 robot fluid bridge against typed Java API doubles and test nested rollback."""
from __future__ import annotations
import subprocess
import tempfile
from pathlib import Path

STUBS = {
    'net/neoforged/neoforge/fluids/FluidStack': '''package net.neoforged.neoforge.fluids;
public final class FluidStack {
 public static final FluidStack EMPTY = new FluidStack("empty",0);
 public final String kind;
 private final int amount;
 public FluidStack(String kind,int amount){this.kind=kind;this.amount=amount;}
 public boolean isEmpty(){return kind.equals("empty")||amount<=0;}
 public int getAmount(){return amount;}
 public FluidStack copy(){return new FluidStack(kind,amount);}
 public FluidStack copyWithAmount(int amount){return amount<=0?EMPTY:new FluidStack(kind,amount);}
}''',
    'net/neoforged/neoforge/transfer/fluid/FluidResource': '''package net.neoforged.neoforge.transfer.fluid;
import net.neoforged.neoforge.fluids.FluidStack;
public record FluidResource(String kind){
 public static final FluidResource EMPTY=new FluidResource("empty");
 public static FluidResource of(FluidStack stack){return stack==null||stack.isEmpty()?EMPTY:new FluidResource(stack.kind);}
 public boolean isEmpty(){return kind.equals("empty");}
 public FluidStack toStack(int amount){return isEmpty()||amount<=0?FluidStack.EMPTY:new FluidStack(kind,amount);}
}''',
    'net/neoforged/neoforge/transfer/ResourceHandler': '''package net.neoforged.neoforge.transfer;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
public interface ResourceHandler<T> {
 int size();T getResource(int index);long getAmountAsLong(int index);long getCapacityAsLong(int index,T resource);
 boolean isValid(int index,T resource);int insert(int index,T resource,int amount,TransactionContext context);
 int extract(int index,T resource,int amount,TransactionContext context);
}''',
    'net/neoforged/neoforge/transfer/TransferPreconditions': '''package net.neoforged.neoforge.transfer;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
public final class TransferPreconditions {
 public static void checkNonEmptyNonNegative(FluidResource resource,int amount){
  if(resource.isEmpty()||amount<0)throw new IllegalArgumentException("invalid transfer");
 }
}''',
    'net/neoforged/neoforge/transfer/transaction/TransactionContext': '''package net.neoforged.neoforge.transfer.transaction;public interface TransactionContext {}''',
    'net/neoforged/neoforge/transfer/transaction/Transaction': '''package net.neoforged.neoforge.transfer.transaction;
import java.util.*;
public final class Transaction implements TransactionContext,AutoCloseable {
 private static Transaction active;
 private final Transaction parent;
 private final List<Runnable> undo=new ArrayList<>();private boolean committed;
 private Transaction(Transaction parent){this.parent=parent;active=this;}
 public static Transaction openRoot(){if(active!=null)throw new IllegalStateException("root while active");return new Transaction(null);}
 public static Transaction open(TransactionContext context){if(context==null)return openRoot();if(context!=active)throw new IllegalArgumentException("wrong parent");return new Transaction((Transaction)context);}
 public static Transaction current(){return active;}
 public static void snapshot(Runnable action){if(active!=null)active.undo.add(action);}
 public void commit(){committed=true;}
 public void close(){active=parent;if(!committed){for(int i=undo.size()-1;i>=0;i--)undo.get(i).run();}else if(parent!=null){parent.undo.addAll(undo);}}
}''',
    'buildcraft/lib/compat/transfer/TransferJournal': '''package buildcraft.lib.compat.transfer;
import java.util.function.*;
import net.neoforged.neoforge.transfer.transaction.Transaction;
public final class TransferJournal<S>{
 private final Supplier<S> capture;private final Consumer<S> restore;private final Consumer<S> committed;
 public TransferJournal(Supplier<S> capture,Consumer<S> restore,Consumer<S> committed){this.capture=capture;this.restore=restore;this.committed=committed;}
 public void record(){S before=capture.get();Transaction.snapshot(()->restore.accept(before));}
}''',
    'buildcraft/robotics/internal/legacy/robots/EntityRobotBase': '''package buildcraft.robotics.internal.legacy.robots;
import net.neoforged.neoforge.fluids.FluidStack;
import buildcraft.transport.internal.pipe.FluidAction;
import buildcraft.lib.compat.transfer.TransferJournal;
public final class EntityRobotBase {
 public final int capacity;private FluidStack tank=FluidStack.EMPTY;
 private final TransferJournal<FluidStack> journal=new TransferJournal<>(()->tank.copy(),old->tank=old.copy(),ignored->{});
 public EntityRobotBase(int capacity){this.capacity=capacity;}
 public FluidStack getFluidInTank(int index){return index==0?tank.copy():FluidStack.EMPTY;}
 public int getTankCapacity(int index){return index==0?capacity:0;}
 public boolean isFluidValid(int index,FluidStack item){return index==0 && !item.isEmpty() && (tank.isEmpty()||tank.kind.equals(item.kind));}
 public int fill(FluidStack fluid,FluidAction action){
  if(fluid.isEmpty()||!isFluidValid(0,fluid))return 0;
  int add=Math.max(0,Math.min(capacity-tank.getAmount(),fluid.getAmount()));
  if(add>0 && action.execute()){journal.record();tank=tank.isEmpty()?fluid.copyWithAmount(add):tank.copyWithAmount(tank.getAmount()+add);}
  return add;
 }
 public FluidStack drain(FluidStack fluid,FluidAction action){
  if(fluid.isEmpty() || tank.isEmpty() || !tank.kind.equals(fluid.kind))return FluidStack.EMPTY;
  int amount=Math.min(tank.getAmount(),fluid.getAmount());
  FluidStack result=tank.copyWithAmount(amount);
  if(amount>0 && action.execute()){journal.record();tank=tank.copyWithAmount(tank.getAmount()-amount);}
  return result;
 }
}''',
    'buildcraft/robotics/compat/Harness': '''package buildcraft.robotics.compat;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
public final class Harness {
 private static int checks;
 private static void ok(boolean b){checks++;if(!b)throw new AssertionError("failed #"+checks);}
 private static void eq(long a,long b){ok(a==b);}
 public static void main(String[] args){
  var r=new EntityRobotBase(16000);
  var handler=new RobotFluidResourceHandler263(r);
  var oil=new FluidResource("oil"); var water=new FluidResource("water");
  eq(handler.size(),1);ok(handler.getResource(0).isEmpty());eq(handler.getAmountAsLong(0),0);
  eq(handler.getCapacityAsLong(0,oil),16000);ok(handler.isValid(0,oil));ok(handler.isValid(0,water));
  try(var tx=Transaction.openRoot()){
    eq(handler.insert(0,oil,4000,tx),4000);
    eq(handler.getAmountAsLong(0),4000);
    ok(handler.getResource(0).equals(oil));
    eq(handler.getCapacityAsLong(0,water),0);
    eq(handler.insert(0,water,1000,tx),0);
  }
  eq(handler.getAmountAsLong(0),0);
  try(var root=Transaction.openRoot()){
    eq(handler.insert(0,oil,20000,root),16000);
    root.commit();
  }
  eq(handler.getAmountAsLong(0),16000);
  eq(handler.getCapacityAsLong(0,oil),16000);ok(!handler.isValid(0,water));
  try(var root=Transaction.openRoot()){
    eq(handler.extract(0,oil,1500,root),1500);
    eq(handler.getAmountAsLong(0),14500);
  }
  eq(handler.getAmountAsLong(0),16000);
  try(var root=Transaction.openRoot()){
    eq(handler.extract(0,water,1000,root),0);
    eq(handler.extract(0,oil,5000,root),5000);
    eq(handler.getAmountAsLong(0),11000);
    root.commit();
  }
  eq(handler.getAmountAsLong(0),11000);
  try(var root=Transaction.openRoot()){
    eq(handler.extract(0,oil,3000,root),3000);
    try(var child=Transaction.open(root)){
      eq(handler.extract(0,oil,2000,child),2000);
      eq(handler.getAmountAsLong(0),6000);
    }
    eq(handler.getAmountAsLong(0),8000);
    root.commit();
  }
  eq(handler.getAmountAsLong(0),8000);
  try(var root=Transaction.openRoot()){
    eq(handler.insert(0,oil,4000,root),4000);
    try(var child=Transaction.open(root)){
      eq(handler.extract(0,oil,1000,child),1000);
      child.commit();
    }
    eq(handler.getAmountAsLong(0),11000);
  }
  eq(handler.getAmountAsLong(0),8000);
  var scratch=RobotFluidResourceHandler263.forRollback(new FluidStack("water",1000));
  eq(scratch.size(),1);eq(scratch.getAmountAsLong(0),1000);
  eq(scratch.getCapacityAsLong(0,water),1000);eq(scratch.getCapacityAsLong(0,oil),0);
  ok(scratch.getResource(0).equals(water));ok(scratch.isValid(0,water));ok(!scratch.isValid(0,oil));
  try(var tx=Transaction.openRoot()){
    eq(scratch.insert(0,water,100,tx),0);
    eq(scratch.extract(0,water,1000,tx),1000);
    ok(scratch.getResource(0).isEmpty());
  }
  eq(scratch.getAmountAsLong(0),1000);
  try(var root=Transaction.openRoot()){
    eq(scratch.extract(0,water,300,root),300);
    try(var child=Transaction.open(root)){
      eq(scratch.extract(0,water,700,child),700);
      child.commit();
    }
    eq(scratch.getAmountAsLong(0),0);
  }
  eq(scratch.getAmountAsLong(0),1000);
  try(var tx=Transaction.openRoot()){
    eq(scratch.extract(0,water,1000,tx),1000);
    tx.commit();
  }
  eq(scratch.getAmountAsLong(0),0);
  try(var tx=Transaction.openRoot()){
    eq(scratch.extract(0,water,100,tx),0);
    eq(scratch.insert(0,water,100,tx),0);
  }
  var empty=RobotFluidResourceHandler263.forRollback(FluidStack.EMPTY);
  eq(empty.getAmountAsLong(0),0);ok(empty.getResource(0).isEmpty());
  boolean badIndex=false;
  try{handler.getAmountAsLong(1);}catch(IndexOutOfBoundsException e){badIndex=true;}
  ok(badIndex);
  boolean negative=false;
  try(var tx=Transaction.openRoot()){
    try{handler.insert(0,oil,-2,tx);}catch(IllegalArgumentException ex){negative=true;}
  }
  ok(negative);
  System.out.println(checks+" assertions PASS");
 }
}'''
}


def execute(adapter: Path, action: Path) -> str:
    with tempfile.TemporaryDirectory(prefix='bc-robotics-26-transaction-') as td:
        src=Path(td)/'src';files=[]
        for p,text in STUBS.items():
            target=src/(p+'.java');target.parent.mkdir(parents=True,exist_ok=True)
            target.write_text(text,encoding='utf-8');files.append(str(target))
        for target,prefix in ((adapter,'buildcraft/robotics/compat'),(action,'buildcraft/transport/internal/pipe')):
            file=src/prefix/target.name;file.parent.mkdir(parents=True,exist_ok=True)
            file.write_bytes(target.read_bytes());files.append(str(file))
        compiled=subprocess.run(['javac','--release','21','-encoding','UTF-8','-d',str(Path(td)/'classes'),*files],capture_output=True,text=True,timeout=60)
        if compiled.returncode: raise AssertionError('Java compiler failed:\n'+compiled.stdout+compiled.stderr)
        launched=subprocess.run(['java','-cp',str(Path(td)/'classes'),'buildcraft.robotics.compat.Harness'],capture_output=True,text=True,timeout=30)
        if launched.returncode: raise AssertionError('Java contract probe failed:\n'+launched.stdout+launched.stderr)
        return launched.stdout.strip()
