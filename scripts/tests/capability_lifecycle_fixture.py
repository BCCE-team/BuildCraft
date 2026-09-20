"""Compiles the real Forge CapabilityHelper against minimal API doubles and probes handle lifecycle."""
from __future__ import annotations

from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def run(work: Path) -> str:
    if not shutil.which('javac') or not shutil.which('java'):
        raise RuntimeError('Java javac/java are required')
    src = work / 'src'
    classes = work / 'classes'
    if work.exists():
        shutil.rmtree(work)
    src.mkdir(parents=True)
    classes.mkdir(parents=True)

    actual = ROOT / 'source-platforms/forge/src/main/java/buildcraft/lib/cap/CapabilityHelper.java'
    dest = src / 'buildcraft/lib/cap/CapabilityHelper.java'
    dest.parent.mkdir(parents=True)
    shutil.copy2(actual, dest)

    stubs = {
        'javax/annotation/Nonnull.java': 'package javax.annotation; public @interface Nonnull {}',
        'javax/annotation/Nullable.java': 'package javax.annotation; public @interface Nullable {}',
        'org/jetbrains/annotations/NotNull.java': 'package org.jetbrains.annotations; public @interface NotNull {}',
        'net/minecraft/core/Direction.java': 'package net.minecraft.core; public enum Direction {DOWN,UP,NORTH,SOUTH,WEST,EAST}',
        'net/minecraftforge/common/capabilities/Capability.java': 'package net.minecraftforge.common.capabilities; public class Capability<T> {}',
        'net/minecraftforge/common/capabilities/ICapabilityProvider.java': '''package net.minecraftforge.common.capabilities;
import net.minecraft.core.Direction; import net.minecraftforge.common.util.LazyOptional;
public interface ICapabilityProvider { <T> LazyOptional<T> getCapability(Capability<T> c, Direction d); }''',
        'net/minecraftforge/common/util/NonNullSupplier.java': 'package net.minecraftforge.common.util; @FunctionalInterface public interface NonNullSupplier<T>{T get();}',
        'net/minecraftforge/common/util/LazyOptional.java': '''package net.minecraftforge.common.util;
public class LazyOptional<T>{private T value;private boolean valid=true;private LazyOptional(T v){value=v;}
public static <T> LazyOptional<T> of(NonNullSupplier<? extends T>s){return new LazyOptional<>(s.get());}
public static <T> LazyOptional<T> empty(){return new LazyOptional<>(null);}public boolean isPresent(){return valid&&value!=null;}
public void invalidate(){valid=false;value=null;}@SuppressWarnings("unchecked")public <R> LazyOptional<R> cast(){return (LazyOptional<R>)(Object)this;}}''',
        'buildcraft/lib/internal/core/EnumPipePart.java': '''package buildcraft.lib.internal.core; import net.minecraft.core.Direction;
public enum EnumPipePart{CENTER(null),DOWN(Direction.DOWN),UP(Direction.UP),NORTH(Direction.NORTH),SOUTH(Direction.SOUTH),WEST(Direction.WEST),EAST(Direction.EAST);
public static final EnumPipePart[] VALUES=values();public final Direction face;EnumPipePart(Direction f){face=f;}
public static EnumPipePart fromFacing(Direction f){return f==null?CENTER:valueOf(f.name());}}''',
        'buildcraft/lib/platform/storage/ItemStorage.java': 'package buildcraft.lib.platform.storage; public interface ItemStorage {}',
        'buildcraft/lib/platform/storage/FluidStorage.java': 'package buildcraft.lib.platform.storage; public interface FluidStorage<T> {}',
        'buildcraft/lib/platform/storage/EnergyStorage.java': 'package buildcraft.lib.platform.storage; public interface EnergyStorage {}',
        'buildcraft/lib/platform/storage/StorageMap.java': '''package buildcraft.lib.platform.storage;
import java.util.function.Function;import buildcraft.lib.internal.core.EnumPipePart;import net.minecraft.core.Direction;
public class StorageMap{private EnergyStorage energy;public void addItems(ItemStorage s,EnumPipePart...p){}public void addItems(Function<Direction,? extends ItemStorage>s,EnumPipePart...p){}
public void addFluids(FluidStorage<?>s,EnumPipePart...p){}public void addFluids(Function<Direction,? extends FluidStorage<?>>s,EnumPipePart...p){}
public void addEnergy(EnergyStorage s,EnumPipePart...p){energy=s;}public void addEnergy(Function<Direction,? extends EnergyStorage>s,EnumPipePart...p){energy=s.apply(Direction.NORTH);}
public ItemStorage items(Direction d){return null;}public FluidStorage<?> fluids(Direction d){return null;}public EnergyStorage energy(Direction d){return d==Direction.NORTH?energy:null;}}''',
        'buildcraft/lib/platform/storage/StorageAdapters.java': '''package buildcraft.lib.platform.storage;
public final class StorageAdapters{public static Object toNativeItems(ItemStorage s){return s;}public static Object toNativeEnergy(EnergyStorage s){return s;}
public static net.minecraftforge.fluids.capability.IFluidHandler toNativeFluids(FluidStorage<net.minecraftforge.fluids.FluidStack>s){return null;}}''',
        'buildcraft/lib/misc/CapUtil.java': '''package buildcraft.lib.misc;import net.minecraftforge.common.capabilities.Capability;
public final class CapUtil{public static final Capability<Object> CAP_ITEMS=new Capability<>(),CAP_FLUIDS=new Capability<>(),CAP_FE=new Capability<>();}''',
        'net/minecraftforge/fluids/FluidStack.java': 'package net.minecraftforge.fluids; public class FluidStack {}',
        'net/minecraftforge/fluids/capability/IFluidHandler.java': 'package net.minecraftforge.fluids.capability; public interface IFluidHandler {}',
        'probe/Probe.java': '''package probe;
import buildcraft.lib.cap.CapabilityHelper;import buildcraft.lib.internal.core.EnumPipePart;import buildcraft.lib.misc.CapUtil;import buildcraft.lib.platform.storage.EnergyStorage;import net.minecraft.core.Direction;
public class Probe{static int checks;static void ok(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}public static void main(String[]a){
CapabilityHelper h=new CapabilityHelper();EnergyStorage e=new EnergyStorage(){};h.addEnergyStorage(e,EnumPipePart.NORTH);
var first=h.getCapability(CapUtil.CAP_FE,Direction.NORTH);var second=h.getCapability(CapUtil.CAP_FE,Direction.NORTH);ok(first==second,"stable handle");ok(first.isPresent(),"present before invalidation");
h.invalidate();ok(!first.isPresent(),"old handle invalidated");ok(!h.getCapability(CapUtil.CAP_FE,Direction.NORTH).isPresent(),"no capability while invalid");
h.revive();var revived=h.getCapability(CapUtil.CAP_FE,Direction.NORTH);ok(revived!=first&&revived.isPresent(),"fresh handle after revive");System.out.println("CapabilityHelper lifecycle: "+checks+" assertions");}}''',
    }
    for rel, text in stubs.items():
        path = src / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    proc = subprocess.run(['javac','--release','17','-encoding','UTF-8','-d',str(classes),*map(str,sorted(src.rglob('*.java')))],capture_output=True,text=True,timeout=60)
    if proc.returncode:
        raise AssertionError(proc.stdout + proc.stderr)
    proc = subprocess.run(['java','-ea','-cp',str(classes),'probe.Probe'],capture_output=True,text=True,timeout=30)
    if proc.returncode:
        raise AssertionError(proc.stdout + proc.stderr)
    return proc.stdout.strip()
