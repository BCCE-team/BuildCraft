#!/usr/bin/env python3
"""Compile the production standalone pipe bridge, both registries and two API-only addon pipes."""
from __future__ import annotations

import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'source-shared/src/main/java'
COMMON = [
    'buildcraft/api/v2/OperationMode.java',
    'buildcraft/api/v2/persistence/ApiCodec.java',
    'buildcraft/api/v2/persistence/CodecResult.java',
    'buildcraft/api/v2/persistence/OpaqueData.java',
    'buildcraft/api/v2/persistence/PersistentType.java',
    'buildcraft/api/v2/persistence/SchemaMigration.java',
    'buildcraft/api/v2/registry/RegistrationContext.java',
    'buildcraft/api/v2/pipe/PipeType.java',
    'buildcraft/api/v2/pipe/PipeMedium.java',
    'buildcraft/api/v2/pipe/ItemTransportProfile.java',
    'buildcraft/api/v2/pipe/FluidTransportProfile.java',
    'buildcraft/api/v2/pipe/PowerTransportProfile.java',
    'buildcraft/api/v2/pipe/ExternalEnergyTransportProfile.java',
    'buildcraft/api/v2/pipe/PipeComponent.java',
    'buildcraft/api/v2/pipe/PipeComponentFactory.java',
    'buildcraft/api/v2/pipe/PipeComponentType.java',
    'buildcraft/api/v2/pipe/PipeComponentSync.java',
    'buildcraft/api/v2/pipe/PipeSyncChannel.java',
    'buildcraft/api/v2/pipe/ItemAdmissionContext.java',
    'buildcraft/api/v2/pipe/ItemAdmissionComponent.java',
    'buildcraft/api/v2/pipe/ItemRouteContext.java',
    'buildcraft/api/v2/pipe/ItemRouteComponent.java',
    'buildcraft/api/v2/pipe/RouteDecision.java',
    'buildcraft/api/v2/pipe/PipeConnectionContext.java',
    'buildcraft/api/v2/pipe/PipeConnectionComponent.java',
    'buildcraft/api/v2/pipe/PipeConnectionDecision.java',
    'buildcraft/api/v2/pipe/ItemTransitComponent.java',
    'buildcraft/api/v2/pipe/ItemTransitPhase.java',
    'buildcraft/api/v2/pipe/PipeComponentLifecycle.java',
    'buildcraft/transport/api2/PipeTypeBridge.java',
    'buildcraft/transport/api2/RuntimePipeComponent.java',
]
ADDON = ROOT / 'addon-fixture/src/main/java/dev/bcce/apifixture/CustomPipeFixtureAddon.java'
REGISTRIES = (
    ROOT / 'source-families/old/src/main/java/buildcraft/transport/pipe/PipeRegistry.java',
    ROOT / 'source-families/1.21.X/src/main/java/buildcraft/transport/pipe/PipeRegistry.java',
)


def method_from_source(text: str, signature: str) -> str:
    start = text.index('    public boolean ' + signature)
    opened = text.index('{', start)
    nesting = 0
    for i in range(opened, len(text)):
        if text[i] == '{': nesting += 1
        if text[i] == '}':
            nesting -= 1
            if nesting == 0: return text[start:i+1]
    raise AssertionError('Unterminated Java method: ' + signature)


