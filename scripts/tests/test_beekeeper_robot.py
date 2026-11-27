"""Offline compile-and-execute tests of the real Beekeeper board and hive AI.

These small Java doubles validate cross-version preprocessing, hive safety, yields,
item consumption, failure paths and AI persistence. They are not in-game GameTests.
"""
from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.source_preprocessor import preprocess_text
from scripts.source_layout import effective_source_files, load_properties, target_layout

ROOT = Path(__file__).resolve().parents[2]
BOARD = ROOT / "source-shared/src/main/java/buildcraft/robotics/boards/BoardRobotBeekeeper.java"
AI = ROOT / "source-shared/src/main/java/buildcraft/robotics/ai/AIRobotHarvestBeehive.java"
TARGETS = (
    ("1.19.2", "old", "forge"), ("1.20.1", "old", "forge"),
    ("1.21.1", "1.21.X", "neoforge"), ("1.21.11", "1.21.X", "neoforge"),
    ("26.1.2", "26.X", "neoforge"), ("26.2", "26.X", "neoforge"),
    ("26.3", "26.X", "neoforge"), ("26.3", "26.X", "fabric"),
)
STUBS = {
    "net/minecraft/core/BlockPos.java": """package net.minecraft.core;
public record BlockPos(int x,int y,int z) {}""",
    "net/minecraft/core/Direction.java": """package net.minecraft.core;
public enum Direction {UP}""",
    "net/minecraft/nbt/CompoundTag.java": """package net.minecraft.nbt;
import java.util.*;public class CompoundTag{
 private final Map<String,Object> data=new HashMap<>();
 public void put(String key,CompoundTag tag){data.put(key,tag);}
 public void putInt(String key,int value){data.put(key,value);}
 public int getInt(String key){return (Integer)data.getOrDefault(key,0);}
 public boolean contains(String key){return data.containsKey(key);}
 public CompoundTag getCompound(String key){return (CompoundTag)data.get(key);}
}""",
    "net/minecraft/world/item/Item.java": """package net.minecraft.world.item;public class Item{}""",
    "net/minecraft/world/item/ShearsItem.java": """package net.minecraft.world.item;public class ShearsItem extends Item{}""",
    "net/minecraft/world/item/Items.java": """package net.minecraft.world.item;
public class Items{public static final Item GLASS_BOTTLE=new Item(),HONEY_BOTTLE=new Item(),HONEYCOMB=new Item();
public static final ShearsItem SHEARS=new ShearsItem();}""",
    "net/minecraft/world/item/ItemStack.java": """package net.minecraft.world.item;
import net.minecraft.world.entity.*;import net.minecraft.world.entity.player.Player;import net.minecraft.server.level.ServerPlayer;
public class ItemStack{
 public static final ItemStack EMPTY=new ItemStack(null,0);private final Item item;private int count,damage;
 public ItemStack(Item item){this(item,1);}public ItemStack(Item item,int count){this.item=item;this.count=count;}
 public Item getItem(){return item;}public int getCount(){return count;}public int getDamageValue(){return damage;}
 public int getMaxDamage(){return 2;}public boolean isDamageableItem(){return item instanceof ShearsItem;}
 public boolean is(Item other){return item==other&&count>0;}public boolean isEmpty(){return item==null||count<=0;}
 public void shrink(int amount){count=Math.max(0,count-amount);}
 public void hurtAndBreak(int n,Player player,EquipmentSlot slot){damage+=n;if(damage>=2)shrink(1);}
 public boolean hurt(int n,java.util.Random random,ServerPlayer player){damage+=n;return damage>=2;}
}""",
    "net/minecraft/world/entity/EquipmentSlot.java": """package net.minecraft.world.entity;public enum EquipmentSlot{MAINHAND}""",
    "net/minecraft/world/entity/player/Player.java": """package net.minecraft.world.entity.player;public class Player{}""",
    "net/minecraft/server/level/ServerPlayer.java": """package net.minecraft.server.level;
public class ServerPlayer extends net.minecraft.world.entity.player.Player{}""",
    "net/minecraft/sounds/SoundSource.java": """package net.minecraft.sounds;public enum SoundSource{BLOCKS}""",
    "net/minecraft/sounds/SoundEvents.java": """package net.minecraft.sounds;
public class SoundEvents{public static final Object BEEHIVE_SHEAR=new Object(),BOTTLE_FILL=new Object();}""",
    "net/minecraft/world/level/block/Block.java": """package net.minecraft.world.level.block;public class Block{}""",
    "net/minecraft/world/level/block/BeehiveBlock.java": """package net.minecraft.world.level.block;
public class BeehiveBlock extends Block{public static final Object HONEY_LEVEL=new Object();}""",
    "net/minecraft/world/level/block/CampfireBlock.java": """package net.minecraft.world.level.block;
import net.minecraft.world.level.Level;import net.minecraft.core.BlockPos;
public class CampfireBlock {public static boolean isSmokeyPos(Level level,BlockPos pos){return level.smoke;}}""",
    "net/minecraft/world/level/block/state/BlockState.java": """package net.minecraft.world.level.block.state;
import net.minecraft.world.level.block.*;
public class BlockState{
 private Block block;public int honey;
 public BlockState(Block block,int honey){this.block=block;this.honey=honey;}
 public Block getBlock(){return block;}
 public boolean hasProperty(Object property){return block instanceof BeehiveBlock&&property==BeehiveBlock.HONEY_LEVEL;}
 public int getValue(Object property){return honey;}
 public BlockState setValue(Object property,int value){return new BlockState(block,value);}
}""",
    "net/minecraft/world/level/Level.java": """package net.minecraft.world.level;
import net.minecraft.core.BlockPos;import net.minecraft.world.level.block.state.BlockState;
public class Level {public boolean loaded=true,smoke=true;public BlockState state;
 public boolean isLoaded(BlockPos pos){return loaded;}
 public BlockState getBlockState(BlockPos pos){return state;}
}""",
    "net/minecraft/server/level/ServerLevel.java": """package net.minecraft.server.level;
import net.minecraft.world.level.Level;import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;import java.util.*;
public class ServerLevel extends Level {
 public final List<ItemStack> drops=new ArrayList<>();public int sounds;public boolean changeAllowed=true;
 public boolean hasChunkAt(BlockPos pos){return loaded;}
 public boolean setBlock(BlockPos pos,BlockState next,int flags){if(!changeAllowed)return false;state=next;return true;}
 public void playSound(Object player,BlockPos pos,Object sound,SoundSource source,float volume,float pitch){sounds++;}
 public java.util.Random getRandom(){return new java.util.Random(1);}
}""",
    "buildcraft/lib/internal/core/BlockIndex.java": """package buildcraft.lib.internal.core;
import net.minecraft.core.BlockPos;import net.minecraft.nbt.CompoundTag;
public class BlockIndex{private final BlockPos pos;
public BlockIndex(BlockPos pos){this.pos=pos;}public BlockIndex(CompoundTag tag){this.pos=new BlockPos(tag.getInt("x"),tag.getInt("y"),tag.getInt("z"));}
public BlockPos toBlockPos(){return pos;}
public void writeTo(CompoundTag nbt){nbt.putInt("x",pos.x());nbt.putInt("y",pos.y());nbt.putInt("z",pos.z());}}
""",
    "buildcraft/lib/internal/core/IStackFilter.java": """package buildcraft.lib.internal.core;
public interface IStackFilter{boolean matches(net.minecraft.world.item.ItemStack stack);}""",
    "buildcraft/lib/misc/BlockUtil.java": """package buildcraft.lib.misc;
import net.minecraft.server.level.ServerLevel;import net.minecraft.core.BlockPos;import net.minecraft.world.item.ItemStack;
public class BlockUtil{public static void dropItem(ServerLevel level,BlockPos pos,int lifetime,ItemStack stack){level.drops.add(stack);}}""",
    "buildcraft/lib/misc/FakePlayerProvider.java": """package buildcraft.lib.misc;
import net.minecraft.server.level.ServerLevel;import net.minecraft.core.BlockPos;import net.minecraft.world.entity.player.Player;
public class FakePlayerProvider{public static final FakePlayerProvider INSTANCE=new FakePlayerProvider();
public Player getFakePlayer(ServerLevel level,Object owner,BlockPos pos){return new Player();}}""",
    "buildcraft/api/v2/OperationMode.java": """package buildcraft.api.v2;public enum OperationMode{EXECUTE,SIMULATE}""",
    "buildcraft/api/v2/permission/WorldOperationKind.java": """package buildcraft.api.v2.permission;public enum WorldOperationKind{ITEM_USE}""",
    "buildcraft/api/v2/automation/UseItemRequest.java": """package buildcraft.api.v2.automation;
import net.minecraft.world.level.Level;import net.minecraft.core.*;import net.minecraft.world.item.ItemStack;import buildcraft.api.v2.OperationMode;
public record UseItemRequest(Level level,BlockPos origin,BlockPos target,Direction side,ItemStack stack,Object actor,OperationMode mode){}""",
    "buildcraft/robotics/internal/legacy/robots/AIRobot.java": """package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.nbt.CompoundTag;
public class AIRobot{
 protected final EntityRobotBase robot;public AIRobot delegate;private boolean success=true,finished=false;
 public AIRobot(EntityRobotBase r){robot=r;}public void update(){}public void start(){}public void end(){}
 public void delegateAIEnded(AIRobot ai){}public void startDelegateAI(AIRobot ai){delegate=ai;}
 public void terminate(){finished=true;end();}public boolean finished(){return finished;}
 public boolean success(){return success;}protected void setSuccess(boolean ok){success=ok;}
 public int getEnergyCost(){return 0;}public boolean canLoadFromNBT(){return false;}
 public void writeSelfToNBT(CompoundTag tag){}public void loadSelfFromNBT(CompoundTag tag){}
}""",
    "buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java": """package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.server.level.ServerLevel;import net.minecraft.world.entity.*;import net.minecraft.world.item.*;import net.minecraft.core.BlockPos;
public class EntityRobotBase{
 public final ServerLevel level;public ItemStack hand=ItemStack.EMPTY;public boolean active;
 public EntityRobotBase(ServerLevel level){this.level=level;}
 public net.minecraft.world.level.Level level(){return level;}public net.minecraft.world.level.Level getCommandSenderWorld(){return level;}public Object getLinkedStation(){return new Object();}
 public ItemStack getItemBySlot(EquipmentSlot slot){return hand;}
 public void setItemInUse(ItemStack item){hand=item;}public void setItemActive(boolean active){this.active=active;}
 public void aimItemAt(BlockPos pos){}public BlockPos blockPosition(){return new BlockPos(0,64,0);}
}""",
    "buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobotNBT.java": """package buildcraft.robotics.internal.legacy.boards;public class RedstoneBoardRobotNBT{}""",
    "buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobot.java": """package buildcraft.robotics.internal.legacy.boards;
import buildcraft.robotics.internal.legacy.robots.*;
public class RedstoneBoardRobot extends AIRobot{public RedstoneBoardRobot(EntityRobotBase r){super(r);}
public RedstoneBoardRobotNBT getNBTHandler(){return new RedstoneBoardRobotNBT();}}""",
    "buildcraft/robotics/boards/BoardRobotGenericSearchBlock.java": """package buildcraft.robotics.boards;
import buildcraft.robotics.internal.legacy.boards.*;import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.lib.internal.core.BlockIndex;import net.minecraft.world.level.Level;import net.minecraft.core.BlockPos;
public class BoardRobotGenericSearchBlock extends RedstoneBoardRobot{
 protected BlockIndex found;public BoardRobotGenericSearchBlock(EntityRobotBase r){super(r);}
 public BlockIndex blockFound(){return found;}protected void releaseBlockFound(boolean success){found=null;}
 public boolean isExpectedBlock(Level level,BlockPos pos){return false;}
}""",
    "buildcraft/robotics/boards/RobotBoardUtil.java": """package buildcraft.robotics.boards;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;import net.minecraft.world.item.ItemStack;
public class RobotBoardUtil{public static boolean dropHeldItem(EntityRobotBase robot){robot.hand=ItemStack.EMPTY;return true;}}""",
    "buildcraft/robotics/BCRoboticsBoards.java": """package buildcraft.robotics;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
public class BCRoboticsBoards{public record Entry(RedstoneBoardRobotNBT nbt){}
 public static Entry getByKey(String key){return new Entry(new RedstoneBoardRobotNBT());}}""",
    "buildcraft/robotics/ai/AIRobotGotoSleep.java": """package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
public class AIRobotGotoSleep extends AIRobot{public AIRobotGotoSleep(EntityRobotBase r){super(r);}}""",
    "buildcraft/robotics/ai/AIRobotFetchAndEquipItemStack.java": """package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;import buildcraft.lib.internal.core.IStackFilter;
public class AIRobotFetchAndEquipItemStack extends AIRobot{
 public final int maxCount;@Override public boolean success(){return false;}public AIRobotFetchAndEquipItemStack(EntityRobotBase r,IStackFilter filter){this(r,filter,1);}
 public AIRobotFetchAndEquipItemStack(EntityRobotBase r,IStackFilter filter,int max){super(r);maxCount=max;}
}""",
    "buildcraft/robotics/scheduler/RobotProcessorWorkScheduler.java": """package buildcraft.robotics.scheduler;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;import net.minecraft.core.BlockPos;
public class RobotProcessorWorkScheduler{public static boolean allowed=true;
 public static boolean allowsRobotTarget(EntityRobotBase r,BlockPos pos){return allowed;}
 public static void executionFinished(EntityRobotBase r,BlockPos pos,boolean success){}}""",
    "buildcraft/robotics/internal/api2/RobotAutomationSupport.java": """package buildcraft.robotics.internal.api2;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;import buildcraft.api.v2.*;
import buildcraft.api.v2.automation.UseItemRequest;import buildcraft.api.v2.permission.WorldOperationKind;
import net.minecraft.core.BlockPos;import net.minecraft.server.level.ServerLevel;
public class RobotAutomationSupport{public static boolean allowed=true;
public static boolean permits(UseItemRequest req){return allowed;}
public static boolean permitsBlock(EntityRobotBase robot,ServerLevel level,BlockPos pos,WorldOperationKind kind,OperationMode mode){return allowed;}
public static Object owner(EntityRobotBase robot){return new Object();}public static Object actor(EntityRobotBase robot){return new Object();}
}""",
}
HARNESS = """import buildcraft.robotics.ai.AIRobotHarvestBeehive;
import buildcraft.robotics.boards.BoardRobotBeekeeper;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.lib.internal.core.BlockIndex;
import net.minecraft.core.BlockPos;import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;import net.minecraft.world.level.block.state.BlockState;
public class BeekeeperExercise{
 static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
 static final BlockPos HIVE=new BlockPos(2,64,2);
 static ServerLevel hive(int honey,boolean smoke){ServerLevel lvl=new ServerLevel();lvl.state=new BlockState(new BeehiveBlock(),honey);lvl.smoke=smoke;return lvl;}
 static AIRobotHarvestBeehive harvest(EntityRobotBase robot){AIRobotHarvestBeehive ai=new AIRobotHarvestBeehive(robot,new BlockIndex(HIVE));ai.start();
 for(int n=0;n<14 && !ai.finished();n++)ai.update();return ai;}
 public static void main(String[] args){
 ServerLevel level=hive(5,true);EntityRobotBase robot=new EntityRobotBase(level);robot.hand=new ItemStack(Items.GLASS_BOTTLE,3);
 check(BoardRobotBeekeeper.isHarvestable(level,HIVE),"ready full smoked hive should be valid");
 AIRobotHarvestBeehive ai=harvest(robot);
 check(ai.finished()&&ai.success(),"bottle harvest failed");
 check(level.state.honey==0,"honey was not reset");
 check(robot.hand.getCount()==2,"bottle count wrong");
 check(level.drops.size()==1 && level.drops.get(0).is(Items.HONEY_BOTTLE),"honey bottle output wrong");
 check(!robot.active && level.sounds==1,"animation/sound not cleaned");
 level=hive(5,true);robot=new EntityRobotBase(level);robot.hand=new ItemStack(Items.SHEARS);
 ai=harvest(robot);
 check(ai.finished()&&ai.success()&&robot.hand.getDamageValue()==1,"shears durability wrong");
 check(level.drops.size()==1&&level.drops.get(0).is(Items.HONEYCOMB)&&level.drops.get(0).getCount()==3,"comb yield wrong");
 level=hive(5,true);robot=new EntityRobotBase(level);robot.hand=new ItemStack(Items.SHEARS);
 harvest(robot);level.state=new BlockState(new BeehiveBlock(),5);harvest(robot);
 check(robot.hand.isEmpty(),"broken shears must be consumed");
 level=hive(4,true);robot=new EntityRobotBase(level);robot.hand=new ItemStack(Items.GLASS_BOTTLE);
 check(!BoardRobotBeekeeper.isHarvestable(level,HIVE),"immature hive accepted");
 ai=harvest(robot);check(!ai.success()&&level.drops.isEmpty()&&robot.hand.getCount()==1&&level.state.honey==4,"immature hive modified");
 level=hive(5,false);robot=new EntityRobotBase(level);robot.hand=new ItemStack(Items.GLASS_BOTTLE);
 check(!BoardRobotBeekeeper.isHarvestable(level,HIVE),"unsmoked hive accepted");
 ai=harvest(robot);check(!ai.success()&&level.drops.isEmpty()&&level.state.honey==5,"unsafe hive modified");
 level=hive(5,true);robot=new EntityRobotBase(level);robot.hand=new ItemStack(Items.GLASS_BOTTLE);
 RobotAutomationSupport.allowed=false;ai=harvest(robot);RobotAutomationSupport.allowed=true;
 check(!ai.success()&&level.state.honey==5&&level.drops.isEmpty(),"protected hive modified");
 level=hive(5,true);level.changeAllowed=false;robot=new EntityRobotBase(level);robot.hand=new ItemStack(Items.GLASS_BOTTLE);
 ai=harvest(robot);check(!ai.success()&&level.drops.isEmpty(),"failed block write still produced items");
 level=hive(5,true);robot=new EntityRobotBase(level);robot.hand=new ItemStack(Items.GLASS_BOTTLE);
 ai=new AIRobotHarvestBeehive(robot,new BlockIndex(HIVE));ai.start();for(int k=0;k<6;k++)ai.update();
 CompoundTag tag=new CompoundTag();ai.writeSelfToNBT(tag);AIRobotHarvestBeehive next=new AIRobotHarvestBeehive(robot);next.loadSelfFromNBT(tag);
 for(int k=0;k<7;k++)next.update();check(next.finished()&&next.success()&&level.drops.size()==1,"hive AI did not resume after NBT load");
 level=hive(5,true);robot=new EntityRobotBase(level);robot.hand=ItemStack.EMPTY;
 BoardRobotBeekeeper board=new BoardRobotBeekeeper(robot);board.update();
 check(board.delegate instanceof buildcraft.robotics.ai.AIRobotFetchAndEquipItemStack,"bottle fetch missing");
 check(((buildcraft.robotics.ai.AIRobotFetchAndEquipItemStack)board.delegate).maxCount==16,"bottle batch size wrong");
 board.delegateAIEnded(board.delegate);check(board.delegate instanceof buildcraft.robotics.ai.AIRobotFetchAndEquipItemStack,"shears fallback missing");
 check(((buildcraft.robotics.ai.AIRobotFetchAndEquipItemStack)board.delegate).maxCount==1,"shears must be fetched singly");
 System.out.println("PASS: Beekeeper Java hive and board behavior");
 }
}"""


