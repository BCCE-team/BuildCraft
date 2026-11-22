"""Stage 27: production weighted per-world scanner budget & real scan cost.

Compiles the unmodified Java allocator and cost estimator, without Mockito,
Minecraft classes or fake copies of the allocation algorithm. Also checks
actual panel integration on all source-layout families.
"""
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SOURCES = [ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotAdaptiveScanBudget.java',
           ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotScanCostEstimator.java']

FIXTURE = r'''
import buildcraft.robotics.scheduler.*;
import java.util.*;
public final class AdaptiveBudgetFixture {
 static int assertions;
 static void ok(boolean yes,String why){assertions++;if(!yes)throw new AssertionError(why);}
 static RobotAdaptiveScanBudget.Demand demand(int requested,int missing,int waiting,int categories,int dirty,int focused,int cost){
  return new RobotAdaptiveScanBudget.Demand(requested,missing,waiting,categories,dirty,focused,cost);
 }
 static RobotAdaptiveScanBudget.Demand neutral(){return demand(384,0,0,1,0,0,12000);}
 public static void main(String[] args) {
  RobotAdaptiveScanBudget a=new RobotAdaptiveScanBudget();
  a.publish(1,10,neutral());
  ok(a.acquire(1,10,900)==384,"single panel exceeded 384 cap");
  ok(a.acquire(1,10,384)==0,"repeated acquire bypassed cap");
  ok(a.totalThisTick()==384,"single panel total wrong");
  a.refund(1,10,120);
  ok(a.totalThisTick()==264,"refund did not restore world capacity");
  ok(a.acquire(1,10,384)==120,"refunded credits could not be reused by their panel");
  ok(a.totalThisTick()==384,"refund double-spent credits");
  a.remove(10);
  ok(a.acquire(1,10,384)==0,"removed panel still has quota");
  a.publish(2,20,neutral());
  ok(a.acquire(2,20,384)==384,"old panel retained work after unload");
  a.publish(3,20,neutral());
  a.publish(3,21,neutral());
  a.publish(3,22,neutral());
  a.publish(3,23,neutral());
  a.publish(3,24,neutral());
  a.publish(3,25,neutral());
  a.publish(3,26,neutral());
  a.publish(3,27,neutral());
  int total=0,min=Integer.MAX_VALUE,max=0;
  for(int i=20;i<28;i++){
   int got=a.acquire(3,i,384);total+=got;min=Math.min(min,got);max=Math.max(max,got);
  }
  ok(total==2048,"shared limit was not fully utilized with eight hungry panels");
  ok(min==max&&min==256,"equal idle pressures weren't fair");
  ok(a.acquire(3,20,384)==0,"panel could collect other panels' protected budget");
  // More queue pressure must improve a panel's share but never evict low-pressure peers.
  a.beginTick(4);
  for(int i=20;i<28;i++)a.remove(i);
  for(int i=0;i<8;i++)a.publish(4,i,demand(384,i==0?16:0,i==0?4:0,1,0,0,12000));
  int high=a.acquire(4,0,384),low=a.acquire(4,1,384);
  ok(high>low,"missing queues do not influence allocation");
  ok(low>=RobotAdaptiveScanBudget.PROTECTED_SLICE,"quiet panel starved by queue pressure");
  int all=high+low;
  for(int i=2;i<8;i++)all+=a.acquire(4,i,384);
  ok(all==RobotAdaptiveScanBudget.LEVEL_CAP,"weighted allocation lost world credits");
  a.beginTick(5);
  for(int i=0;i<8;i++)a.publish(5,i,demand(384,0,0,1,i==0?12:0,i==0?4:0,12000));
  high=a.acquire(5,0,384);low=a.acquire(5,1,384);
  ok(high>low,"dirty/focused discovery did not get priority");
  a.beginTick(6);
  for(int i=0;i<8;i++)a.publish(6,i,demand(384,0,0,1,0,0,i==0?2000:(i==1?60000:12000)));
  ok(a.acquire(6,0,384)>a.acquire(6,1,384),"measured scan costs ignored");
  // Budgets are fixed on first genuine request; passive probes cannot freeze them.
  a.beginTick(7);
  for(int i=0;i<8;i++)a.remove(i);
  a.publish(7,2,neutral());
  ok(a.acquire(7,2,0)==0,"passive query consumed budget");
  a.publish(7,3,neutral());
  ok(a.acquire(7,2,384)==384&&a.acquire(7,3,384)==384,"probes affected panel registration");
  // One-tick warmup when a panel registers after quotas froze; the next tick it participates.
  a.publish(7,99,neutral());
  ok(a.acquire(7,99,384)==0,"late join stole promised credits");
  a.beginTick(8);
  a.publish(8,2,neutral());
  ok(a.acquire(8,99,384)==384,"late join was starved beyond a tick");
  // Idle/limited-demand panels surrender weight to active panels, and never
  // reserve 384 units just to inspect a couple of dirty blocks.
  a.beginTick(9);
  a.remove(99);
  for(int i=0;i<8;i++)a.publish(9,i,demand(i==0?3:384,0,0,1,0,0,12000));
  ok(a.acquire(9,0,384)==3,"small dirty-only demand inflated into full scan quota");
  total=3;
  for(int i=1;i<8;i++)total+=a.acquire(9,i,384);
  ok(total==2048,"small-demand participant wasted the remaining work budget");
  // 100 panels still all receive nonzero, bounded shares.
  a.beginTick(10);
  for(int i=0;i<100;i++)a.publish(10,i,neutral());
  total=0;min=Integer.MAX_VALUE;
  for(int i=0;i<100;i++){
   int share=a.acquire(10,i,384);total+=share;min=Math.min(min,share);
  }
  ok(total==2048&&min>=1,"high panel density caused starvation or overspend");
  // The impossible case (more panels than credits) must rotate opportunities.
  a.beginTick(11);
  for(int i=0;i<2500;i++)a.publish(11,i,neutral());
  Set<Integer> serviced=new HashSet<>();
  for(long tick=11;tick<=13;tick++){
   a.beginTick(tick);
   for(int i=0;i<2500;i++)a.publish(tick,i,neutral());
   total=0;
   for(int i=0;i<2500;i++){
    int share=a.acquire(tick,i,384);
    total+=share;if(share>0)serviced.add(i);
   }
   ok(total==2048,"oversubscribed world cap violated");
  }
  ok(serviced.size()==2500,"rotating overload permanently starved some panels");
  // rollback/reset must discard registration and old grants.
  a.beginTick(1);
  ok(a.activeParticipants()==0,"time rollback carried stale demand");
  RobotScanCostEstimator estimator=new RobotScanCostEstimator();
  ok(estimator.nanosPerBlock()==12000,"cost baseline changed");
  estimator.sample(1_000_000_000L,0);
  ok(estimator.nanosPerBlock()==12000,"empty sample affected CPU estimate");
  estimator.sample(1_000_000_000L,7);
  ok(estimator.nanosPerBlock()==12000,"underfilled scan affected CPU estimate");
  for(int i=0;i<16;i++)estimator.sample(80_000_000,400);
  ok(estimator.nanosPerBlock()>60000,"expensive block work was not smoothed");
  for(int i=0;i<32;i++)estimator.sample(800_000,400);
  ok(estimator.nanosPerBlock()<15000,"cheap work failed to restore scan allowance");
  estimator.reset();
  ok(estimator.nanosPerBlock()==12000,"world reset kept old scan timings");
  System.out.println("PASS "+assertions+" adaptive scan assertions");
 }
}
'''

class AdaptiveScanBudgetTests(unittest.TestCase):
    def test_real_java_allocator_guarantees_weighted_fairness_and_no_overrun(self):
        with tempfile.TemporaryDirectory(prefix='bc-stage27-budget-') as tmp:
            java=Path(tmp)/'AdaptiveBudgetFixture.java'
            java.write_text(FIXTURE)
            cmd=['javac','--release','17','-d',tmp,*map(str,SOURCES),str(java)]
            compile=subprocess.run(cmd,capture_output=True,text=True,timeout=45)
            self.assertEqual(compile.returncode,0,compile.stderr)
            result=subprocess.run(['java','-cp',tmp,'AdaptiveBudgetFixture'],capture_output=True,text=True,timeout=30)
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertIn('adaptive scan assertions',result.stdout)

    def test_scanner_uses_shared_cost_estimator_and_refunds_unused_palette_budget(self):
        code=(ROOT/'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotProcessorWorkScheduler.java').read_text()
        for fragment in ('state.adaptiveBudget.publish(now, panelId,',
                         'state.budget(now, panelId, requested)',
                         'state.adaptiveBudget.refund(now, panelId, grantedBudget - usedBudget)',
                         'scanCost.sample(System.nanoTime() - scanStarted,',
                         'int maxAttempts = budget + 48',
                         'state.adaptiveBudget.remove(panel.getBlockPos().asLong())',
                         'PER_LEVEL_BLOCK_BUDGET = 2048', 'PER_PANEL_BLOCK_BUDGET = 384'):
            self.assertIn(fragment,code)
        self.assertNotIn('tickFairShare',code)
        self.assertNotIn('remainingBudget = PER_LEVEL_BLOCK_BUDGET',code)
        self.assertNotIn('setChunkForced(',code)

    def test_source_version_materialization_resolves_both_new_classes(self):
        import sys
        sys.path.insert(0,str(ROOT/'scripts'))
        from source_lookup import resolve_target_source, read_materialized_java
        targets=('1.19.2-forge','1.20.1-forge','1.21.1-neoforge','1.21.11-neoforge',
                 '26.1.2-neoforge','26.3-neoforge','26.3-fabric')
        for target in targets:
            with self.subTest(target=target):
                for filename in ('RobotAdaptiveScanBudget.java','RobotScanCostEstimator.java','RobotProcessorWorkScheduler.java'):
                    logical='src/main/java/buildcraft/robotics/scheduler/'+filename
                    resolved=resolve_target_source(target,logical)
                    self.assertTrue(resolved.is_file(),logical)
                    materialized=read_materialized_java(target,logical)
                    self.assertNotIn('//?',materialized)
                    if filename=='RobotProcessorWorkScheduler.java':
                        self.assertIn('adaptiveBudget.acquire',materialized)

if __name__=='__main__':unittest.main()
