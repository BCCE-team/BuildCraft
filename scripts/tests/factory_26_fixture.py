"""Compile and exercise the 26.3 factory tank ResourceHandler against typed API doubles."""
from __future__ import annotations

from pathlib import Path
import subprocess
import tempfile

from transport_26_fixture import STUBS as TRANSPORT_STUBS

STUBS = {name: TRANSPORT_STUBS[name] for name in (
    'net/neoforged/neoforge/fluids/FluidStack',
    'net/neoforged/neoforge/transfer/fluid/FluidResource',
    'net/neoforged/neoforge/transfer/transaction/TransactionContext',
    'net/neoforged/neoforge/transfer/transaction/Transaction',
    'net/neoforged/neoforge/transfer/ResourceHandler',
)}
STUBS.update({
    'net/neoforged/neoforge/transfer/TransferPreconditions': '''package net.neoforged.neoforge.transfer;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
public final class TransferPreconditions {
    public static void checkNonEmptyNonNegative(FluidResource resource, int amount) {
       if (resource.isEmpty() || amount < 0) throw new IllegalArgumentException("invalid transfer");
    }
}''',
    'buildcraft/lib/fluid/FluidCompatRegistry': '''package buildcraft.lib.fluid;
import net.neoforged.neoforge.fluids.FluidStack;
public final class FluidCompatRegistry {
    public static FluidStack canonicalize(FluidStack fluid) { return fluid; }
    public static boolean areEquivalent(FluidStack a, FluidStack b) { return a.name().equals(b.name()); }
}''',
    'buildcraft/lib/fluid/Tank': '''package buildcraft.lib.fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.transaction.Transaction;
public final class Tank {
    private FluidStack contents;
    private final int capacity;
    private final String allowed;
    public boolean input = true, output = true;
    public int changes = 0;
    public Tank(String allowed,int capacity,FluidStack contents){
      this.allowed=allowed;this.capacity=capacity;this.contents=contents;
    }
    public FluidStack getFluid(){return contents;}
    public int getFluidAmount(){return contents.getAmount();}
    public int getCapacity(){return capacity;}
    public boolean canFill(){return input;}
    public boolean canDrain(){return output;}
    public boolean isFluidValid(FluidStack fluid){return fluid.name().equals(allowed);}
    public void setFluid(FluidStack fluid){
       FluidStack before=contents;
       Transaction.snapshot(()->contents=before);
       contents=fluid;
       changes++;
    }
}''',
    'buildcraft/factory/compat/Harness': '''package buildcraft.factory.compat;
import buildcraft.lib.fluid.Tank;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
public final class Harness {
  private static int checks;
  private static void ok(boolean passed){checks++;if(!passed)throw new AssertionError("test #"+checks);}
  private static void eq(long a,long b){ok(a==b);}
  public static void main(String[] args) {
    var a=new Tank("oil",100,FluidStack.EMPTY);
    var b=new Tank("water",200,new FluidStack("water",30));
    var handler=new FactoryTankResourceHandler263(a,b);
    var oil=new FluidResource("oil");
    var water=new FluidResource("water");
    eq(handler.size(),2);
    ok(handler.getResource(0).isEmpty());
    ok(handler.getResource(1).equals(water));
    eq(handler.getAmountAsLong(0),0);
    eq(handler.getAmountAsLong(1),30);
    eq(handler.getCapacityAsLong(0,oil),100);
    eq(handler.getCapacityAsLong(0,water),0);
    eq(handler.getCapacityAsLong(1,water),200);
    ok(handler.isValid(0,oil));
    ok(!handler.isValid(0,water));
    ok(!handler.isValid(1,oil));
    try (var tx=Transaction.openRoot()) {
      eq(handler.insert(0,oil,80,tx),80);
      eq(handler.insert(0,water,10,tx),0);
      eq(handler.getAmountAsLong(0),80);
    }
    eq(handler.getAmountAsLong(0),0);
    try (var tx=Transaction.openRoot()) {
      eq(handler.insert(0,oil,180,tx),100);
      eq(handler.getAmountAsLong(0),100);
      tx.commit();
    }
    eq(handler.getAmountAsLong(0),100);
    try(var tx=Transaction.openRoot()) {
      eq(handler.extract(0,oil,55,tx),55);
      eq(handler.getAmountAsLong(0),45);
    }
    eq(handler.getAmountAsLong(0),100);
    try(var tx=Transaction.openRoot()) {
      eq(handler.extract(0,water,5,tx),0);
      eq(handler.extract(0,oil,200,tx),100);
      eq(handler.getAmountAsLong(0),0);
      tx.commit();
    }
    ok(handler.getResource(0).isEmpty());
    try(var tx=Transaction.openRoot()) {
      eq(handler.extract(1,water,10,tx),10);
      eq(handler.getAmountAsLong(1),20);
      try(var child=Transaction.open(tx)) {
        eq(handler.extract(1,water,5,child),5);
        eq(handler.getAmountAsLong(1),15);
      }
      eq(handler.getAmountAsLong(1),20);
    }
    eq(handler.getAmountAsLong(1),30);
    try(var tx=Transaction.openRoot()) {
      eq(handler.insert(0,oil,90,tx),90);
      try(var child=Transaction.open(tx)) {
        eq(handler.extract(0,oil,25,child),25);
        child.commit();
      }
      eq(handler.getAmountAsLong(0),65);
    }
    eq(handler.getAmountAsLong(0),0);
    try(var tx=Transaction.openRoot()) {
      eq(handler.insert(0,oil,40,tx),40);
      try(var child=Transaction.open(tx)) {
        eq(handler.extract(0,oil,10,child),10);
        child.commit();
      }
      tx.commit();
    }
    eq(handler.getAmountAsLong(0),30);
    a.input=false;
    try(var tx=Transaction.openRoot()) {eq(handler.insert(0,oil,10,tx),0);}
    a.output=false;
    try(var tx=Transaction.openRoot()) {eq(handler.extract(0,oil,10,tx),0);}
    a.input=true;a.output=true;
    try(var tx=Transaction.openRoot()) {
      eq(handler.insert(0,oil,0,tx),0);
      eq(handler.extract(0,oil,0,tx),0);
    }
    eq(handler.getAmountAsLong(0),30);
    System.out.println(checks+" assertions PASS");
  }
}''',
})


def execute(adapter_java: Path) -> str:
    with tempfile.TemporaryDirectory(prefix='factory-26-native-') as temp:
        root=Path(temp)/'src'
        names=[]
        for path,src in STUBS.items():
            dst=root/(path+'.java')
            dst.parent.mkdir(parents=True,exist_ok=True)
            dst.write_text(src,encoding='utf-8')
            names.append(str(dst))
        dst=root/'buildcraft/factory/compat'/adapter_java.name
        dst.write_bytes(adapter_java.read_bytes())
        names.append(str(dst))
        classes=Path(temp)/'classes'
        compile=subprocess.run(['javac','-encoding','UTF-8','-d',str(classes),*names],capture_output=True,text=True,timeout=60)
        if compile.returncode:
            raise AssertionError('factory adapter compilation failed:\n'+compile.stdout+'\n'+compile.stderr)
        run=subprocess.run(['java','-cp',str(classes),'buildcraft.factory.compat.Harness'],capture_output=True,text=True,timeout=30)
        if run.returncode:
            raise AssertionError('factory adapter tests failed:\n'+run.stdout+'\n'+run.stderr)
        return run.stdout.strip()
