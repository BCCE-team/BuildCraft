#!/usr/bin/env python3
"""Compile temperature-tier fuel registration and API v2 profiles for every BCCE target.

The extracted Java method is compiled verbatim with small registries/fluid doubles;
this detects a failed source-family downport, wrong fuel multipliers, missing tiers,
loss of dirty-fuel residue, and incompatible API v2 profile serialization.
"""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_lookup import read_materialized_java

TARGETS = (
    '1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge', '1.21.11-neoforge',
    '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge',
)
API = ROOT / 'source-shared/src/main/java'
API_CLASSES = [
    'persistence/ApiCodec', 'persistence/CodecResult',
    'fluid/FluidAmount', 'fluid/FluidComponentPayload', 'fluid/FluidVariantData',
    'fluid/FluidVariant', 'fluid/FluidMatcher', 'fluid/FluidMatchContext', 'fluid/FluidVolume',
    'fuels/FluidSelectorData', 'fuels/FluidSelector', 'fuels/EnergyFluidDefinition',
    'fuels/FuelProfileData', 'fuels/FuelProfile',
]

RESOURCE_LOCATION = r'''package net.minecraft.resources;
import java.util.Objects;
public record ResourceLocation(String namespace, String path) {
    public static ResourceLocation tryParse(String input) {
        if(input == null || !input.contains(":")) return null;
        String[] halves = input.split(":", 2);
        return new ResourceLocation(halves[0], halves[1]);
    }
    public String getNamespace(){ return namespace; }
    public String getPath(){ return path; }
    public String toString(){return namespace+":"+path;}
}'''

API_HARNESS = r'''import buildcraft.api.v2.fuels.*;
import buildcraft.api.v2.fluid.*;
import net.minecraft.resources.ResourceLocation;
public final class ApiFuelHarness {
    static void check(boolean value, String reason) {if(!value)throw new AssertionError(reason);}
    static ResourceLocation id(String name){return ResourceLocation.tryParse("test:"+name);}
    public static void main(String[] args) {
        FluidSelector selector = FluidSelector.fluid(id("crude"));
        FuelProfile hot = FuelProfile.clean(selector, 5_000_000, 10_000, 6_000_000);
        FuelProfile hotRoundtrip = FuelProfile.CODEC.decode(FuelProfile.CODEC.encode(hot).valueOrThrow()).valueOrThrow();
        check(hotRoundtrip.powerPerTickMicroMj()==5_000_000,"hot power codec");
        check(hotRoundtrip.heatPerTickMicroMj()==6_000_000,"hot heat codec");
        check(hotRoundtrip.burnTicksPerBucket()==10_000,"hot burn duration codec");
        FluidVolume residue = FluidVolume.of(FluidVariant.of(id("residue")), 250);
        FuelProfile dirty = FuelProfile.dirty(selector, 6_000_000, 10_000, 8_000_000, residue);
        FuelProfile dirtied = FuelProfile.CODEC.decode(FuelProfile.CODEC.encode(dirty).valueOrThrow()).valueOrThrow();
        check(dirtied.heatPerTickMicroMj()==8_000_000,"dirty heat codec");
        check(dirtied.residuePerBucket().equals(residue),"dirty residue codec");
        FuelProfile oldAddon = FuelProfile.clean(selector, 2_000_000, 10_000);
        check(oldAddon.heatPerTickMicroMj()==2_000_000,"old clean signature");
        FuelProfile oldDirty = FuelProfile.dirty(selector, 2_000_000, 10_000, residue);
        check(oldDirty.heatPerTickMicroMj()==2_000_000,"old dirty signature");
        FuelProfileData oldData = new FuelProfileData(FluidSelector.CODEC.encode(selector).valueOrThrow(),
            2_000_000, 10_000, null, 0);
        FuelProfile legacy = FuelProfile.CODEC.decode(oldData).valueOrThrow();
        check(legacy.heatPerTickMicroMj()==legacy.powerPerTickMicroMj(),"legacy serialized heat");
        check(!FuelProfile.CODEC.decode(new FuelProfileData(
            oldData.selector(), 2_000_000, 10_000, null, 0, -1L)).successful(),"negative heat accepted");
        System.out.println("API fuel profile and legacy codec PASS");
    }
}'''

