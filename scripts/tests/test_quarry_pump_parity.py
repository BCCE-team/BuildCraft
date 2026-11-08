#!/usr/bin/env python3
"""BCU-style Quarry/Pump integration guards for all effective loader families."""
from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
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
        # Compile exactly the two methods in production, not Python imitations.
        method_sets = [
            (rel, extract_method((ROOT / rel).read_text(), 'private boolean sameOwner(IPumpHost host)'),
             extract_method((ROOT / rel).read_text(), 'private long drawPower(long amount)'))
            for rel in PUMPS
        ]
        for rel, check_owner, draw in method_sets:
            with self.subTest(source=rel), tempfile.TemporaryDirectory(prefix='bcce-host-power-') as tmp:
                src = r'''import java.util.UUID;
public class QuarryPumpHostProbe {
    static final class GameProfile {
        private final UUID id;
        GameProfile(UUID id) { this.id = id; }
        UUID getId() { return id; }
    }
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


if __name__ == '__main__':
    unittest.main()
