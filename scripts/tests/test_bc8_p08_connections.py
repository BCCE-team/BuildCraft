#!/usr/bin/env python3
"""BC8-P08 public endpoints/attachments, real connection wiring, and addon isolation."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'source-shared/src/main/java'

STUBS = {
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

PUBLIC = ('pipe/PipeExternalEndpoint.java', 'pipe/PipeEndpointProvider.java',
          'pipe/PipeAttachment.java', 'pipe/PipeConnectionContext.java',
          'pipe/PipeConnectionDecision.java')
ADDON = '''package addon;
import buildcraft.api.v2.pipe.*;
import buildcraft.api.v2.item.ItemPort;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import java.util.Optional;
public class P08Addon {
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
HOST = '''package host;
import addon.P08Addon;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.pipe.*;
import buildcraft.transport.api2.*;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import java.util.*;
public class P08Host {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args) {
  Level world=new Level();PipeView pipe=()->new BlockPos(1,2,3);
  BuildCraftApi.PROVIDERS.add(ctx -> {throw new RuntimeException("broken provider");});
  BuildCraftApi.PROVIDERS.add(P08Addon.endpoint());
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
  check(PipeEndpointRuntime.attachmentPort(new ApiPipePluggable(P08Addon.attachment()),
   PipeAttachment::itemPort).isPresent(),"addon attachment port");
  PipeConnectionContext ctx=new PipeConnectionContext(world,pipe,Direction.NORTH,world.getBlockState(pipe.position()));
  check(PipeEndpointRuntime.attachmentDecision(new ApiPipePluggable(P08Addon.attachment()),ctx)
    ==PipeConnectionDecision.DENY,"attachment veto");
  world.loaded=false;int queries=world.queryCount;
  check(PipeEndpointRuntime.find(world,pipe,Direction.NORTH).isEmpty(),"unloaded refusal");
  check(world.queryCount==queries,"no forced chunk load");
  world.client=true;world.loaded=true;
  check(PipeEndpointRuntime.find(world,pipe,Direction.NORTH).isEmpty(),"client callbacks disabled");
  check(!PipeEndpointRuntime.canConnect(new PipeExternalEndpoint(){},pipe,Direction.NORTH),"inert endpoints do not connect");
 }
}'''


def write(base, name, content):
    result = base / name
    result.parent.mkdir(parents=True, exist_ok=True)
    result.write_text(content, encoding='utf-8')
    return result


class P08Tests(unittest.TestCase):
    def test_sided_wiring(self):
        native = list(ROOT.glob('source-*/**/buildcraft/transport/pipe/Pipe.java'))
        self.assertEqual(5, len(native))
        for file in native:
            with self.subTest(path=str(file)):
                code = file.read_text(encoding='utf-8')
                self.assertIn('PipeEndpointRuntime.canConnect', code)
                self.assertIn('PipeEndpointRuntime.attachmentDecision', code)
                self.assertIn('PipeEndpointRuntime.attachmentPort', code)
                self.assertIn('level.hasChunkAt(nPos)' if '/source-platforms/forge/' not in str(file)
                    else 'hasChunkAt(adjacentPos)', code)
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
        with tempfile.TemporaryDirectory(prefix='p08-api-') as td:
            temp=Path(td)
            stub=[write(temp/'stubs',n,v) for n,v in STUBS.items()]
            public=[SRC/'buildcraft/api/v2'/n for n in PUBLIC]
            backend=SRC/'buildcraft/transport/api2/PipeEndpointRuntime.java'
            pub=temp/'public';addon=temp/'addon';host=temp/'host'
            for path in (pub,addon,host):path.mkdir()
            subprocess.run(['javac','-d',str(pub),*(str(p) for p in stub+public)],check=True,capture_output=True,text=True)
            addonjava=write(temp/'src-addon','addon/P08Addon.java',ADDON)
            subprocess.run(['javac','-cp',str(pub),'-d',str(addon),str(addonjava)],check=True,capture_output=True,text=True)
            addonjar=temp/'P08Addon.jar'
            subprocess.run(['jar','cf',str(addonjar),'-C',str(addon),'.'],check=True,capture_output=True,text=True)
            hostjava=write(temp/'src-host','host/P08Host.java',HOST)
            cp=os.pathsep.join((str(pub),str(addonjar)))
            subprocess.run(['javac','-cp',cp,'-d',str(host),str(backend),str(hostjava)],check=True,capture_output=True,text=True)
            subprocess.run(['java','-cp',os.pathsep.join((cp,str(host))),'host.P08Host'],check=True,capture_output=True,text=True)


if __name__ == '__main__':
    unittest.main()
