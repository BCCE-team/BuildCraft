"""Portable media invariants; the codec runs in Java against an isolated zone fixture."""
from pathlib import Path
import json
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
JAVA = ROOT / 'source-shared/src/main/java/buildcraft/robotics'

class PortableDiskTests(unittest.TestCase):
    def test_exact_translation_for_all_layers(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            fixtures = {
                'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public record BlockPos(int x,int y,int z) {
 public int getX(){return x;} public int getZ(){return z;}
}''',
                'buildcraft/robotics/zone/ZonePlan.java': '''package buildcraft.robotics.zone;
import java.util.*;
public class ZonePlan {
 private final Map<Long,Boolean> cells=new HashMap<>();
 private long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
 public boolean get(int x,int z){return cells.containsKey(key(x,z));}
 public void set(int x,int z,boolean value){if(value)cells.put(key(x,z),true);else cells.remove(key(x,z));}
 public Map<Long,Boolean> getChunkMapping(){return cells;}
}''',
                'CheckDisk.java': '''import buildcraft.robotics.zone.*;
import net.minecraft.core.BlockPos;
import java.util.Arrays;
public class CheckDisk {
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  BlockPos a=new BlockPos(-17,70,31), b=new BlockPos(13,82,-46);
  ProcessorDiskLayout layout=ProcessorDiskLayout.at(a);
  ZonePlan[] zones=new ZonePlan[16];
  for(int i=0;i<16;i++){
   zones[i]=new ZonePlan();
   zones[i].set(layout.minX(a),layout.minZ(a)+i,true);
   zones[i].set(layout.minX(a)+111,layout.minZ(a)+111,true);
   zones[i].set(layout.minX(a)-1,layout.minZ(a),true);
  }
  byte[] paint=layout.encode(zones,a);
  ZonePlan[] moved=layout.decode(paint,b);
  for(int i=0;i<16;i++){
   check(moved[i].get(layout.minX(b),layout.minZ(b)+i));
   check(moved[i].get(layout.minX(b)+111,layout.minZ(b)+111));
   check(!moved[i].get(layout.minX(b)-1,layout.minZ(b)));
   check(moved[i].getChunkMapping().size()==2);
  }
  check(Arrays.equals(paint,layout.encode(moved,b)));
  check(layout.contains(b,layout.minX(b)+111,layout.minZ(b)+111));
  check(!layout.contains(b,layout.minX(b)+112,layout.minZ(b)));
  check(layout.intersectsChunk(b,layout.minX(b)>>4,layout.minZ(b)>>4));
  check(layout.encode(new ZonePlan[16],a).length==0);
  check(layout.decode(new byte[0],b)[15].getChunkMapping().isEmpty());
  try{layout.decode(new byte[ProcessorDiskLayout.TOTAL_BYTES+1],b);throw new AssertionError();}catch(IllegalArgumentException ok){}
 }
}''',
            }
            for name, content in fixtures.items():
                p=base/name; p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
            subprocess.run(['javac','--release','17','-d',tmp,str(JAVA/'zone/ProcessorDiskLayout.java'),*[str(base/p) for p in fixtures]],check=True)
            subprocess.run(['java','-cp',tmp,'CheckDisk'],check=True)

    def test_disk_authority_and_gui_gate(self):
        tile=(JAVA/'tile/TileRobotProcessor.java').read_text()
        menu=(JAVA/'container/ContainerRobotProcessor.java').read_text()
        for fragment in ('ItemHardDisk.write(hardDisk.getStackInSlot(0), data)', 'diskLayout.decode(diskPaint, worldPosition)',
                         '!hasMountedDisk() || isPhysicsOperationPaused()', 'processorBattery.extractPower(requestedPower)',
                         'data.putLongArray("linkedRobots"', 'data.putLongArray("linkedStations"', 'removeLegacyMapData'):
            self.assertIn(fragment,tile)
        self.assertIn('HDD_X = 229, HDD_Y = 22',menu)
        self.assertIn('buffer.readInt() != machine.getMediaRevision()',menu)
        self.assertIn('out.writeInt(diskState.revision())',menu)
        self.assertIn('processorOnline() && areaReady',menu)
        from scripts.tests.robot_processor.ui.test_panel_layout import ZONE_SOURCES, ROBOT_SCREENS
        for path in ZONE_SOURCES+ROBOT_SCREENS:
            text=(ROOT/path).read_text()
            self.assertIn('if (!processorControlsEnabled()',text)

    def test_hdd_translation_and_sprite_are_available_on_all_targets(self):
        from scripts.source_config import load_properties, target_layout
        from scripts.source_layout import resolve_effective_source
        from scripts.transforms.resources import (generate_legacy_resources_from_modern_canonical,
                                                  generate_modern_item_definitions)
        from scripts.tests.robot_processor.ui.test_map_and_help import TARGETS

        props = load_properties()
        base = 'src/main/resources/assets/'
        sprite = 'buildcraftrobotics:items/hard_disk'
        atlas = base + 'minecraft/atlases/blocks.json'
        with tempfile.TemporaryDirectory() as tmp:
            destination = Path(tmp)
            generate_legacy_resources_from_modern_canonical(
                destination, minecraft='1.20.1', family='old', platform='forge')
            for target in TARGETS:
                with self.subTest(target=target):
                    layout = target_layout(target, props)
                    def resource(path):
                        source = resolve_effective_source(layout, props, base + path)
                        self.assertIsNotNone(source, path)
                        return source
                    lang = json.loads(resource('buildcraft/lang/en_us.json').read_text(encoding='utf-8'))
                    self.assertEqual('Hard Disk Drive', lang['item.buildcraftrobotics.hard_disk'])
                    model_source = resource('buildcraftrobotics/models/item/hard_disk.json')
                    model = json.loads(model_source.read_text(encoding='utf-8'))
                    self.assertEqual(sprite, model['textures']['layer0'])
                    self.assertEqual(b'\x89PNG\r\n\x1a\n', resource('buildcraftrobotics/textures/items/hard_disk.png').read_bytes()[:8])
                    if target.startswith('26.'):
                        item = json.loads(resource('buildcraftrobotics/items/hard_disk.json').read_text(encoding='utf-8'))
                        self.assertEqual('buildcraftrobotics:item/hard_disk', item['model']['model'])
                    elif target.startswith('1.21.11-'):
                        # This family generates its client item definition from the model.
                        generated_model = destination / base / 'buildcraftrobotics/models/item/hard_disk.json'
                        generated_model.parent.mkdir(parents=True, exist_ok=True)
                        generated_model.write_bytes(model_source.read_bytes())
                        generate_modern_item_definitions(destination, minecraft='1.21.11', platform='neoforge')
                        item_path = destination / base / 'buildcraftrobotics/items/hard_disk.json'
                        item = json.loads(item_path.read_text(encoding='utf-8'))
                        self.assertEqual('buildcraftrobotics:item/hard_disk', item['model']['model'])
                    if target.startswith('1.19.2-'):
                        continue # vanilla directory source automatically stitches legacy item textures
                    path = (destination / atlas if target.startswith('1.20.1-') else
                            resolve_effective_source(layout, props, atlas))
                    self.assertIsNotNone(path)
                    sources = json.loads(path.read_text(encoding='utf-8'))['sources']
                    self.assertEqual([{'type': 'minecraft:single', 'resource': sprite}],
                                     [entry for entry in sources if entry.get('resource') == sprite])

    def test_recipe_and_user_assets(self):
        from PIL import Image
        resources=ROOT/'source-shared/src/main/resources/assets/buildcraftrobotics'
        with Image.open(resources/'textures/items/hard_disk.png') as image:
            self.assertEqual(image.size,(32,32))
        with Image.open(resources/'textures/gui/robot_processor.png') as image:
            rgba=image.convert('RGBA')
            self.assertEqual(rgba.size,(512,512))
            self.assertEqual(len(set(rgba.crop((229,22,245,38)).getdata())),1)
        recipes=list(ROOT.glob('source-families/*/src/main/resources/data/buildcraftrobotics/recipe*/assembly/hard_disk.json'))
        self.assertEqual(len(recipes),2)
        for p in recipes:
            r=json.loads(p.read_text());self.assertEqual(r['type'],'buildcraftsilicon:assembly');self.assertEqual(r['MJ'],12_000_000_000)
