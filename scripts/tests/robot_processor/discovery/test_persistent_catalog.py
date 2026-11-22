"""Stage 20: bounded persistent discovery catalog, lifecycle and NBT contracts.

Compile the actual Java index and scheduler with stand-in Level/Tile; the
filesystem/Stonecutter contracts are checked separately. No actual Gradle build
or save-file runtime is simulated by these tests.
"""
import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.source_preprocessor import preprocess_text
from scripts.tests.robot_processor.discovery.test_scheduler_runtime import ROOT, SOURCES, STUBS

INDEX = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotWorkIndex.java'
SCHEDULER = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotProcessorWorkScheduler.java'
TILE = ROOT / 'source-shared/src/main/java/buildcraft/robotics/tile/TileRobotProcessor.java'

PURE_CATALOG = r'''
import buildcraft.robotics.scheduler.RobotWorkIndex;
import java.util.List;
public class WorkCatalogPersistenceFixture {
 static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
 static long packed(int x, int y, int z) {
  return ((long)x & 0x3ffffffL)<<38 | ((long)z & 0x3ffffffL)<<12 | ((long)y & 0xfffL);
 }
 public static void main(String[] args) {
  var index=new RobotWorkIndex();
  var block=packed(-3,-48,7);var other=packed(31,80,-42);var removed=packed(2,66,2);
  index.observe("buildcraft:miner",block,1);
  long mutation=index.generation();
  index.observe("buildcraft:miner",block,200);
  check(index.generation()==mutation,"Observing unchanged ore schedules another save");
  index.observe("buildcraft:boardRobotFarmer",other,1);
  index.observe("buildcraft:miner",removed,1);
  index.invalidate("buildcraft:miner",removed);
  check(index.generation()>mutation,"Removal not marked as a dirty catalog");
  var saved=index.snapshot();
  check(saved.counts().length==2 && saved.positions().length==2,"Catalog snapshot counts wrong");
  saved.positions()[0]=123; // snapshots must not expose mutable arrays.
  saved.counts()[0]=999;
  var loaded=new RobotWorkIndex();
  int number=loaded.restore(new RobotWorkIndex.CatalogSnapshot(saved.categories(),
                       saved.counts(),saved.positions()), p -> p!=other);
  check(number==1,"Area rejection or array ownership failed");
  check(loaded.nearest("buildcraft:miner",-3,-48,7,3,10,p->true).equals(List.of(block)),
     "Saved ore was not restored at negative height");
  check(loaded.count("buildcraft:boardRobotFarmer",3)==0,"Outside-area farmer crop was loaded");
  var roundtrip=new RobotWorkIndex();
  check(roundtrip.restore(saved,p->true)==2,"Roundtrip did not restore all categories");
  check(roundtrip.count("buildcraft:boardRobotFarmer",0)==1,"Camel-case category was silently dropped");
  check(roundtrip.restore(saved,p->true)==0,"Replay produced duplicate catalog entries");
  check(roundtrip.restore(new RobotWorkIndex.CatalogSnapshot("buildcraft:miner",new int[]{-1},new long[0]),p->true)==0,
     "Negative count accepted");
  check(roundtrip.restore(new RobotWorkIndex.CatalogSnapshot("buildcraft:miner",new int[]{2},new long[]{block}),p->true)==0,
     "Truncated NBT was partially restored");
  check(roundtrip.restore(new RobotWorkIndex.CatalogSnapshot("buildcraft:miner\\nbuildcraft:miner",new int[]{1,1},new long[]{block,other}),p->true)==0,
     "Duplicate categories accepted");
  check(roundtrip.restore(new RobotWorkIndex.CatalogSnapshot("buildcraft:miner\\nBAD\\nCAT",new int[]{1,1},new long[]{block,other}),p->true)==0,
     "Corrupt category name layout accepted");
  check(roundtrip.restore(new RobotWorkIndex.CatalogSnapshot("buildcraft:miner",new int[]{1},
       new long[RobotWorkIndex.MAX_SAVED_POSITIONS+1]),p->true)==0,"Oversized NBT accepted");
  var many=new RobotWorkIndex();
  for(int i=0;i<RobotWorkIndex.MAX_CANDIDATES_PER_CATEGORY+2048;i++){
   many.observe("buildcraft:miner",packed(i,12,1),1);
  }
  check(many.count("buildcraft:miner",2)==RobotWorkIndex.MAX_CANDIDATES_PER_CATEGORY,
        "In-memory safety cap lost");
  check(many.snapshot().positions().length==RobotWorkIndex.MAX_CANDIDATES_PER_CATEGORY,
        "Snapshot exceeds per-category limit");
  System.out.println("PASS catalog snapshot / policy / malformed payload / bounds");
 }
}
'''

