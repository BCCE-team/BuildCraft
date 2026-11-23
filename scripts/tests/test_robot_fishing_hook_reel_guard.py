"""Regression: hooks cannot attach to or reel in any BCCE robot.

Earlier impact-only regression coverage missed vanilla FishingHook.pullEntity().
Compile and execute the actual mixin callbacks with minimal Minecraft stubs.
"""
from __future__ import annotations

import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
MIXIN_FILES = {
    'neoforge': ROOT / 'source-platforms/neoforge/src/main/java/buildcraft/robotics/mixin/FishingHookRobotGuardMixin.java',
    'fabric': ROOT / 'source-family-platforms/26.X/fabric/src/main/java/buildcraft/fabric/mixin/FishingHookRobotGuardMixin.java',
}
NEO_CONFIG = ROOT / 'source-platforms/neoforge/src/main/resources/buildcraft.robot-fishing.mixins.json'
NEO_MODS = ROOT / 'source-platforms/neoforge/src/main/resources/META-INF/neoforge.mods.toml'
FABRIC_CONFIG = ROOT / 'source-family-platforms/26.X/fabric/src/main/resources/buildcraft.fabric263.mixins.json'

STUBS = {
    'net/minecraft/world/entity/Entity.java': '''package net.minecraft.world.entity;
public class Entity {
    public double motion;
    public void addFishingPull(){motion += 10.0;}
}''',
    'buildcraft/robotics/entity/EntityRobot.java': '''package buildcraft.robotics.entity;
public class EntityRobot extends net.minecraft.world.entity.Entity {}''',
    'net/minecraft/world/entity/projectile/FishingHook.java': '''package net.minecraft.world.entity.projectile;
public class FishingHook extends net.minecraft.world.entity.Entity {}''',
    'net/minecraft/world/phys/EntityHitResult.java': '''package net.minecraft.world.phys;
public class EntityHitResult {
    private final net.minecraft.world.entity.Entity entity;
    public EntityHitResult(net.minecraft.world.entity.Entity entity){this.entity=entity;}
    public net.minecraft.world.entity.Entity getEntity(){return entity;}
}''',
    'org/spongepowered/asm/mixin/Mixin.java': '''package org.spongepowered.asm.mixin;
public @interface Mixin {Class<?> value();}''',
    'org/spongepowered/asm/mixin/injection/At.java': '''package org.spongepowered.asm.mixin.injection;
public @interface At {String value();}''',
    'org/spongepowered/asm/mixin/injection/Inject.java': '''package org.spongepowered.asm.mixin.injection;
public @interface Inject {String method();At at();boolean cancellable();}''',
    'org/spongepowered/asm/mixin/injection/callback/CallbackInfo.java': '''package org.spongepowered.asm.mixin.injection.callback;
public class CallbackInfo {
    private boolean canceled;
    public void cancel(){canceled=true;}
    public boolean isCancelled(){return canceled;}
}''',
    'org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable.java': '''package org.spongepowered.asm.mixin.injection.callback;
public class CallbackInfoReturnable<T> extends CallbackInfo {
    private T value;
    public void setReturnValue(T value){this.value=value;cancel();}
    public T getReturnValue(){return value;}
}''',
}