REGISTRY_HARNESS = r'''import java.util.*;
import net.minecraft.resources.ResourceLocation;
import buildcraft.api.v2.fuels.*;
import buildcraft.api.v2.fluid.*;

public class FuelRegistrationHarness {
    static class Fluid {
        final String name;
        Fluid(String name){ this.name=name; }
    }
    static class FluidStack {
        final Fluid fluid;
        FluidStack(Fluid fluid, int amount){this.fluid=fluid;}
    }
    static class FuelApiBridge {
        static FluidVariant variantOf(FluidStack f){return FluidVariant.of(ResourceLocation.tryParse("test:"+f.fluid.name));}
    }
    static class BCEnergyFluids {
        static final Fluid[] oilResidue={new Fluid("residue"),new Fluid("residue_heat_1"),new Fluid("residue_heat_2")};
    }
    static class MjAmount {static final long MICRO_MJ_PER_MJ=1_000_000L;}
    static class Service {
        final Map<ResourceLocation,FuelProfile> registry=new LinkedHashMap<>();
        void register(ResourceLocation id, FuelProfile fuel, Object provenance) {
            if(registry.putIfAbsent(id, fuel)!=null) throw new AssertionError("duplicate fuel id " + id);
        }
    }
    static class Recipes {
        static final int TIME_BASE = 240_000;
        static final int[] POWER_QUARTERS_BY_HEAT = { 4, 5, 6 };
        static final int[] HEAT_QUARTERS_BY_HEAT = { 4, 6, 8 };
        static final Object BUILTIN=new Object();
        static final Service service=new Service();
        static Service energyFluids(){return service;}
        static ResourceLocation id(String path){return ResourceLocation.tryParse("buildcraftenergy:"+path);}
        static FluidVariant variantOf(Fluid fluid) {return FluidVariant.of(ResourceLocation.tryParse("test:"+fluid.name));}
        private static Fluid getFirstOrNull(Fluid[] fluids) {
            return fluids == null || fluids.length == 0 ? null : fluids[0];
        }
SOURCE_METHOD
    }
    static void check(boolean yes, String why){if(!yes)throw new AssertionError(why);}
    static void test(boolean dirty) {
        Recipes.service.registry.clear();
        for(int family=0;family<9;family++) {
            Fluid[] fluids={new Fluid("fuel"+family), new Fluid("fuel"+family+"_heat_1"),new Fluid("fuel"+family+"_heat_2")};
            Recipes.registerFuel(fluids, 2, 8, 4, dirty);
        }
        check(Recipes.service.registry.size()==27,"not all 27 three-temperature fuels registered");
        for(int family=0;family<9;family++)for(int heat=0;heat<3;heat++) {
            String name="fuel"+family+(heat==0?"":"_heat_"+heat);
            ResourceLocation key=ResourceLocation.tryParse("buildcraftenergy:fuel/test/"+name);
            FuelProfile profile=Recipes.service.registry.get(key);
            check(profile!=null,"missing "+name);
            long[] power={8_000_000L,10_000_000L,12_000_000L};
            long[] warming={8_000_000L,12_000_000L,16_000_000L};
            check(profile.powerPerTickMicroMj()==power[heat],name+" wrong MJ power");
            check(profile.heatPerTickMicroMj()==warming[heat],name+" wrong heat");
            check(profile.burnTicksPerBucket()==15_000,name+" wrong burn duration");
            check(profile.hasResidue()==dirty,name+" dirty mode mismatch");
            if(dirty) check(profile.residuePerBucket().amount().milliBuckets()==500,name+" residue mismatch");
        }
    }
    public static void main(String[] args) {
        test(false);test(true);
        // Disabled/missing heat tier must be safely skipped.
        Recipes.service.registry.clear();
        Recipes.registerFuel(new Fluid[]{new Fluid("only_cool"),null,null},2,8,4,false);
        check(Recipes.service.registry.size()==1,"null heat-tier handling");
        System.out.println("27 clean + 27 dirty fuel profiles PASS");
    }
}'''