class BeekeeperRobotTests(unittest.TestCase):
    def test_real_java_harvesting_on_each_target(self):
        for version, family, platform in TARGETS:
            with self.subTest(version=version, platform=platform), tempfile.TemporaryDirectory(prefix="bc-beekeeper-") as tmp:
                root = Path(tmp)
                files = []
                sources = dict(STUBS)
                for source in (BOARD, AI):
                    logical = source.relative_to(ROOT).as_posix().split("src/main/java/", 1)[1]
                    sources[logical] = preprocess_text(source.read_text(encoding="utf-8"),
                          minecraft=version, family=family, platform=platform, source=str(source))
                sources["BeekeeperExercise.java"] = HARNESS
                for relative, contents in sources.items():
                    path = root / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(contents, encoding="utf-8")
                    files.append(str(path))
                result = subprocess.run(["javac", "--release", "17", "-d", str(root / "classes"), *files],
                                        text=True, capture_output=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stderr)
                run = subprocess.run(["java", "-cp", str(root / "classes"), "BeekeeperExercise"],
                                     text=True, capture_output=True, timeout=15)
                self.assertEqual(run.returncode, 0, run.stdout + "\n" + run.stderr)
                self.assertIn("PASS:", run.stdout)

    def test_registration_and_resources(self):
        for family in ("old", "1.21.X"):
            board = (ROOT / f"source-families/{family}/src/main/java/buildcraft/robotics/BCRoboticsBoards.java").read_text()
            self.assertIn('board("buildcraft:boardRobotBeekeeper", "beekeeper"', board)
            self.assertIn('return new BoardRobotBeekeeper(robot);', board)
        for target in ("1.19.2-forge", "1.20.1-forge"):
            r = ROOT / f"version-src/{target}/src/main/java/buildcraft/robotics/BCRobotics.java"
            self.assertIn('"boardBeekeeper"', r.read_text())
            self.assertIn('"harvestBeehive"', r.read_text())
        for family, directory in (("old", "recipes"), ("1.21.X", "recipe"), ("26.X", "recipe")):
            path = ROOT / f"source-families/{family}/src/main/resources/data/buildcraftrobotics/{directory}/programming_table/beekeeper.json"
            self.assertEqual(json.loads(path.read_text())["energy"]["mj"], 3200)
        for suffix in ("", "_active"):
            self.assertTrue((ROOT / f"source-shared/src/main/resources/assets/buildcraftrobotics/textures/entities/robot_beekeeper{suffix}.png").exists())
        model = json.loads((ROOT / "source-shared/src/main/resources/assets/buildcraftrobotics/models/item/robot.json").read_text())
        powered = [e for e in model["overrides"] if "buildcraftrobotics:robot" in e["predicate"] and e["predicate"].get("buildcraftrobotics:robot_power") == 1]
        self.assertEqual(20, len(powered))
        self.assertEqual("buildcraftrobotics:item/robot/fisher", powered[-2]["model"])
        self.assertEqual(0.19, powered[-2]["predicate"]["buildcraftrobotics:robot"])
        self.assertEqual("buildcraftrobotics:item/robot/beekeeper", powered[-1]["model"])
        self.assertEqual(0.20, powered[-1]["predicate"]["buildcraftrobotics:robot"])
        for asset in ("resource-src/1.21.X/1.21.11/assets/buildcraftrobotics/items/robot.json",
                      "source-families/26.X/src/main/resources/assets/buildcraftrobotics/items/robot.json"):
            modern = json.loads((ROOT / asset).read_text())
            self.assertEqual("buildcraftrobotics:item/robot/fisher", modern["model"]["entries"][-2]["model"]["entries"][0]["model"]["model"])
            self.assertEqual(0.19, modern["model"]["entries"][-2]["threshold"])
            self.assertEqual("buildcraftrobotics:item/robot/beekeeper", modern["model"]["entries"][-1]["model"]["entries"][0]["model"]["model"])
            self.assertEqual(0.20, modern["model"]["entries"][-1]["threshold"])
        lang = json.loads((ROOT / "source-shared/src/main/resources/assets/buildcraft/lang/en_us.json").read_text())
        self.assertIn("Beekeeper Robot", lang["item.buildcraftrobotics.robot.beekeeper"])
        self.assertIn("requires campfire smoke", lang["buildcraft.boardRobotBeekeeper.desc"])
        self.assertEqual("Beekeeper", lang["buildcraft.boardRobot.beekeeper"])
        guide = json.loads((ROOT / "source-shared/src/main/resources/assets/buildcraft/guide/original_manifest.json").read_text())
        self.assertEqual(1, sum(1 for e in guide["entries"] if e.get("id") == "buildcraftrobotics:robot/beekeeper"))

        # Check which source file the multilayer build actually selects for every target.
        props = load_properties()
        for version, family, platform in TARGETS:
            target = f"{version}-{platform}"
            with self.subTest(selected_sources=target):
                selected = effective_source_files(target_layout(target, props), props)
                prefix = "src/main/java/buildcraft/robotics/"
                register = selected[prefix + "BCRobotics.java"].read_text(encoding="utf-8")
                board_registry = selected[prefix + "BCRoboticsBoards.java"].read_text(encoding="utf-8")
                self.assertEqual(1, register.count('"boardBeekeeper"'))
                self.assertEqual(1, register.count('"harvestBeehive"'))
                self.assertIn('board("buildcraft:boardRobotBeekeeper", "beekeeper"', board_registry)
                self.assertIn(prefix + "ai/AIRobotHarvestBeehive.java", selected)
                self.assertIn(prefix + "boards/BoardRobotBeekeeper.java", selected)
                self.assertIn("src/main/resources/assets/buildcraft/lang/en_us.json", selected)


if __name__ == "__main__":
    unittest.main()
