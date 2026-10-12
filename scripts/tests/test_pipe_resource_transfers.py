#!/usr/bin/env python3
"""Compile the production pipe resource service and native transfer methods; verify conservation."""
from __future__ import annotations

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'source-shared/src/main/java'
FLOW_BASE = ROOT / 'source-platforms/neoforge/src/main/java/buildcraft/transport/pipe/flow'
FLOW_VARIANTS = tuple(sorted(ROOT.glob('source-*/**/buildcraft/transport/pipe/flow/PipeFlowFluids.java')))
assert len(FLOW_VARIANTS) == 6
SOURCES = [BASE / p for p in (
    'buildcraft/api/v2/OperationMode.java',
    'buildcraft/api/v2/fluid/FluidAmount.java',
    'buildcraft/api/v2/fluid/FluidVolume.java',
    'buildcraft/api/v2/energy/MjAmount.java',
    'buildcraft/api/v2/pipe/PipeMedium.java',
    'buildcraft/api/v2/pipe/PipeResourceStatus.java',
    'buildcraft/api/v2/pipe/PipeFluidTransferResult.java',
    'buildcraft/api/v2/pipe/PipeMjTransferResult.java',
    'buildcraft/api/v2/pipe/PipeFeTransferResult.java',
    'buildcraft/api/v2/pipe/PipeResourceService.java',
    'buildcraft/api/v2/pipe/PipeResourceTickComponent.java',
    'buildcraft/api/v2/pipe/PipeResourceTickContext.java',
    'buildcraft/transport/api2/PipeResourceServiceImpl.java',
    'buildcraft/transport/api2/PipeResourceRuntime.java',
)]


def production_method(source: Path, signature: str) -> str:
    """Extract a complete method from the actual flow (including braces) for isolated compilation."""
    data = source.read_text()
    start = data.index('    public ' + signature)
    first = data.index('{', start)
    balance = 0
    for offset in range(first, len(data)):
        if data[offset] == '{':
            balance += 1
        elif data[offset] == '}':
            balance -= 1
            if balance == 0:
                return data[start: offset + 1]
    raise ValueError(signature)


