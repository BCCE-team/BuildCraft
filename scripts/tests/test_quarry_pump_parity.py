#!/usr/bin/env python3
"""BCU-style Quarry/Pump integration guards for all effective loader families."""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_config import load_properties, target_layout
from source_layout import _materialize_text_file, resolve_effective_source
PUMPS = (
    'source-platforms/forge/src/main/java/buildcraft/factory/tile/TilePump.java',
    'source-platforms/neoforge/src/main/java/buildcraft/factory/tile/TilePump.java',
)
QUARRIES = (
    'version-src/1.19.2-forge/src/main/java/buildcraft/builders/tile/TileQuarry.java',
    'version-src/1.20.1-forge/src/main/java/buildcraft/builders/tile/TileQuarry.java',
    'source-platforms/neoforge/src/main/java/buildcraft/builders/tile/TileQuarry.java',
)


def extract_method(source: str, signature: str) -> str:
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 1
    index = opening + 1
    while depth:
        depth += (source[index] == '{') - (source[index] == '}')
        index += 1
    return source[start:index]


class QuarryPumpParity(unittest.TestCase):
    def test_common_host_does_not_expose_loader_specific_types(self):
        code = (ROOT / 'source-shared/src/main/java/buildcraft/lib/tile/IPumpHost.java').read_text()
        for required in ('BlockPos getBlockingFluid()', 'MjBattery getBattery()',
                         'boolean hasWork()', 'GameProfile getOwner()',
                         'void onPumpWantsPower()', 'void onBlockingFluidDrained()'):
            self.assertIn(required, code)
        self.assertNotIn('net.minecraftforge', code)
        self.assertNotIn('net.neoforged', code)

    def test_quarries_cache_high_viscosity_fluid_and_revisit_after_drain(self):
        for rel in QUARRIES:
            with self.subTest(source=rel):
                source = (ROOT / rel).read_text()
                self.assertIn('MachineRuntimeView, IPumpHost', source)
                self.assertIn('fluid.getFluidType().getViscosity() > 1000', source)
                self.assertIn('noteBlockingFluid(firstBlockingFluidAbove(pos));', source)
                self.assertIn('blockingFluid = candidate.immutable();', source)
                self.assertIn('drainedSinceWalk = true;', source)
                self.assertIn('if (shouldRevisitBlockedColumns())', source)
                self.assertIn('revisitPending = true;', source)
                self.assertEqual(2, source.count('skipUnworkableCells();'))
                self.assertIn('return wantsPower() ? super.getPowerRequested() : 0;', source)
                self.assertIn('return wantsPower() ? super.receivePower(microJoules, action) : microJoules;', source)
                self.assertIn('pumpWantsPowerUntil = level.getGameTime() + PUMP_POWER_GRACE_TICKS;', source)
                self.assertIn('return level.hasChunkAt(blockingFluid) && !isBlockingFluid(blockingFluid);', source)

    def test_pumps_target_host_not_shaft_and_borrow_only_idle_owner_power(self):
        for rel in PUMPS:
            with self.subTest(source=rel):
                source = (ROOT / rel).read_text()
                self.assertIn('level.getBlockEntity(worldPosition.below())', source)
                self.assertIn('IPumpHost host = getHost();', source)
                self.assertIn('BlockPos blocking = host.getBlockingFluid();', source)
                self.assertIn('fluid.getFluidType().getViscosity() > 1000', source)
                self.assertIn('level.hasChunkAt(blocking)', source)
                self.assertIn('if (getHost() != null) return null;', source)
                self.assertIn('!host.hasWork() && sameOwner(host)', source)
                self.assertIn('host.onPumpWantsPower();', source)
                self.assertIn('progress += drawPower(target - progress);', source)
                self.assertIn('if (host != null) host.onBlockingFluidDrained();', source)
                self.assertIn('boolean hosted = getHost() != null;', source)
                self.assertIn('if (hosted != wasHosted)', source)
                self.assertIn('if (!keepSource)', source)

    def test_host_ownership_power_transfer_runs_real_java_methods(self):
        # Test the materialized code for each target: modern GameProfile exposes id(),
        # whereas Forge and NeoForge 1.21.1 expose getId().
        props = load_properties()
        relative = 'src/main/java/buildcraft/factory/tile/TilePump.java'
        targets = ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge',
                   '1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge')
        for target in targets:
            with self.subTest(target=target), tempfile.TemporaryDirectory(prefix='bcce-host-power-') as tmp:
                layout = target_layout(target, props)
                source = resolve_effective_source(layout, props, relative)
                self.assertIsNotNone(source)
                materialized = Path(tmp) / 'TilePump.java'
                _materialize_text_file(
                    source, materialized, logical_relative=relative,
                    minecraft=props[f'target.{target}.deps.minecraft'],
                    family=layout.family, platform=layout.platform, preprocess=True,
                )
                code = materialized.read_text(encoding='utf-8')
                modern = target.endswith('-neoforge') and target != '1.21.1-neoforge'
                self.assertEqual('import buildcraft.lib.compat.GameProfileCompat;' in code, modern)
                check_owner = extract_method(code, 'private boolean sameOwner(IPumpHost host)')
                draw = extract_method(code, 'private long drawPower(long amount)')
                self.assertNotIn('//?', check_owner)
                if modern:
                    self.assertNotIn('pumpOwner.getId()', check_owner)
                    self.assertIn('GameProfileCompat.id(pumpOwner)', check_owner)
                src = r'''import java.util.UUID;
public class QuarryPumpHostProbe {
    static final class GameProfile {
        private final UUID id;
        GameProfile(UUID id) { this.id = id; }
        /*PROFILE_ACCESSOR*/
    }
    /*COMPAT_CLASS*/
    static final class Battery {
        long energy;
        Battery(long energy) { this.energy = energy; }
        long extractPower(long min, long requested) {
            long transferred = Math.min(energy, requested);
            energy -= transferred;
            return transferred;
        }
    }
    interface IPumpHost {
        boolean hasWork();
        GameProfile getOwner();
        Battery getBattery();
        void onPumpWantsPower();
    }
    static final class Host implements IPumpHost {
        final Battery battery = new Battery(80);
        boolean busy;
        GameProfile owner;
        int wakes;
        Host(GameProfile owner) { this.owner = owner; }
        public boolean hasWork() { return busy; }
        public GameProfile getOwner() { return owner; }
        public Battery getBattery() { return battery; }
        public void onPumpWantsPower() { wakes++; }
    }
    static final class Pump {
        final Battery battery = new Battery(8);
        Host host;
        GameProfile owner;
        Pump(GameProfile owner) { this.owner = owner; }
        GameProfile getOwner() { return owner; }
        Host getHost() { return host; }
        /*OWNER_METHOD*/
        /*DRAW_METHOD*/
    }
    static void check(boolean result) { if (!result) throw new AssertionError(); }
    public static void main(String[] args) {
        GameProfile alice = new GameProfile(UUID.randomUUID());
        GameProfile bob = new GameProfile(UUID.randomUUID());
        Pump pump = new Pump(alice);
        Host quarry = new Host(alice);
        pump.host = quarry;
        check(pump.drawPower(10) == 10);
        check(pump.battery.energy == 0 && quarry.battery.energy == 78 && quarry.wakes == 1);
        quarry.busy = true;
        check(pump.drawPower(10) == 0 && quarry.battery.energy == 78);
        quarry.busy = false;
        pump.owner = bob;
        check(pump.drawPower(10) == 0 && quarry.battery.energy == 78);
        pump.owner = null;
        quarry.owner = null;
        check(pump.drawPower(10) == 10 && quarry.battery.energy == 68);
        pump.host = null;
        check(pump.drawPower(10) == 0);
    }
}
'''.replace('/*OWNER_METHOD*/', check_owner).replace('/*DRAW_METHOD*/', draw)
                src = src.replace('/*PROFILE_ACCESSOR*/', 'UUID id() { return id; }' if modern else 'UUID getId() { return id; }')
                src = src.replace('/*COMPAT_CLASS*/', ('static final class GameProfileCompat { static UUID id(GameProfile profile) { return profile.id(); } }' if modern else ''))
                java = Path(tmp) / 'QuarryPumpHostProbe.java'
                java.write_text(src)
                subprocess.run(['javac', str(java)], capture_output=True, text=True, check=True)
                subprocess.run(['java', '-cp', tmp, 'QuarryPumpHostProbe'], capture_output=True, text=True, check=True)

    def test_real_gametests_cover_blocking_fluid_and_no_tube(self):
        for loader in ('forge', 'neoforge'):
            rel = f'source-platforms/{loader}/src/gametest/java/buildcraft/gametest/SystemQuarryPumpGameTests.java'
            with self.subTest(loader=loader):
                src = (ROOT / rel).read_text()
                self.assertIn('pumpOnQuarryDrainsBlockingLavaAndResumesMining', src)
                self.assertIn('helper.setBlock(LAVA, Blocks.LAVA.defaultBlockState());', src)
                self.assertIn('pump.getHost() == quarry', src)
                self.assertIn('pump.getLength(1.0F) == 0', src)
                self.assertIn('lavaGone && stoneMined && lavaCaptured', src)
                self.assertIn('quarry.getBattery()', src)


    def test_hosted_pump_fixture_has_no_unbreakable_frame_obstructions(self):
        # A live regression: BEDROCK placed inside frameBox gets queued as an
        # unbreakable frame-break task, so host.hasWork() never becomes false
        # and the Pump cannot borrow power. Seal lava with the frame itself.
        from itertools import product
        frame_min, frame_max = (2, 1, 2), (4, 2, 4)
        lava = (3, 1, 3)
        stone = (3, 0, 3)
        def on_frame_edge(pos):
            return sum(pos[i] in (frame_min[i], frame_max[i]) for i in range(3)) >= 2
        ring = {(x, y, z) for x, y, z in product(range(2, 5), range(1, 3), range(2, 5))
                if on_frame_edge((x, y, z))}
        for side in ((3, 1, 2), (3, 1, 4), (2, 1, 3), (4, 1, 3)):
            self.assertIn(side, ring)
        self.assertNotIn(lava, ring)
        self.assertEqual((lava[0], lava[1] - 1, lava[2]), stone)
        for loader in ('forge', 'neoforge'):
            rel = f'source-platforms/{loader}/src/gametest/java/buildcraft/gametest/SystemQuarryPumpGameTests.java'
            with self.subTest(loader=loader):
                src = (ROOT / rel).read_text(encoding='utf-8')
                self.assertIn('new BlockPos(2, 1, 2)', src)
                self.assertIn('new BlockPos(4, 2, 4)', src)
                self.assertIn('mining.setMin(helper.absolutePos(STONE));', src)
                self.assertIn('mining.setMax(helper.absolutePos(LAVA));', src)
                self.assertIn('LAVA.north(), LAVA.south(), LAVA.east(), LAVA.west()', src)
                self.assertNotIn('Blocks.BEDROCK.defaultBlockState()', src)
                self.assertIn('quarry.frameBox.isOnEdge(helper.absolutePos(wall))', src)
                self.assertIn('quarry.getBlockingFluid()', src)
                self.assertIn('lavaGone && stoneMined && lavaCaptured', src)


if __name__ == '__main__':
    unittest.main()
