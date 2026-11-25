#!/usr/bin/env python3
"""Regression for coordinate-independent pipe outlines and level-owned picking.

Compiles production geometry and the effective holder entry points against small
Minecraft API doubles. The rotated plot is a simulated sublevel, not Sable.
"""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_layout import load_properties, target_layout, target_ids, resolve_effective_source, _materialize_text_file
from test_minecraft_compat import PICK_1211_extract_method as method
from minecraft_compat_fixture import parse_sources

PREFIX = 'src/main/java/buildcraft/transport/block/'
STUBS = {
    'javax/annotation/Nullable.java': 'package javax.annotation; public @interface Nullable {}',
    'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public record BlockPos(int x,int y,int z) {public int getX(){return x;} public int getY(){return y;} public int getZ(){return z;}}''',
    'net/minecraft/core/Direction.java': '''package net.minecraft.core; public enum Direction {DOWN,UP,NORTH,SOUTH,WEST,EAST}''',
    'net/minecraft/world/phys/Vec3.java': '''package net.minecraft.world.phys;
public class Vec3 {public final double x,y,z;public Vec3(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
public Vec3 subtract(double a,double b,double c){return new Vec3(x-a,y-b,z-c);}public Vec3 subtract(Vec3 a){return subtract(a.x,a.y,a.z);}
public Vec3 add(Vec3 a){return new Vec3(x+a.x,y+a.y,z+a.z);}public Vec3 scale(double s){return new Vec3(x*s,y*s,z*s);}
public double lengthSqr(){return x*x+y*y+z*z;} public Vec3 normalize(){return scale(1/Math.sqrt(lengthSqr()));}}''',
    'net/minecraft/world/phys/AABB.java': '''package net.minecraft.world.phys;
public class AABB {public final double minX,minY,minZ,maxX,maxY,maxZ;
public AABB(double x,double y,double z,double a,double b,double c){minX=x;minY=y;minZ=z;maxX=a;maxY=b;maxZ=c;}}''',
    'net/minecraft/world/phys/HitResult.java': '''package net.minecraft.world.phys;
public class HitResult {public enum Type {MISS,BLOCK,ENTITY}}''',
    'net/minecraft/world/phys/BlockHitResult.java': '''package net.minecraft.world.phys;
import net.minecraft.core.*;public class BlockHitResult extends HitResult {
private final Vec3 loc;private final Direction side;private final BlockPos pos;private final boolean inside,miss;
public BlockHitResult(Vec3 loc,Direction side,BlockPos pos,boolean inside){this(loc,side,pos,inside,false);}
public BlockHitResult(Vec3 loc,Direction side,BlockPos pos,boolean inside,boolean miss){this.loc=loc;this.side=side;this.pos=pos;this.inside=inside;this.miss=miss;}
public Vec3 getLocation(){return loc;} public Direction getDirection(){return side;} public BlockPos getBlockPos(){return pos;} public boolean isInside(){return inside;} public Type getType(){return miss?Type.MISS:Type.BLOCK;}}''',
    'net/minecraft/world/phys/shapes/VoxelShape.java': '''package net.minecraft.world.phys.shapes;
import java.util.*; import net.minecraft.world.phys.AABB;
public record VoxelShape(List<AABB> toAabbs){public boolean isEmpty(){return toAabbs.isEmpty();}}''',
    'net/minecraft/world/phys/shapes/Shapes.java': '''package net.minecraft.world.phys.shapes;
import java.util.*; import net.minecraft.world.phys.AABB;
public class Shapes {public static VoxelShape box(double x,double y,double z,double a,double b,double c){return new VoxelShape(List.of(new AABB(x,y,z,a,b,c)));}
public static VoxelShape or(VoxelShape a,VoxelShape b){var out=new ArrayList<>(a.toAabbs());out.addAll(b.toAabbs());return new VoxelShape(out);}
public static VoxelShape empty(){return new VoxelShape(List.of());}}''',
    'net/minecraft/world/phys/shapes/CollisionContext.java': 'package net.minecraft.world.phys.shapes; public class CollisionContext {}',
    'net/minecraft/world/entity/player/Player.java': '''package net.minecraft.world.entity.player;
import net.minecraft.world.phys.Vec3;
public record Player(Vec3 eye,Vec3 look){public Vec3 getEyePosition(){return eye;}public Vec3 getLookAngle(){return look;}public double blockInteractionRange(){return 5;}public double getReachDistance(){return 5;}public double getAttributeValue(Object a){return 5;}}''',
    'net/minecraft/world/level/ClipContext.java': '''package net.minecraft.world.level;
import net.minecraft.world.phys.Vec3;import net.minecraft.world.entity.player.Player;
public record ClipContext(Vec3 from,Vec3 to,Block block,Fluid fluid,Player player){public enum Block {OUTLINE} public enum Fluid {NONE}}''',
    'net/minecraft/world/level/BlockGetter.java': '''package net.minecraft.world.level;import net.minecraft.world.phys.BlockHitResult;
public interface BlockGetter {BlockHitResult clip(ClipContext context);}''',
    'net/minecraft/world/level/block/state/BlockState.java': 'package net.minecraft.world.level.block.state; public class BlockState {}',
}