HARNESS = '''import buildcraft.robotics.entity.EntityRobot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public final class ReelHarness {
    private static void check(boolean condition, String message){if(!condition)throw new AssertionError(message);}
    private static void checkCollision(Object mixin, Class<?> cls, Entity robot, Entity mob) throws Exception {
        var method = cls.getDeclaredMethod("bcce$ignoreRobots", Entity.class, CallbackInfoReturnable.class);
        method.setAccessible(true);
        var a = new CallbackInfoReturnable<Boolean>();
        method.invoke(mixin, robot, a);
        check(a.isCancelled() && Boolean.FALSE.equals(a.getReturnValue()), "robot collision was not blocked");
        var b = new CallbackInfoReturnable<Boolean>();
        method.invoke(mixin, mob, b);
        check(!b.isCancelled(), "ordinary mob collision was blocked");
    }
    private static void checkAttach(Object mixin, Class<?> cls, Entity robot, Entity mob) throws Exception {
        var method = cls.getDeclaredMethod("bcce$ignoreRobotHit", EntityHitResult.class, CallbackInfo.class);
        method.setAccessible(true);
        var a = new CallbackInfo();
        method.invoke(mixin, new EntityHitResult(robot), a);
        check(a.isCancelled(), "robot attachment was not blocked");
        var b = new CallbackInfo();
        method.invoke(mixin, new EntityHitResult(mob), b);
        check(!b.isCancelled(), "ordinary mob attachment was blocked");
    }
    private static void checkReel(Object mixin, Class<?> cls, Entity robot, Entity mob) throws Exception {
        var method = cls.getDeclaredMethod("bcce$ignoreRobotPull", Entity.class, CallbackInfo.class);
        method.setAccessible(true);
        var a = new CallbackInfo();
        method.invoke(mixin, robot, a);
        if(!a.isCancelled()){robot.addFishingPull();}
        check(a.isCancelled() && robot.motion == 0.0, "robot acquired reel-in velocity");
        var b = new CallbackInfo();
        method.invoke(mixin, mob, b);
        if(!b.isCancelled()){mob.addFishingPull();}
        check(!b.isCancelled() && mob.motion == 10.0, "ordinary mob was not reeled in");
    }
    private static void checkMixin(Object mixin) throws Exception {
        var robot = new EntityRobot();
        var mob = new Entity();
        var cls = mixin.getClass().getSuperclass();
        checkCollision(mixin, cls, robot, mob);
        checkAttach(mixin, cls, robot, mob);
        checkReel(mixin, cls, robot, mob);
    }
    public static void main(String[] args) throws Exception {
        checkMixin(new buildcraft.fabric.mixin.FishingHookRobotGuardMixin() {});
        checkMixin(new buildcraft.robotics.mixin.FishingHookRobotGuardMixin() {});
        System.out.println("PASS: 12 loader-specific collision/attach/reel scenarios");
    }
}
'''


class RobotFishingHookReelTests(unittest.TestCase):
    def test_both_loaders_reject_collision_attachment_and_reeling(self):
        sources = dict(STUBS)
        for path in MIXIN_FILES.values():
            package = path.read_text().split('package ', 1)[1].split(';', 1)[0].replace('.', '/')
            sources[f'{package}/FishingHookRobotGuardMixin.java'] = path.read_text()
        sources['ReelHarness.java'] = HARNESS
        with tempfile.TemporaryDirectory(prefix='bcce-robot-reel-') as tmp:
            dest = Path(tmp)
            java_paths = []
            for relative, source in sources.items():
                path = dest / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source)
                java_paths.append(str(path))
            result = subprocess.run(['javac', '-d', str(dest), *java_paths], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(['java', '-cp', str(dest), 'ReelHarness'], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('PASS: 12 loader-specific collision/attach/reel scenarios', result.stdout)

    def test_mixin_registration_for_both_loaders(self):
        for config in (NEO_CONFIG, FABRIC_CONFIG):
            parsed = json.loads(config.read_text())
            self.assertIn('FishingHookRobotGuardMixin', parsed['mixins'])
            self.assertNotIn('FishingHookRobotGuardMixin', parsed.get('client', []))
        self.assertTrue(json.loads(NEO_CONFIG.read_text())['required'])
        text = NEO_MODS.read_text()
        self.assertIn('config="buildcraft.robot-fishing.mixins.json"', text)
        self.assertLess(text.index('config="buildcraft.robot-fishing.mixins.json"'), text.index('//? if'))
        for loader, java in MIXIN_FILES.items():
            src = java.read_text()
            for method in ('canHitEntity', 'onHitEntity', 'pullEntity'):
                self.assertIn(f'@Inject(method = "{method}"', src)
            self.assertNotIn('setDeltaMovement', src, loader + ' must not alter AI motion')

    def test_robot_navigation_and_noclip_unchanged(self):
        roots = list(ROOT.rglob('EntityRobot.java'))
        self.assertGreater(len(roots), 3)
        for path in roots:
            if 'buildcraft/robotics/entity/' not in path.as_posix():
                continue
            src = path.read_text()
            self.assertIn('noPhysics', src, path.as_posix())
            self.assertIn('public void travel(Vec3 travelVector)', src, path.as_posix())


if __name__ == '__main__':
    unittest.main()
