#!/usr/bin/env python3
"""Pipe endpoints, pluggables and platform capability adapters."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import os
import re
import subprocess
import tempfile
import unittest

PIPE_ENDPOINTS_SRC = ROOT / 'source-shared/src/main/java'

PIPE_ENDPOINTS_STUBS = {
    'net/minecraft/core/Direction.java': '''package net.minecraft.core;
public enum Direction { DOWN,UP,NORTH,SOUTH,EAST,WEST }''',
    'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public record BlockPos(int x,int y,int z) {
 public BlockPos relative(Direction side){return new BlockPos(x+1,y,z);}
}''',
    'net/minecraft/resources/ResourceLocation.java': '''package net.minecraft.resources;
public record ResourceLocation(String id) {}''',
    'net/minecraft/world/level/block/state/BlockState.java': '''package net.minecraft.world.level.block.state;
public class BlockState {}''',
    'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
public class Level {
 public boolean loaded=true, client=false;
 public int queryCount=0;
 public boolean isClientSide(){return client;}
 public boolean hasChunkAt(BlockPos pos){return loaded;}
 public BlockState getBlockState(BlockPos pos){queryCount++;return new BlockState();}
}''',
    'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
public class ItemStack {public static final ItemStack EMPTY=new ItemStack();public ItemStack copy(){return this;}
 public boolean isEmpty(){return true;}}''',
    'net/minecraft/world/phys/shapes/VoxelShape.java': '''package net.minecraft.world.phys.shapes;
public class VoxelShape {}''',
    'net/minecraft/world/phys/shapes/Shapes.java': '''package net.minecraft.world.phys.shapes;
public class Shapes {public static VoxelShape empty(){return new VoxelShape();}}''',
    'buildcraft/api/v2/pipe/PipeView.java': '''package buildcraft.api.v2.pipe;
import net.minecraft.core.BlockPos;
public interface PipeView {BlockPos position();
 default java.util.Optional<buildcraft.api.v2.item.ItemPort> itemPort(net.minecraft.core.Direction side){
 return java.util.Optional.of(new buildcraft.api.v2.item.ItemPort(){}); }
 default java.util.Optional<buildcraft.api.v2.fluid.FluidPort> fluidPort(net.minecraft.core.Direction side){return java.util.Optional.empty();}
 default java.util.Optional<buildcraft.api.v2.energy.MjPort> mjPort(net.minecraft.core.Direction side){return java.util.Optional.empty();}
 default java.util.Optional<buildcraft.api.v2.platform.ExternalEnergyPort> externalEnergyPort(net.minecraft.core.Direction side){return java.util.Optional.empty();}
}''',
    'buildcraft/api/v2/pipe/ItemPipePort.java': '''package buildcraft.api.v2.pipe;
public interface ItemPipePort extends buildcraft.api.v2.item.ItemPort {}''',
    'buildcraft/api/v2/pipe/PipeActivationContext.java': '''package buildcraft.api.v2.pipe;
public class PipeActivationContext {}''',
    'buildcraft/api/v2/pipe/PipeActivationResult.java': '''package buildcraft.api.v2.pipe;
public enum PipeActivationResult {PASS,SUCCESS,FAILED}''',
    'buildcraft/api/v2/pipe/PipeExecutionContext.java': '''package buildcraft.api.v2.pipe;
public interface PipeExecutionContext {}''',
    'buildcraft/api/v2/energy/MjPort.java': '''package buildcraft.api.v2.energy;
public interface MjPort {}''',
    'buildcraft/api/v2/fluid/FluidPort.java': '''package buildcraft.api.v2.fluid;
public interface FluidPort {}''',
    'buildcraft/api/v2/item/ItemPort.java': '''package buildcraft.api.v2.item;
public interface ItemPort {}''',
    'buildcraft/api/v2/platform/ExternalEnergyPort.java': '''package buildcraft.api.v2.platform;
public interface ExternalEnergyPort {}''',
    'buildcraft/api/v2/signal/SignalPort.java': '''package buildcraft.api.v2.signal;
public interface SignalPort<T> {}''',
    'buildcraft/transport/internal/pluggable/PipePluggable.java': '''package buildcraft.transport.internal.pluggable;
import net.minecraft.core.Direction;
public class PipePluggable {public Direction side;public PipePluggable(Direction side){this.side=side;}}''',
    'buildcraft/transport/api2/ApiPipePluggable.java': '''package buildcraft.transport.api2;
import buildcraft.api.v2.pipe.PipeAttachment;
import buildcraft.transport.internal.pluggable.PipePluggable;
public class ApiPipePluggable extends PipePluggable {
 private final PipeAttachment attachment;
 public ApiPipePluggable(PipeAttachment attachment){super(attachment.side());this.attachment=attachment;}
 public PipeAttachment attachment(){return attachment;}
}''',
    'buildcraft/transport/api2/ReadOnlyPipeView.java': '''package buildcraft.transport.api2;
import buildcraft.api.v2.pipe.PipeView;
public class ReadOnlyPipeView {public static PipeView of(PipeView pipe){return pipe;}}''',
    'buildcraft/lib/internal/debug/BCLog.java': '''package buildcraft.lib.internal.debug;
public class BCLog {public static final Logger logger=new Logger();
 public static class Logger {public void warn(String text,Object... args){} }}''',
    'buildcraft/api/v2/BuildCraftRegistries.java': '''package buildcraft.api.v2;
import buildcraft.api.v2.pipe.PipeEndpointProvider;
public final class BuildCraftRegistries {
 public static final Object PIPE_ENDPOINT_PROVIDERS=new Object();
}''',
    'buildcraft/api/v2/BuildCraftApi.java': '''package buildcraft.api.v2;
import buildcraft.api.v2.pipe.PipeEndpointProvider;
import java.util.*;
public class BuildCraftApi {
 public static final List<PipeEndpointProvider> PROVIDERS=new ArrayList<>();
 public static Registry registry(Object key){return new Registry();}
 public static class Registry {public List<PipeEndpointProvider> values(){return PROVIDERS;}}
}''',
}

PIPE_ENDPOINTS_PUBLIC = ('pipe/PipeExternalEndpoint.java', 'pipe/PipeEndpointProvider.java',
          'pipe/PipeAttachment.java', 'pipe/PipeConnectionContext.java',
          'pipe/PipeConnectionDecision.java')
PIPE_ENDPOINTS_ADDON = '''package addon;
import buildcraft.api.v2.pipe.*;
import buildcraft.api.v2.item.ItemPort;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import java.util.Optional;
public class PipeEndpointAddon {
 public static PipeEndpointProvider endpoint() {return ctx -> Optional.of(new PipeExternalEndpoint() {
  public Optional<ItemPort> itemPort(){return Optional.of(new ItemPort(){});}
  public float extension(){return 0.75f;}
 });}
 public static PipeAttachment attachment() {return new PipeAttachment() {
  public ResourceLocation typeId(){return new ResourceLocation("addon:pluggable");}
  public Direction side(){return Direction.NORTH;}
  public boolean solidFace(){return true;}
  public boolean canConnectRedstone(Direction side){return side==Direction.NORTH;}
  public PipeConnectionDecision connection(PipeConnectionContext c){return PipeConnectionDecision.DENY;}
  public Optional<ItemPort> itemPort(){return Optional.of(new ItemPort(){});}
 };}
}'''
PIPE_ENDPOINTS_HOST = '''package host;
import addon.PipeEndpointAddon;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.pipe.*;
import buildcraft.transport.api2.*;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import java.util.*;
public class PipeEndpointHost {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args) {
  Level world=new Level();PipeView pipe=()->new BlockPos(1,2,3);
  BuildCraftApi.PROVIDERS.add(ctx -> {throw new RuntimeException("broken provider");});
  BuildCraftApi.PROVIDERS.add(PipeEndpointAddon.endpoint());
  check(PipeEndpointRuntime.find(world,pipe,Direction.NORTH).isPresent(),"lookup survived exception");
  check(world.queryCount==1,"loaded state queried once");
  PipeExternalEndpoint e=PipeEndpointRuntime.find(world,pipe,Direction.NORTH).orElseThrow();
  check(PipeEndpointRuntime.canConnect(e,pipe,Direction.NORTH),"declared endpoint connected");
  check(PipeEndpointRuntime.extension(e)==0.5f,"physical extension bounded");
  check(e.itemPort().isPresent(),"addon port exposed");
  check(!PipeEndpointRuntime.canConnect(new PipeExternalEndpoint(){
    public Optional<buildcraft.api.v2.fluid.FluidPort> fluidPort(){
      return Optional.of(new buildcraft.api.v2.fluid.FluidPort(){});}
   },pipe,Direction.NORTH),"incompatible fluid endpoint rejected");
  check(PipeEndpointRuntime.attachmentPort(new ApiPipePluggable(PipeEndpointAddon.attachment()),
   PipeAttachment::itemPort).isPresent(),"addon attachment port");
  PipeConnectionContext ctx=new PipeConnectionContext(world,pipe,Direction.NORTH,world.getBlockState(pipe.position()));
  check(PipeEndpointRuntime.attachmentDecision(new ApiPipePluggable(PipeEndpointAddon.attachment()),ctx)
    ==PipeConnectionDecision.DENY,"attachment veto");
  world.loaded=false;int queries=world.queryCount;
  check(PipeEndpointRuntime.find(world,pipe,Direction.NORTH).isEmpty(),"unloaded refusal");
  check(world.queryCount==queries,"no forced chunk load");
  world.client=true;world.loaded=true;
  check(PipeEndpointRuntime.find(world,pipe,Direction.NORTH).isEmpty(),"client callbacks disabled");
  check(!PipeEndpointRuntime.canConnect(new PipeExternalEndpoint(){},pipe,Direction.NORTH),"inert endpoints do not connect");
 }
}'''


def pipe_endpoints_write(base, name, content):
    result = base / name
    result.parent.mkdir(parents=True, exist_ok=True)
    result.write_text(content, encoding='utf-8')
    return result


class PipeEndpointConformanceTest(unittest.TestCase):
    def test_sided_wiring(self):
        native = list(ROOT.glob('source-*/**/buildcraft/transport/pipe/Pipe.java'))
        self.assertEqual(5, len(native))
        for file in native:
            with self.subTest(path=str(file)):
                code = file.read_text(encoding='utf-8')
                self.assertIn('PipeEndpointRuntime.canConnect', code)
                self.assertIn('PipeEndpointRuntime.attachmentDecision', code)
                self.assertIn('PipeEndpointRuntime.attachmentPort', code)
                # Verify the unloaded-neighbour guard in the connection path, not
                # the OS-specific spelling of the file path or a variable name.
                self.assertIn('private void updateConnections()', code)
                connections = code.split('private void updateConnections()', 1)[1].split(
                    '// API2 pipe runtime surface', 1)[0]
                guard = re.search(
                    r'if\s*\(\s*!\s*(?:level|holder\.getPipeWorld\(\))'
                    r'\.hasChunkAt\((?:nPos|adjacentPos)\)\s*\)\s*continue\s*;',
                    connections)
                self.assertIsNotNone(guard, f'missing unloaded-chunk guard in {file}')
                endpoint_lookup = connections.find('PipeEndpointRuntime.find(')
                self.assertGreaterEqual(endpoint_lookup, 0)
                self.assertLess(guard.start(), endpoint_lookup,
                    f'endpoint queried before checking loaded chunk in {file}')
        native = list(ROOT.glob('source-*/**/buildcraft/transport/tile/TilePipeHolder.java'))
        self.assertEqual(4, len(native))
        for file in native:
            with self.subTest(path=str(file)):
                code = file.read_text(encoding='utf-8')
                self.assertIn('ApiPortCapabilities.external', code)
                self.assertIn('ApiPortCapabilities.attachment', code)
        for file in ROOT.glob('source-*/**/buildcraft/transport/wire/WorldSavedDataWireSystems.java'):
            self.assertIn('PipeEndpointRuntime.find', file.read_text(encoding='utf-8'))
        for loader in ('forge', 'neoforge'):
            file = ROOT / ('source-platforms/'+loader+'/src/main/java/buildcraft/transport/api2/ApiPortCapabilities.java')
            data=file.read_text(encoding='utf-8')
            for typename in ('ItemAdapter', 'FluidAdapter', 'EnergyAdapter', 'MjAdapter'):
                self.assertIn(typename, data)
            self.assertIn('OperationMode.SIMULATE', data)

    def test_addon_jar_uses_public_contracts(self):
        with tempfile.TemporaryDirectory(prefix='pipe-endpoints-') as td:
            temp=Path(td)
            stub=[pipe_endpoints_write(temp/'stubs',n,v) for n,v in PIPE_ENDPOINTS_STUBS.items()]
            public=[PIPE_ENDPOINTS_SRC/'buildcraft/api/v2'/n for n in PIPE_ENDPOINTS_PUBLIC]
            backend=PIPE_ENDPOINTS_SRC/'buildcraft/transport/api2/PipeEndpointRuntime.java'
            pub=temp/'public';addon=temp/'addon';host=temp/'host'
            for path in (pub,addon,host):path.mkdir()
            subprocess.run(['javac','-d',str(pub),*(str(p) for p in stub+public)],check=True,capture_output=True,text=True)
            addonjava=pipe_endpoints_write(temp/'src-addon','addon/PipeEndpointAddon.java',PIPE_ENDPOINTS_ADDON)
            subprocess.run(['javac','-cp',str(pub),'-d',str(addon),str(addonjava)],check=True,capture_output=True,text=True)
            addonjar=temp/'PipeEndpointAddon.jar'
            subprocess.run(['jar','cf',str(addonjar),'-C',str(addon),'.'],check=True,capture_output=True,text=True)
            hostjava=pipe_endpoints_write(temp/'src-host','host/PipeEndpointHost.java',PIPE_ENDPOINTS_HOST)
            cp=os.pathsep.join((str(pub),str(addonjar)))
            subprocess.run(['javac','-cp',cp,'-d',str(host),str(backend),str(hostjava)],check=True,capture_output=True,text=True)
            subprocess.run(['java','-cp',os.pathsep.join((cp,str(host))),'host.PipeEndpointHost'],check=True,capture_output=True,text=True)


CAPABILITIES_C={
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
CAPABILITIES_TEST='''package host;
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


def capabilities_mk(base,n,s):
 p=base/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf8');return p

class PipeCapabilityConformanceTest(unittest.TestCase):
 def test_compile_and_transfer_contracts(self):
  for loader in ('forge','neoforge'):
   with self.subTest(loader=loader),tempfile.TemporaryDirectory(prefix='pipe-capability-'+loader+'-') as td:
    tmp=Path(td)
    content=dict(CAPABILITIES_C)
    if loader=='neoforge':
     content={n.replace('net/minecraftforge/','net/neoforged/neoforge/'):s.replace('net.minecraftforge.','net.neoforged.neoforge.') for n,s in content.items() if n!='net/minecraftforge/common/capabilities/Capability.java' and n!='net/minecraftforge/common/util/LazyOptional.java'}
     content['net/neoforged/neoforge/capabilities/BlockCapability.java']='package net.neoforged.neoforge.capabilities;public class BlockCapability<T,D>{}'
     content['buildcraft/lib/misc/CapUtil.java']=CAPABILITIES_C['buildcraft/lib/misc/CapUtil.java'].replace('net.minecraftforge.common.capabilities.Capability;','net.neoforged.neoforge.capabilities.BlockCapability;\nimport net.minecraft.core.Direction;').replace('net.minecraftforge.','net.neoforged.neoforge.').replace('Capability<','BlockCapability<').replace('new Capability<>','new BlockCapability<>').replace('BlockCapability<IItemHandler>','BlockCapability<IItemHandler,Direction>').replace('BlockCapability<IFluidHandler>','BlockCapability<IFluidHandler,Direction>').replace('BlockCapability<IEnergyStorage>','BlockCapability<IEnergyStorage,Direction>')
     content['buildcraft/lib/internal/mj/MjCapabilities.java']=CAPABILITIES_C['buildcraft/lib/internal/mj/MjCapabilities.java'].replace('net.minecraftforge.common.capabilities.Capability;','net.neoforged.neoforge.capabilities.BlockCapability;\nimport net.minecraft.core.Direction;').replace('Capability<','BlockCapability<').replace('new Capability<>','new BlockCapability<>').replace('BlockCapability<IMjConnector>','BlockCapability<IMjConnector,Direction>').replace('BlockCapability<IMjReceiver>','BlockCapability<IMjReceiver,Direction>').replace('BlockCapability<IMjPassiveProvider>','BlockCapability<IMjPassiveProvider,Direction>')
    stubs=[capabilities_mk(tmp/'stubs',n,s) for n,s in content.items()]
    host=CAPABILITIES_TEST
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
    src=capabilities_mk(tmp/'host','host/Host.java',host)
    adapter=ROOT / f'source-platforms/{loader}/src/main/java/buildcraft/transport/api2/ApiPortCapabilities.java'
    dest=tmp/'classes';dest.mkdir()
    done=subprocess.run(['javac','-d',str(dest),*(str(p) for p in stubs),str(adapter),str(src)],capture_output=True,text=True)
    self.assertEqual(done.returncode,0,done.stderr[:4500])
    done=subprocess.run(['java','-cp',str(dest),'host.Host'],capture_output=True,text=True)
    self.assertEqual(done.returncode,0,done.stderr[:4500])

if __name__ == "__main__":
    unittest.main()
