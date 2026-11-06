#!/usr/bin/env python3
"""26.2/26.3 PiP smooth-frame budget and entity fallback guards.

Parses materialized target sources and compiles/executes the actual pure-Java
frame budget. These do NOT prove Minecraft render correctness or a measured FPS.
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'scripts'),str(ROOT/'scripts/tests')]
from source_lookup import read_materialized_java, resolve_target_source
from minecraft_compat_fixture import parse_sources

B='src/main/java/buildcraft/builders/client/render/pip/'
SCENE=B+'BlueprintPreviewScene.java'
PIP=B+'BlueprintPipRenderer.java'
BUDGET=B+'PreviewFrameBudget.java'


class PiPRenderBudgetAndEntityFallbackTests(unittest.TestCase):
    def test_262_263_skip_redrawing_only_when_texture_is_safe_to_reuse(self):
        for target in ('26.2-neoforge','26.3-neoforge'):
            with self.subTest(target=target):
                renderer=read_materialized_java(target,PIP)
                budget=read_materialized_java(target,BUDGET)
                self.assertIn('protected boolean textureIsReadyToBlit(BlueprintPipRenderState state)',renderer)
                self.assertIn('frameBudget.canReuse(state, client.level,',renderer)
                self.assertIn('frameBudget.markRendered(state, minecraft.level, cachedModelSet,',renderer)
                self.assertIn('PreviewFrameBudget.forMaxFps(PREVIEW_MAX_FPS)',renderer)
                self.assertIn('Integer.getInteger("buildcraft.builders.preview.maxFps", 60)',renderer)
                self.assertNotIn('new PreviewFrameBudget(125_000_000L)',renderer)
                self.assertIn('Objects.equals(lastState, state)',budget)
                self.assertIn('lastWorld == world && lastModels == models',budget)
                self.assertIn('now - lastFrameNanos < intervalNanos',budget)
                self.assertIn('intervalNanos > 0 && valid', budget)
                self.assertIn('fps == 0 ? 0L : Math.max(1L, 1_000_000_000L / fps)',budget)
        self.assertNotIn('textureIsReadyToBlit(',read_materialized_java('26.1.2-neoforge',PIP))
        for target in ('26.1.2-neoforge', '1.21.11-neoforge'):
            with self.assertRaises(FileNotFoundError):
                read_materialized_java(target, BUDGET)

    def test_whitelisted_entity_decode_has_a_fallback_not_real_world_spawn(self):
        for target in ('26.2-neoforge','26.3-neoforge'):
            with self.subTest(target=target):
                scene=read_materialized_java(target,SCENE)
                self.assertIn('VISUAL_ENTITIES.contains(id)',scene)
                self.assertIn('EntityType.create(type,',scene)
                self.assertIn('EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(',scene)
                self.assertIn('entity = withPreviewId(type.create(world,',scene)
                self.assertIn('new ItemFrame(world, anchor, facing)',scene)
                self.assertIn('entity.load(TagValueInput.create(',scene)
                self.assertIn('entity.snapTo(position.x, position.y, position.z,',scene)
                self.assertIn('restorePreviewEquipment(entity, tag)',scene)
                self.assertIn('reportEntityFailure("submit", type, invalidEntity)',scene)
                self.assertNotIn('world.addFreshEntity(',scene)
                self.assertNotIn('schematic.buildWithoutChecks(',scene)
                self.assertIn('buildcraft.builders.preview.debug',scene)
        old=read_materialized_java('26.1.2-neoforge',SCENE)
        self.assertNotIn('EntitySpawnRequest',old)
        self.assertNotIn('type.create(world,',old)

    def test_syntax_across_materialized_targets(self):
        for target in ('26.1.2-neoforge','26.2-neoforge','26.3-neoforge'):
            with self.subTest(target=target),tempfile.TemporaryDirectory(prefix='bcce26-budget-') as tmp:
                files=[]
                for relative in ([SCENE,PIP,BUDGET] if target!='26.1.2-neoforge' else [SCENE,PIP]):
                    dest=Path(tmp)/relative
                    dest.parent.mkdir(parents=True,exist_ok=True)
                    dest.write_text(read_materialized_java(target,relative),encoding='utf8')
                    files.append(dest)
                self.assertIn('0 errors',parse_sources(files))

    def test_actual_budget_compiles_and_reuses_only_unchanged_frame(self):
        source=read_materialized_java('26.2-neoforge',BUDGET)
        probe='''package buildcraft.builders.client.render.pip;
public final class BudgetProbe {
    public static void main(String... args) {
        var b=PreviewFrameBudget.forMaxFps(60);
        Object world=new Object(), models=new Object(), w2=new Object(), m2=new Object();
        long started=1000000000L;
        if(b.canReuse("a",world,models,started)) throw new AssertionError("reused undrawn");
        b.markRendered("a",world,models,started);
        if(!b.canReuse("a",world,models,started+16000000L))throw new AssertionError("missed valid 60 fps frame");
        if(b.canReuse("a",world,models,started+16666666L))throw new AssertionError("60 fps frame was still locked");
        if(b.canReuse("a",world,models,started+125000000L))throw new AssertionError("old 8 fps cap persists");
        if(b.canReuse("b",world,models,started+10000000L))throw new AssertionError("wrong snapshot");
        if(b.canReuse("a",w2,models,started+10000000L))throw new AssertionError("wrong level");
        if(b.canReuse("a",world,m2,started+10000000L))throw new AssertionError("old models");
        if(b.canReuse("a",world,models,started-1L))throw new AssertionError("clock reversal");
        b.markRendered("b",w2,m2,started+16666666L);
        if(!b.canReuse("b",w2,m2,started+16666667L))throw new AssertionError("new state");
        if(b.canReuse("a",world,models,started+16666667L))throw new AssertionError("stale old frame");
        // At 120 display frames/s, the capped 60 Hz preview must actually
        // submit roughly 60 frames per second (the old budget managed eight).
        var simulated=PreviewFrameBudget.forMaxFps(60);
        int redraws=0;
        for(int i=0;i<120;i++){
            long now=started+i*8333334L;
            if(!simulated.canReuse("a",world,models,now)){
                simulated.markRendered("a",world,models,now);
                redraws++;
            }
        }
        if(redraws<57 || redraws>61) throw new AssertionError("expected 60 Hz preview, got "+redraws);
        var unlimited=PreviewFrameBudget.forMaxFps(0);
        unlimited.markRendered("a",world,models,started);
        if(unlimited.canReuse("a",world,models,started+1L))throw new AssertionError("uncapped reused texture");
        var slow=PreviewFrameBudget.forMaxFps(30);
        slow.markRendered("a",world,models,started);
        if(!slow.canReuse("a",world,models,started+33333332L))throw new AssertionError("30 fps too fast");
        if(slow.canReuse("a",world,models,started+33333333L))throw new AssertionError("30 fps too slow");
        boolean rejected=false;
        try { PreviewFrameBudget.forMaxFps(-1); } catch(IllegalArgumentException expected){ rejected=true; }
        if(!rejected)throw new AssertionError("negative frame rate accepted");
        System.out.println("PiP budget PASS");
    }
}
'''
        with tempfile.TemporaryDirectory(prefix='bcce26-budget-javac-') as tmp:
            root=Path(tmp)
            for name,body in [('PreviewFrameBudget.java',source),('BudgetProbe.java',probe)]:
                (root/name).write_text(body,encoding='utf8')
            result=subprocess.run(['javac','-d',str(root),str(root/'PreviewFrameBudget.java'),
                str(root/'BudgetProbe.java')],capture_output=True,text=True,timeout=30)
            self.assertEqual(result.returncode,0,result.stderr)
            result=subprocess.run(['java','-cp',str(root),
                'buildcraft.builders.client.render.pip.BudgetProbe'],capture_output=True,text=True,timeout=30)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn('PiP budget PASS',result.stdout)

if __name__=='__main__':unittest.main()
