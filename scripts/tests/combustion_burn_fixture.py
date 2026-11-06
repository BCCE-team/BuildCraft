#!/usr/bin/env python3
"""Execute combustion-engine burn accounting against isolated Java doubles.

The real burn() method is copied without edits into a Java test harness. The
Tank double deliberately returns defensive FluidStack copies, matching the
production P2 storage contract. This is not a Minecraft runtime test.
"""
from __future__ import annotations

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_lookup import read_materialized_java

HARNESS = r'''
import java.util.*;
public class BurnHarness {
    static final class MjAmount { static final long MICRO_MJ_PER_MJ = 1_000_000L; }
    static final class FluidStack {
        static final FluidStack EMPTY = new FluidStack("empty", 0);
        final String name;
        int count;
        FluidStack(String name, int count) { this.name=name; this.count=count; }
        boolean isEmpty() { return count <= 0; }
        int getAmount() { return count; }
        void setAmount(int amount) { count=amount; }
        void shrink(int amount) { count = Math.max(0, count-amount); }
        FluidStack copy() { return new FluidStack(name, count); }
    }
    enum FluidAction { EXECUTE }
    static final class Tank {
        FluidStack fuel = FluidStack.EMPTY;
        // The real Tank#getFluid returns a defensive view after the P2 fix.
        FluidStack getFluid() { return fuel.copy(); }
        int getFluidAmount() { return fuel.getAmount(); }
        void setFluid(FluidStack next) { fuel=next.copy(); }
        FluidStack drainInternal(int amount, FluidAction action) {
            int drained = Math.min(Math.max(amount, 0), getFluidAmount());
            if (drained == 0) return FluidStack.EMPTY;
            FluidStack extracted = new FluidStack(fuel.name, drained);
            fuel.shrink(drained);
            return extracted;
        }
    }
    static final class EnergyFluidResourceHandler {
        static FluidStack drainInternal(Tank tank, int amount, BurnHarness engine) {
            return tank.drainInternal(amount, FluidAction.EXECUTE);
        }
    }
    record Amount(double milliBuckets) {}
    record Residue(Amount amount) {}
    record FuelProfile(long powerPerTickMicroMj, int burnTicksPerBucket) {
        boolean hasResidue() { return false; }
        Residue residuePerBucket() { return new Residue(new Amount(0)); }
    }
    record Match(FuelProfile profile) {}
    static final class FuelService {
        Map<String,FuelProfile> types=new HashMap<>();
        Optional<Match> findFuel(String type, Object ctx) {
            FuelProfile profile=types.get(type);
            return profile == null ? Optional.empty() : Optional.of(new Match(profile));
        }
    }
    static final class FuelApiBridge {
        static final Object MATCH_CONTEXT=new Object();
        static String variantOf(FluidStack stack) { return stack.name; }
    }
    static final FuelService service = new FuelService();
    static final double HEAT_PER_MJ = 1;
    static FuelService energyFluids() { return service; }
    private final Tank tankFuel = new Tank();
    boolean isRedstonePowered = true, lastPowered, residueBlocked;
    int penaltyCooling;
    double burnTime, residueAmount, heat;
    long activePowerPerTick, currentOutput, totalEnergy;
    FuelProfile currentFuel;
    void addPower(long amount) { totalEnergy += amount; }
    void markChunkDirty() {}
    double getBiomeTempScalar() { return 1; }
    boolean canConsumeFuelWithResidue() { return true; }
    boolean flushPendingResidue() { return true; }
    protected void burn() {
BURN_BODY
    }
    static void eq(long actual,long expected,String detail) {
        if (actual!=expected) throw new AssertionError(detail+": got="+actual+" wanted="+expected);
    }
    static void testStandard() {
        BurnHarness a=new BurnHarness(); a.tankFuel.setFluid(new FluidStack("slow",1));
        a.burn();eq(a.totalEnergy,1_000_000,"first normal tick");eq(a.tankFuel.getFluidAmount(),0,"paid fuel");
        for(int i=0;i<89;i++) a.burn();
        eq(a.totalEnergy,90_000_000,"all prepaid 90 ticks");a.burn();
        eq(a.currentOutput,0,"no unpaid output");eq(a.totalEnergy,90_000_000,"no free energy after credit");
    }
    static void testFast() {
        BurnHarness a=new BurnHarness();a.tankFuel.setFluid(new FluidStack("fast",10));
        a.burn();eq(a.tankFuel.getFluidAmount(),0,"ten mB per tick");eq(a.totalEnergy,1_000_000,"short profile nominal output");
        a.burn();eq(a.totalEnergy,1_000_000,"no extra MJ for short burn");
    }
    static void testSwitch() {
        BurnHarness a=new BurnHarness();a.tankFuel.setFluid(new FluidStack("slow",1));a.burn();
        a.tankFuel.setFluid(new FluidStack("high",1));
        for(int i=0;i<89;i++)a.burn();
        eq(a.totalEnergy,90_000_000,"old credit retains its fuel power");
        eq(a.tankFuel.getFluidAmount(),1,"new fuel not consumed while old paid time remains");
        a.burn();eq(a.totalEnergy,98_000_000,"new fuel power only after old credit expires");
    }
    static void testPartial() {
        BurnHarness a=new BurnHarness();a.tankFuel.setFluid(new FluidStack("half",1));
        a.burn();eq(a.totalEnergy,4_000_000,"final partial tick power");
        a.burn();eq(a.totalEnergy,4_000_000,"no duplicate partial output");
    }
    static void testReloadCredit() {
        BurnHarness first=new BurnHarness();first.tankFuel.setFluid(new FluidStack("slow",1));first.burn();
        BurnHarness loaded=new BurnHarness();loaded.burnTime=first.burnTime;
        loaded.activePowerPerTick=first.activePowerPerTick;
        for(int i=0;i<89;i++)loaded.burn();
        eq(loaded.totalEnergy,89_000_000,"prepaid credit survives empty tank reload");
    }
    static void testGaseousFuelDepletionAndRefill() {
        BurnHarness a=new BurnHarness();
        for(int cycle=1;cycle<=2;cycle++) {
            a.tankFuel.setFluid(new FluidStack("gaseous",1));
            a.burn();
            if (!a.tankFuel.getFluid().isEmpty()) throw new AssertionError("last gaseous mB was not consumed");
            eq(a.tankFuel.getFluidAmount(),0,"gaseous fuel tank empty");
            for(int tick=0;tick<3;tick++) a.burn();
            eq(a.currentOutput,0,"engine stops after gaseous fuel credit expires");
            eq(a.totalEnergy,cycle*15_000_000L,"gaseous fuel energy conserved across refill");
        }
    }
    public static void main(String[] args) {
        service.types.put("slow",new FuelProfile(1_000_000,90_000));
        service.types.put("fast",new FuelProfile(1_000_000,100));
        service.types.put("high",new FuelProfile(8_000_000,1000));
        service.types.put("half",new FuelProfile(8_000_000,500));
        // Built-in cool Gaseous Fuel: 8 MJ/t and 240000 / 8 / 16 ticks/bucket.
        service.types.put("gaseous",new FuelProfile(8_000_000,1875));
        testStandard();testFast();testSwitch();testPartial();testReloadCredit();
        testGaseousFuelDepletionAndRefill();
        System.out.println("6 combustion fuel invariants PASS");
    }
}
'''