STUBS = {
    'net/minecraft/resources/ResourceLocation.java': '''package net.minecraft.resources;
public record ResourceLocation(String id) {
    public static ResourceLocation tryParse(String id) { return new ResourceLocation(id); }
}''',
    'net/minecraft/core/Direction.java': '''package net.minecraft.core;
public enum Direction { NORTH, SOUTH, WEST, EAST, UP, DOWN }''',
    'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public record BlockPos(int x,int y,int z) {}''',
    'net/minecraft/server/MinecraftServer.java': '''package net.minecraft.server;
public class MinecraftServer { public boolean sameThread=true;
    public boolean isSameThread() { return sameThread; } }''',
    'net/minecraft/world/level/block/entity/BlockEntity.java': '''package net.minecraft.world.level.block.entity;
public class BlockEntity {}''',
    'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
public class Level {
    public boolean client, loaded=true;
    public long gameTime=100;
    public int lookups;
    public final net.minecraft.server.MinecraftServer server;
    public final java.util.Map<BlockPos,BlockEntity> blocks=new java.util.HashMap<>();
    public Level(net.minecraft.server.MinecraftServer server) { this.server=server; }
    public boolean isClientSide() { return client; }
    public net.minecraft.server.MinecraftServer getServer() { return server; }
    public long getGameTime() { return gameTime; }
    public boolean hasChunkAt(BlockPos pos) { return loaded; }
    public BlockEntity getBlockEntity(BlockPos pos) { lookups++; return blocks.get(pos); }
}''',
    'buildcraft/api/v2/fluid/FluidVariant.java': '''package buildcraft.api.v2.fluid;
public record FluidVariant(String id, String components) {}''',
    'buildcraft/api/v2/fluid/FluidMatchContext.java': '''package buildcraft.api.v2.fluid;
public interface FluidMatchContext {}''',
    'buildcraft/api/v2/fluid/FluidMatcher.java': '''package buildcraft.api.v2.fluid;
@FunctionalInterface public interface FluidMatcher {
    boolean matches(FluidVariant v, FluidMatchContext ctx);
    static FluidMatcher any() { return (v,c) -> true; }
}''',
    'buildcraft/api/v2/permission/AutomationActor.java': '''package buildcraft.api.v2.permission;
public class AutomationActor { public static AutomationActor unknown() { return new AutomationActor(); } }''',
    'buildcraft/api/v2/permission/PermissionVerdict.java': '''package buildcraft.api.v2.permission;
public enum PermissionVerdict { PASS, ALLOW, DENY }''',
    'buildcraft/api/v2/permission/PermissionDecision.java': '''package buildcraft.api.v2.permission;
public record PermissionDecision(PermissionVerdict verdict) {}''',
    'buildcraft/api/v2/permission/WorldOperationKind.java': '''package buildcraft.api.v2.permission;
public enum WorldOperationKind { FLUID_DRAIN, FLUID_PLACE, OTHER }''',
    'buildcraft/api/v2/permission/WorldOperationTarget.java': '''package buildcraft.api.v2.permission;
import net.minecraft.core.BlockPos;
public record WorldOperationTarget(BlockPos pos) {
    public static WorldOperationTarget block(BlockPos pos) { return new WorldOperationTarget(pos); }
}''',
    'buildcraft/api/v2/permission/WorldOperationContext.java': '''package buildcraft.api.v2.permission;
import buildcraft.api.v2.OperationMode;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
public record WorldOperationContext(AutomationActor actor,Level level,BlockPos origin,
    WorldOperationTarget target, WorldOperationKind operation,OperationMode mode,ResourceLocation reasonId) {}''',
    'buildcraft/api/v2/permission/PermissionService.java': '''package buildcraft.api.v2.permission;
@FunctionalInterface public interface PermissionService {
    PermissionDecision decide(WorldOperationContext context);
}''',
    'buildcraft/api/v2/service/ServiceKey.java': '''package buildcraft.api.v2.service;
public class ServiceKey<T> {}''',
    'buildcraft/api/v2/BuildCraftServices.java': '''package buildcraft.api.v2;
import buildcraft.api.v2.permission.PermissionService;
import buildcraft.api.v2.service.ServiceKey;
public class BuildCraftServices { public static final ServiceKey<PermissionService> PERMISSIONS = new ServiceKey<>(); }''',
    'buildcraft/api/v2/BuildCraftApi.java': '''package buildcraft.api.v2;
import buildcraft.api.v2.permission.*;
import buildcraft.api.v2.service.*;
public class BuildCraftApi {
    public static boolean deny;
    @SuppressWarnings("unchecked") public static <T> T service(ServiceKey<T> key) {
        return (T)(PermissionService)(ctx -> new PermissionDecision(deny ? PermissionVerdict.DENY : PermissionVerdict.PASS));
    }
}''',
    'buildcraft/lib/internal/debug/BCLog.java': '''package buildcraft.lib.internal.debug;
public class BCLog {
    public static final Log logger=new Log();
    public static class Log { public void warn(String message,Object... args) {} }
}''',
    'buildcraft/api/v2/pipe/PipeView.java': '''package buildcraft.api.v2.pipe;
public interface PipeView {}''',
    'buildcraft/api/v2/pipe/PipeComponent.java': '''package buildcraft.api.v2.pipe;
import net.minecraft.resources.ResourceLocation;
public interface PipeComponent { ResourceLocation typeId(); }''',
    'buildcraft/transport/api2/ReadOnlyPipeView.java': '''package buildcraft.transport.api2;
import buildcraft.api.v2.pipe.PipeView;
import buildcraft.transport.pipe.Pipe;
public class ReadOnlyPipeView { public static PipeView of(Pipe pipe) { return pipe; } }''',
    'buildcraft/transport/pipe/Pipe.java': '''package buildcraft.transport.pipe;
import java.util.*;
import buildcraft.api.v2.pipe.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
public class Pipe implements PipeView {
    public static final Pipe EMPTY = new Pipe(null);
    public Object flow;
    public int dirty;
    private final Holder holder;
    public List<PipeComponent> listeners=new ArrayList<>();
    public Pipe(Level level) { holder=new Holder(level); }
    public Holder getHolder() { return holder; }
    public List<PipeComponent> components() { return listeners; }
    public void markChanged() { dirty++; }
    public BlockPos position() { return new BlockPos(0,0,0); }
    public record Holder(Level world) { public Level getPipeWorld() { return world; } }
}''',
    'buildcraft/transport/tile/TilePipeHolder.java': '''package buildcraft.transport.tile;
import buildcraft.transport.pipe.Pipe;
import net.minecraft.world.level.block.entity.BlockEntity;
public class TilePipeHolder extends BlockEntity {
    private final Pipe pipe;
    public TilePipeHolder(Pipe pipe) { this.pipe=pipe; }
    public Pipe getPipe() { return pipe; }
}''',
    'buildcraft/transport/pipe/flow/EnumPipePart.java': '''package buildcraft.transport.pipe.flow;
import net.minecraft.core.Direction;
public enum EnumPipePart { NORTH, SOUTH, WEST, EAST, UP, DOWN, CENTER;
    public static EnumPipePart fromFacing(Direction side) { return valueOf(side.name()); }
}''',
    'buildcraft/transport/pipe/flow/FluidAction.java': '''package buildcraft.transport.pipe.flow;
public enum FluidAction { EXECUTE, SIMULATE }''',
    'buildcraft/transport/pipe/flow/FluidStack.java': '''package buildcraft.transport.pipe.flow;
import buildcraft.api.v2.fluid.FluidVariant;
public class FluidStack {
    public static final FluidStack EMPTY=new FluidStack(null,0);
    public final FluidVariant variant;
    private final int amount;
    public FluidStack(FluidVariant variant,int amount) { this.variant=variant;this.amount=amount; }
    public boolean isEmpty() { return variant == null || amount<=0; }
    public FluidStack copy() { return new FluidStack(variant,amount); }
    public FluidStack copyWithAmount(int amount) { return new FluidStack(variant,amount); }
}''',
    'buildcraft/lib/fluid/FuelApiBridge.java': '''package buildcraft.lib.fluid;
import buildcraft.api.v2.fluid.*;
import buildcraft.transport.pipe.flow.FluidStack;
public class FuelApiBridge {
    public static final FluidMatchContext MATCH_CONTEXT = new FluidMatchContext() {};
    public static FluidVolume volumeOf(FluidStack stack) {
        return stack.isEmpty()?FluidVolume.empty():FluidVolume.of(stack.variant,1);
    }
}''',
}


def native_flow_stubs(flow_base=FLOW_BASE):
    fluid = flow_base / 'PipeFlowFluids.java'
    power = flow_base / 'PipeFlowPower.java'
    fe = flow_base / 'PipeFlowForgeEnergy.java'
    fmethods = '\n'.join(production_method(fluid, sig) for sig in (
        'buildcraft.api.v2.fluid.FluidVolume availableFluidForApi(',
        'int transferFluidForApi(',
    ))
    pmethods = '\n'.join(production_method(power, sig) for sig in (
        'long extractPowerForApi(', 'long transferPowerForApi(',
    ))
    emethods = '\n'.join(production_method(fe, sig) for sig in (
        'int extractEnergyForApi(', 'int transferEnergyForApi(',
    ))
    return {
        'buildcraft/transport/pipe/flow/PipeFlowFluids.java': '''package buildcraft.transport.pipe.flow;
import buildcraft.transport.pipe.Pipe;
import buildcraft.api.v2.fluid.*;
import buildcraft.lib.fluid.FuelApiBridge;
import net.minecraft.core.Direction;
import java.util.*;
public class PipeFlowFluids {
    public final Pipe pipe;
    private int transferPerTick=10, capacity=60;
    private static final int COOLDOWN_OUTPUT=60, COOLDOWN_INPUT=-60;
    private final Map<EnumPipePart,Section> sections=new EnumMap<>(EnumPipePart.class);
    private FluidStack currentFluid=FluidStack.EMPTY;
    private long lastApiTransferTick=Long.MIN_VALUE;
    private int apiSentThisTick;
    private long lastApiReceiveTick=Long.MIN_VALUE;
    private int apiReceivedThisTick;
    public PipeFlowFluids(Pipe pipe) { this.pipe=pipe;
        for(EnumPipePart p:EnumPipePart.values()) sections.put(p,new Section()); }
    public void seed(Direction side,FluidVariant variant,int amount) {
        currentFluid=new FluidStack(variant,amount);sections.get(EnumPipePart.fromFacing(side)).amount=amount;
    }
    public int quantity(Direction side) { return sections.get(EnumPipePart.fromFacing(side)).amount; }
    private void setFluid(FluidStack fluid) { currentFluid=fluid; }
    class Section {
        int amount,incomingTotalCache,incomingThisTick,ticksInDirection;
        int getMaxFilled() { return Math.min(capacity-amount,transferPerTick-incomingThisTick); }
        int getMaxDrained() { return Math.min(Math.max(0,amount-incomingTotalCache),transferPerTick); }
        int fill(int proposed,FluidAction action) {
            int n=Math.max(0,Math.min(proposed,getMaxFilled()));
            if(action==FluidAction.EXECUTE) {amount+=n;incomingTotalCache+=n;incomingThisTick+=n;}
            return n;
        }
        int drainInternal(int proposed,boolean execute) {
            int n=Math.max(0,Math.min(proposed,getMaxDrained()));
            if(execute) amount-=n;
            return n;
        }
    }
''' + fmethods + '\n}',
        'buildcraft/transport/pipe/flow/PipeFlowPower.java': '''package buildcraft.transport.pipe.flow;
import buildcraft.transport.pipe.Pipe;
import net.minecraft.core.Direction;
import java.util.*;
public class PipeFlowPower {
    public final Pipe pipe;
    private long maxPower=100, currentWorldTime=100;
    private boolean disabled;
    private final Map<Direction,Section> sections=new EnumMap<>(Direction.class);
    private long lastApiSendTick=Long.MIN_VALUE, apiSentThisTick;
    private long lastApiReceiveTick=Long.MIN_VALUE, apiReceivedThisTick;
    public PipeFlowPower(Pipe pipe) { this.pipe=pipe;for(Direction d:Direction.values()) sections.put(d,new Section()); }
    public void seed(Direction side,long n) { sections.get(side).internalPower=n; }
    public void seedNext(Direction side,long n) { sections.get(side).internalNextPower=n; }
    public void stale() { currentWorldTime=Long.MIN_VALUE; }
    public long quantity(Direction side) {Section s=sections.get(side);return s.internalPower+s.internalNextPower;}
    private void ensureConfigured() {}
    private static long saturatingAdd(long a,long b) {return a>Long.MAX_VALUE-b?Long.MAX_VALUE:a+b;}
    private void step() { if (currentWorldTime != pipe.getHolder().getPipeWorld().getGameTime()) {
        currentWorldTime=pipe.getHolder().getPipeWorld().getGameTime();
        for(Section s:sections.values()) {
            long old=s.internalPower;
            s.internalPower=s.internalNextPower;
            s.internalNextPower=old;
        }
    } }
    class Section { long internalPower,internalNextPower; }
    private final Journal transferJournal=new Journal();
    static class Journal { void record() {} }
''' + pmethods + '\n}',
        'buildcraft/transport/pipe/flow/PipeFlowForgeEnergy.java': '''package buildcraft.transport.pipe.flow;
import buildcraft.transport.pipe.Pipe;
import net.minecraft.core.Direction;
import java.util.*;
public class PipeFlowForgeEnergy {
    public final Pipe pipe;
    private int maxPower=100;
    private long currentWorldTime=100;
    private boolean disabled;
    private final Map<Direction,Section> sections=new EnumMap<>(Direction.class);
    private long lastApiSendTick=Long.MIN_VALUE, lastApiReceiveTick=Long.MIN_VALUE;
    private int apiSentThisTick,apiReceivedThisTick;
    public PipeFlowForgeEnergy(Pipe pipe) { this.pipe=pipe;for(Direction d:Direction.values()) sections.put(d,new Section()); }
    public void seed(Direction side,int n) { sections.get(side).internalPower=n; }
    public int quantity(Direction side) {Section s=sections.get(side);return s.internalPower+s.internalNextPower;}
    private void ensureConfigured() {}
    private static int saturatingAdd(int a,int b) {return a>Integer.MAX_VALUE-b?Integer.MAX_VALUE:a+b;}
    private void step() { if (currentWorldTime != pipe.getHolder().getPipeWorld().getGameTime()) {
        currentWorldTime=pipe.getHolder().getPipeWorld().getGameTime();
        for(Section s:sections.values()) {s.internalPower=saturatingAdd(s.internalPower,s.internalNextPower);s.internalNextPower=0;}
    } }
    class Section { int internalPower,internalNextPower; }
    private final Journal transferJournal=new Journal();
    static class Journal { void record() {} }
''' + emethods + '\n}',
    }


FIXTURE = '''package test;
import buildcraft.api.v2.*;
import buildcraft.api.v2.energy.*;
import buildcraft.api.v2.fluid.*;
import buildcraft.api.v2.pipe.*;
import buildcraft.api.v2.permission.*;
import buildcraft.transport.api2.*;
import buildcraft.transport.pipe.*;
import buildcraft.transport.pipe.flow.*;
import buildcraft.transport.tile.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.server.*;
import net.minecraft.resources.*;
public class Fixture {
    static void check(boolean t,String message) { if(!t)throw new AssertionError(message); }
    static final BlockPos SRC=new BlockPos(0,0,0), DST=new BlockPos(8,0,0);
    static final AutomationActor ACTOR=AutomationActor.unknown();
    static final Direction NORTH=Direction.NORTH;
    static final FluidVariant WATER=new FluidVariant("water","cold");
    static final FluidVariant HOT=new FluidVariant("water","hot");
    record Pair(Level a,Level b,Pipe src,Pipe dst) {}
    static Pair pair(boolean distant) {
        MinecraftServer srv=new MinecraftServer();
        Level a=new Level(srv),b=distant?new Level(srv):a;
        Pipe x=new Pipe(a),y=new Pipe(b);
        a.blocks.put(SRC,new TilePipeHolder(x));b.blocks.put(DST,new TilePipeHolder(y));
        return new Pair(a,b,x,y);
    }
    static PipeResourceService service(){return PipeResourceServiceImpl.INSTANCE;}
    static PipeFluidTransferResult fluid(Pair p,FluidMatcher matcher,OperationMode mode) {
        return service().transferFluid(p.a,SRC,NORTH,p.b,DST,NORTH,matcher,FluidAmount.of(15),ACTOR,mode);
    }
    static PipeMjTransferResult mj(Pair p,OperationMode mode) {
        return service().transferMj(p.a,SRC,NORTH,p.b,DST,NORTH,MjAmount.ofMicro(70),ACTOR,mode);
    }
    static PipeFeTransferResult fe(Pair p,OperationMode mode) {
        return service().transferExternalEnergy(p.a,SRC,NORTH,p.b,DST,NORTH,70,ACTOR,mode);
    }
    public static void main(String[] args) {
        Pair p=pair(true);
        PipeFlowFluids from=new PipeFlowFluids(p.src),to=new PipeFlowFluids(p.dst);
        p.src.flow=from;p.dst.flow=to;from.seed(NORTH,WATER,24);
        var s=fluid(p,FluidMatcher.any(),OperationMode.SIMULATE);
        check(s.status()==PipeResourceStatus.SIMULATED && s.transferred().amount().milliBuckets()==10,"fluid simulate throughput");
        check(from.quantity(NORTH)==24 && to.quantity(NORTH)==0 && p.src.dirty==0,"fluid simulation purity");
        s=fluid(p,(v,c)->v.equals(HOT),OperationMode.EXECUTE);
        check(s.status()==PipeResourceStatus.BLOCKED && from.quantity(NORTH)==24,"component mismatch filtered");
        s=fluid(p,FluidMatcher.any(),OperationMode.EXECUTE);
        check(s.status()==PipeResourceStatus.PARTIAL && s.transferred().requireVariant().equals(WATER),"fluid partial variant");
        check(from.quantity(NORTH)==14 && to.quantity(NORTH)==10 && p.src.dirty==1 && p.dst.dirty==1,"fluid conservation");
        s=fluid(p,FluidMatcher.any(),OperationMode.EXECUTE);
        check(s.status()==PipeResourceStatus.BLOCKED && from.quantity(NORTH)==14,"fluid per-tick budget");
        p.a.gameTime++;p.b.gameTime++;
        s=fluid(p,FluidMatcher.any(),OperationMode.EXECUTE);
        check(s.status()==PipeResourceStatus.BLOCKED,"fluid target backpressure delayed");
        p.b.loaded=false;
        int lookups=p.b.lookups;
        s=fluid(p,FluidMatcher.any(),OperationMode.EXECUTE);
        check(s.status()==PipeResourceStatus.UNLOADED && p.b.lookups==lookups,"no forced load");
        p.b.loaded=true;
        BuildCraftApi.deny=true;
        check(fluid(p,FluidMatcher.any(),OperationMode.EXECUTE).status()==PipeResourceStatus.DENIED,"permission denial");
        BuildCraftApi.deny=false;
        check(fluid(p,(v,c)->{throw new IllegalStateException("addon");},OperationMode.EXECUTE).status()==PipeResourceStatus.FAILED,"bad matcher isolation");
        p.a.client=true;
        check(fluid(p,FluidMatcher.any(),OperationMode.EXECUTE).status()==PipeResourceStatus.SERVER_ONLY,"client rejected");

        Pair early=pair(true);
        PipeFlowPower preFrom=new PipeFlowPower(early.src),preTo=new PipeFlowPower(early.dst);
        early.src.flow=preFrom;early.dst.flow=preTo;
        preFrom.seed(NORTH,30);preFrom.seedNext(NORTH,50);
        preFrom.stale();preTo.stale();
        var presim=mj(early,OperationMode.SIMULATE);
        check(presim.status()==PipeResourceStatus.SIMULATED && presim.transferred().microMj()==50,
            "MJ simulated tick rollover must use next-power buffer");
        check(preFrom.quantity(NORTH)==80 && preTo.quantity(NORTH)==0,"MJ rollover simulation pure");
        var preactual=mj(early,OperationMode.EXECUTE);
        check(preactual.transferred().microMj()==50 && preFrom.quantity(NORTH)==30
            && preTo.quantity(NORTH)==50,"MJ rollover executes the same reserved amount");
        Pair later=pair(true);
        PipeFlowPower onlyOld=new PipeFlowPower(later.src),emptyTarget=new PipeFlowPower(later.dst);
        later.src.flow=onlyOld;later.dst.flow=emptyTarget;
        onlyOld.seed(NORTH,40);onlyOld.stale();emptyTarget.stale();
        check(mj(later,OperationMode.EXECUTE).status()==PipeResourceStatus.BLOCKED
            && onlyOld.quantity(NORTH)==40,"MJ cannot drain old power before it matures");
        onlyOld.seedNext(NORTH,11);
        check(onlyOld.extractPowerForApi(NORTH,20,true)==11 && onlyOld.quantity(NORTH)==51,
            "MJ rollover extract simulate does not mutate");
        check(onlyOld.extractPowerForApi(NORTH,20,false)==11 && onlyOld.quantity(NORTH)==40,
            "MJ rollover extract executes after rotating buffers");

        Pair q=pair(true);
        PipeFlowPower mjFrom=new PipeFlowPower(q.src),mjTo=new PipeFlowPower(q.dst);
        q.src.flow=mjFrom;q.dst.flow=mjTo;mjFrom.seed(NORTH,80);mjTo.seed(NORTH,40);
        var m=mj(q,OperationMode.SIMULATE);
        check(m.status()==PipeResourceStatus.SIMULATED && m.transferred().microMj()==60,"MJ sim capacity");
        check(mjFrom.quantity(NORTH)==80 && mjTo.quantity(NORTH)==40,"MJ sim pure");
        m=mj(q,OperationMode.EXECUTE);
        check(m.status()==PipeResourceStatus.PARTIAL && m.transferred().microMj()==60,"MJ partial");
        check(mjFrom.quantity(NORTH)==20 && mjTo.quantity(NORTH)==100,"MJ conservation");
        check(mj(q,OperationMode.EXECUTE).status()==PipeResourceStatus.BLOCKED,"MJ capacity blocked");
        check(mjFrom.extractPowerForApi(NORTH,11,true)==11,"MJ extract simulate");
        check(mjFrom.quantity(NORTH)==20,"MJ extraction sim pure");
        check(mjFrom.extractPowerForApi(NORTH,11,false)==11 && mjFrom.quantity(NORTH)==9,"MJ extract execution");

        Pair z=pair(false);
        PipeFlowForgeEnergy feFrom=new PipeFlowForgeEnergy(z.src),feTo=new PipeFlowForgeEnergy(z.dst);
        z.src.flow=feFrom;z.dst.flow=feTo;feFrom.seed(NORTH,74);feTo.seed(NORTH,80);
        var e=fe(z,OperationMode.SIMULATE);
        check(e.status()==PipeResourceStatus.SIMULATED && e.transferred()==20,"FE sim cap");
        check(feFrom.quantity(NORTH)==74 && feTo.quantity(NORTH)==80,"FE sim pure");
        e=fe(z,OperationMode.EXECUTE);
        check(e.status()==PipeResourceStatus.PARTIAL && e.transferred()==20,"FE partial");
        check(feFrom.quantity(NORTH)==54 && feTo.quantity(NORTH)==100,"FE conservation");
        check(feFrom.extractEnergyForApi(NORTH,10,true)==10 && feFrom.quantity(NORTH)==54,"FE extraction sim");
        check(feFrom.extractEnergyForApi(NORTH,10,false)==10 && feFrom.quantity(NORTH)==44,"FE extraction execution");
        check(fe(z,OperationMode.EXECUTE).status()==PipeResourceStatus.BLOCKED,"FE backpressure");

        Pair h=pair(true);h.src.flow=new PipeFlowFluids(h.src);
        class Listener implements PipeResourceTickComponent {
            PipeResourceTickContext saved; int fired; boolean throwsAfter;
            public ResourceLocation typeId(){return ResourceLocation.tryParse("addon:resource");}
            public void onResourceTick(PipeResourceTickContext ctx) {
                saved=ctx;fired++;
                check(ctx.medium()==PipeMedium.FLUID && ctx.transfers()!=null && ctx.pipe()!=null,"resource callback");
                if(throwsAfter)throw new IllegalStateException("callback");
            }
        }
        Listener one=new Listener(),two=new Listener();one.throwsAfter=true;
        h.src.listeners.add(one);h.src.listeners.add(two);
        PipeResourceRuntime.tick(h.src);
        check(one.fired==1 && two.fired==1,"failure isolation");
        boolean expired=false;
        try{one.saved.world();}catch(IllegalStateException expected){expired=true;}
        check(expired,"expired context");
        h.a.client=true;
        PipeResourceRuntime.tick(h.src);
        check(one.fired==1,"client callbacks disabled");
        System.out.println("Resource transfer and native flow assertions passed");
    }
}'''


class ResourceTransfersTest(unittest.TestCase):
    def test_compiled_service_and_native_buffer_methods(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('JDK required')
        for src in SOURCES:
            self.assertTrue(src.exists(), src)
        for variant in FLOW_VARIANTS:
            with self.subTest(variant=variant.relative_to(ROOT)):
                self.compile_and_run(variant.parent)

    def compile_and_run(self, flow_base):
        with tempfile.TemporaryDirectory(prefix='bcce-resources-') as temp:
            folder=Path(temp)
            sources=[str(p) for p in SOURCES]
            stubs=dict(STUBS)
            stubs.update(native_flow_stubs(flow_base))
            stubs['test/Fixture.java']=FIXTURE
            for name,text in stubs.items():
                target=folder/name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(text)
                sources.append(str(target))
            classes=folder/'classes'
            classes.mkdir()
            result=subprocess.run(['javac','-encoding','UTF-8','-d',str(classes),*sources],
                capture_output=True,text=True,timeout=55)
            self.assertEqual(result.returncode,0,result.stderr)
            run=subprocess.run(['java','-cp',str(classes),'test.Fixture'],
                capture_output=True,text=True,timeout=20)
            self.assertEqual(run.returncode,0,run.stderr)
            self.assertIn('assertions passed',run.stdout)


if __name__=='__main__':
    unittest.main()
