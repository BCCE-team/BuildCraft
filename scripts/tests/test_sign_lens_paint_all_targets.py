#!/usr/bin/env python3
"""Cross-target sign text and installed lens paint regressions.

Compiles the actual extracted Java logic against minimal API doubles to check
side isolation, wax/permissions, filter retention, click targeting and no-ops.
"""
from __future__ import annotations

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source, _materialize_text_file

TARGETS = (
    '1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge',
    '1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge',
)


def effective(target: str, name: str) -> str:
    props = load_properties()
    layout = target_layout(target, props)
    logical = f'src/main/java/buildcraft/{name}.java'
    src = resolve_effective_source(layout, props, logical)
    if src is None:
        raise AssertionError((target, logical))
    with tempfile.TemporaryDirectory(prefix='bc-sign-lens-src-') as td:
        dest = Path(td) / 'Source.java'
        _materialize_text_file(src, dest, logical_relative=logical,
                               minecraft=target.split('-')[0], family=layout.family,
                               platform=layout.platform, preprocess=True)
        return dest.read_text(encoding='utf8')


def run_java(source: str) -> None:
    with tempfile.TemporaryDirectory(prefix='bc-sign-lens-java-') as td:
        main = Path(td) / 'Main.java'
        main.write_text(source, encoding='utf8')
        subprocess.run(['javac', 'Main.java'], cwd=td, check=True,
                       capture_output=True, text=True)
        subprocess.run(['java', 'Main'], cwd=td, check=True,
                       capture_output=True, text=True)


