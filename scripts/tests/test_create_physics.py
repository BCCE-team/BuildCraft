"""Verify the optional ship-physics profiles and bounded state bridge."""
from __future__ import annotations
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_ids, target_layout
from source_layout import resolve_effective_source

RESOURCE = 'src/main/resources/data/buildcraftcompat/physics_block_properties/'
SHARED = ROOT / 'source-shared' / RESOURCE
JAVA = 'src/main/java/buildcraft/compat/physics/'
DOWNPORT = ROOT / 'source-downports/1.21.X/1.21.1/neoforge'

class CreatePhysicsTests(unittest.TestCase):
    def test_resource_coverage(self):
        props = load_properties()
        profiles = sorted(SHARED.rglob('*.json'))
        self.assertEqual(35, len(profiles))
        for target in target_ids(props):
            layout = target_layout(target, props)
            ids = set()
            for profile in profiles:
                with self.subTest(target=target, profile=profile.name):
                    rel = profile.relative_to(SHARED).as_posix()
                    source = resolve_effective_source(layout, props, RESOURCE + rel)
                    self.assertIsNotNone(source)
                    data = json.loads(source.read_text())
                    ns, block = data['selector'].split(':')
                    self.assertNotIn(data['selector'], ids)
                    ids.add(data['selector'])
                    self.assertIsNotNone(resolve_effective_source(layout, props,
                        f'src/main/resources/assets/{ns}/blockstates/{block}.json'))
                    p = data['properties']
                    self.assertGreater(p['sable:mass'], 0)
                    self.assertTrue(0 < p['sable:volume'] <= 1)
                    self.assertGreaterEqual(p['sable:friction'], 0)
                    self.assertTrue(0 <= p['sable:restitution'] <= 1)
                    if 'sable:inertia' in p:
                        self.assertEqual(3, len(p['sable:inertia']))
                        self.assertTrue(all(n > 0 for n in p['sable:inertia']))
                    if block in ('pipe_holder', 'tank'):
                        dynamic = target == '1.21.1-neoforge'
                        self.assertEqual(dynamic, 'overrides' in data)
                        if dynamic:
                            base, step = (0.1, 0.05) if block == 'pipe_holder' else (0.5, 0.1)
                            self.assertEqual(21, len(data['overrides']))
                            for i in range(21):
                                self.assertAlmostEqual(base + step*i,
                                    data['overrides'][f'bc_physics_mass={i}']['sable:mass'])
            legacy = resolve_effective_source(layout, props,
                'src/main/resources/data/buildcraftcompat/vs_mass/buildcraft.json')
            if target in ('1.19.2-forge', '1.20.1-forge'):
                self.assertIsNotNone(legacy)
                records = json.loads(legacy.read_text())
                self.assertEqual(35, len(records))
                self.assertEqual(ids, {r['block'] for r in records})
                self.assertTrue(all(r['mass'] > 0 and r['friction'] >= 0 for r in records))
            else:
                self.assertIsNone(legacy)

    def test_runtime_ownership(self):
        props = load_properties()
        for target in target_ids(props):
            layout = target_layout(target, props)
            helper = resolve_effective_source(layout, props, JAVA + 'SableMassState.java')
            self.assertEqual(target == '1.21.1-neoforge', helper is not None)
            if helper is None:
                continue
            for owner in ('transport/block/BlockPipeHolder', 'factory/block/BlockTank',
                          'transport/tile/TilePipeHolder', 'factory/tile/TileTank'):
                source = resolve_effective_source(layout, props, f'src/main/java/buildcraft/{owner}.java')
                self.assertIn('SableMassState.MASS', source.read_text())
            flow = resolve_effective_source(layout, props,
                'src/main/java/buildcraft/transport/pipe/flow/PipeFlowFluids.java')
            self.assertIn('public double getPhysicsContentsMass(int connections)', flow.read_text())

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK unavailable')
    def test_balance_java(self):
        with tempfile.TemporaryDirectory() as directory:
            d = Path(directory)
            src = d / 'Probe.java'
            src.write_text('''import buildcraft.compat.physics.PhysicalMassBalance;
public class Probe {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) {
        double wood=PhysicalMassBalance.dryPipeMass("buildcrafttransport","wood_item");
        double gold=PhysicalMassBalance.dryPipeMass("buildcrafttransport","gold_fluid");
        check(wood<gold); check(gold==0.30);
        check(PhysicalMassBalance.dryPipeMass("addon","gold_item")==0.20);
        check(PhysicalMassBalance.pipeMass(wood,6,0,0,0,0)>PhysicalMassBalance.pipeMass(wood,2,0,0,0,0));
        check(PhysicalMassBalance.pipeMass(gold,2,1,1,0,0)>PhysicalMassBalance.pipeMass(gold,2,0,0,0,0));
        check(PhysicalMassBalance.pipeMass(gold,2,0,0,8,0)>PhysicalMassBalance.pipeMass(gold,2,0,0,0,0));
        check(PhysicalMassBalance.pipeStep(0.20,-1)==2);
        check(PhysicalMassBalance.pipeStep(100,-1)==20);
        check(PhysicalMassBalance.pipeStep(0,-1)==0);
        check(PhysicalMassBalance.pipeStep(0.228,2)==2);
        check(PhysicalMassBalance.pipeStep(0.238,2)==3);
        check(PhysicalMassBalance.tankStep(0,16000,1000,20)==0);
        check(PhysicalMassBalance.tankStep(16000,16000,1000,-1)>0);
        check(PhysicalMassBalance.tankStep(16000,16000,2000,-1)>PhysicalMassBalance.tankStep(16000,16000,1000,-1));
        check(PhysicalMassBalance.contentsMass(16000,16000,-100,0.45)>=0);
        check(PhysicalMassBalance.contentsMass(0,0,1000,0.45)==0);
        check(PhysicalMassBalance.contentsMass(Long.MAX_VALUE,1,Integer.MAX_VALUE,0.45)<=3.6);
        for (int i=0;i<=20;i++) {
            check(PhysicalMassBalance.pipeStep(PhysicalMassBalance.pipeMassForStep(i),-1)==i);
            check(PhysicalMassBalance.tankMassForStep(i)>0);
        }
        int previous=0;
        for (int i=0;i<=16000;i+=10) {
            int next=PhysicalMassBalance.tankStep(i,16000,1000,previous);
            check(next>=previous); previous=next;
        }
    }
}''')
            subprocess.run(['javac', '--release', '17', '-d', str(d),
                str(ROOT / 'source-shared' / JAVA / 'PhysicalMassBalance.java'), str(src)], check=True)
            subprocess.run(['java', '-cp', str(d), 'Probe'], check=True)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK unavailable')
    def test_optional_state_bridge_java(self):
        # Contract doubles isolate the bridge from Minecraft/NeoForge startup.
        sources = {
            'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public class BlockPos { public long asLong() { return 7L; } }''',
            'net/minecraft/world/level/block/Block.java': '''package net.minecraft.world.level.block;
public class Block { public static final int UPDATE_CLIENTS=2, UPDATE_KNOWN_SHAPE=16; }''',
            'net/minecraft/world/level/block/state/properties/IntegerProperty.java': '''package net.minecraft.world.level.block.state.properties;
public class IntegerProperty { public static IntegerProperty create(String s,int min,int max) {
    if (!s.equals("bc_physics_mass") || min != 0 || max != 20) throw new AssertionError();
    return new IntegerProperty(); } }''',
            'net/minecraft/world/level/block/state/BlockState.java': '''package net.minecraft.world.level.block.state;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
public class BlockState { public final int step; public final boolean other, present;
    public BlockState(int step,boolean other,boolean present) { this.step=step; this.other=other; this.present=present; }
    public boolean hasProperty(IntegerProperty p) { return present; }
    public Integer getValue(IntegerProperty p) { return step; }
    public BlockState setValue(IntegerProperty p,Integer n) { return new BlockState(n,other,present); }
}''',
            'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
import net.minecraft.core.BlockPos; import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
public class Level { public boolean isClientSide; public long time; public int writes;
    public BlockEntity tile; public BlockState state=new BlockState(2,true,true);
    public long getGameTime() { return time; }
    public BlockEntity getBlockEntity(BlockPos pos) { return tile; }
    public BlockState getBlockState(BlockPos pos) { return state; }
    public boolean setBlock(BlockPos pos,BlockState next,int flags) {
        if (flags != 18) throw new AssertionError(); state=next; writes++; return true;
    }
}''',
            'net/minecraft/world/level/block/entity/BlockEntity.java': '''package net.minecraft.world.level.block.entity;
import net.minecraft.world.level.Level; import net.minecraft.core.BlockPos;
public class BlockEntity { public Level level; public boolean removed;
    public Level getLevel() { return level; } public boolean isRemoved() { return removed; }
    public BlockPos getBlockPos() { return new BlockPos(); }
}''',
            'net/neoforged/fml/ModList.java': '''package net.neoforged.fml;
public class ModList { public static ModList get() { return new ModList(); }
    public boolean isLoaded(String name) { return name.equals("sable") && Boolean.getBoolean("sable.present"); }
}''',
            'BridgeProbe.java': '''import buildcraft.compat.physics.SableMassState;
import net.minecraft.world.level.Level; import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
public class BridgeProbe {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) {
        Level level=new Level(); BlockEntity tile=new BlockEntity(); tile.level=level; level.tile=tile;
        boolean loaded=Boolean.getBoolean("sable.present");
        check(SableMassState.due(tile,false)==loaded);
        check(!SableMassState.due(tile,true)); level.time=7;
        check(SableMassState.due(tile,true)==loaded);
        check(SableMassState.sync(tile,3)==loaded);
        check(level.writes==(loaded?1:0)); check(level.state.other);
        check(SableMassState.sync(tile,3)==loaded); check(level.writes==(loaded?1:0));
        level.isClientSide=true; check(!SableMassState.due(tile,false)); check(!SableMassState.sync(tile,4));
        level.isClientSide=false; tile.removed=true; check(!SableMassState.sync(tile,4));
        tile.removed=false; level.tile=null; check(!SableMassState.sync(tile,4));
        level.tile=tile; level.state=new BlockState(3,true,false); check(!SableMassState.sync(tile,4));
        check(level.writes==(loaded?1:0));
    }
}''',
        }
        with tempfile.TemporaryDirectory() as directory:
            d=Path(directory); files=[]
            for name, content in sources.items():
                path=d/name; path.parent.mkdir(parents=True,exist_ok=True); path.write_text(content); files.append(str(path))
            subprocess.run(['javac','--release','17','-d',str(d),
                str(ROOT/'source-shared'/JAVA/'PhysicalMassBalance.java'),
                str(DOWNPORT/JAVA/'SableMassState.java'),*files],check=True)
            for present in ('true','false'):
                subprocess.run(['java',f'-Dsable.present={present}','-cp',str(d),'BridgeProbe'],check=True)

if __name__ == '__main__':
    unittest.main()
