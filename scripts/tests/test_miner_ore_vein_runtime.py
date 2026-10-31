#!/usr/bin/env python3
"""Compile real Forge/NeoForge miner boards and shared ore-vein memory against focused MC stubs."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_preprocessor import preprocess_text

VEIN = ROOT / "source-shared/src/main/java/buildcraft/robotics/boards/RobotOreVeinMemory.java"
BOARDS = {
    "forge": ROOT / "source-platforms/forge/src/main/java/buildcraft/robotics/boards/BoardRobotMiner.java",
    "neoforge-1.21.1": ROOT / "source-platforms/neoforge/src/main/java/buildcraft/robotics/boards/BoardRobotMiner.java",
    "neoforge-1.21.11": ROOT / "source-platforms/neoforge/src/main/java/buildcraft/robotics/boards/BoardRobotMiner.java",
    "neoforge-26.2": ROOT / "source-platforms/neoforge/src/main/java/buildcraft/robotics/boards/BoardRobotMiner.java",
}

STUBS = {
    "net/minecraft/core/BlockPos.java": '''package net.minecraft.core;
public final class BlockPos {
 public final int x,y,z;
 public BlockPos(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
 public int getX(){return x;} public int getY(){return y;}public int getZ(){return z;}
 public BlockPos offset(int dx,int dy,int dz){return new BlockPos(x+dx,y+dy,z+dz);}
 public BlockPos immutable(){return this;}
 public long asLong(){return ((long)x & 0x3ffffffL)<<38 | ((long)z & 0x3ffffffL)<<12 | ((long)y & 0xfffL);}
 public static BlockPos of(long p){return new BlockPos((int)(p>>38),(int)(p<<52>>52),(int)(p<<26>>38));}
 @Override public boolean equals(Object o){return o instanceof BlockPos b&&x==b.x&&y==b.y&&z==b.z;}
 @Override public int hashCode(){return java.util.Objects.hash(x,y,z);}
 public String toString(){return x+","+y+","+z;}
}''',
    "net/minecraft/nbt/Tag.java": '''package net.minecraft.nbt;
public abstract class Tag {}''',
    "net/minecraft/nbt/LongArrayTag.java": '''package net.minecraft.nbt;
public final class LongArrayTag extends Tag {
 private final long[] data;
 public LongArrayTag(long[] source){data=source.clone();}
 public long[] getAsLongArray(){return data.clone();}
}''',
    "net/minecraft/nbt/CompoundTag.java": '''package net.minecraft.nbt;
import java.util.*;
public final class CompoundTag {
 final Map<String,Object> values=new HashMap<>();
 public void put(String k,CompoundTag v){values.put(k,v);}
 public boolean contains(String k){return values.containsKey(k);}
 public void putLong(String k,long v){values.put(k,v);}
 public long getLong(String k){return (long)values.getOrDefault(k,0L);}
 public void putString(String k,String v){values.put(k,v);}
 public String getString(String k){return (String)values.getOrDefault(k,"");}
 public void putLongArray(String k,long[] v){values.put(k,new LongArrayTag(v));}
 public Tag get(String k){return values.get(k) instanceof Tag tag?tag:null;}
 public long[] getLongArray(String k){return values.get(k) instanceof LongArrayTag t?t.getAsLongArray():new long[0];}
 public CompoundTag getCompound(String k){return (CompoundTag)values.getOrDefault(k,new CompoundTag());}
}''',
    "net/minecraft/tags/TagKey.java": "package net.minecraft.tags; public record TagKey<T>(String id){}",
    "net/minecraft/tags/BlockTags.java": '''package net.minecraft.tags;
import net.minecraft.world.level.block.Block;
public final class BlockTags {
 public static final TagKey<Block> COAL_ORES=new TagKey<>("coal");
 public static final TagKey<Block> COPPER_ORES=new TagKey<>("copper");
 public static final TagKey<Block> IRON_ORES=new TagKey<>("iron");
 public static final TagKey<Block> GOLD_ORES=new TagKey<>("gold");
 public static final TagKey<Block> REDSTONE_ORES=new TagKey<>("redstone");
 public static final TagKey<Block> EMERALD_ORES=new TagKey<>("emerald");
 public static final TagKey<Block> LAPIS_ORES=new TagKey<>("lapis");
 public static final TagKey<Block> DIAMOND_ORES=new TagKey<>("diamond");
}''',
    "net/minecraft/world/level/block/Block.java": '''package net.minecraft.world.level.block;
public final class Block {
 private final String name;public Block(String name){this.name=name;}
 public String getDescriptionId(){return "block."+name;}
}''',
    "net/minecraft/world/level/block/state/BlockState.java": '''package net.minecraft.world.level.block.state;
import java.util.*;import net.minecraft.tags.*;import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;import net.minecraft.world.level.block.Block;
public final class BlockState {
 public static final BlockState AIR=new BlockState(new Block("air"),true);
 private final Block block;private final boolean air;
 private final Set<TagKey<Block>> tags=new HashSet<>();
 public BlockState(Block block){this(block,false);}
 private BlockState(Block b,boolean a){block=b;air=a;}
 @SafeVarargs public final BlockState tags(TagKey<Block>... t){tags.addAll(Arrays.asList(t));return this;}
 public boolean is(TagKey<Block> t){return tags.contains(t);}
 public boolean isAir(){return air;}
 public float getDestroySpeed(Level level,BlockPos pos){return air?0:1;}
 public Block getBlock(){return block;}
}''',
    "net/minecraft/world/level/Level.java": '''package net.minecraft.world.level;
import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.world.level.block.state.BlockState;
public final class Level {
 private final Map<BlockPos,BlockState> blocks=new HashMap<>();
 private final Set<Integer> unloadedXChunks=new HashSet<>();
 public boolean isLoaded(BlockPos p){return !unloadedXChunks.contains(p.getX()>>4);}
 public void unloadXChunk(int x){unloadedXChunks.add(x);}public void loadXChunk(int x){unloadedXChunks.remove(x);}
 public BlockState getBlockState(BlockPos p){if(!isLoaded(p))throw new AssertionError("Unloaded chunk read at "+p);return blocks.getOrDefault(p,BlockState.AIR);}
 public void put(BlockPos p,BlockState b){blocks.put(p,b);}
 public void mine(BlockPos p){blocks.remove(p);}
}''',
    "net/minecraft/world/phys/Vec3.java": 'package net.minecraft.world.phys;public record Vec3(double x,double y,double z){}',
    "net/minecraft/world/entity/EquipmentSlot.java": 'package net.minecraft.world.entity;public enum EquipmentSlot{MAINHAND}',
    "net/minecraft/world/item/Item.java": 'package net.minecraft.world.item;public class Item{}',
    "net/minecraft/world/item/PickaxeItem.java": 'package net.minecraft.world.item;public final class PickaxeItem extends Item{}',
    "net/minecraft/world/item/ItemStack.java": '''package net.minecraft.world.item;
import net.minecraft.world.level.block.state.BlockState;
public class ItemStack {
 private final Item item;
 public ItemStack(Item i){item=i;}
 public boolean isEmpty(){return item==null;}
 public Item getItem(){return item;}
 public boolean canPerformAction(Object action){return item instanceof PickaxeItem;}
 public boolean isCorrectToolForDrops(BlockState state){return item instanceof PickaxeItem;}
}''',
    "buildcraft/lib/compat/ItemCompat.java": '''package buildcraft.lib.compat;
import net.minecraft.world.item.*;
public final class ItemCompat {public static boolean isPickaxe(ItemStack stack){return stack.getItem() instanceof PickaxeItem;}}''',
    "net/minecraftforge/common/ToolActions.java": 'package net.minecraftforge.common;public final class ToolActions{public static final Object PICKAXE_DIG=new Object();}',
    "net/neoforged/neoforge/common/ItemAbilities.java": 'package net.neoforged.neoforge.common;public final class ItemAbilities{public static final Object PICKAXE_DIG=new Object();}',
    "net/minecraftforge/common/Tags.java": '''package net.minecraftforge.common;
import net.minecraft.tags.TagKey;import net.minecraft.world.level.block.Block;
public class Tags {public static class Blocks{public static final TagKey<Block> ORES=new TagKey<>("all_ores");}}''',
    "net/neoforged/neoforge/common/Tags.java": '''package net.neoforged.neoforge.common;
import net.minecraft.tags.TagKey;import net.minecraft.world.level.block.Block;
public class Tags {public static class Blocks{public static final TagKey<Block> ORES=new TagKey<>("all_ores");}}''',
    "buildcraft/lib/internal/area/IZone.java": 'package buildcraft.lib.internal.area;import net.minecraft.world.phys.Vec3;public interface IZone{boolean contains(Vec3 position);}',
    "buildcraft/lib/internal/core/BlockIndex.java": '''package buildcraft.lib.internal.core;
import net.minecraft.core.BlockPos;
public class BlockIndex {final BlockPos p;public BlockIndex(BlockPos p){this.p=p;}public BlockPos toBlockPos(){return p;}}''',
    "buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobotNBT.java": 'package buildcraft.robotics.internal.legacy.boards;public class RedstoneBoardRobotNBT{}',
    "buildcraft/robotics/BCRoboticsBoards.java": '''package buildcraft.robotics;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
public final class BCRoboticsBoards {
 public static Entry getByKey(String key){return new Entry();}
 public record Entry(){public RedstoneBoardRobotNBT nbt(){return new RedstoneBoardRobotNBT();}}
}''',
    "buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java": '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.world.entity.EquipmentSlot;import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;import buildcraft.lib.internal.area.IZone;
public final class EntityRobotBase {
 public final Level world;
 public IZone zone;
 private final ItemStack held=new ItemStack(new PickaxeItem());
 public EntityRobotBase(Level w){world=w;}
 public Level level(){return world;}
 public ItemStack getItemBySlot(EquipmentSlot slot){return held;}
 public IZone getZoneToWork(){return zone;}
}''',
    "buildcraft/robotics/internal/legacy/robots/AIRobot.java": '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.nbt.CompoundTag;
public class AIRobot {
 private boolean success=true;public AIRobot(EntityRobotBase r){}
 public void fail(){success=false;}
 public boolean success(){return success;}
 public void update(){}public void delegateAIEnded(AIRobot ai){}
 public void writeSelfToNBT(CompoundTag nbt){}public void loadSelfFromNBT(CompoundTag nbt){}
}''',
    "buildcraft/robotics/ai/AIRobotSearchAndGotoBlock.java": '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;import buildcraft.lib.internal.core.BlockIndex;
public final class AIRobotSearchAndGotoBlock extends AIRobot {
 private final BlockIndex found;
 public AIRobotSearchAndGotoBlock(EntityRobotBase r,BlockIndex found){super(r);this.found=found;}
 public BlockIndex getBlockFound(){return found;}
}''',
    "buildcraft/robotics/ai/AIRobotBreak.java": 'package buildcraft.robotics.ai;import buildcraft.robotics.internal.legacy.robots.*;public final class AIRobotBreak extends AIRobot{public AIRobotBreak(EntityRobotBase r){super(r);}}',
    "buildcraft/robotics/boards/BoardRobotGenericBreakBlock.java": '''package buildcraft.robotics.boards;
import buildcraft.robotics.internal.legacy.robots.*;import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.robotics.ai.*;import buildcraft.lib.internal.core.BlockIndex;
import net.minecraft.core.BlockPos;import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;import net.minecraft.nbt.CompoundTag;
public abstract class BoardRobotGenericBreakBlock extends AIRobot {
 protected final EntityRobotBase robot;
 private BlockIndex found;
 public int sleeps;
 public BoardRobotGenericBreakBlock(EntityRobotBase r){super(r);robot=r;}
 public abstract RedstoneBoardRobotNBT getNBTHandler();
 public abstract boolean isExpectedTool(ItemStack stack);
 public abstract boolean isExpectedBlock(Level level,BlockPos pos);
 protected boolean matchesGateFilter(Level level,BlockPos pos){return true;}
 protected BlockIndex blockFound(){return found;}
 @Override public void delegateAIEnded(AIRobot ai){
  if(ai instanceof AIRobotSearchAndGotoBlock s){if(s.success())found=s.getBlockFound();else sleeps++;}
  else if(ai instanceof AIRobotBreak)found=null;
 }
}''',
    "fixture/MinerVeinFixture.java": '''package fixture;
import net.minecraft.core.BlockPos;import net.minecraft.nbt.CompoundTag;import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.*;import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import buildcraft.robotics.boards.BoardRobotMiner;
import buildcraft.robotics.ai.*;import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.lib.internal.core.BlockIndex;
public class MinerVeinFixture {
 static void ok(boolean yes,String reason){if(!yes)throw new AssertionError(reason);}
 static BlockState ore(String name,net.minecraft.tags.TagKey<Block> kind){
  return new BlockState(new Block(name)).tags(kind,net.minecraftforge.common.Tags.Blocks.ORES,net.neoforged.neoforge.common.Tags.Blocks.ORES);
 }
 static void search(BoardRobotMiner b,EntityRobotBase r,BlockPos p){b.delegateAIEnded(new AIRobotSearchAndGotoBlock(r,new BlockIndex(p)));}
 static void breakOre(BoardRobotMiner b,EntityRobotBase r,Level world,BlockPos p){
  world.mine(p);b.delegateAIEnded(new AIRobotBreak(r));
 }
 public static void main(String[] args){
  var level=new Level();var r=new EntityRobotBase(level);
  var a=new BlockPos(14,40,-5);var b=new BlockPos(15,40,-5);
  var c=new BlockPos(16,40,-5);var d=new BlockPos(17,41,-4);
  var e=new BlockPos(18,41,-4);
  var coal=new BlockPos(28,40,-5);
  level.put(a,ore("copper",BlockTags.COPPER_ORES));
  level.put(b,ore("deepslate_copper",BlockTags.COPPER_ORES));
  level.put(c,ore("copper",BlockTags.COPPER_ORES));
  level.put(d,ore("deepslate_copper",BlockTags.COPPER_ORES));
  level.put(e,ore("copper",BlockTags.COPPER_ORES));
  level.put(coal,ore("coal",BlockTags.COAL_ORES));
  level.unloadXChunk(1); // Copper vein extends into an unloaded neighboring chunk.
  var miner=new BoardRobotMiner(r);search(miner,r,a);
  ok(!miner.isExpectedBlock(level,coal),"A different deposit should not interrupt active copper vein");
  ok(miner.isExpectedBlock(level,b),"Loaded connected deepslate copper missing");
  level.loadXChunk(1);
  breakOre(miner,r,level,a);
  ok(miner.isExpectedBlock(level,c),"Cross-chunk copper was not discovered after loading chunk");
  ok(miner.isExpectedBlock(level,d),"Diagonal ore in same vein was not discovered");
  ok(!miner.isExpectedBlock(level,coal),"Coal stolen miner between chunks");
  search(miner,r,b);
  breakOre(miner,r,level,b);
  search(miner,r,c);
  breakOre(miner,r,level,c);
  ok(!miner.isExpectedBlock(level,coal),"After 3 copper blocks, coal must still be deferred");
  search(miner,r,d);
  // Persist while the miner is still working on the copper deposit.
  CompoundTag saved=new CompoundTag();miner.writeSelfToNBT(saved);
  var restored=new BoardRobotMiner(r);restored.loadSelfFromNBT(saved);
  ok(!restored.isExpectedBlock(level,coal),"Save/load discarded active vein priority");
  ok(restored.isExpectedBlock(level,d),"Save/load discarded diagonal copper member");
  breakOre(miner,r,level,d);
  ok(!miner.isExpectedBlock(level,coal),"After 4 copper blocks, coal must still be deferred");
  search(miner,r,e);breakOre(miner,r,level,e);
  ok(miner.isExpectedBlock(level,coal),"Miner failed to release target priority after 5 copper blocks");
  // Failure to route to any remaining vein member must allow the normal scanner to retry other deposits.
  level.put(a,ore("copper",BlockTags.COPPER_ORES));
  level.put(b,ore("copper",BlockTags.COPPER_ORES));
  search(miner,r,a);
  var failed=new AIRobotSearchAndGotoBlock(r,new BlockIndex(b));failed.fail();
  miner.delegateAIEnded(failed);
  ok(miner.isExpectedBlock(level,coal),"Unreachable vein should not lock miner forever");
  ok(miner.sleeps==0,"Failed cached vein caused unnecessary sleep");
  // Work-zone-bound ore must not cause a cache to cross a work-zone boundary.
  var zoneLevel=new Level();var zr=new EntityRobotBase(zoneLevel);
  zoneLevel.put(a,ore("copper",BlockTags.COPPER_ORES));
  zoneLevel.put(b,ore("copper",BlockTags.COPPER_ORES));
  zr.zone = p -> p.x()<15.5;
  var zoned=new BoardRobotMiner(zr);search(zoned,zr,a);
  CompoundTag onlyInside=new CompoundTag();zoned.writeSelfToNBT(onlyInside);
  ok(((net.minecraft.nbt.LongArrayTag)onlyInside.getCompound("minerOreVein").get("remaining")).getAsLongArray().length==1,
      "Vein traversal crossed the configured work-zone boundary");
  breakOre(zoned,zr,zoneLevel,a);
  ok(!onlyInside.getCompound("minerOreVein").getString("family").isEmpty(),"Invalid zone fixture");
  System.out.println("PASS: connected ore, diagonal and cross-chunk discovery, family separation, persistence, fallback, zone");
 }
}''',
}


def materialize_board(code: str, variant: str) -> str:
    if variant == "forge":
        minecraft, family, loader = "1.20.1", "old", "forge"
    elif variant.startswith("neoforge-1.21."):
        minecraft, family, loader = variant.removeprefix("neoforge-"), "1.21.X", "neoforge"
    else:
        minecraft, family, loader = "26.2", "26.X", "neoforge"
    return preprocess_text(code, minecraft=minecraft, family=family, platform=loader)


def materialize_neoforge(code: str, modern_26_2: bool) -> str:
    return materialize_board(code, "neoforge-26.2" if modern_26_2 else "neoforge-1.21.1")


class MinerVeinRuntimeTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Java JDK required")
    def test_loader_variants(self):
        for name, source in BOARDS.items():
            with self.subTest(variant=name), tempfile.TemporaryDirectory(prefix="bc-miner-vein-") as tmp:
                root = Path(tmp)
                paths = [str(VEIN)]
                for rel, source_code in STUBS.items():
                    code = source_code
                    if name in ("neoforge-1.21.11", "neoforge-26.2") and rel == "net/minecraft/nbt/CompoundTag.java":
                        # Modern CompoundTag has an Optional-returning convenience getter.
                        # The real shared vein code must compile against both API shapes.
                        old_getter = "public long[] getLongArray(String k)"
                        assert old_getter in code
                        code = code.replace(old_getter, "public java.util.Optional<long[]> getLongArray(String k)")
                        code = code.replace(
                            'return values.get(k) instanceof LongArrayTag t?t.getAsLongArray():new long[0];',
                            'return values.get(k) instanceof LongArrayTag t?java.util.Optional.of(t.getAsLongArray()):java.util.Optional.empty();')
                    dest = root / rel
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_text(code, encoding="utf-8")
                    paths.append(str(dest))
                board = root / "buildcraft/robotics/boards/BoardRobotMiner.java"
                board.parent.mkdir(parents=True, exist_ok=True)
                src = materialize_board(source.read_text(encoding="utf-8"), name)
                board.write_text(src, encoding="utf-8")
                paths.append(str(board))
                classes = root / "classes"
                classes.mkdir()
                compiled = subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(classes), *paths],
                                          capture_output=True, text=True, timeout=40)
                self.assertEqual(compiled.returncode, 0, f"{name}: {compiled.stderr}")
                executed = subprocess.run(["java", "-cp", str(classes), "fixture.MinerVeinFixture"],
                                          capture_output=True, text=True, timeout=20)
                self.assertEqual(executed.returncode, 0, f"{name}: {executed.stderr}")
                self.assertIn("PASS:", executed.stdout)
                print(f"{name}: {executed.stdout.strip()}", flush=True)


if __name__ == "__main__":
    unittest.main()