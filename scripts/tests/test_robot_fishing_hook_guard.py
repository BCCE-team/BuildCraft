"""Regression coverage: vanilla fishing hooks cannot catch any BuildCraft robot.

The Java harness compiles and executes the exact NeoForge impact handler and Fabric
mixin injection body with lightweight stand-ins for the Minecraft/loader types.
"""
from __future__ import annotations

import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
NEOFORGE_BOOTSTRAPS = [
    'source-platforms/neoforge/src/main/java/buildcraft/robotics/BCRobotics.java',
    'source-family-platforms/26.X/neoforge/src/main/java/buildcraft/robotics/BCRobotics.java',
    'source-downports/26.X/26.2/neoforge/src/main/java/buildcraft/robotics/BCRobotics.java',
    'source-downports/26.X/26.1.2/neoforge/src/main/java/buildcraft/robotics/BCRobotics.java',
]
FABRIC_MIXIN = 'source-family-platforms/26.X/fabric/src/main/java/buildcraft/fabric/mixin/FishingHookRobotGuardMixin.java'
FABRIC_MANIFEST = 'source-family-platforms/26.X/fabric/src/main/resources/buildcraft.fabric263.mixins.json'


def neo_handler(source: str) -> str:
    match = re.search(r'    private static void preventRobotFishingHook\(ProjectileImpactEvent event\) \{[\s\S]*?\n    \}', source)
    if not match:
        raise AssertionError('NeoForge fishing hook handler missing')
    return match.group()


STUBS = {
    'net/minecraft/world/entity/Entity.java': '''package net.minecraft.world.entity;
public class Entity {}''',
    'buildcraft/robotics/entity/EntityRobot.java': '''package buildcraft.robotics.entity;
public class EntityRobot extends net.minecraft.world.entity.Entity {}''',
    'net/minecraft/world/entity/projectile/Projectile.java': '''package net.minecraft.world.entity.projectile;
public class Projectile extends net.minecraft.world.entity.Entity {}''',
    'net/minecraft/world/entity/projectile/FishingHook.java': '''package net.minecraft.world.entity.projectile;
public class FishingHook extends Projectile {}''',
    'net/minecraft/world/phys/HitResult.java': '''package net.minecraft.world.phys;
public class HitResult {}''',
    'net/minecraft/world/phys/EntityHitResult.java': '''package net.minecraft.world.phys;
public class EntityHitResult extends HitResult {
 private final net.minecraft.world.entity.Entity target;
 public EntityHitResult(net.minecraft.world.entity.Entity e){target=e;}
 public net.minecraft.world.entity.Entity getEntity(){return target;}
}''',
    'net/neoforged/neoforge/event/entity/ProjectileImpactEvent.java': '''package net.neoforged.neoforge.event.entity;
public class ProjectileImpactEvent {
 private final net.minecraft.world.entity.projectile.Projectile projectile;
 private final net.minecraft.world.phys.HitResult hit;
 private boolean canceled;
 public ProjectileImpactEvent(net.minecraft.world.entity.projectile.Projectile p, net.minecraft.world.phys.HitResult h){projectile=p;hit=h;}
 public net.minecraft.world.entity.projectile.Projectile getProjectile(){return projectile;}
 public net.minecraft.world.phys.HitResult getRayTraceResult(){return hit;}
 public void setCanceled(boolean value){canceled=value;}
 public boolean isCanceled(){return canceled;}
}''',
    'org/spongepowered/asm/mixin/Mixin.java': '''package org.spongepowered.asm.mixin;
public @interface Mixin {Class<?> value();}''',
    'org/spongepowered/asm/mixin/injection/At.java': '''package org.spongepowered.asm.mixin.injection;
public @interface At {String value();}''',
    'org/spongepowered/asm/mixin/injection/Inject.java': '''package org.spongepowered.asm.mixin.injection;
public @interface Inject {String method();At at();boolean cancellable();}''',
    'org/spongepowered/asm/mixin/injection/callback/CallbackInfo.java': '''package org.spongepowered.asm.mixin.injection.callback;
public class CallbackInfo {public void cancel(){}}''',
    'org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable.java': '''package org.spongepowered.asm.mixin.injection.callback;
public class CallbackInfoReturnable<T> {
 private T value;
 public void setReturnValue(T result){value=result;}
 public T getReturnValue(){return value;}
}''',
}