def method_body(java: str, signature: str) -> str:
    start = java.index(signature)
    brace = java.index('{', start)
    depth = 1
    cursor = brace + 1
    while depth:
        if java[cursor] == '{': depth += 1
        if java[cursor] == '}': depth -= 1
        cursor += 1
    return java[start:cursor]


class FuelTemperatureTierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which('javac') or not shutil.which('java'):
            raise unittest.SkipTest('Requires Java 21 toolchain')
        cls.tmp = tempfile.TemporaryDirectory(prefix='bcce-fuel-heat-')
        cls.dir = Path(cls.tmp.name)
        classes = cls.dir / 'classes'
        classes.mkdir()
        stub = cls.dir / 'net/minecraft/resources/ResourceLocation.java'
        stub.parent.mkdir(parents=True)
        stub.write_text(RESOURCE_LOCATION, encoding='utf-8')
        cls.api_files = [API / ('buildcraft/api/v2/' + suffix + '.java') for suffix in API_CLASSES]
        cls.compile([str(stub), *map(str, cls.api_files)])

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    @classmethod
    def compile(cls, source_files):
        proc = subprocess.run(['javac', '-cp', str(cls.dir/'classes'), '-d', str(cls.dir/'classes'),
                               *source_files], capture_output=True, text=True, timeout=30)
        if proc.returncode:
            raise AssertionError(proc.stdout + proc.stderr)

    @classmethod
    def run_java(cls, main_class):
        proc = subprocess.run(['java', '-cp', str(cls.dir/'classes'), main_class],
                              capture_output=True, text=True, timeout=30)
        if proc.returncode:
            raise AssertionError(proc.stdout + proc.stderr)
        return proc.stdout.strip()

    def test_api_v2_heat_codec_and_legacy_addons(self):
        java = self.dir / 'ApiFuelHarness.java'
        java.write_text(API_HARNESS, encoding='utf-8')
        self.compile([str(java)])
        self.assertIn('PASS', self.run_java('ApiFuelHarness'))

    def test_all_seven_targets_register_cold_hot_searing(self):
        for target in TARGETS:
            with self.subTest(target=target):
                source = read_materialized_java(target, 'src/main/java/buildcraft/energy/BCEnergyRecipes.java')
                power = re.search(r'POWER_QUARTERS_BY_HEAT\s*=\s*\{([^}]+)\}', source)
                heat = re.search(r'HEAT_QUARTERS_BY_HEAT\s*=\s*\{([^}]+)\}', source)
                self.assertIsNotNone(power)
                self.assertIsNotNone(heat)
                self.assertEqual([int(value) for value in power.group(1).split(',')], [4, 5, 6])
                self.assertEqual([int(value) for value in heat.group(1).split(',')], [4, 6, 8])
                self.assertEqual(source.count('addFuel(BCEnergyFluids.'), 6)
                self.assertEqual(source.count('addDirtyFuel(BCEnergyFluids.'), 3)
                method = method_body(source, '    private static void registerFuel(')
                self.assertIn('heat < input.length', method)
                self.assertIn('FuelProfile.dirty(selector, powerPerTick, totalTime, heatPerTick, residuePerBucket)', method)
                harness = self.dir / 'FuelRegistrationHarness.java'
                harness.write_text(REGISTRY_HARNESS.replace('SOURCE_METHOD', method), encoding='utf-8')
                self.compile([str(harness)])
                self.assertIn('PASS', self.run_java('FuelRegistrationHarness'))

    def test_all_seven_engines_store_separate_heat_debt(self):
        for target in TARGETS:
            with self.subTest(target=target):
                text = read_materialized_java(target, 'src/main/java/buildcraft/energy/tile/TileEngineIron_BC8.java')
                self.assertIn('activeHeatPerTick = nextFuel.heatPerTickMicroMj();', text)
                self.assertIn('generatedHeat += used * activeHeatPerTick;', text)
                self.assertEqual(text.count('generatedHeat += used * activeHeatPerTick;'), 2)
                self.assertIn('heat += generatedHeat * HEAT_PER_MJ', text)
                self.assertIn('"activeHeatPerTick"', text)
                self.assertIn('if (activeHeatPerTick == 0) activeHeatPerTick = activePowerPerTick;', text)


if __name__ == '__main__':
    unittest.main()