HARNESS = '''package buildcraft.transport.block;
import javax.annotation.Nullable;
import net.minecraft.core.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.state.BlockState;

public class TARGET_CLASS {
    static class ForgeMod {static final Attr BLOCK_REACH = new Attr(); static class Attr {Object get(){return this;}}}
    static class BCBlockHitResult {final BlockHitResult result;final int subHit;BCBlockHitResult(BlockHitResult hit,int part){result=hit;subHit=part;}}
    static final VoxelShape BOX_CENTER=Shapes.box(.25,.25,.25,.75,.75,.75);
    final VoxelShape[] parts=new VoxelShape[57];
    public VoxelShape[] getAllShape(BlockGetter world,BlockPos pos){return parts;}
    METHODS
    static int checks=0;
    static void check(boolean ok,String desc){checks++;if(!ok)throw new AssertionError(desc);}
    static BlockHitResult hit(BlockPos p,double x,double y,double z,Direction face){return new BlockHitResult(new Vec3(p.x()+x,p.y()+y,p.z()+z),face,p,false);}
    void run(BlockPos pos,boolean moved) {
        java.util.Arrays.fill(parts,null);
        parts[0]=BOX_CENTER;
        parts[6]=Shapes.box(.75,.25,.25,1,.75,.75);
        parts[9]=Shapes.box(0,0,0,1,1,.0625); // north facade
        parts[13]=Shapes.box(.02,.02,.02,.08,.08,.08); // corner wire
        parts[21]=Shapes.box(.02,.9,.02,.08,.96,.8); // wire between nodes
        Player player=new Player(new Vec3(2,70,3),new Vec3(1,0,0));
        final BlockHitResult[] picked={hit(pos,.25,.5,.5,Direction.WEST)};
        final int[] clips={0};
        BlockGetter level=ctx->{clips[0]++;
            check(ctx.player()==player,"preserves entity collision context");
            check(ctx.block()==ClipContext.Block.OUTLINE && ctx.fluid()==ClipContext.Fluid.NONE,"native outline ray");
            check(ctx.from().subtract(player.eye()).lengthSqr()==0,"native clip receives world eye");
            check(Math.abs(ctx.to().subtract(ctx.from()).lengthSqr()-25)<1e-8,"block reach preserved");
            // The simulated level has already transformed the ray to its plot;
            // the hit returned here is in the target block's coordinate frame.
            return picked[0];};
        BlockState state=new BlockState();CollisionContext context=new CollisionContext();
        check(getShape(state,level,pos,context).toAabbs().size()==5,"outline contains all actual parts");
        check(clips[0]==0,"outline cannot perform a second world ray");
        check(rayTrace(level,pos,player).subHit==0,"pipe body selectable");
        picked[0]=hit(pos,1,.5,.5,Direction.EAST);check(rayTrace(level,pos,player).subHit==6,"pipe connection selectable");
        picked[0]=hit(pos,.5,.5,0,Direction.NORTH);check(rayTrace(level,pos,player).subHit==9,"facade selectable");
        picked[0]=hit(pos,.02,.05,.05,Direction.WEST);check(rayTrace(level,pos,player).subHit==13,"corner wire selectable");
        picked[0]=hit(pos,.02,.93,.4,Direction.WEST);check(rayTrace(level,pos,player).subHit==21,"first wire between selectable");
        picked[0]=hit(pos,.5,.5,.25,Direction.NORTH);parts[9]=Shapes.box(.25,.25,.25,.75,.75,.31);
        check(rayTrace(level,pos,player).subHit==9,"coincident attachment wins");
        parts[9]=null;check(rayTrace(level,pos,player).subHit==0,"absent attachment not selected");
        picked[0]=hit(new BlockPos(pos.x()+1,pos.y(),pos.z()),.25,.5,.5,Direction.WEST);check(rayTrace(level,pos,player)==null,"wrong holder rejected");
        picked[0]=new BlockHitResult(new Vec3(pos.x()+.25,pos.y()+.5,pos.z()+.5),Direction.WEST,pos,false,true);
        check(rayTrace(level,pos,player)==null,"miss rejected");
        picked[0]=hit(pos,.1,.5,.5,Direction.WEST);check(rayTrace(level,pos,player)==null,"point in empty space rejected");
        check(resolveBlockHit(level,pos,null)==null,"null hit rejected");
        if(moved) {
            check(resolveBlockHit(level,pos,new BlockHitResult(player.eye(),Direction.WEST,pos,false))==null,"world-space location cannot masquerade as plot-space hit");
        }
        // Wire-only and empty/pending holders cannot pass a null shape to Shapes.or.
        java.util.Arrays.fill(parts,null);parts[21]=Shapes.box(.02,.9,.02,.08,.96,.8);
        check(getShape(state,level,pos,context).toAabbs().size()==1,"wire-only holder shape");
        java.util.Arrays.fill(parts,null);
        check(getShape(state,level,pos,context)==BOX_CENTER,"pending holder has small outline");
    }
    void rotatedPlot() {
        java.util.Arrays.fill(parts,null); parts[0]=BOX_CENTER;
        BlockPos plot=new BlockPos(20481031,130,20524045);
        Vec3 origin=new Vec3(8,70,10);
        Player player=new Player(new Vec3(9.5,70.5,10.5),new Vec3(-1,0,0));
        // Simulated sublevel yaw of 90 degrees: world = origin + (-z,y,x).
        // Apply the inverse pose to the engine clip, then return a plot-space hit.
        BlockGetter transformed=ctx->{
            Vec3 a=ctx.from().subtract(origin), b=ctx.to().subtract(origin);
            Vec3 localA=new Vec3(a.z,a.y,-a.x), localB=new Vec3(b.z,b.y,-b.x);
            double t=(.25-localA.z)/(localB.z-localA.z);
            check(t>=0 && t<=1,"transformed clip within reach");
            Vec3 local=localA.add(localB.subtract(localA).scale(t));
            check(local.x>=.25 && local.x<=.75 && local.y>=.25 && local.y<=.75,"transformed clip hits body");
            return hit(plot,local.x,local.y,local.z,Direction.NORTH);
        };
        check(!getShape(new BlockState(),transformed,plot,new CollisionContext()).isEmpty(),"rotated plot still targetable");
        check(rayTrace(transformed,plot,player).subHit==0,"rotated translated plot hits correct body");
    }
    public static void main(String[]args){var test=new TARGET_CLASS();test.run(new BlockPos(4,70,3),false);test.run(new BlockPos(20481031,130,20524045),true);test.rotatedPlot();System.out.println(checks+" assertions PASS");}
}'''