def execute(target: str) -> str:
    if not shutil.which('javac') or not shutil.which('java'):
        raise RuntimeError('Java toolchain required for burn accounting fixture')
    text = read_materialized_java(target, 'src/main/java/buildcraft/energy/tile/TileEngineIron_BC8.java')
    start = text.index('    protected void burn() {')
    end = text.index('    public void updateHeatLevel()', start)
    body = text[start:text.rindex('}', start, end) + 1]
    if 'burnTime = ticksPerMb - used;' not in body:
        raise AssertionError('No remainder of prepaid fractional fuel was recorded')
    # Insert the exact production Java body, preserving its braces.
    java = HARNESS.replace('    protected void burn() {\nBURN_BODY\n    }', body.rstrip())
    with tempfile.TemporaryDirectory(prefix='bcce-engine-burn-') as temporary:
        path = Path(temporary)
        (path / 'BurnHarness.java').write_text(java, encoding='utf-8')
        compiled = subprocess.run(
            ['javac', '-d', str(path), str(path / 'BurnHarness.java')],
            capture_output=True, text=True, timeout=30, check=False,
        )
        if compiled.returncode:
            raise AssertionError('Production burn() failed in fixture:\n' + compiled.stderr)
        executed = subprocess.run(
            ['java', '-cp', str(path), 'BurnHarness'],
            capture_output=True, text=True, timeout=30, check=False,
        )
        if executed.returncode:
            raise AssertionError('Engine fuel accounting regression:\n' + executed.stdout + executed.stderr)
        return executed.stdout.strip()
