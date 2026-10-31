"""Compile the real StripesRegistry with minimal Java API stubs and exercise statuses."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / 'source-shared/src/main/java/buildcraft/transport/pipe/StripesRegistry.java'

STUBS = {
    'buildcraft/api/v2/BuildCraftApi.java': '''
        package buildcraft.api.v2;
        import java.util.*;
        import buildcraft.api.v2.automation.StripesHandler;
        public class BuildCraftApi {
          public static final Registry REGISTRY = new Registry();
          public static Registry registry(Object key) { return REGISTRY; }
          public static class Registry {
            public final List<StripesHandler> handlers=new ArrayList<>();
            public List<StripesHandler> values() { return handlers; }
          }
        }
    ''',
    'buildcraft/api/v2/BuildCraftRegistries.java': '''
        package buildcraft.api.v2;
        public class BuildCraftRegistries { public static final Object STRIPES_HANDLERS=new Object(); }
    ''',
    'buildcraft/api/v2/OperationMode.java': '''
        package buildcraft.api.v2;
        public enum OperationMode { SIMULATE, EXECUTE }
    ''',
    'buildcraft/api/v2/automation/AutomationResult.java': '''
        package buildcraft.api.v2.automation;
        public record AutomationResult(Status status) {
          public enum Status { PASS,SUCCESS,DENIED,RETRY,FAILED }
        }
    ''',
    'buildcraft/api/v2/automation/StripesHandler.java': '''
        package buildcraft.api.v2.automation;
        public interface StripesHandler {
          AutomationResult activate(StripesContext context);
          default int priority() {return 0;}
        }
    ''',
    'buildcraft/api/v2/automation/StripesOutput.java': '''
        package buildcraft.api.v2.automation;
        public interface StripesOutput { }
    ''',
    'buildcraft/api/v2/automation/StripesContext.java': '''
        package buildcraft.api.v2.automation;
        import buildcraft.api.v2.OperationMode;
        import buildcraft.api.v2.permission.AutomationActor;
        import net.minecraft.core.*;
        import net.minecraft.world.entity.player.Player;
        import net.minecraft.world.item.ItemStack;
        import net.minecraft.world.level.Level;
        public class StripesContext {
          private ItemStack stack;
          public StripesContext(Level level,BlockPos pos,Direction side,ItemStack stack,
             AutomationActor actor,OperationMode mode,Player player,StripesOutput output) {
            this.stack=stack;
          }
          public ItemStack stack() {return stack;}
          public void replaceStack(ItemStack replacement) {this.stack=replacement;}
        }
    ''',
    'buildcraft/api/v2/permission/AutomationActor.java': '''
        package buildcraft.api.v2.permission;
        import java.util.UUID;
        public class AutomationActor {
          public static AutomationActor unknown() {return new AutomationActor();}
          public static AutomationActor player(UUID id,String name) {return new AutomationActor();}
        }
    ''',
    'net/minecraft/core/BlockPos.java': 'package net.minecraft.core; public record BlockPos(int x,int y,int z) {}',
    'net/minecraft/core/Direction.java': 'package net.minecraft.core; public enum Direction { NORTH }',
    'net/minecraft/world/level/Level.java': 'package net.minecraft.world.level; public class Level {}',
    'net/minecraft/world/item/ItemStack.java': '''
        package net.minecraft.world.item;
        public class ItemStack {
          public static final ItemStack EMPTY=new ItemStack("empty",true);
          public final String id;
          private final boolean empty;
          public ItemStack(String id) {this(id,false);}
          private ItemStack(String id,boolean empty){this.id=id;this.empty=empty;}
          public boolean isEmpty(){return empty;}
        }
    ''',
    'net/minecraft/world/entity/player/Player.java': '''
        package net.minecraft.world.entity.player;
        import java.util.UUID;
        import net.minecraft.world.item.ItemStack;
        public class Player {
          private final Inventory inventory=new Inventory();
          public UUID getUUID(){return new UUID(0,1);}
          public Profile getGameProfile(){return new Profile();}
          public Inventory getInventory(){return inventory;}
          public static class Profile {public String getName(){return "test";}}
          public static class Inventory {
            public int selected;
            public ItemStack stored=new ItemStack("original");
            public void setItem(int index,ItemStack stack){stored=stack;}
          }
        }
    ''',
    'buildcraft/transport/pipe/StripesFixture.java': '''
        package buildcraft.transport.pipe;
        import buildcraft.api.v2.BuildCraftApi;
        import buildcraft.api.v2.automation.*;
        import net.minecraft.core.*;
        import net.minecraft.world.entity.player.Player;
        import net.minecraft.world.item.ItemStack;
        import net.minecraft.world.level.Level;
        import java.util.concurrent.atomic.AtomicInteger;
        public class StripesFixture {
          static final Level world=new Level();
          static final BlockPos pos=new BlockPos(1,2,3);
          static final StripesOutput output=new StripesOutput(){};
          static void check(boolean condition,String message){
            if(!condition)throw new AssertionError(message);
          }
          static boolean perform(Player player){
            return StripesRegistry.INSTANCE.handleItem(world,pos,Direction.NORTH,new ItemStack("original"),player,output);
          }
          static void rejected(AutomationResult.Status status){
            BuildCraftApi.REGISTRY.handlers.clear();
            AtomicInteger second=new AtomicInteger(0);
            BuildCraftApi.REGISTRY.handlers.add(c->{
              c.replaceStack(new ItemStack("rejected_mutation"));
              return new AutomationResult(status);
            });
            BuildCraftApi.REGISTRY.handlers.add(c->{
              second.incrementAndGet();
              return new AutomationResult(AutomationResult.Status.SUCCESS);
            });
            Player player=new Player();
            check(!perform(player),status+" must not succeed");
            check(second.get()==0,status+" must stop the handler chain");
            check(player.getInventory().stored.id.equals("original"),status+" must preserve inventory");
          }
          public static void main(String[] args){
            for(var status:new AutomationResult.Status[]{
                 AutomationResult.Status.DENIED,AutomationResult.Status.FAILED,
                 AutomationResult.Status.RETRY})rejected(status);
            BuildCraftApi.REGISTRY.handlers.clear();
            AtomicInteger passCount=new AtomicInteger();
            BuildCraftApi.REGISTRY.handlers.add(c->{
              passCount.incrementAndGet();
              return new AutomationResult(AutomationResult.Status.PASS);
            });
            BuildCraftApi.REGISTRY.handlers.add(c->{
              c.replaceStack(new ItemStack("committed"));
              return new AutomationResult(AutomationResult.Status.SUCCESS);
            });
            Player player=new Player();
            check(perform(player),"SUCCESS must succeed after PASS");
            check(passCount.get()==1,"PASS must continue");
            check(player.getInventory().stored.id.equals("committed"),"SUCCESS must commit changes");
            BuildCraftApi.REGISTRY.handlers.clear();
            BuildCraftApi.REGISTRY.handlers.add(c->new AutomationResult(AutomationResult.Status.PASS));
            check(!perform(new Player()),"PASS-only must not succeed");
            System.out.println("All Stripes execution status checks passed");
          }
        }
    ''',
}


class RobotStripesRuntime(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'Java JDK is unavailable')
    def test_handler_chain_and_inventory_transaction(self):
        self.assertTrue(REGISTRY.exists())
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            sources = [REGISTRY]
            for name, content in STUBS.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding='utf-8')
                sources.append(path)
            classes = root / 'classes'
            classes.mkdir()
            compile_result = subprocess.run(
                ['javac', '-encoding', 'UTF-8', '-d', str(classes), *(str(s) for s in sources)],
                capture_output=True, text=True, timeout=40,
            )
            self.assertEqual(compile_result.returncode, 0, compile_result.stderr)
            run_result = subprocess.run(
                ['java', '-cp', str(classes), 'buildcraft.transport.pipe.StripesFixture'],
                capture_output=True, text=True, timeout=20,
            )
            self.assertEqual(run_result.returncode, 0, run_result.stderr)
            self.assertIn('All Stripes execution status checks passed', run_result.stdout)


if __name__ == '__main__':
    unittest.main()
