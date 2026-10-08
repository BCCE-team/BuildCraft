"""Execute the effective 26.3 energy fluid handler with typed transaction doubles."""
from __future__ import annotations

from pathlib import Path
import subprocess
import tempfile

STUBS = {
    'net/neoforged/neoforge/fluids/FluidStack': '''package net.neoforged.neoforge.fluids;
public final class FluidStack {
    public static final FluidStack EMPTY = new FluidStack("empty", 0);
    private final String fluid; private final int amount;
    public FluidStack(String fluid, int amount) {this.fluid=fluid;this.amount=amount;}
    public String getFluid() {return fluid;}
    public boolean isEmpty() {return amount <= 0 || fluid.equals("empty");}
    public int getAmount() {return amount;}
    public FluidStack copyWithAmount(int size) {return size==0 ? EMPTY : new FluidStack(fluid,size);}
}''',
    'net/neoforged/neoforge/transfer/fluid/FluidResource': '''package net.neoforged.neoforge.transfer.fluid;
import net.neoforged.neoforge.fluids.FluidStack;
public record FluidResource(String name) {
    public static final FluidResource EMPTY = new FluidResource("empty");
    public static FluidResource of(FluidStack stack) {return stack == null || stack.isEmpty() ? EMPTY : new FluidResource(stack.getFluid());}
    public boolean isEmpty() {return name.equals("empty");}
    public FluidStack toStack(int amount) {return amount == 0 || isEmpty() ? FluidStack.EMPTY : new FluidStack(name,amount);}
}''',
    'net/neoforged/neoforge/transfer/transaction/TransactionContext': '''package net.neoforged.neoforge.transfer.transaction;
public interface TransactionContext {}''',
    'net/neoforged/neoforge/transfer/transaction/Transaction': '''package net.neoforged.neoforge.transfer.transaction;
import java.util.*;
public final class Transaction implements TransactionContext, AutoCloseable {
    private static Transaction active;
    private final Transaction parent;
    private final List<Runnable> undo=new ArrayList<>(), callbacks=new ArrayList<>();
    private boolean committed, closed;
    private Transaction(Transaction parent) {this.parent=parent;active=this;}
    public static Transaction openRoot() {if(active!=null)throw new IllegalStateException("already active");return new Transaction(null);}
    public static Transaction open(TransactionContext parent) {if(parent==null)return openRoot();if(active!=parent)throw new IllegalStateException("not current parent");return new Transaction((Transaction)parent);}
    public static Transaction current() {return active;}
    public static void snapshot(Runnable undo) {if(active!=null)active.undo.add(undo);}
    public static void notifyCommit(Runnable callback) {if(active!=null)active.callbacks.add(callback);else callback.run();}
    public void commit() {if(closed)throw new IllegalStateException();committed=true;}
    public void close() {if(closed)throw new IllegalStateException();closed=true;active=parent;
        if(!committed) {for(int i=undo.size()-1;i>=0;i--)undo.get(i).run();}
        else if(parent!=null) {parent.undo.addAll(undo);parent.callbacks.addAll(callbacks);}
        else {for(Runnable r:callbacks)r.run();}
    }
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
 public static void checkNonEmptyNonNegative(FluidResource resource,int amount) {
    if(resource.isEmpty() || amount<0) throw new IllegalArgumentException("bad resource/amount");
 }
}''',
    'buildcraft/lib/fluid/Tank': '''package buildcraft.lib.fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.transaction.Transaction;
public final class Tank {
    private FluidStack stack = FluidStack.EMPTY;
    private final String accepted;private final int capacity;
    public Tank(String name,int capacity) {this.accepted=name;this.capacity=capacity;}
    public FluidStack getFluid() {return stack;}
    public int getFluidAmount() {return stack.getAmount();}
    public int getCapacity() {return capacity;}
    public boolean isFluidValid(FluidStack offered) {return !offered.isEmpty() && offered.getFluid().equals(accepted)
        && (stack.isEmpty() || stack.getFluid().equals(offered.getFluid()));}
    public void setFluid(FluidStack next) {FluidStack before=stack;Transaction.snapshot(()->stack=before);stack=next;}
}''',
    'buildcraft/lib/compat/transfer/TransferJournal': '''package buildcraft.lib.compat.transfer;
import net.neoforged.neoforge.transfer.transaction.Transaction;
public final class TransferJournal {public static void notifyAfterCommit(Runnable r) {Transaction.notifyCommit(r);}}''',
    'buildcraft/energy/tile/TileEngineIron_BC8': '''package buildcraft.energy.tile;
public class TileEngineIron_BC8 {public int dirty;public void markChunkDirty() {dirty++;}}''',
    'buildcraft/energy/tile/Harness': '''package buildcraft.energy.tile;
        import buildcraft.energy.tile.*;
import buildcraft.lib.fluid.Tank;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
public final class Harness {
    private static int assertions;
    private static void yes(boolean condition) {assertions++;if(!condition) throw new AssertionError("assertion " + assertions);}
    private static void equals(long x,long y) {yes(x==y);}
    public static void main(String[] args) {
        var engine = new TileEngineIron_BC8();
        var fuel=new Tank("fuel",100);var coolant=new Tank("coolant",80);var residue=new Tank("residue",50);
        var handler=new EnergyFluidResourceHandler(engine,fuel,coolant,residue);
        var oil=new FluidResource("fuel");var water=new FluidResource("coolant");var waste=new FluidResource("residue");
        equals(handler.size(),3);equals(handler.getAmountAsLong(0),0);equals(handler.getCapacityAsLong(0,oil),100);
        equals(handler.getCapacityAsLong(0,water),0);yes(!handler.isValid(2,oil));
        try(Transaction root=Transaction.openRoot()) {
            equals(handler.insert(0,oil,65,root),65);
            equals(handler.getAmountAsLong(0),65);
            equals(engine.dirty,0);
            equals(handler.insert(1,water,60,root),60);
            equals(handler.insert(2,waste,7,root),0);
            equals(handler.extract(0,oil,30,root),0);
        }
        equals(handler.getAmountAsLong(0),0);equals(handler.getAmountAsLong(1),0);equals(engine.dirty,0);
        try(Transaction root=Transaction.openRoot()) {
            equals(handler.insert(0,oil,300,root),100);
            equals(handler.insert(1,water,15,root),15);
            root.commit();
        }
        equals(handler.getAmountAsLong(0),100);equals(handler.getAmountAsLong(1),15);
        yes(engine.dirty>0);int dirtyBefore=engine.dirty;
        try(Transaction root=Transaction.openRoot()) {
            equals(handler.insert(0,oil,1,root),0);
            equals(handler.insert(1,oil,10,root),0);
            equals(handler.insert(1,water,10,root),10);
        }
        equals(handler.getAmountAsLong(1),15);equals(engine.dirty,dirtyBefore);
        residue.setFluid(new FluidStack("residue",45));
        yes(handler.isValid(2,waste));yes(handler.getResource(2).equals(waste));
        equals(handler.getCapacityAsLong(2,waste),50);
        try(Transaction root=Transaction.openRoot()) {
            equals(handler.extract(2,oil,5,root),0);
            equals(handler.extract(2,waste,20,root),20);
            equals(handler.getAmountAsLong(2),25);
        }
        equals(handler.getAmountAsLong(2),45);
        try(Transaction root=Transaction.openRoot()) {
            equals(handler.extract(2,waste,80,root),45);
            root.commit();
        }
        equals(handler.getAmountAsLong(2),0);
        equals(handler.getCapacityAsLong(2,waste),0);
        int mark=engine.dirty;
        equals(EnergyFluidResourceHandler.insertInternal(residue,new FluidStack("residue",65),engine),50);
        equals(handler.getAmountAsLong(2),50);yes(engine.dirty>mark);
        mark=engine.dirty;
        try(Transaction root=Transaction.openRoot()) {
            equals(EnergyFluidResourceHandler.drainInternal(coolant,10,engine).getAmount(),10);
            equals(handler.getAmountAsLong(1),5);
            root.commit();
        }
        equals(handler.getAmountAsLong(1),5);yes(engine.dirty>mark);
        System.out.println(assertions+" assertions PASS");
    }
}''',
}


