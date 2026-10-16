#!/usr/bin/env python3
"""Compile and exercise the real combustion-engine burn() body with isolated Java doubles.

This is a deterministic mechanics regression, not a Minecraft runtime/Gradle test.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
ACTUAL = ROOT / 'source-family-platforms/26.X/neoforge/src/main/java/buildcraft/energy/tile/TileEngineIron_BC8.java'
text = ACTUAL.read_text()
start = text.index('    protected void burn() {')
end = text.index('    public void updateHeatLevel()', start)
body = text[start:end]
assert 'burnTime = ticksPerMb - used;' in body

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
    }
    static final class Tank {
        FluidStack fuel = FluidStack.EMPTY;
        FluidStack getFluid() { return fuel; }
        int getFluidAmount() { return fuel.getAmount(); }
        void setFluid(FluidStack next) { fuel=next; }
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
    public static void main(String[] args) {
        service.types.put("slow",new FuelProfile(1_000_000,90_000));
        service.types.put("fast",new FuelProfile(1_000_000,100));
        service.types.put("high",new FuelProfile(8_000_000,1000));
        service.types.put("half",new FuelProfile(8_000_000,500));
        testStandard();testFast();testSwitch();testPartial();testReloadCredit();
        System.out.println("5 combustion fuel invariants PASS");
    }
}
'''
# The extracted text starts at the method declaration. Keep it verbatim in the harness.
java = HARNESS.replace('    protected void burn() {\nBURN_BODY\n    }', body.rstrip())
with tempfile.TemporaryDirectory(prefix='bcce-p1-engine-') as temporary:
    path = Path(temporary)
    (path/'BurnHarness.java').write_text(java)
    result = subprocess.run(['javac','-d',str(path),str(path/'BurnHarness.java')],text=True,capture_output=True,timeout=25)
    if result.returncode: raise AssertionError('Engine burn() did not compile in fixture:\n'+result.stderr)
    executed = subprocess.run(['java','-cp',str(path),'BurnHarness'],text=True,capture_output=True,timeout=25)
    if executed.returncode: raise AssertionError('Engine fuel accounting failed:\n'+executed.stdout+executed.stderr)
    print(executed.stdout.strip())
