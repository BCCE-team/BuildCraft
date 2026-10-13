#!/usr/bin/env python3
"""Compile platform capability adapters and exercise their conservation semantics without loader jars."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
C={
'net/minecraft/core/Direction.java': 'package net.minecraft.core;public enum Direction{NORTH,SOUTH}',
'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
public class ItemStack {public static final ItemStack EMPTY=new ItemStack(0);
private int count;public ItemStack(int count){this.count=count;}
public boolean isEmpty(){return count<=0;} public int getCount(){return count;}
public void setCount(int value){count=value;} public void shrink(int value){count=Math.max(0,count-value);}
public ItemStack copy(){return new ItemStack(count);}}''',
'buildcraft/api/v2/OperationMode.java':'package buildcraft.api.v2;public enum OperationMode{SIMULATE,EXECUTE}',
'buildcraft/api/v2/item/ItemMatcher.java':'package buildcraft.api.v2.item;public interface ItemMatcher{static ItemMatcher any(){return new ItemMatcher(){};}}',
'buildcraft/api/v2/item/ItemTransferResult.java':'''package buildcraft.api.v2.item;
import net.minecraft.world.item.ItemStack;
public record ItemTransferResult(int transferredCount,ItemStack transferred){}''',
'buildcraft/api/v2/item/ItemPort.java':'''package buildcraft.api.v2.item;
import buildcraft.api.v2.OperationMode;import net.minecraft.world.item.ItemStack;
public interface ItemPort {
 ItemTransferResult insert(ItemStack offered,OperationMode mode);
 ItemTransferResult extract(ItemMatcher filter,int max,OperationMode mode);
}''',
'buildcraft/api/v2/item/SlottedItemPort.java':'''package buildcraft.api.v2.item;
import net.minecraft.world.item.ItemStack;
public interface SlottedItemPort extends ItemPort{
 int slots(); ItemStack stackInSlot(int slot); ItemMatcher filter(int slot);
}''',
'buildcraft/api/v2/fluid/FluidAmount.java':'''package buildcraft.api.v2.fluid;
public record FluidAmount(long milliBuckets){public static FluidAmount of(long amt){return new FluidAmount(amt);}}''',
'buildcraft/api/v2/fluid/FluidVariant.java':'package buildcraft.api.v2.fluid;public record FluidVariant(String type){}',
'buildcraft/api/v2/fluid/FluidVolume.java':'''package buildcraft.api.v2.fluid;
public record FluidVolume(FluidVariant requireVariant,FluidAmount amount) {
 public boolean isEmpty(){return amount.milliBuckets()<=0;}
 public boolean sameVariant(FluidVolume other){return requireVariant.equals(other.requireVariant);}
}''',
'buildcraft/api/v2/fluid/FluidMatcher.java':'''package buildcraft.api.v2.fluid;
public interface FluidMatcher{
 static FluidMatcher any(){return new FluidMatcher(){};}
 static FluidMatcher exact(FluidVariant variant){return new FluidMatcher(){};}
}''',
'buildcraft/api/v2/fluid/FluidTransferResult.java':'''package buildcraft.api.v2.fluid;
public record FluidTransferResult(FluidVolume transferred){
 public FluidAmount transferredAmount(){return transferred.amount();}
}''',
'buildcraft/api/v2/fluid/FluidPort.java':'''package buildcraft.api.v2.fluid;
import buildcraft.api.v2.OperationMode;
public interface FluidPort {
 FluidTransferResult insert(FluidVolume amount,OperationMode mode);
 FluidTransferResult extract(FluidMatcher matcher,FluidAmount maximum,OperationMode mode);
}''',
'buildcraft/api/v2/energy/MjAmount.java':'''package buildcraft.api.v2.energy;
public record MjAmount(long microMj){public static MjAmount ofMicro(long amt){return new MjAmount(amt);}}''',
'buildcraft/api/v2/energy/MjTransferResult.java':'package buildcraft.api.v2.energy;public record MjTransferResult(MjAmount transferred){}',
'buildcraft/api/v2/energy/MjPort.java':'''package buildcraft.api.v2.energy;
import buildcraft.api.v2.OperationMode;
public interface MjPort {
 MjTransferResult insert(MjAmount offered,OperationMode mode);
 MjTransferResult extract(MjAmount requested,OperationMode mode);
 MjAmount stored();MjAmount capacity();
 default boolean canInsert(){return true;}
 default boolean canExtract(){return true;}
}''',
'buildcraft/api/v2/platform/ExternalEnergyPort.java':'''package buildcraft.api.v2.platform;
import buildcraft.api.v2.OperationMode;
public interface ExternalEnergyPort {
 long insert(long amount,OperationMode mode);long extract(long amount,OperationMode mode);
 long stored();long capacity();boolean canInsert();boolean canExtract();
}''',
'buildcraft/api/v2/pipe/PipeAttachment.java':'''package buildcraft.api.v2.pipe;
import java.util.Optional;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.platform.ExternalEnergyPort;
public interface PipeAttachment {
 Optional<ItemPort> itemPort();Optional<? extends ItemPort> itemPipePort();
 Optional<FluidPort> fluidPort();Optional<MjPort> mjPort();Optional<ExternalEnergyPort> externalEnergyPort();
}''',
'buildcraft/api/v2/pipe/PipeExternalEndpoint.java':'''package buildcraft.api.v2.pipe;
import java.util.Optional;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.platform.ExternalEnergyPort;
public interface PipeExternalEndpoint {
 Optional<ItemPort> itemPort();Optional<FluidPort> fluidPort();
 Optional<MjPort> mjPort();Optional<ExternalEnergyPort> externalEnergyPort();
}''',
'buildcraft/lib/fluid/FuelApiBridge.java':'''package buildcraft.lib.fluid;
import buildcraft.api.v2.fluid.*;
import net.minecraftforge.fluids.FluidStack;
public class FuelApiBridge{
 public static FluidStack stackOf(FluidVolume volume){return new FluidStack((int)volume.amount().milliBuckets());}
 public static FluidVolume volumeOf(FluidStack stack){return new FluidVolume(new FluidVariant("water"),FluidAmount.of(stack.getAmount()));}
}''',
'buildcraft/lib/internal/mj/IMjConnector.java':'''package buildcraft.lib.internal.mj;
public interface IMjConnector{boolean canConnect(IMjConnector other);}''',
'buildcraft/lib/internal/mj/IMjReceiver.java':'''package buildcraft.lib.internal.mj;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
public interface IMjReceiver extends IMjConnector{
 boolean canReceive();long getPowerRequested();long receivePower(long amount,FluidAction action);
}''',
'buildcraft/lib/internal/mj/IMjPassiveProvider.java':'''package buildcraft.lib.internal.mj;
public interface IMjPassiveProvider extends IMjConnector{long extractPower(long minimum,long maximum,boolean execute);}''',
'net/minecraftforge/fluids/FluidStack.java':'''package net.minecraftforge.fluids;
public class FluidStack {
public static final FluidStack EMPTY=new FluidStack(0);
private final int amount;public FluidStack(int amount){this.amount=amount;}
public int getAmount(){return amount;}
public boolean isEmpty(){return amount<=0;}
}''',
'net/minecraftforge/fluids/capability/IFluidHandler.java':'''package net.minecraftforge.fluids.capability;
import net.minecraftforge.fluids.FluidStack;
public interface IFluidHandler{
 enum FluidAction{SIMULATE,EXECUTE}
 int getTanks();FluidStack getFluidInTank(int tank);int getTankCapacity(int tank);
 boolean isFluidValid(int tank,FluidStack stack);int fill(FluidStack stack,FluidAction action);
 FluidStack drain(FluidStack stack,FluidAction action);FluidStack drain(int amount,FluidAction action);
}''',
'net/minecraftforge/items/IItemHandler.java':'''package net.minecraftforge.items;
import net.minecraft.world.item.ItemStack;
public interface IItemHandler{
int getSlots();ItemStack getStackInSlot(int slot);ItemStack insertItem(int slot,ItemStack stack,boolean simulate);
ItemStack extractItem(int slot,int amount,boolean simulate);
int getSlotLimit(int slot);boolean isItemValid(int slot,ItemStack stack);
}''',
'net/minecraftforge/energy/IEnergyStorage.java':'''package net.minecraftforge.energy;
public interface IEnergyStorage {
int receiveEnergy(int offered,boolean simulate);int extractEnergy(int requested,boolean simulate);
int getEnergyStored();int getMaxEnergyStored();boolean canExtract();boolean canReceive();
}''',
'net/minecraftforge/common/capabilities/Capability.java':'''package net.minecraftforge.common.capabilities;
public class Capability<T>{}''',
'net/minecraftforge/common/util/LazyOptional.java':'''package net.minecraftforge.common.util;
import java.util.function.Supplier;
public class LazyOptional<T>{private final Supplier<T> supplier;
private LazyOptional(Supplier<T> supplier){this.supplier=supplier;}
public static <T> LazyOptional<T> of(Supplier<T> supplier){return new LazyOptional<>(supplier);}
public static <T> LazyOptional<T> empty(){return new LazyOptional<>(()->null);}
public T orElse(T other){T value=supplier.get();return value==null?other:value;}
}''',
'buildcraft/lib/misc/CapUtil.java':'''package buildcraft.lib.misc;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.energy.IEnergyStorage;
public final class CapUtil{
public static final Capability<IItemHandler> CAP_ITEMS=new Capability<>();
public static final Capability<IFluidHandler> CAP_FLUIDS=new Capability<>();
public static final Capability<IEnergyStorage> CAP_FE=new Capability<>();}
''',
'buildcraft/lib/internal/mj/MjCapabilities.java':'''package buildcraft.lib.internal.mj;
import net.minecraftforge.common.capabilities.Capability;
public class MjCapabilities{
 public static final Capability<IMjConnector> CAP_CONNECTOR=new Capability<>();
 public static final Capability<IMjReceiver> CAP_RECEIVER=new Capability<>();
 public static final Capability<IMjPassiveProvider> CAP_PASSIVE_PROVIDER=new Capability<>();
}''',
}
TEST='''package host;
import buildcraft.api.v2.*;
import buildcraft.api.v2.energy.*;
import buildcraft.api.v2.fluid.*;
import buildcraft.api.v2.item.*;
import buildcraft.api.v2.platform.*;
import buildcraft.api.v2.pipe.*;
import buildcraft.transport.api2.ApiPortCapabilities;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.internal.mj.*;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraft.world.item.ItemStack;
import java.util.*;
public class Host{
static void check(boolean c){if(!c)throw new AssertionError();}
static class Items implements ItemPort {
 int value=4;
 public ItemTransferResult insert(ItemStack offered,OperationMode mode){
  int n=Math.min(2,offered.getCount());if(mode==OperationMode.EXECUTE)value+=n;
  return new ItemTransferResult(n,new ItemStack(n));
 }
 public ItemTransferResult extract(ItemMatcher f,int n,OperationMode mode){
  int count=Math.min(n,value);if(mode==OperationMode.EXECUTE)value-=count;
  return new ItemTransferResult(count,new ItemStack(count));
 }
}
static class Fluids implements FluidPort {
 long value=4;
 public FluidTransferResult insert(FluidVolume offered,OperationMode mode){
  long n=Math.min(2,offered.amount().milliBuckets());if(mode==OperationMode.EXECUTE)value+=n;
  return new FluidTransferResult(new FluidVolume(offered.requireVariant(),FluidAmount.of(n)));
 }
 public FluidTransferResult extract(FluidMatcher m,FluidAmount n,OperationMode mode){
  long amount=Math.min(n.milliBuckets(),value);if(mode==OperationMode.EXECUTE)value-=amount;
  return new FluidTransferResult(new FluidVolume(new FluidVariant("water"),FluidAmount.of(amount)));
 }
}
static class Fe implements ExternalEnergyPort {
 long stored=6;
 public long insert(long n,OperationMode mode){n=Math.min(3,n);if(mode==OperationMode.EXECUTE)stored+=n;return n;}
 public long extract(long n,OperationMode mode){n=Math.min(stored,n);if(mode==OperationMode.EXECUTE)stored-=n;return n;}
 public long stored(){return stored;}public long capacity(){return 20;}
 public boolean canInsert(){return true;}public boolean canExtract(){return true;}
}
static class Mj implements MjPort {
 long stored=8;
 public MjTransferResult insert(MjAmount n,OperationMode mode){long moved=Math.min(3,n.microMj());
 if(mode==OperationMode.EXECUTE)stored+=moved;return new MjTransferResult(MjAmount.ofMicro(moved));}
 public MjTransferResult extract(MjAmount n,OperationMode mode){long moved=Math.min(n.microMj(),stored);
 if(mode==OperationMode.EXECUTE)stored-=moved;return new MjTransferResult(MjAmount.ofMicro(moved));}
 public MjAmount stored(){return MjAmount.ofMicro(stored);}
 public MjAmount capacity(){return MjAmount.ofMicro(20);}
}
public static void main(String[] args){
 Items items=new Items();Fluids fluids=new Fluids();Fe fe=new Fe();Mj mj=new Mj();
 PipeExternalEndpoint endpoint=new PipeExternalEndpoint(){
 public Optional<ItemPort> itemPort(){return Optional.of(items);}
 public Optional<FluidPort> fluidPort(){return Optional.of(fluids);}
 public Optional<MjPort> mjPort(){return Optional.of(mj);}
 public Optional<ExternalEnergyPort> externalEnergyPort(){return Optional.of(fe);}
 };
 IItemHandler handler=CAP_ITEM;
 check(handler!=null);
 check(handler.insertItem(0,new ItemStack(10),true).getCount()==8);
 check(items.value==4);
 check(handler.insertItem(0,new ItemStack(10),false).getCount()==8);
 check(items.value==6);
 check(handler.extractItem(0,3,true).getCount()==3);
 check(items.value==6);
 IFluidHandler f=CAP_FLUID;
 check(f.fill(new net.minecraftforge.fluids.FluidStack(10),IFluidHandler.FluidAction.SIMULATE)==2);
 check(fluids.value==4);
 check(f.fill(new net.minecraftforge.fluids.FluidStack(10),IFluidHandler.FluidAction.EXECUTE)==2);
 check(fluids.value==6);
 IEnergyStorage e=CAP_FE;
 check(e.receiveEnergy(10,true)==3);
 check(fe.stored==6);
 check(e.receiveEnergy(10,false)==3);
 check(fe.stored==9);
 IMjReceiver receiver=CAP_MJ_RECEIVER;
 check(receiver.receivePower(7,IFluidHandler.FluidAction.SIMULATE)==4);
 check(mj.stored==8);
 check(receiver.receivePower(7,IFluidHandler.FluidAction.EXECUTE)==4);
 check(mj.stored==11);
 IMjPassiveProvider passive=CAP_MJ_SOURCE;
 check(passive.extractPower(3,9,false)==9);
 check(mj.stored==11);
 check(passive.extractPower(3,9,true)==9);
 check(mj.stored==2);
}
static PipeExternalEndpoint endpoint;
static IItemHandler CAP_ITEM;
static IFluidHandler CAP_FLUID;
static IEnergyStorage CAP_FE;
static IMjReceiver CAP_MJ_RECEIVER;
static IMjPassiveProvider CAP_MJ_SOURCE;
static { // assigned by launcher-specific expression
}
}'''


def mk(base,n,s):
 p=base/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf8');return p

class P08CapabilitiesTest(unittest.TestCase):
 def test_compile_and_transfer_contracts(self):
  for loader in ('forge','neoforge'):
   with self.subTest(loader=loader),tempfile.TemporaryDirectory(prefix='p08-cap-'+loader+'-') as td:
    tmp=Path(td)
    content=dict(C)
    if loader=='neoforge':
     content={n.replace('net/minecraftforge/','net/neoforged/neoforge/'):s.replace('net.minecraftforge.','net.neoforged.neoforge.') for n,s in content.items() if n!='net/minecraftforge/common/capabilities/Capability.java' and n!='net/minecraftforge/common/util/LazyOptional.java'}
     content['net/neoforged/neoforge/capabilities/BlockCapability.java']='package net.neoforged.neoforge.capabilities;public class BlockCapability<T,D>{}'
     content['buildcraft/lib/misc/CapUtil.java']=C['buildcraft/lib/misc/CapUtil.java'].replace('net.minecraftforge.common.capabilities.Capability;','net.neoforged.neoforge.capabilities.BlockCapability;\nimport net.minecraft.core.Direction;').replace('net.minecraftforge.','net.neoforged.neoforge.').replace('Capability<','BlockCapability<').replace('new Capability<>','new BlockCapability<>').replace('BlockCapability<IItemHandler>','BlockCapability<IItemHandler,Direction>').replace('BlockCapability<IFluidHandler>','BlockCapability<IFluidHandler,Direction>').replace('BlockCapability<IEnergyStorage>','BlockCapability<IEnergyStorage,Direction>')
     content['buildcraft/lib/internal/mj/MjCapabilities.java']=C['buildcraft/lib/internal/mj/MjCapabilities.java'].replace('net.minecraftforge.common.capabilities.Capability;','net.neoforged.neoforge.capabilities.BlockCapability;\nimport net.minecraft.core.Direction;').replace('Capability<','BlockCapability<').replace('new Capability<>','new BlockCapability<>').replace('BlockCapability<IMjConnector>','BlockCapability<IMjConnector,Direction>').replace('BlockCapability<IMjReceiver>','BlockCapability<IMjReceiver,Direction>').replace('BlockCapability<IMjPassiveProvider>','BlockCapability<IMjPassiveProvider,Direction>')
    stubs=[mk(tmp/'stubs',n,s) for n,s in content.items()]
    host=TEST
    if loader=='neoforge':
     host=host.replace('net.minecraftforge.', 'net.neoforged.neoforge.')
    if loader=='forge':
     host=host.replace('IItemHandler handler=CAP_ITEM;', 'IItemHandler handler=CAP_ITEM;')
    host=host.replace('static { // assigned by launcher-specific expression\n}', '''static {
 endpoint=new PipeExternalEndpoint(){
 public Optional<ItemPort> itemPort(){return Optional.of(itemData);}
 public Optional<FluidPort> fluidPort(){return Optional.of(fluidData);}
 public Optional<MjPort> mjPort(){return Optional.of(mjData);}
 public Optional<ExternalEnergyPort> externalEnergyPort(){return Optional.of(feData);}
 };
}''')
    # runtime uses local endpoint variables, connect via factory after local instantiation
    host=host.replace(' IItemHandler handler=CAP_ITEM;', ' setNative(endpoint);\n IItemHandler handler=CAP_ITEM;')
    host=host.replace('static {\n endpoint=', 'static Items itemData;static Fluids fluidData;static Fe feData;static Mj mjData;\n static void setNative(PipeExternalEndpoint e){\n'+(''' CAP_ITEM=ApiPortCapabilities.external(e,CapUtil.CAP_ITEMS).orElse(null);
 CAP_FLUID=ApiPortCapabilities.external(e,CapUtil.CAP_FLUIDS).orElse(null);
 CAP_FE=ApiPortCapabilities.external(e,CapUtil.CAP_FE).orElse(null);
 CAP_MJ_RECEIVER=ApiPortCapabilities.external(e,MjCapabilities.CAP_RECEIVER).orElse(null);
 CAP_MJ_SOURCE=ApiPortCapabilities.external(e,MjCapabilities.CAP_PASSIVE_PROVIDER).orElse(null);
 ''' if loader=='forge' else ''' CAP_ITEM=ApiPortCapabilities.external(e,CapUtil.CAP_ITEMS);
 CAP_FLUID=ApiPortCapabilities.external(e,CapUtil.CAP_FLUIDS);
 CAP_FE=ApiPortCapabilities.external(e,CapUtil.CAP_FE);
 CAP_MJ_RECEIVER=ApiPortCapabilities.external(e,MjCapabilities.CAP_RECEIVER);
 CAP_MJ_SOURCE=ApiPortCapabilities.external(e,MjCapabilities.CAP_PASSIVE_PROVIDER);
 ''')+'}\nstatic {\n endpoint=')
    # remove static initializer entirely - it is irrelevant for test
    begin=host.index('static Items itemData;')
    tail=host.index('static {\n endpoint=',begin)
    host=host[:tail]+host[host.index('\n}',tail)+2:]
    src=mk(tmp/'host','host/Host.java',host)
    adapter=ROOT / f'source-platforms/{loader}/src/main/java/buildcraft/transport/api2/ApiPortCapabilities.java'
    dest=tmp/'classes';dest.mkdir()
    done=subprocess.run(['javac','-d',str(dest),*(str(p) for p in stubs),str(adapter),str(src)],capture_output=True,text=True)
    self.assertEqual(done.returncode,0,done.stderr[:4500])
    done=subprocess.run(['java','-cp',str(dest),'host.Host'],capture_output=True,text=True)
    self.assertEqual(done.returncode,0,done.stderr[:4500])

if __name__=='__main__':unittest.main()