class SignLensPaintTest(unittest.TestCase):
    def test_signs_use_native_api_on_every_version(self):
        for target in TARGETS:
            with self.subTest(target=target):
                code = effective(target, 'core/item/ItemPaintbrush_BC8')
                self.assertIn('paintSignText(level, pos, player)', code)
                if target.endswith('neoforge'):
                    self.assertIn('level.getBlockEntity(pos) instanceof SignBlockEntity', code)
                    self.assertIn('state.getBlock() instanceof ICustomPaintHandler', code)
                    core = effective(target, 'core/BCCore')
                    self.assertIn('ItemPaintbrush_BC8.shouldOverrideBlockUse(', core)
                else:
                    self.assertIn('event.getLevel().getBlockEntity(event.getPos()) instanceof SignBlockEntity', code)
                    self.assertIn('state.getBlock() instanceof ICustomPaintHandler', code)
                self.assertIn('!player.mayBuild()', code)
                self.assertIn('!level.mayInteract(player, pos)', code)
                self.assertIn('return InteractionResult.FAIL', code)
                self.assertNotIn('//?', code)
                if target == '1.19.2-forge':
                    self.assertIn('sign.setColor(type)', code)
                    self.assertNotIn('SignTextSlot', code)
                elif target == '26.3-neoforge':
                    self.assertIn('sign.getSlotPlayerIsFacing(player)', code)
                    self.assertIn('text.withColor(type)', code)
                    self.assertNotIn('sign.isFacingFrontText(player)', code)
                    self.assertNotIn('text.setColor(type)', code)
                else:
                    self.assertIn('sign.isFacingFrontText(player)', code)
                    self.assertIn('text.setColor(type)', code)
                    self.assertNotIn('SignTextSlot', code)
                if target != '1.19.2-forge':
                    self.assertIn('sign.isWaxed()', code)
                    self.assertIn('current.getColor() == type', code)

    def test_lens_recolor_is_bounded_to_clicked_pluggable(self):
        for target in TARGETS:
            with self.subTest(target=target):
                code = effective(target, 'transport/block/BlockPipeHolder')
                self.assertIn('Direction lensSide = getLensSideAt(tile, pos, hitPos, hitSide)', code)
                self.assertIn('lens.colour == paintColour', code)
                self.assertIn('lens.definition, tile, lensSide, paintColour, lens.isFilter', code)
                self.assertIn('tile.replacePluggable(lensSide,', code)
                self.assertIn('tile.requestModelDataUpdate();', code)
                self.assertIn('.bounds().inflate(1.0E-4).contains(localHit)', code)
                self.assertLess(code.index('getLensSideAt(tile, pos, hitPos, hitSide)'),
                                code.index('pipe.setColour(paintColour)'))
                self.assertLess(code.index('getFacadeSideAt(tile, pos, hitPos, hitSide)')
                                if target != '1.19.2-forge' else
                                code.index('// A facade occupies'),
                                code.index('getLensSideAt(tile, pos, hitPos, hitSide)'))
                self.assertNotIn('//?', code)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'Java compiler required')
    def test_actual_sign_painter_methods_compile_and_preserve_text(self):
        # Exercise all three vanilla sign API generations.
        for target in ('1.19.2-forge', '1.20.1-forge', '26.3-neoforge'):
            with self.subTest(target=target):
                code = effective(target, 'core/item/ItemPaintbrush_BC8')
                method = code[code.index('private InteractionResult paintSignText('):
                              code.index('    public boolean tryBrush(', code.index('private InteractionResult paintSignText('))]
                method = method.replace('net.minecraft.world.level.block.entity.SignTextSlot', 'SignTextSlot')
                main = '''import java.util.function.UnaryOperator;
public class Main {
  enum DyeColor { RED, BLUE }
  enum InteractionResult { PASS, SUCCESS, FAIL }
  static class BlockPos {}
  static class Player { boolean canBuild=true; boolean mayBuild() {return canBuild;} }
  static class Level {
    Object be; boolean allow=true;
    Object getBlockEntity(BlockPos pos) {return be;}
    boolean mayInteract(Player player, BlockPos pos) {return allow;}
  }
  enum SignTextSlot { FRONT, BACK }
  static class SignText {
    final DyeColor color; final String text; final boolean glowing;
    SignText(DyeColor c, String t, boolean g) {color=c;text=t;glowing=g;}
    DyeColor getColor() {return color;}
    SignText setColor(DyeColor c) {return new SignText(c,text,glowing);}
    SignText withColor(DyeColor c) {return new SignText(c,text,glowing);}
  }
  static class SignBlockEntity {
    boolean waxed; boolean frontFacing=true; int changes;
    SignText front = new SignText(DyeColor.BLUE,"Line and formatting",true);
    SignText back = new SignText(DyeColor.BLUE,"Back message",false);
    DyeColor color=DyeColor.BLUE;
    boolean isWaxed() {return waxed;}
    boolean isFacingFrontText(Player player) {return frontFacing;}
    SignTextSlot getSlotPlayerIsFacing(Player player) {return frontFacing ? SignTextSlot.FRONT : SignTextSlot.BACK;}
    SignText getText(boolean front) {return front ? this.front : this.back;}
    SignText getText(SignTextSlot slot) {return slot == SignTextSlot.FRONT ? front : back;}
    boolean updateText(UnaryOperator<SignText> op, boolean isFront) {
      SignText prior=getText(isFront), next=op.apply(prior);
      if(prior.getColor()==next.getColor()) return false;
      if(isFront) front=next;else back=next;changes++;return true;
    }
    boolean updateText(UnaryOperator<SignText> op, SignTextSlot slot) {
      return updateText(op, slot==SignTextSlot.FRONT);
    }
    boolean setColor(DyeColor newColor) {
      if(color==newColor) return false;
      color=newColor;changes++;return true;
    }
  }
  static class Brush {
    DyeColor type = DyeColor.RED;
''' + method + '''\n  }
  static void check(boolean v, String description) {if(!v) throw new AssertionError(description);}
  public static void main(String[] args) {
    Brush brush = new Brush();
    Player player = new Player();
    Level level = new Level(); BlockPos pos = new BlockPos();
    check(brush.paintSignText(level,pos,player)==InteractionResult.PASS,"non sign passes through");
    SignBlockEntity sign = new SignBlockEntity();level.be=sign;
    check(brush.paintSignText(level,pos,player)==InteractionResult.SUCCESS,"initial recolor");
    check(sign.changes==1,"exactly one update");
    check(brush.paintSignText(level,pos,player)==InteractionResult.FAIL,"same color is no-op");
    check(sign.changes==1,"no update on same color");
    if (!"1.19.2-forge".equals("'''+target+'''")) {
      check(sign.front.color==DyeColor.RED,"front changed");
      check(sign.back.color==DyeColor.BLUE,"back not changed");
      check(sign.front.text.equals("Line and formatting") && sign.front.glowing,"text preserved");
      sign.frontFacing=false;brush.type=DyeColor.RED;
      check(brush.paintSignText(level,pos,player)==InteractionResult.SUCCESS,"back changed separately");
      check(sign.back.color==DyeColor.RED && sign.back.text.equals("Back message"),"back text preserved");
      sign.waxed=true;brush.type=DyeColor.BLUE;
      check(brush.paintSignText(level,pos,player)==InteractionResult.FAIL,"wax protects text");
    }
    player.canBuild=false;
    check(brush.paintSignText(level,pos,player)==InteractionResult.FAIL,"no build permission");
    player.canBuild=true;level.allow=false;
    check(brush.paintSignText(level,pos,player)==InteractionResult.FAIL,"no interaction permission");
  }
}
'''
                run_java(main)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'Java compiler required')
    def test_actual_lens_hit_selection_compiles_and_preserves_filter(self):
        code = effective('26.3-neoforge', 'transport/block/BlockPipeHolder')
        method = code[code.index('    @Nullable\n    private static Direction getLensSideAt('):
                      code.index('\n\tpublic ItemStack getCloneItemStack(', code.index('    @Nullable\n    private static Direction getLensSideAt('))]
        start = code.index('        // A lens/filter is itself a coloured pluggable.')
        end = code.index('\n\t\tPipe pipe = tile.getPipe();', start)
        action = code[start:end]
        main = '''import java.util.*;
public class Main {
  @interface Nullable {}
  enum DyeColor { RED, BLUE }
  enum InteractionResult { PASS, SUCCESS, FAIL }
  enum Direction { DOWN, UP, NORTH, SOUTH, WEST, EAST }
  static class BlockPos { int getX(){return 0;} int getY(){return 0;} int getZ(){return 0;} }
  static class Vec3 {
    final double x,y,z;
    Vec3(double x,double y,double z) {this.x=x;this.y=y;this.z=z;}
    Vec3 subtract(double x,double y,double z) {return new Vec3(this.x-x,this.y-y,this.z-z);}
  }
  static class AABB {
    final double minX,minY,minZ,maxX,maxY,maxZ;
    AABB(double ax,double ay,double az,double bx,double by,double bz) {
      minX=ax;minY=ay;minZ=az;maxX=bx;maxY=by;maxZ=bz;
    }
    AABB inflate(double d) {return new AABB(minX-d,minY-d,minZ-d,maxX+d,maxY+d,maxZ+d);}
    boolean contains(Vec3 v) {return v.x>=minX && v.x<=maxX && v.y>=minY && v.y<=maxY && v.z>=minZ && v.z<=maxZ;}
  }
  static class VoxelShape { final AABB box; VoxelShape(AABB box){this.box=box;} AABB bounds(){return box;} }
  static class PipePluggable {
    final Object definition; final Direction side;
    PipePluggable(Object d,Direction side){definition=d;this.side=side;}
    VoxelShape getBoundingBox(){return new VoxelShape(new AABB(0,0,0,0,0,0));}
  }
  static class PluggableLens extends PipePluggable {
    final DyeColor colour; final boolean isFilter;
    PluggableLens(Object def,TilePipeHolder tile,Direction side,DyeColor color,boolean filter){
      super(def,side);colour=color;isFilter=filter;
    }
    @Override VoxelShape getBoundingBox(){
      return new VoxelShape(side==Direction.NORTH ? new AABB(.1875,.1875,0,.8125,.8125,.125) :
        new AABB(.1875,.1875,.875,.8125,.8125,1));
    }
  }
  static class TilePipeHolder {
    final EnumMap<Direction,PipePluggable> plugs = new EnumMap<>(Direction.class);
    int swaps, updates, renders, redraws, changes;
    PipePluggable getPluggable(Direction d){return plugs.get(d);}
    PipePluggable replacePluggable(Direction d,PipePluggable replacement){
      swaps++;return plugs.put(d,replacement);
    }
    void requestModelDataUpdate(){updates++;}
    void scheduleRenderUpdate(){renders++;}
    void redrawBlock(){redraws++;}
    void setChanged(){changes++;}
  }
  static Direction getLensSideAt(TilePipeHolder tile, BlockPos pos, Vec3 hitPos, Direction hitSide) { return null; }
  static InteractionResult paintLens(TilePipeHolder tile,BlockPos pos,Vec3 hitPos,Direction hitSide,DyeColor paintColour) {
''' + action + '''\n    return InteractionResult.PASS;
  }
  static void check(boolean test,String desc){if(!test)throw new AssertionError(desc);}
  public static void main(String[] args){
    BlockPos pos=new BlockPos();TilePipeHolder tile=new TilePipeHolder();
    Object definition=new Object();
    tile.plugs.put(Direction.NORTH,new PluggableLens(definition,tile,Direction.NORTH,DyeColor.BLUE,true));
    tile.plugs.put(Direction.SOUTH,new PluggableLens(definition,tile,Direction.SOUTH,DyeColor.BLUE,false));
    Vec3 hit=new Vec3(.5,.5,.02);
    check(paintLens(tile,pos,hit,Direction.SOUTH,DyeColor.RED)==InteractionResult.SUCCESS,"geometric north selected");
    PluggableLens lens=(PluggableLens)tile.getPluggable(Direction.NORTH);
    check(lens.colour==DyeColor.RED && lens.isFilter && lens.definition==definition,"filter preserved");
    check(tile.swaps==1 && tile.updates==1 && tile.changes==1,"one replacement, one update");
    check(paintLens(tile,pos,hit,Direction.NORTH,DyeColor.RED)==InteractionResult.FAIL,"same-colour no-op");
    check(tile.swaps==1,"no duplicate replacement");
    check(paintLens(tile,pos,new Vec3(.5,.5,.5),Direction.NORTH,DyeColor.BLUE)==InteractionResult.PASS,"pipe not lens");
    check(tile.swaps==1,"pipe untouched");
    Vec3 southHit=new Vec3(.5,.5,.98);
    check(paintLens(tile,pos,southHit,Direction.SOUTH,DyeColor.RED)==InteractionResult.SUCCESS,"normal lens recoloured");
    check(!((PluggableLens)tile.getPluggable(Direction.SOUTH)).isFilter,"ordinary lens remains ordinary");
  }
}
'''
        # In this fixture use the actual helper, instead of the placeholder.
        method = method.replace('    @Nullable\n', '    @Nullable\n', 1)
        main = main.replace('  static Direction getLensSideAt(TilePipeHolder tile, BlockPos pos, Vec3 hitPos, Direction hitSide) { return null; }', method.replace('buildcraft.silicon.plug.PluggableLens', 'PluggableLens'))
        main = main.replace('buildcraft.silicon.plug.PluggableLens','PluggableLens')
        run_java(main)


if __name__ == '__main__':
    unittest.main()