HARNESS = '''import buildcraft.robotics.entity.EntityRobot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import buildcraft.fabric.mixin.FishingHookRobotGuardMixin;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public class GuardHarness {
NEO_HANDLER
    private static void check(boolean value,String msg){if(!value)throw new AssertionError(msg);}
    public static void main(String[] args) throws Exception {
        EntityRobot robot = new EntityRobot();
        Entity other = new Entity();
        FishingHook fishingHook = new FishingHook();
        Projectile otherProjectile = new Projectile();
        ProjectileImpactEvent hitRobot = new ProjectileImpactEvent(fishingHook,new EntityHitResult(robot));
        preventRobotFishingHook(hitRobot);
        check(hitRobot.isCanceled(),"Robot was caught by fishing hook (NeoForge)");
        ProjectileImpactEvent hitOther = new ProjectileImpactEvent(fishingHook,new EntityHitResult(other));
        preventRobotFishingHook(hitOther);
        check(!hitOther.isCanceled(),"Normal mob was immune to fishing hook");
        ProjectileImpactEvent otherHitRobot = new ProjectileImpactEvent(otherProjectile,new EntityHitResult(robot));
        preventRobotFishingHook(otherHitRobot);
        check(!otherHitRobot.isCanceled(),"Other projectiles were blocked");
        ProjectileImpactEvent hitBlock = new ProjectileImpactEvent(fishingHook,new HitResult());
        preventRobotFishingHook(hitBlock);
        check(!hitBlock.isCanceled(),"Block hit was blocked");

        FishingHookRobotGuardMixin mixin = new FishingHookRobotGuardMixin() {};
        var method = FishingHookRobotGuardMixin.class.getDeclaredMethod(
            "bcce$ignoreRobots",Entity.class,CallbackInfoReturnable.class);
        method.setAccessible(true);
        var denied = new CallbackInfoReturnable<Boolean>();
        method.invoke(mixin,robot,denied);
        check(Boolean.FALSE.equals(denied.getReturnValue()),"Robot was caught by fishing hook (Fabric)");
        var untouched = new CallbackInfoReturnable<Boolean>();
        method.invoke(mixin,other,untouched);
        check(untouched.getReturnValue()==null,"Normal entity fishing was modified");
        System.out.println("PASS 6 fishing-hook collision cases");
    }
}
'''


class RobotFishingHookGuardTests(unittest.TestCase):
    def test_all_neoforge_targets_register_identical_server_hook(self):
        signatures = []
        for relative_path in NEOFORGE_BOOTSTRAPS:
            source = (ROOT / relative_path).read_text()
            self.assertIn('NeoForge.EVENT_BUS.addListener(BCRobotics::preventRobotFishingHook)', source, relative_path)
            self.assertIn('net.neoforged.neoforge.event.entity.ProjectileImpactEvent', source)
            signatures.append(neo_handler(source))
        self.assertEqual(len(set(signatures)), 1, 'NeoForge downport protection diverged')

    def test_fabric_mixin_registered_on_common_side(self):
        data = json.loads((ROOT / FABRIC_MANIFEST).read_text())
        self.assertIn('FishingHookRobotGuardMixin', data['mixins'])
        self.assertNotIn('FishingHookRobotGuardMixin', data['client'])
        source = (ROOT / FABRIC_MIXIN).read_text()
        self.assertIn('@Mixin(FishingHook.class)', source)
        self.assertIn('method = "canHitEntity"', source)
        self.assertIn('cancellable = true', source)

    def test_robot_keeps_normal_targeting_and_interactions(self):
        for path in ROOT.rglob('EntityRobot.java'):
            if 'buildcraft/robotics/entity/' not in path.as_posix():
                continue
            text = path.read_text()
            self.assertRegex(text, r'boolean isPickable\(\)\s*\{\s*return true;', str(path))
            self.assertNotIn('boolean canBeHitByProjectile()', text, str(path))

    def test_real_handlers_compile_and_filter_correctly(self):
        neo = neo_handler((ROOT / NEOFORGE_BOOTSTRAPS[0]).read_text())
        sources = dict(STUBS)
        sources['buildcraft/fabric/mixin/FishingHookRobotGuardMixin.java'] = (ROOT / FABRIC_MIXIN).read_text()
        sources['GuardHarness.java'] = HARNESS.replace('NEO_HANDLER', neo)
        with tempfile.TemporaryDirectory(prefix='bcce-hook-guard-') as temp:
            dest = Path(temp)
            java_paths = []
            for relative, content in sources.items():
                path = dest / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
                java_paths.append(str(path))
            result = subprocess.run(['javac', '-d', str(dest), *java_paths], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(['java', '-cp', str(dest), 'GuardHarness'], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('PASS 6 fishing-hook collision cases', result.stdout)


if __name__ == '__main__':
    unittest.main()
