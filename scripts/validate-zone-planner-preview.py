#!/usr/bin/env python3
"""Verify existing local block-preview rendering on each selected API generation."""
from pathlib import Path
from source_lookup import resolve_target_source

ROOT = Path(__file__).resolve().parents[1]
TARGETS = ('1.19.2-forge','1.20.1-forge','1.21.1-neoforge','1.21.11-neoforge')
for target in TARGETS:
    path = resolve_target_source(target,'src/main/java/buildcraft/robotics/client/render/RenderZonePlanner.java')
    text = path.read_text(encoding='utf-8')
    needles = ['TEXTURE_WIDTH = 10','TEXTURE_HEIGHT = 8','BLOCKS_PER_PIXEL = 4', 'new ZonePlannerMapChunk(', 'LightTexture.FULL_BRIGHT', '.maximumSize(256)', 'private final Level level;', 'level == other.level', 'System.identityHashCode(key.level)']
    if target.startswith('1.21.11'):
        needles += ['level.dimension().identifier().toString()', 'RenderCompat.entityCutoutNoCull(texture)', 'collector.submitCustomGeometry']
    else:
        needles += ['level.dimension().location().hashCode()', 'RenderType.entityCutoutNoCull(preview.location)']
    missing = [needle for needle in needles if needle not in text]
    if missing:
        raise SystemExit(f'{target}: local preview lost {missing}')
    if 'expireAfterAccess(' in text:
        raise SystemExit(f'{target}: live Zone Planner preview texture must not expire underneath a cached render state')
    if 'colours[textureY * TEXTURE_WIDTH + textureX] = MAP_BACKGROUND_COLOUR;' not in text:
        raise SystemExit(f'{target}: an unloaded edge chunk must leave a background pixel instead of blanking the whole preview')
    if 'ZonePlannerMapDataClient' in text or 'MessageZoneMapRequest' in text:
        raise SystemExit(f'{target}: block preview must not use remote GUI-map requests')
    bootstrap = resolve_target_source(target,'src/main/java/buildcraft/robotics/BCRobotics.java').read_text(encoding='utf-8')
    if 'BCRoboticsClientRenderers.register(PlatformClientRegistration.renderers(event));' not in bootstrap:
        raise SystemExit(f'{target}: Zone Planner client renderer catalogue is not wired into loader registration')
    renderers = resolve_target_source(target,'src/main/java/buildcraft/robotics/BCRoboticsClientRenderers.java').read_text(encoding='utf-8')
    if 'registry.registerBlockEntityRenderer(BCRoboticsBlocks.ZONE_PLANNER_TILE.get(), RenderZonePlanner::new);' not in renderers:
        raise SystemExit(f'{target}: Zone Planner block preview renderer is not registered')
print('Zone Planner block preview parity OK: local 10x8 terrain preview registered for all four targets')
