"""Single-block-scheme inventory preview on every maintained Minecraft target.

The targeted Java fixture verifies stable NBT-keyed cache identity, invalid/blank
handling and item immutability. Source checks verify that every target reaches
an actual Blueprint renderer, not a plain item-icon fallback. These cannot
replace Gradle compilation or an in-game visual smoke test.
"""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_lookup import read_materialized_java, resolve_target_source

TARGETS = ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge',
           '1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge')
PKG = 'src/main/java/buildcraft/builders/client/tooltip/'


def read(target, filename):
    return read_materialized_java(target, PKG + filename)


class SingleSchemePreviewParityTest(unittest.TestCase):
    def test_all_targets_select_real_cached_3d_preview(self):
        for target in TARGETS:
            with self.subTest(target=target):
                cache = read(target, 'SingleSchematicPreviewCache.java')
                overlay_file = ('LegacyBlueprintTooltipOverlay.java' if target.endswith('forge') and
                                not target.endswith('neoforge') else 'BlueprintTooltipOverlay.java')
                overlay = read(target, overlay_file)
                self.assertIn('SingleSchematicPreviewCache.previewFor(stack)', overlay)
                self.assertIn('stack.getItem() instanceof ItemSchematicSingle', overlay)
                self.assertIn('blueprint.size = new BlockPos(1, 1, 1);', cache)
                self.assertIn('blueprint.data = new int[] { 0 };', cache)
                self.assertIn('blueprint.palette.add(block);', cache)
                self.assertIn('blueprint.facing = Direction.NORTH;', cache)
                self.assertIn('blueprint.offset = BlockPos.ZERO;', cache)
                self.assertIn('if (block == null || block.isAir()) return null;', cache)
                self.assertIn('if (cachedTag != null && cachedTag.equals(schematicTag)) return cachedPreview;', cache)
                self.assertIn('cachedTag = schematicTag.copy();', cache)
                self.assertNotIn('cachedSchematic.equals(', cache)
                self.assertIn('snapshot = ClientSnapshots.INSTANCE.getSnapshot(header.key);', overlay)
                self.assertIn('if (snapshot == null) return; // blank', overlay)
                self.assertIn('SIDE = 100' if target.endswith('neoforge') else 'SIZE = 100', overlay)
                self.assertIn('LegacyBlueprintGuiRenderer.render(snapshot' if target.endswith('forge') and
                              not target.endswith('neoforge') else 'BlueprintRenderer.renderSnapshot(', overlay)

    def test_version_ownership_and_special_block_renderer_are_preserved(self):
        for target in TARGETS:
            with self.subTest(target=target):
                source = resolve_target_source(target, PKG + 'SingleSchematicPreviewCache.java').as_posix()
                self.assertIn('source-families/old/' if target.endswith('forge') and
                              not target.endswith('neoforge') else 'source-families/1.21.X/', source)
                cache = read(target, 'SingleSchematicPreviewCache.java')
                self.assertIn('stack.getTag()', cache) if target.endswith('forge') and not target.endswith('neoforge') else self.assertIn('ItemStackUtil.getCustomDataOrNull(stack)', cache)
                # This is never placed into a real world or consumed as a building operation.
                for forbidden in ('setBlockAndUpdate(', 'buildWithoutChecks(', 'setCustomData(', 'sendToServer('):
                    self.assertNotIn(forbidden, cache)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK unavailable')
    def test_executable_cache_reuses_data_and_updates_only_on_content_change(self):
        stubs = {
            'javax/annotation/Nullable.java': 'package javax.annotation; public @interface Nullable {}',
            'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
                public record BlockPos(int x,int y,int z) {public static final BlockPos ZERO = new BlockPos(0,0,0);}''',
            'net/minecraft/core/Direction.java': 'package net.minecraft.core; public enum Direction {NORTH}',
            'net/minecraft/nbt/Tag.java': 'package net.minecraft.nbt; public abstract class Tag {public abstract Tag copy();}',
            'net/minecraft/nbt/CompoundTag.java': '''package net.minecraft.nbt;
                import java.util.*;
                public class CompoundTag extends Tag {
                    private final Map<String,Object> entries = new HashMap<>();
                    public void put(String k,Tag v){entries.put(k,v);}
                    public void putInt(String k,int n){entries.put(k,n);}
                    public int getInt(String k){return (int)entries.getOrDefault(k,0);}
                    public Tag get(String k){Object o=entries.get(k);return o instanceof Tag ? (Tag)o : null;}
                    public CompoundTag getCompound(String k){return (CompoundTag)entries.get(k);}
                    public boolean contains(String k,int t){return t==10 && get(k) instanceof CompoundTag;}
                    @Override public CompoundTag copy(){CompoundTag result=new CompoundTag();
                        for(var entry:entries.entrySet())result.entries.put(entry.getKey(),entry.getValue() instanceof Tag t?t.copy():entry.getValue());
                        return result;}
                    @Override public boolean equals(Object other){return other instanceof CompoundTag c && entries.equals(c.entries);}
                    @Override public int hashCode(){return entries.hashCode();}
                }''',
            'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
                import net.minecraft.nbt.CompoundTag;
                public class ItemStack {public CompoundTag data;public boolean used;
                  public ItemStack(CompoundTag d,boolean u){data=d;used=u;}
                  public CompoundTag getTag(){return data;}}
            ''',
            'buildcraft/builders/internal/schematic/legacy/ISchematicBlock.java': '''package buildcraft.builders.internal.schematic.legacy;
                public interface ISchematicBlock{boolean isAir();}''',
            'buildcraft/builders/snapshot/Blueprint.java': '''package buildcraft.builders.snapshot;
                import net.minecraft.core.*;import java.util.*;
                import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
                public class Blueprint {public BlockPos size,offset;public Direction facing;
                  public int[] data;public final List<ISchematicBlock> palette=new ArrayList<>();}''',
            'buildcraft/builders/item/ItemSchematicSingle.java': '''package buildcraft.builders.item;
                import net.minecraft.world.item.ItemStack;
                import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
                public class ItemSchematicSingle {
                  public static final String NBT_KEY="schematic";public static int reads;
                  public static boolean isUsed(ItemStack s){return s.used;}
                  public static ISchematicBlock getSchematicSafe(ItemStack s){reads++;
                    int id=s.data.getCompound(NBT_KEY).getInt("block");
                    return id<0?null:()->id==0;}}
                ''',
            'buildcraft/lib/misc/ItemStackUtil.java': '''package buildcraft.lib.misc;
                import net.minecraft.world.item.ItemStack;import net.minecraft.nbt.CompoundTag;
                public class ItemStackUtil{public static CompoundTag getCustomDataOrNull(ItemStack s){return s.data==null?null:s.data.copy();}}''',
            'Test.java': '''import buildcraft.builders.client.tooltip.SingleSchematicPreviewCache;
                import buildcraft.builders.item.ItemSchematicSingle;
                import buildcraft.builders.snapshot.Blueprint;
                import net.minecraft.nbt.CompoundTag;import net.minecraft.world.item.ItemStack;
                public class Test {
                  static ItemStack stack(int id){CompoundTag payload=new CompoundTag();payload.putInt("block",id);
                    CompoundTag root=new CompoundTag();root.put("schematic",payload);return new ItemStack(root,true);}
                  static void require(boolean x){if(!x)throw new AssertionError();}
                  public static void main(String[]args){
                    ItemStack s=stack(2);
                    Blueprint first=SingleSchematicPreviewCache.previewFor(s);
                    require(first!=null && first.data.length==1 && first.palette.size()==1);
                    require(first==SingleSchematicPreviewCache.previewFor(s));
                    require(first==SingleSchematicPreviewCache.previewFor(stack(2)));
                    require(ItemSchematicSingle.reads==1);
                    s.data.getCompound("schematic").putInt("block",3);
                    Blueprint second=SingleSchematicPreviewCache.previewFor(s);
                    require(second!=null && second!=first && ItemSchematicSingle.reads==2);
                    require(SingleSchematicPreviewCache.previewFor(stack(0))==null);
                    require(SingleSchematicPreviewCache.previewFor(stack(-1))==null);
                    require(SingleSchematicPreviewCache.previewFor(new ItemStack(null,true))==null);
                    require(SingleSchematicPreviewCache.previewFor(new ItemStack(null,false))==null);
                    SingleSchematicPreviewCache.clear();
                    require(SingleSchematicPreviewCache.previewFor(stack(2))!=first);
                    require(first.palette.size()==1 && second.palette.size()==1);
                  }
                }''',
        }
        for target in ('1.19.2-forge', '1.21.11-neoforge', '26.3-neoforge'):
            with self.subTest(target=target), tempfile.TemporaryDirectory(prefix='bcce-single-preview-') as temporary:
                folder = Path(temporary)
                for name, contents in stubs.items():
                    path = folder / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(contents, encoding='utf-8')
                cache = folder / 'buildcraft/builders/client/tooltip/SingleSchematicPreviewCache.java'
                cache.parent.mkdir(parents=True, exist_ok=True)
                cache.write_text(read(target, 'SingleSchematicPreviewCache.java'), encoding='utf-8')
                subprocess.run(['javac', '-d', str(folder)] + [str(path) for path in folder.rglob('*.java')],
                               check=True, capture_output=True, text=True, timeout=30)
                subprocess.run(['java', '-cp', str(folder), 'Test'],
                               check=True, capture_output=True, text=True, timeout=15)


if __name__ == '__main__':
    unittest.main()