def execute(java_file: Path) -> str:
    with tempfile.TemporaryDirectory(prefix='energy-26-compiled-') as tmp:
        root=Path(tmp)/'src'
        root.mkdir()
        sources=[]
        for name,body in STUBS.items():
            file=root/(name+'.java')
            file.parent.mkdir(parents=True,exist_ok=True)
            file.write_text(body,encoding='utf-8')
            sources.append(str(file))
        actual=root/'buildcraft/energy/tile/EnergyFluidResourceHandler.java'
        actual.parent.mkdir(parents=True,exist_ok=True)
        actual.write_bytes(java_file.read_bytes())
        sources.append(str(actual))
        target=Path(tmp)/'classes'
        compiled=subprocess.run(['javac','-encoding','UTF-8','-d',str(target),*sources],capture_output=True,text=True,timeout=60)
        if compiled.returncode:
            raise AssertionError('Energy fluid adapter did not compile:\n'+compiled.stdout+'\n'+compiled.stderr)
        executed=subprocess.run(['java','-cp',str(target),'buildcraft.energy.tile.Harness'],capture_output=True,text=True,timeout=30)
        if executed.returncode:
            raise AssertionError('Energy fluid adapter violated transaction contract:\n'+executed.stdout+'\n'+executed.stderr)
        return executed.stdout.strip()