STUBS = {
    'net/minecraft/resources/ResourceLocation.java': '''package net.minecraft.resources;
public record ResourceLocation(String namespace, String path) {
    public ResourceLocation(String text) { this(text.split(":")[0], text.split(":")[1]); }
    public static ResourceLocation parse(String text) { return new ResourceLocation(text); }
    public static ResourceLocation tryParse(String text) { return text==null||!text.contains(":")?null:parse(text); }
    public String getNamespace() { return namespace; }
    public String getPath() { return path; }
    public String toString() { return namespace+":"+path; }
}''',
    'net/minecraft/core/Direction.java': '''package net.minecraft.core;
public enum Direction { DOWN,UP,NORTH,SOUTH,WEST,EAST }''',
    'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public record BlockPos(int x,int y,int z) { }''',
    'net/minecraft/world/item/Item.java': '''package net.minecraft.world.item;
public class Item { }''',
    'net/minecraft/world/item/Items.java': '''package net.minecraft.world.item;
public class Items { public static final Item IRON_INGOT = new Item(), GOLD_INGOT = new Item(); }''',
    'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
public class ItemStack {
    public final Item item; private int count;
    public ItemStack(Item item,int count) { this.item=item;this.count=count; }
    public boolean is(Item other) { return item==other; }
    public boolean isEmpty() { return count<=0; }
    public int getCount() { return count; }
    public void setCount(int n) { count=n; }
    public ItemStack copy() { return new ItemStack(item,count); }
}''',
    'net/minecraft/world/level/Level.java': 'package net.minecraft.world.level; public class Level {}',
    'net/minecraft/world/level/block/state/BlockState.java': 'package net.minecraft.world.level.block.state; public class BlockState {}',
    'net/minecraft/world/level/block/Block.java': 'package net.minecraft.world.level.block; public class Block {}',
    'javax/annotation/Nonnull.java': 'package javax.annotation; public @interface Nonnull {}',
    'javax/annotation/Nullable.java': 'package javax.annotation; public @interface Nullable {}',
    'com/google/common/collect/ImmutableList.java': '''package com.google.common.collect;
public class ImmutableList { public static <T> java.util.List<T> copyOf(java.util.Collection<T> c) { return java.util.List.copyOf(c); } }''',
    'buildcraft/api/v2/energy/MjAmount.java': '''package buildcraft.api.v2.energy;
public record MjAmount(long microMj) implements Comparable<MjAmount> {
    public static final long MICRO_MJ_PER_MJ=1_000_000L;
    public static MjAmount ofMicro(long n) { return new MjAmount(n); }
    public boolean isZero() { return microMj==0; }
    public int compareTo(MjAmount o) { return Long.compare(microMj,o.microMj); }
}''',
    'buildcraft/api/v2/fluid/FluidAmount.java': '''package buildcraft.api.v2.fluid;
public record FluidAmount(long milliBuckets) { public static FluidAmount of(long n) { return new FluidAmount(n); } }''',
    'buildcraft/api/v2/pipe/PipeView.java': '''package buildcraft.api.v2.pipe;
import java.util.Set;
import net.minecraft.core.Direction;
public interface PipeView { Set<Direction> connectedSides(); }''',
    'buildcraft/api/v2/pipe/PipeMutationContext.java': '''package buildcraft.api.v2.pipe;
public interface PipeMutationContext extends PipeView { void markChanged(); }''',
    'buildcraft/api/v2/pipe/PipeConnectionChange.java': '''package buildcraft.api.v2.pipe;
public record PipeConnectionChange() {}''',
    'buildcraft/api/v2/pipe/ItemTransitContext.java': '''package buildcraft.api.v2.pipe;
import java.util.Optional;
import net.minecraft.core.Direction;
public interface ItemTransitContext {
    PipeView pipe(); ItemTransitPhase phase(); Optional<Direction> input(); boolean redirect(Direction dir);
}''',
    'buildcraft/api/v2/registry/ApiRegistry.java': '''package buildcraft.api.v2.registry;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
public class ApiRegistry<T> {
    private final Map<ResourceLocation,T> items=new LinkedHashMap<>();
    public T get(ResourceLocation id) { return items.get(id); }
    public void register(ResourceLocation id,T value,RegistrationContext ctx) {
        if(items.putIfAbsent(id,value)!=null)throw new IllegalStateException("Duplicate id: "+id);
    }
    public Collection<T> values() { return items.values(); }
    public void registerAlias(ResourceLocation alias,ResourceLocation target,RegistrationContext ctx) {
        T value=items.get(target);if(value==null)throw new IllegalStateException();items.put(alias,value);
    }
}''',
    'buildcraft/api/v2/registry/RegistryKey.java': '''package buildcraft.api.v2.registry;
public record RegistryKey<T>(String id) {}''',
    'buildcraft/api/v2/BuildCraftRegistries.java': '''package buildcraft.api.v2;
import buildcraft.api.v2.registry.RegistryKey;
import buildcraft.api.v2.pipe.*;
public class BuildCraftRegistries {
    public static final RegistryKey<PipeType> PIPE_TYPES=new RegistryKey<>("pipes");
    public static final RegistryKey<PipeFlowDefinition<?>> PIPE_FLOW_TYPES=new RegistryKey<>("flows");
    public static final RegistryKey<PipeComponentType<?>> PIPE_COMPONENT_TYPES=new RegistryKey<>("components");
}''',
    'buildcraft/api/v2/BuildCraftServices.java': '''package buildcraft.api.v2;
import buildcraft.api.v2.pipe.PipeService;
public class BuildCraftServices { public static final Object PIPES=new Object(); }''',
    'buildcraft/api/v2/BuildCraftApi.java': '''package buildcraft.api.v2;
import java.util.*;
import buildcraft.api.v2.registry.*;
import buildcraft.api.v2.pipe.PipeService;
import buildcraft.transport.pipe.PipeRegistry;
public class BuildCraftApi {
    private static final Map<RegistryKey<?>,ApiRegistry<?>> reg=new HashMap<>();
    @SuppressWarnings("unchecked") public static <T> ApiRegistry<T> registry(RegistryKey<T> key) {
        return (ApiRegistry<T>) reg.computeIfAbsent(key, k->new ApiRegistry<>());
    }
    @SuppressWarnings("unchecked") public static PipeService service(Object key) {
        return id->{ var def=PipeRegistry.INSTANCE.ensureRuntimeDefinition(registry(BuildCraftRegistries.PIPE_TYPES).get(id));
            var existing=PipeRegistry.INSTANCE.getItemForPipe(def);
            if(existing instanceof net.minecraft.world.item.Item item)return item;
            return PipeRegistry.INSTANCE.createItemForPipe(def); };
    }
}''',
    'buildcraft/api/v2/pipe/PipeFlowDefinition.java': '''package buildcraft.api.v2.pipe;
import net.minecraft.resources.ResourceLocation;
public record PipeFlowDefinition<T extends PipeComponent>(ResourceLocation id, PipeComponentType<T> componentType) {}''',
    'buildcraft/api/v2/pipe/PipeService.java': '''package buildcraft.api.v2.pipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
public interface PipeService { Item createItem(ResourceLocation id); }''',
    'buildcraft/transport/internal/pipe/PipeDefinition.java': '''package buildcraft.transport.internal.pipe;
import net.minecraft.resources.ResourceLocation;
import buildcraft.api.v2.pipe.PipeType;
public class PipeDefinition {
    public final ResourceLocation identifier;
    public final PipeFlowType flowType;
    public final boolean canBeColoured;
    private PipeType type;
    private ResourceLocation componentId;
    public PipeDefinition(ResourceLocation id,PipeFlowType flow,boolean color) {
        identifier=id;flowType=flow;canBeColoured=color;
    }
    public static PipeDefinition apiVariant(PipeType type,PipeDefinition base) {
        PipeDefinition result=new PipeDefinition(type.id(),base.flowType,type.colorable());
        result.type=type;result.componentId=base.componentId;return result;
    }
    public PipeType getApiType(){return type;}
    public void setApiType(PipeType t){if(type!=null && type!=t)throw new IllegalStateException();type=t;}
    public void setRuntimeComponentId(ResourceLocation id){componentId=id;}
}''',
    'buildcraft/transport/internal/pipe/PipeFlowType.java': '''package buildcraft.transport.internal.pipe;
public class PipeFlowType {}''',
    'buildcraft/transport/internal/pipe/PipeApi.java': '''package buildcraft.transport.internal.pipe;
import buildcraft.api.v2.energy.MjAmount;
public class PipeApi {
    public static final PipeFlowType flowStructure=new PipeFlowType(),flowItems=new PipeFlowType(),
        flowFluids=new PipeFlowType(),flowPower=new PipeFlowType(),flowForgeEnergy=new PipeFlowType();
    public static class FluidTransferInfo { public final int transferPerTick;public final double transferDelayMultiplier;
        public FluidTransferInfo(int t,int d){transferPerTick=t;transferDelayMultiplier=d;} }
    public static class PowerTransferInfo { public final long transferPerTick,lossPerTick,resistancePerTick;
        public final boolean isReceiver;
        public PowerTransferInfo(long t,long l,long r,boolean recv){transferPerTick=t;lossPerTick=l;resistancePerTick=r;isReceiver=recv;} }
    public static class ForgeEnergyTransferInfo { public final int transferPerTick;public final boolean isReceiver;
        public ForgeEnergyTransferInfo(int t,boolean recv){transferPerTick=t;isReceiver=recv;} }
    public static FluidTransferInfo getFluidTransferInfo(PipeDefinition def){return new FluidTransferInfo(20,10);}
    public static PowerTransferInfo getPowerTransferInfo(PipeDefinition def){return new PowerTransferInfo(4*MjAmount.MICRO_MJ_PER_MJ,0,0,false);}
    public static ForgeEnergyTransferInfo getForgeEnergyTransferInfo(PipeDefinition def){return new ForgeEnergyTransferInfo(40,false);}
}''',
    'buildcraft/lib/platform/registry/BCRegistryEntry.java': '''package buildcraft.lib.platform.registry;
public interface BCRegistryEntry<T> {}''',
    'buildcraft/lib/internal/core/InvalidInputDataException.java': '''package buildcraft.lib.internal.core;
public class InvalidInputDataException extends Exception {
    public InvalidInputDataException(String message){super(message);}
}''',
    'buildcraft/transport/internal/pipe/IItemPipe.java': '''package buildcraft.transport.internal.pipe;
public interface IItemPipe { PipeDefinition getDefinition(); }''',
    'buildcraft/transport/item/ItemPipeHolder.java': '''package buildcraft.transport.item;
import buildcraft.transport.internal.pipe.*;
import net.minecraft.world.item.Item;
public class ItemPipeHolder extends Item implements IItemPipe {
    public final PipeDefinition definition;
    public ItemPipeHolder(PipeDefinition def){definition=def;}
    public PipeDefinition getDefinition(){return definition;}
}''',
    'buildcraft/lib/internal/debug/BCLog.java': '''package buildcraft.lib.internal.debug;
public class BCLog {
    public static final Logger logger=new Logger();
    public static class Logger { public void warn(String fmt,Object...args){} }
}''',
    'buildcraft/transport/api2/ReadOnlyPipeView.java': '''package buildcraft.transport.api2;
import buildcraft.api.v2.pipe.*;
import buildcraft.transport.pipe.Pipe;
public class ReadOnlyPipeView { public static PipeView of(Pipe pipe){return pipe;} }''',
}


MAIN = r'''package test;
import java.util.*;
import buildcraft.api.v2.*;
import buildcraft.api.v2.pipe.*;
import buildcraft.api.v2.persistence.*;
import buildcraft.transport.api2.*;
import buildcraft.transport.internal.pipe.*;
import buildcraft.transport.pipe.*;
import dev.bcce.apifixture.CustomPipeFixtureAddon;
import net.minecraft.resources.*;
import net.minecraft.core.*;
import net.minecraft.world.item.*;

public class Main {
    static ResourceLocation id(String value) { return ResourceLocation.parse(value); }
    static void check(boolean condition,String why) { if(!condition)throw new AssertionError(why); }
    static void fails(Runnable run,String why) {
        try { run.run(); throw new AssertionError("Expected rejection: "+why); }
        catch(IllegalStateException|IllegalArgumentException expected) { }
    }
    static void builtin(String name,PipeFlowType flow) {
        PipeRegistry.INSTANCE.registerPipe(new PipeDefinition(id("buildcrafttransport:"+name),flow,true));
    }
    static PipeDefinition runtime(PipeType type) {
        BuildCraftApi.registry(BuildCraftRegistries.PIPE_TYPES).register(type.id(),type,()->"addon");
        return PipeRegistry.INSTANCE.ensureRuntimeDefinition(type);
    }
    static class FakePipe implements PipeMutationContext {
        int changes;
        public Set<Direction> connectedSides() {return EnumSet.of(Direction.WEST,Direction.NORTH,Direction.SOUTH,Direction.EAST);}
        public void markChanged(){changes++;}
    }
    static class FakeTransit implements ItemTransitContext {
        PipeView pipe; Direction input=Direction.WEST,redirected; boolean accepted=true;
        FakeTransit(PipeView pipe){this.pipe=pipe;}
        public PipeView pipe(){return pipe;}
        public ItemTransitPhase phase(){return ItemTransitPhase.CENTER;}
        public Optional<Direction> input(){return Optional.ofNullable(input);}
        public boolean redirect(Direction side){if(!accepted)return false; redirected=side;return true;}
    }

    public static void main(String[]args)throws Exception {
        builtin("stone_item",PipeApi.flowItems);
        builtin("stone_fluid",PipeApi.flowFluids);
        builtin("stone_power",PipeApi.flowPower);
        builtin("stone_fe",PipeApi.flowForgeEnergy);
        builtin("structure",PipeApi.flowStructure);
        List<Item> items=CustomPipeFixtureAddon.register();
        check(items.size()==2 && items.get(0)!=items.get(1),"two independent addon items");
        PipeDefinition filter=PipeRegistry.INSTANCE.loadDefinition("api_fixture:iron_filter");
        PipeDefinition distribute=PipeRegistry.INSTANCE.loadDefinition("api_fixture:round_robin");
        check(filter!=distribute && filter.flowType==PipeApi.flowItems,"item carrier");
        check(distribute.flowType==PipeApi.flowItems,"distributor carrier");
        check(filter.getApiType().defaultComponents().size()==1,"filter component composition");
        check(distribute.getApiType().defaultComponents().size()==1,"distributor component composition");
        check(PipeRegistry.INSTANCE.ensureRuntimeDefinition(filter.getApiType())==filter,"stable definition");
        check(BuildCraftApi.service(BuildCraftServices.PIPES).createItem(filter.identifier)==items.get(0),"stable item");

        CustomPipeFixtureAddon.IronFilter rule=new CustomPipeFixtureAddon.IronFilter();
        Pipe runtimePipe=new Pipe();
        runtimePipe.apiComponents=List.of(rule);
        check(runtimePipe.acceptItemInjection(new ItemStack(Items.IRON_INGOT,3),Direction.WEST,OperationMode.SIMULATE),
            "production admission accepted iron");
        check(!runtimePipe.acceptItemInjection(new ItemStack(Items.GOLD_INGOT,3),Direction.WEST,OperationMode.EXECUTE),
            "production admission rejected gold");
        runtimePipe.apiComponents=List.of(new ItemAdmissionComponent() {
            public ResourceLocation typeId(){return id("test:broken");}
            public boolean accepts(ItemAdmissionContext ctx){throw new IllegalStateException("failing addon");}
        });
        check(!runtimePipe.acceptItemInjection(new ItemStack(Items.IRON_INGOT,3),Direction.WEST,OperationMode.EXECUTE),
            "broken admission callback fails closed");
        FakePipe pipe=new FakePipe();
        check(rule.accepts(new ItemAdmissionContext(pipe,Direction.WEST,new ItemStack(Items.IRON_INGOT,3),OperationMode.SIMULATE)),"iron allowed");
        check(!rule.accepts(new ItemAdmissionContext(pipe,Direction.WEST,new ItemStack(Items.GOLD_INGOT,3),OperationMode.EXECUTE)),"gold rejected");
        ItemStack stack=new ItemStack(Items.IRON_INGOT,2);
        ItemAdmissionContext ctx=new ItemAdmissionContext(pipe,Direction.WEST,stack,OperationMode.SIMULATE);
        stack.setCount(100);
        check(ctx.stack().getCount()==2,"admission copied input");
        ctx.stack().setCount(111);
        check(ctx.stack().getCount()==2,"admission copied output");
        var route=rule.route(new ItemRouteContext(pipe,Direction.WEST,new ItemStack(Items.IRON_INGOT,1),
            EnumSet.of(Direction.NORTH,Direction.EAST,Direction.SOUTH)));
        check(route.weights().get(Direction.EAST)==1 && route.weights().get(Direction.NORTH)==0,"directional filtering");
        check(rule.connection(new PipeConnectionContext(new net.minecraft.world.level.Level(),pipe,
            Direction.NORTH,new net.minecraft.world.level.block.state.BlockState()))==PipeConnectionDecision.DENY,
            "physical connection denied");

        CustomPipeFixtureAddon.RoundRobin robin=new CustomPipeFixtureAddon.RoundRobin();
        robin.attached(pipe);
        FakeTransit event=new FakeTransit(pipe);
        robin.onTransit(event);
        check(event.redirected==Direction.NORTH && pipe.changes==1,"first item routes north");
        event.redirected=null;
        robin.onTransit(event);
        check(event.redirected==Direction.SOUTH && pipe.changes==2,"second item routes south");
        var type=BuildCraftApi.registry(BuildCraftRegistries.PIPE_COMPONENT_TYPES).get(id("api_fixture:round_robin_logic"));
        @SuppressWarnings("unchecked") var pers=(PersistentType<CustomPipeFixtureAddon.RoundRobin,OpaqueData>) type.persistence().orElseThrow();
        var stored=pers.codec().encode(robin).valueOrThrow();
        var loaded=pers.codec().decode(stored).valueOrThrow();
        loaded.attached(pipe);
        event.redirected=null;
        loaded.onTransit(event);
        check(event.redirected==Direction.EAST && pipe.changes==3,"counter restored and routes east");
        check(!pers.codec().decode(new OpaqueData(id("api_fixture:round_robin_counter"),new byte[1])).successful(),
            "malformed saved state rejected");
        event.accepted=false;event.redirected=null;
        loaded.onTransit(event);
        check(pipe.changes==3,"failed redirect keeps state");

        PipeType raw=PipeType.builder(id("addon:plain_item")).medium(PipeMedium.ITEM).build();
        check(raw.itemProfile().orElseThrow().maxItemsPerCycle()==64,"default standalone item profile");
        check(runtime(raw).flowType==PipeApi.flowItems,"raw type works");
        PipeType fluid=PipeType.builder(id("addon:fluid")).fluidProfile(
            new FluidTransportProfile(buildcraft.api.v2.fluid.FluidAmount.of(200),3)).build();
        check(runtime(fluid).flowType==PipeApi.flowFluids,"fluid carrier");
        PipeType power=PipeType.builder(id("addon:power")).mjProfile(
            PowerTransportProfile.fromResistance(buildcraft.api.v2.energy.MjAmount.ofMicro(4_000_000),10000,false)).build();
        check(runtime(power).flowType==PipeApi.flowPower,"MJ carrier");
        PipeType fe=PipeType.builder(id("addon:fe")).externalEnergyProfile(new ExternalEnergyTransportProfile(40,false)).build();
        check(runtime(fe).flowType==PipeApi.flowForgeEnergy,"FE carrier");
        PipeType structure=PipeType.builder(id("addon:structure")).medium(PipeMedium.STRUCTURE).build();
        check(runtime(structure).flowType==PipeApi.flowStructure,"structure carrier");

        PipeType wrong=PipeType.builder(id("addon:wrong")).itemProfile(new ItemTransportProfile(4,1))
            .archetype(id("buildcrafttransport:stone_fluid")).build();
        fails(()->runtime(wrong),"incompatible carrier");
        fails(()->PipeType.builder(id("addon:multi")).medium(PipeMedium.ITEM)
            .medium(PipeMedium.FLUID).build(),"hybrid flow must be explicitly registered");
        PipeType a=PipeType.builder(id("addon:cycle_a")).medium(PipeMedium.ITEM)
            .archetype(id("addon:cycle_b")).build();
        PipeType b=PipeType.builder(id("addon:cycle_b")).medium(PipeMedium.ITEM)
            .archetype(id("addon:cycle_a")).build();
        BuildCraftApi.registry(BuildCraftRegistries.PIPE_TYPES).register(a.id(),a,()->"addon");
        BuildCraftApi.registry(BuildCraftRegistries.PIPE_TYPES).register(b.id(),b,()->"addon");
        fails(()->PipeRegistry.INSTANCE.ensureRuntimeDefinition(a),"cyclic carriers");
        PipeType lazily=PipeType.builder(id("addon:lazy")).medium(PipeMedium.ITEM).build();
        BuildCraftApi.registry(BuildCraftRegistries.PIPE_TYPES).register(lazily.id(),lazily,()->"addon");
        check(PipeRegistry.INSTANCE.loadDefinition("addon:lazy").getApiType()==lazily,"load lazily materializes");
        PipeType variant=PipeType.variant(id("addon:variant"),filter.getApiType()).build();
        check(runtime(variant).flowType==PipeApi.flowItems,"legacy variant retained");
        System.out.println("PASS: two addon pipes, admission, routing, persistence, carriers, cycles, lazy load");
    }
}
'''


class CustomPipeTypesTest(unittest.TestCase):
    def test_external_components_and_carriers(self):
        self.assertTrue(shutil.which('javac') and shutil.which('java'), 'JDK required')
        # Each runtime family has a different pipe registry bootstrap signature.
        for registry in REGISTRIES:
            with self.subTest(registry=registry.parent.parent.parent.parent.parent.name):
                with tempfile.TemporaryDirectory(prefix='bc-custom-pipes-') as folder:
                    temp=Path(folder)
                    files=[]
                    for relative,source in STUBS.items():
                        path=temp/relative
                        path.parent.mkdir(parents=True,exist_ok=True)
                        path.write_text(source)
                        files.append(path)
                    interface=temp/'buildcraft/transport/internal/pipe/IPipeRegistry.java'
                    entry='buildcraft.lib.platform.registry.BCRegistryEntry<net.minecraft.world.level.block.Block>' \
                        if '/old/' in registry.as_posix() else 'java.util.function.Supplier<? extends net.minecraft.world.level.block.Block>'
                    interface.write_text(f'''package buildcraft.transport.internal.pipe;
import net.minecraft.resources.ResourceLocation;
public interface IPipeRegistry {{
    void registerPipe(PipeDefinition d);
    IItemPipe registryItemForPipe({entry} b,PipeDefinition d);
    buildcraft.transport.item.ItemPipeHolder createItemForPipe(PipeDefinition d);
    void setItemForPipe(PipeDefinition d,IItemPipe item);
    IItemPipe getItemForPipe(PipeDefinition d);
    PipeDefinition getDefinition(ResourceLocation id);
    Iterable<PipeDefinition> getAllRegisteredPipes();
}}''')
                    files.append(interface)
                    for name in COMMON:
                        src=JAVA/name
                        self.assertTrue(src.exists(),str(src))
                        path=temp/name
                        path.parent.mkdir(parents=True,exist_ok=True)
                        path.write_bytes(src.read_bytes())
                        files.append(path)
                    for src in (ADDON,registry):
                        path=temp/src.relative_to(ROOT).as_posix().replace('source-families/old/src/main/java/','').replace('source-families/1.21.X/src/main/java/','').replace('addon-fixture/src/main/java/','')
                        path.parent.mkdir(parents=True,exist_ok=True)
                        path.write_bytes(src.read_bytes())
                        files.append(path)
                    # Compile the actual Pipe admission method, independent of a Minecraft/NeoForge runtime.
                    prod=(ROOT/'source-platforms/neoforge/src/main/java/buildcraft/transport/pipe/Pipe.java').read_text()
                    method=method_from_source(prod,'acceptItemInjection(')
                    path=temp/'buildcraft/transport/pipe/Pipe.java'
                    # The standalone registry source itself also uses this package.
                    if path not in files:
                        path.parent.mkdir(parents=True,exist_ok=True)
                        path.write_text('''package buildcraft.transport.pipe;
import java.util.*;
import buildcraft.api.v2.*;
import buildcraft.api.v2.pipe.*;
import buildcraft.lib.internal.debug.BCLog;
import net.minecraft.core.*;
import net.minecraft.world.item.*;
public class Pipe implements PipeMutationContext {
    public List<PipeComponent> apiComponents=List.of();
    public Set<Direction> connectedSides(){return Set.of();}
    public void markChanged(){}
    public BlockPos position(){return new BlockPos(0,0,0);}
''' + method + '\n}')
                        files.append(path)
                    main=temp/'test/Main.java'
                    main.parent.mkdir(parents=True,exist_ok=True)
                    main.write_text(MAIN)
                    files.append(main)
                    out=temp/'classes'
                    out.mkdir()
                    cmd=['javac','-encoding','UTF-8','-d',str(out),*[str(p) for p in files]]
                    result=subprocess.run(cmd,capture_output=True,text=True,timeout=40)
                    self.assertEqual(result.returncode,0,result.stderr[-5000:])
                    result=subprocess.run(['java','-cp',str(out),'test.Main'],capture_output=True,text=True,timeout=25)
                    self.assertEqual(result.returncode,0,result.stdout+'\n'+result.stderr)
                    self.assertIn('PASS: two addon pipes',result.stdout)

    def test_all_targets_admission_and_bridge(self):
        pipes=sorted(ROOT.glob('source-*/**/buildcraft/transport/pipe/Pipe.java'))
        flows=sorted(ROOT.glob('source-*/**/buildcraft/transport/pipe/flow/PipeFlowItems.java'))
        self.assertEqual(len(pipes),5)
        self.assertEqual(len(flows),6)
        for src in pipes:
            s=src.read_text()
            self.assertIn('acceptItemInjection(',s,str(src))
            self.assertIn('ItemAdmissionContext',s,str(src))
            self.assertIn('PipeConnectionDecision.DENY',s,str(src))
        for src in flows:
            s=src.read_text()
            self.assertEqual(s.count('runtimePipe.acceptItemInjection('),3,str(src))
            self.assertIn('doAdd ? OperationMode.EXECUTE : OperationMode.SIMULATE',s,str(src))
        for src in REGISTRIES:
            s=src.read_text()
            self.assertIn('PipeTypeBridge.carrierId(type)',s)
            self.assertIn('PipeTypeBridge.validateCarrier(type, base)',s)


if __name__ == '__main__':
    unittest.main()