class PipeSublevelTargeting(unittest.TestCase):
    def test_effective_holder_entry_points(self):
        props = load_properties()
        with tempfile.TemporaryDirectory(prefix='bc-pipe-sublevel-') as tmp:
            tmp = Path(tmp)
            for rel, text in STUBS.items():
                p = tmp / 'src' / rel; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(text)
            effective = tmp / 'effective'
            fixtures = []
            for target in target_ids(props):
                with self.subTest(target=target):
                    layout = target_layout(target, props)
                    for name in ('BlockPipeHolder.java', 'PipeHitGeometry.java'):
                        rel = PREFIX + name
                        path = resolve_effective_source(layout, props, rel)
                        self.assertIsNotNone(path)
                        dest = effective / target / rel
                        dest.parent.mkdir(parents=True, exist_ok=True)
                        downs = tuple(r for r in (layout.family_downport_root, layout.family_platform_downport_root) if r)
                        _materialize_text_file(path, dest, logical_relative=rel,
                            minecraft=props[f'target.{target}.deps.minecraft'], family=layout.family, platform=layout.platform,
                            preprocess=True, native_source=any(path.is_relative_to(r) for r in downs),
                            native_passthrough=(layout.platform=='fabric' and path.is_relative_to(layout.family_platform_root)))
                    source = (effective / target / PREFIX / 'BlockPipeHolder.java').read_text()
                    geometry = (effective / target / PREFIX / 'PipeHitGeometry.java').read_text()
                    helper = tmp / 'src/buildcraft/transport/block/PipeHitGeometry.java'
                    helper.parent.mkdir(parents=True,exist_ok=True)
                    if helper.exists(): self.assertEqual(helper.read_text(), geometry)
                    else: helper.write_text(geometry)
                    methods = '\n'.join(method(source,sig) for sig in (
                        'public VoxelShape getShape(', 'public VoxelShape getInteractionShape(',
                        'public BCBlockHitResult rayTrace(BlockGetter world, BlockPos pos, Player player)',
                        'BCBlockHitResult resolveBlockHit('))
                    self.assertIn('.dynamicShape()',source)
                    self.assertNotIn('getEyePosition()',method(source,'public VoxelShape getShape('))
                    self.assertIn('world.clip(new ClipContext(', methods)
                    destroyed = method(source, 'public boolean onDestroyedByPlayer(')
                    self.assertIn('return super.onDestroyedByPlayer', destroyed)
                    # Minecraft 26.3 uses the vanilla three-argument method. The
                    # NeoForge materializer adapts it, Fabric native sources do not.
                    # Older targets expose the player-aware overload.
                    particle_args = 'world, pos, state' if props[f'target.{target}.deps.minecraft'] == '26.3' else 'world, player, pos, state'
                    self.assertIn(f'this.spawnDestroyParticles({particle_args});', destroyed)
                    self.assertEqual(1, destroyed.count('this.spawnDestroyParticles('))
                    self.assertIn('resolveBlockHit(world, pos, re)',source)
                    name = 'Probe_' + target.replace('.','_').replace('-','_')
                    out = tmp / 'src/buildcraft/transport/block' / (name+'.java')
                    out.write_text(HARNESS.replace('TARGET_CLASS',name).replace('METHODS',methods))
                    fixtures.append(name)
            self.assertEqual(8,len(fixtures))
            out = tmp / 'classes'; out.mkdir()
            result = subprocess.run(['javac','--release','17','-d',str(out),*[str(p) for p in (tmp/'src').rglob('*.java')]],capture_output=True,text=True)
            self.assertEqual(0,result.returncode,result.stderr)
            for name in fixtures:
                result = subprocess.run(['java','-cp',str(out),'buildcraft.transport.block.'+name],capture_output=True,text=True)
                self.assertEqual(0,result.returncode,name+'\n'+result.stderr)
                self.assertIn('assertions PASS',result.stdout)
                print(name,result.stdout.strip())
            self.assertIn('0 errors',parse_sources([effective]))


if __name__ == '__main__':
    unittest.main()