# Uses actual scheduler + index, with only the game objects replaced by tiny stubs.
SCHEDULER_INTEGRATION = r'''
package fixture;
import buildcraft.robotics.scheduler.RobotProcessorWorkScheduler;
import buildcraft.robotics.scheduler.RobotWorkIndex;
import buildcraft.robotics.tile.TileRobotProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
public class WorkCatalogWorldFixture {
 static void check(boolean good,String reason){if(!good)throw new AssertionError(reason);}
 static long at(int x,int y,int z){return new BlockPos(x,y,z).asLong();}
 public static void main(String[] args) throws Exception {
  ServerLevel world=new ServerLevel();
  TileRobotProcessor panel=new TileRobotProcessor(world,new BlockPos(0,65,0));
  var first=new RobotProcessorWorkScheduler(panel);
  var input=new RobotWorkIndex();
  long accepted=at(5,65,5),outOfHeight=at(5,-10,5);
  input.observe("buildcraft:miner",accepted,0);
  input.observe("buildcraft:miner",outOfHeight,0);
  check(first.restoreCatalog(input.snapshot())==1,"Invalid-height NBT was accepted");
  check(world.blockReads==0,"Restore loaded a chunk or read a block");
  first.flushCatalogDirty(1);
  check(panel.changed==0,"Loading the saved catalog caused a redundant chunk save");
  var field=RobotProcessorWorkScheduler.class.getDeclaredField("index");
  field.setAccessible(true);
  var index=(RobotWorkIndex)field.get(first);
  index.observe("buildcraft:miner",at(6,65,6),1);
  first.flushCatalogDirty(2);
  check(panel.changed==1,"Newly discovered target not scheduled for save");
  index.observe("buildcraft:miner",at(7,65,7),3);
  first.flushCatalogDirty(3);
  check(panel.changed==1,"One block scan dirtied the chunk repeatedly");
  first.flushCatalogDirty(202);
  check(panel.changed==2,"Catalog changes never flushed after debounce");
  var saved=first.catalogSnapshot();
  first.onRemoved();
  var reloaded=new RobotProcessorWorkScheduler(panel);
  check(reloaded.restoreCatalog(saved)==3,"Saved world catalog was not reused after reload");
  check(world.blockReads==0,"Reloaded catalog performed a world read");
  check(reloaded.catalogSnapshot().positions().length==3,"Reloaded targets lost");
  reloaded.onLinkChanged();
  check(reloaded.catalogSnapshot().positions().length==0,"Changing work policy kept stale targets");
  reloaded.onRemoved();
  System.out.println("PASS load/unload, no chunk loads, periodic saves, policy change");
 }
}
'''

class PersistentWorkCatalogTests(unittest.TestCase):
    def test_java_index_round_trip_and_bounded_corrupt_payload(self):
        with tempfile.TemporaryDirectory(prefix='bc-work-catalog-') as temp:
            source=Path(temp)/'WorkCatalogPersistenceFixture.java'
            source.write_text(PURE_CATALOG)
            compiled=subprocess.run(['javac','--release','17','-d',temp,str(INDEX),str(source)],
                                    text=True,capture_output=True,timeout=35)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            run=subprocess.run(['java','-cp',temp,'WorkCatalogPersistenceFixture'],
                               text=True,capture_output=True,timeout=20)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)

    def test_actual_scheduler_restores_without_reading_chunks_and_debounces_saves(self):
        for version,family,loader in [('1.19.2','old','forge'),('1.21.11','1.21.X','neoforge'),
                                      ('26.3','26.X','fabric')]:
            with self.subTest(version=version, loader=loader), tempfile.TemporaryDirectory(prefix='bc-catalog-server-') as temp:
                work=Path(temp); paths=[]
                stubs=STUBS.copy()
                key='buildcraft/robotics/tile/TileRobotProcessor.java'
                stubs[key]=stubs[key].replace('public boolean removed;', 'public int changed; public boolean removed;').replace(
                    'public void setChanged(){}','public void setChanged(){changed++;}')
                for name,source in stubs.items():
                    dest=work/name;dest.parent.mkdir(parents=True,exist_ok=True)
                    dest.write_text(source);paths.append(str(dest))
                for name in SOURCES:
                    dest=work/name.removeprefix('source-shared/src/main/java/')
                    dest.parent.mkdir(parents=True,exist_ok=True)
                    dest.write_text(preprocess_text((ROOT/name).read_text(),minecraft=version,
                                    family=family,platform=loader,source=name));paths.append(str(dest))
                dest=work/'fixture/WorkCatalogWorldFixture.java'
                dest.parent.mkdir(parents=True,exist_ok=True)
                dest.write_text(SCHEDULER_INTEGRATION);paths.append(str(dest))
                folder=work/'classes';folder.mkdir()
                compiled=subprocess.run(['javac','--release','17','-d',str(folder),*paths],
                                        text=True,capture_output=True,timeout=50)
                self.assertEqual(compiled.returncode,0,compiled.stderr)
                result=subprocess.run(['java','-cp',str(folder),'fixture.WorkCatalogWorldFixture'],
                                      text=True,capture_output=True,timeout=20)
                self.assertEqual(result.returncode,0,result.stdout+result.stderr)

    def test_real_tile_persists_all_fields_in_both_nbt_apis(self):
        code=TILE.read_text()
        for field in ('discoveryCatalogVersion','discoveryCatalogTypes','discoveryCatalogCounts','discoveryCatalogPositions'):
            self.assertIn(field,code)
        for fragment in ('data.writeString(CATALOG_TYPES_KEY, catalog.categories())',
                         'data.writeIntArray(CATALOG_COUNTS_KEY, catalog.counts())',
                         'data.writeLongArray(CATALOG_POSITIONS_KEY, catalog.positions())',
                         'tag.putString(CATALOG_TYPES_KEY, catalog.categories())',
                         'tag.putIntArray(CATALOG_COUNTS_KEY, catalog.counts())',
                         'tag.putLongArray(CATALOG_POSITIONS_KEY, catalog.positions())'):
            self.assertIn(fragment,code)
        self.assertLess(code.index('workScheduler.onLinkChanged();\n        workScheduler.restorePendingWork'),
                        code.index('workScheduler.restoreCatalog(persistedCatalog);'))
        unloading=code.split('public void onChunkUnloaded() {',1)[1].split('public void onRemove(boolean',1)[0]
        self.assertIn('persistedCatalog = workScheduler.catalogSnapshot()',unloading)
        self.assertNotIn('setChanged();',unloading)
        self.assertIn('workScheduler.flushCatalogDirty(now)',code)

if __name__=='__main__':unittest.main()
