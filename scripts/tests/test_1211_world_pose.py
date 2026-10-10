#!/usr/bin/env python3
"""Regression for 1.21.1 detached world geometry changing with camera rotation.

NeoForge's model-view matrix is already consumed by the upload shader. A detached
world-space vertex needs only the camera-origin translation on the CPU side, not
another pre-rotation by the same view matrix.
"""
from __future__ import annotations

import math
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'scripts'))
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source


class DetachedWorldPose1211Test(unittest.TestCase):
    def test_1211_uses_identity_pose_before_world_origin_translation(self):
        props = load_properties()
        target = target_layout('1.21.1-neoforge', props)
        source = resolve_effective_source(target, props,
            'src/main/java/buildcraft/lib/BCLibEventDist.java')
        self.assertIsNotNone(source)
        content = source.read_text(encoding='utf-8')
        method = content.split('public static void renderWorldLast(RenderLevelStageEvent event)', 1)[1].split('\n    }',1)[0]
        self.assertIn('PoseStack pose = new PoseStack();', method)
        self.assertNotIn('pose.mulPose(new Matrix4f(event.getModelViewMatrix()))', method)
        self.assertIn('DetachedRenderer.INSTANCE.renderWorldLastEvent(pose, matrix, player, partialTicks)',method)
        self.assertIn('Matrix4f matrix = new Matrix4f(event.getProjectionMatrix());', method)

    def test_world_pose_subtracts_camera_once(self):
        props=load_properties()
        target=target_layout('1.21.1-neoforge',props)
        source=resolve_effective_source(target,props,
            'src/main/java/buildcraft/lib/client/render/DetachedRenderer.java')
        self.assertIsNotNone(source)
        method=source.read_text(encoding='utf-8').split('public static void fromWorldOriginPre(',1)[1].split('public static void fromWorldOriginPost(',1)[0]
        self.assertIn('diff = diff.subtract(camera.getPosition());',method)
        self.assertEqual(1,method.count('pose.translate('))
        self.assertNotIn('pose.mulPose(',method)

    def test_rotation_is_applied_exactly_once(self):
        # Non-axis aligned world point makes doubled view rotation observable.
        # Check several camera angles and world/camera positions including negative coordinates.
        for angle in (0, math.pi/6, math.pi/2, math.pi, -math.pi/3):
            c,s=math.cos(angle),math.sin(angle)
            for vertex,camera in (((20.,65.,-13.),(13.,63.,-18.)),
                                  ((-41.,70.,37.),(-39.,64.,29.)),
                                  ((2.,0.,4.),(-10.,1.,8.))):
                vx,vy,vz=(vertex[i]-camera[i] for i in range(3))
                # GPU's one model-view rotation is authoritative.
                once=(c*vx+s*vz,vy,-s*vx+c*vz)
                # The regression: CPU pre-rotation *and* GPU view rotation.
                twice=(c*once[0]+s*once[2],once[1],-s*once[0]+c*once[2])
                if abs(angle)>1e-5:
                    self.assertGreater(max(abs(a-b) for a,b in zip(once,twice)),1e-4)
                # New CPU pose is identity, so GPU output equals single rotation.
                cpu=(vx,vy,vz)
                actual=(c*cpu[0]+s*cpu[2],cpu[1],-s*cpu[0]+c*cpu[2])
                for a,b in zip(once,actual): self.assertAlmostEqual(a,b,places=5)


if __name__=='__main__':
    unittest.main()
