"""Regression: direct fishing-hook velocity impulses must not steer BuildCraft robots.

Runs the production RobotMotionGuard Java class against a typed fake Entity.
Also checks every target's selected EntityRobot tick integrates the guard around
its real AI cycle, instead of relying exclusively on optional platform hooks.
"""
from __future__ import annotations

from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
CLASS = ROOT / 'source-shared/src/main/java/buildcraft/robotics/internal/legacy/robots/RobotMotionGuard.java'
ENTITY_FILES = [
    'version-src/1.19.2-forge/src/main/java/buildcraft/robotics/entity/EntityRobot.java',
    'version-src/1.20.1-forge/src/main/java/buildcraft/robotics/entity/EntityRobot.java',
    'source-downports/1.21.X/1.21.1/neoforge/src/main/java/buildcraft/robotics/entity/EntityRobot.java',
    'source-family-platforms/1.21.X/neoforge/src/main/java/buildcraft/robotics/entity/EntityRobot.java',
    'source-downports/26.X/26.1.2/neoforge/src/main/java/buildcraft/robotics/entity/EntityRobot.java',
    'source-downports/26.X/26.2/neoforge/src/main/java/buildcraft/robotics/entity/EntityRobot.java',
    'source-family-platforms/26.X/neoforge/src/main/java/buildcraft/robotics/entity/EntityRobot.java',
    'source-family-platforms/26.X/fabric/src/main/java/buildcraft/robotics/entity/EntityRobot.java',
]

STUBS = {
    'net/minecraft/world/phys/Vec3.java': '''package net.minecraft.world.phys;
public record Vec3(double x, double y, double z) {
    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public Vec3 add(Vec3 other) {return new Vec3(x+other.x, y+other.y, z+other.z);}
}''',
    'net/minecraft/world/entity/Entity.java': '''package net.minecraft.world.entity;
import net.minecraft.world.phys.Vec3;
public class Entity {
    private Vec3 velocity = Vec3.ZERO;
    public Vec3 getDeltaMovement() {return velocity;}
    public void setDeltaMovement(Vec3 velocity) {this.velocity=velocity;}
}''',
    'GuardHarness.java': '''import buildcraft.robotics.internal.legacy.robots.RobotMotionGuard;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
public class GuardHarness {
    private static void expect(boolean condition, String message) {if(!condition)throw new AssertionError(message);}
    static class Robot extends Entity {
        final RobotMotionGuard guard = new RobotMotionGuard();
        Vec3 beforeAI;
        void tick(Vec3 commandedVelocity) {
            guard.beforeRobotTick(this);
            beforeAI=getDeltaMovement();
            if(commandedVelocity != null) setDeltaMovement(commandedVelocity);
            guard.afterRobotTick(this);
        }
    }
    public static void main(String[] args) {
        var idle = new Robot();
        idle.tick(Vec3.ZERO);
        idle.setDeltaMovement(new Vec3(0.1, 0.1, 0.2)); // FishingHook.pullEntity()
        idle.tick(null);
        expect(idle.beforeAI.equals(Vec3.ZERO), "a stationary robot retained a fishing pull");
        expect(idle.getDeltaMovement().equals(Vec3.ZERO), "stationary robot moved after reel");

        var moving = new Robot();
        Vec3 navigating = new Vec3(0.05, 0.0, 0.04);
        moving.tick(navigating);
        moving.setDeltaMovement(moving.getDeltaMovement().add(new Vec3(0.5, 0, 0.25)));
        moving.tick(null);
        expect(moving.getDeltaMovement().equals(navigating), "hook changed navigation velocity");
        Vec3 newWaypoint = new Vec3(0, 0.15, 0);
        moving.tick(newWaypoint); // AI picks a new target during the tick
        expect(moving.getDeltaMovement().equals(newWaypoint), "AI change rejected");
        moving.tick(null);
        expect(moving.getDeltaMovement().equals(newWaypoint), "AI change lost after a tick");
        for (int i = 0; i < 100; ++i) { // repeat direct vanilla-style pulls
            moving.setDeltaMovement(moving.getDeltaMovement().add(new Vec3(1, 0, 0)));
            moving.tick(null);
            expect(moving.getDeltaMovement().equals(newWaypoint), "impulse leaked at tick " + i);
        }
        expect(idle.getDeltaMovement().equals(Vec3.ZERO), "robots share guard state");
        var firstTick = new Robot();
        firstTick.setDeltaMovement(new Vec3(0.02, 0, 0));
        firstTick.tick(null);
        expect(firstTick.getDeltaMovement().equals(Vec3.ZERO), "old uncontrolled motion survived a restart");
        firstTick.tick(new Vec3(0.03, 0, 0));
        expect(firstTick.getDeltaMovement().equals(new Vec3(0.03, 0, 0)), "new AI motion was blocked");
        System.out.println("RobotMotionGuard runtime scenarios passed");
    }
}''',
}


class RobotMotionGuardTests(unittest.TestCase):
    def test_real_guard_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            for name, content in STUBS.items():
                target=root/name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content, encoding='utf-8')
            out=root/'classes'
            out.mkdir()
            compiled=subprocess.run(['javac', '-d', str(out), str(CLASS),
                *[str(root/name) for name in STUBS]],capture_output=True,text=True)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            result=subprocess.run(['java', '-cp', str(out), 'GuardHarness'],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stdout+'\n'+result.stderr)
            self.assertIn('runtime scenarios passed',result.stdout)

    def test_forge_1201_guard_uses_world_accessor(self) -> None:
        # Entity.level is private in 1.20.1 Forge; the hook must call level().
        source = (ROOT / ENTITY_FILES[1]).read_text(encoding='utf-8')
        for method in ('beforeRobotTick(this);', 'afterRobotTick(this);'):
            with self.subTest(method=method):
                index = source.index('robotMotionGuard.' + method)
                condition = source.rfind('if (', 0, index)
                self.assertEqual(source[condition:index].strip(),
                                 'if (!level().isClientSide) {')
        self.assertNotIn('if (!level.isClientSide) {', source)

    def test_every_target_has_guard_at_correct_tick_stage(self) -> None:
        for relative in ENTITY_FILES:
            with self.subTest(target=relative):
                source=(ROOT/relative).read_text(encoding='utf-8')
                self.assertIn('import buildcraft.robotics.internal.legacy.robots.RobotMotionGuard;',source)
                self.assertIn('new RobotMotionGuard()',source)
                before=source.index('robotMotionGuard.beforeRobotTick(this);')
                ai=source.index('mainAI.cycle();')
                physics=source.index('        super.tick();',before)
                after=source.index('robotMotionGuard.afterRobotTick(this);')
                self.assertLess(before,ai)
                self.assertLess(ai,physics)
                self.assertLess(physics,after)
                self.assertIn('    public boolean isPickable() {\n        return true;', source)
                self.assertIn('noPhysics = !isShutdownActive();',source)

if __name__ == '__main__':
    unittest.main()
