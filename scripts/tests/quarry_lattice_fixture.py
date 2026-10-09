"""Compile the real UV tessellator and verify the quarry lattice on all six prism faces."""
from __future__ import annotations
from pathlib import Path
import subprocess
import tempfile

STUBS = {
    'buildcraft/lib/internal/core/render/ISprite': '''package buildcraft.lib.internal.core.render;
public interface ISprite {double getInterpU(double unit);double getInterpV(double unit);}''',
    'buildcraft/lib/client/render/laser/LaserData_BC8': '''package buildcraft.lib.client.render.laser;
import buildcraft.lib.internal.core.render.ISprite;
public final class LaserData_BC8 {
    public enum LaserSide { TOP, BOTTOM, LEFT, RIGHT }
    public static final class LaserRow {
        public final ISprite sprite;
        public final double uMin,vMin,uMax,vMax;
        public final int width,height;
        public final LaserSide[] validSides;
        public LaserRow(ISprite sprite,int x0,int y0,int x1,int y1) {
            this.sprite=sprite;this.uMin=x0/16D;this.vMin=y0/16D;
            this.uMax=x1/16D;this.vMax=y1/16D;
            this.width=x1-x0;this.height=y1-y0;
            this.validSides=LaserSide.values();
        }
    }
}''',
    'buildcraft/lib/client/render/laser/LaserContext': '''package buildcraft.lib.client.render.laser;
import java.util.*;
public final class LaserContext {
 public final double length=16;
 public final List<String> normals=new ArrayList<>();
 public final List<double[]> points=new ArrayList<>();
 public void setFaceNormal(double x,double y,double z){normals.add(x+","+y+","+z);}
 public void addPoint(double x,double y,double z,double u,double v){
   if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Double.isFinite(u)||!Double.isFinite(v))
      throw new AssertionError("non-finite coordinate");
   points.add(new double[]{x,y,z,u,v});
 }
}''',
    'buildcraft/lib/client/render/laser/LatticeHarness': '''package buildcraft.lib.client.render.laser;
import buildcraft.lib.client.render.laser.LaserData_BC8.LaserRow;
import buildcraft.lib.client.render.laser.LaserData_BC8.LaserSide;
import buildcraft.lib.internal.core.render.ISprite;
public final class LatticeHarness {
  private static int checks;
  static void ok(boolean value){checks++;if(!value)throw new AssertionError("assertion "+checks);}
  public static void main(String[] args) {
    ISprite sprite = new ISprite(){
      public double getInterpU(double value){return value;}
      public double getInterpV(double value){return value;}
    };
    var middle=new CompiledLaserRow(new LaserRow(sprite,0,4,16,12));
    var ctx=new LaserContext();
    for(LaserSide side:LaserSide.values()) {
       int before=ctx.points.size();
       middle.bakeFor(ctx,side,0,1);
       ok(ctx.points.size()==before+4);
       double uMin=Double.POSITIVE_INFINITY,uMax=Double.NEGATIVE_INFINITY;
       double vMin=Double.POSITIVE_INFINITY,vMax=Double.NEGATIVE_INFINITY;
       for(int j=before;j<before+4;j++){
         var v=ctx.points.get(j);uMin=Math.min(uMin,v[3]);uMax=Math.max(uMax,v[3]);
         vMin=Math.min(vMin,v[4]);vMax=Math.max(vMax,v[4]);
       }
       ok(Math.abs(uMax-uMin-1)<1e-9);
       ok(Math.abs(vMax-vMin-0.5)<1e-9);
    }
    ok(ctx.normals.contains("0.0,1.0,0.0"));
    ok(ctx.normals.contains("0.0,-1.0,0.0"));
    ok(ctx.normals.contains("0.0,0.0,-1.0"));
    ok(ctx.normals.contains("0.0,0.0,1.0"));
    var cap=new CompiledLaserRow(new LaserRow(sprite,4,4,12,12));
    var capCtx=new LaserContext();
    cap.bakeStartCap(capCtx);
    cap.bakeEndCap(capCtx);
    ok(capCtx.points.size()==8);
    ok(capCtx.normals.contains("-1.0,0.0,0.0"));
    ok(capCtx.normals.contains("1.0,0.0,0.0"));
    for(var v:capCtx.points){
       ok(v[3]>=0.25 && v[3]<=0.75);
       ok(v[4]>=0.25 && v[4]<=0.75);
    }
    // The cutting head is a DIFFERENT texture from the gantry lattice.
    // Original quarry/drill.png stores a 16x4 lateral strip and a separate
    // 4x4 cap detail (u=6..10, v=0..4). At scale 1/16 it remains 4px thick.
    ISprite drillSprite = new ISprite(){
      public double getInterpU(double value){return value+2;}
      public double getInterpV(double value){return value+4;}
    };
    var drill=new CompiledLaserRow(new LaserRow(drillSprite,0,0,16,4));
    var drillCap=new CompiledLaserRow(new LaserRow(drillSprite,6,0,10,4));
    ok(middle.height==8);ok(drill.height==4);ok(drillCap.height==4);
    ok(Math.abs((drill.height/16D)-.25)<1e-9);
    ok(Math.abs((middle.height/16D)-.5)<1e-9);
    var drillContext=new LaserContext();
    for(LaserSide side:LaserSide.values()) {
      int before=drillContext.points.size();
      drill.bakeFor(drillContext,side,0,1);
      ok(drillContext.points.size()==before+4);
      double minU=100,maxU=-100,minV=100,maxV=-100;
      for(int j=before;j<before+4;j++){
        var point=drillContext.points.get(j);
        ok(Math.abs(Math.abs(point[1])/16D-.125)<1e-9 || Math.abs(Math.abs(point[2])/16D-.125)<1e-9);
        minU=Math.min(minU,point[3]);maxU=Math.max(maxU,point[3]);
        minV=Math.min(minV,point[4]);maxV=Math.max(maxV,point[4]);
      }
      ok(Math.abs(maxU-minU-1)<1e-9);
      ok(Math.abs(minU-2)<1e-9 && Math.abs(maxU-3)<1e-9);
      ok(Math.abs(minV-4)<1e-9 && Math.abs(maxV-4.25)<1e-9);
    }
    var drillCapContext=new LaserContext();
    drillCap.bakeStartCap(drillCapContext);drillCap.bakeEndCap(drillCapContext);
    ok(drillCapContext.points.size()==8);
    ok(drillCapContext.normals.contains("-1.0,0.0,0.0"));
    ok(drillCapContext.normals.contains("1.0,0.0,0.0"));
    for(var vertex:drillCapContext.points){
      ok(Math.abs(vertex[1]/16D)==.125 || Math.abs(vertex[2]/16D)==.125);
      ok(vertex[3]>=2.375 && vertex[3]<=2.625);
      ok(vertex[4]>=4 && vertex[4]<=4.25);
    }
    System.out.println(checks+" assertions PASS");
  }
}'''
}

def execute(compiled_laser_row: Path) -> str:
    with tempfile.TemporaryDirectory(prefix='bc-quarry-lattice-java-') as td:
        root=Path(td)/'src'
        sources=[]
        for name,text in STUBS.items():
            target=root/(name+'.java');target.parent.mkdir(parents=True,exist_ok=True)
            target.write_text(text,encoding='utf-8')
            sources.append(str(target))
        target=root/'buildcraft/lib/client/render/laser/CompiledLaserRow.java'
        target.parent.mkdir(parents=True,exist_ok=True)
        target.write_bytes(compiled_laser_row.read_bytes())
        sources.append(str(target))
        out=Path(td)/'classes'
        result=subprocess.run(['javac','--release','17','-d',str(out),*sources],text=True,capture_output=True,timeout=50)
        if result.returncode:
            raise AssertionError('Quarry lattice tessellator compilation failed:\n'+result.stderr)
        result=subprocess.run(['java','-cp',str(out),'buildcraft.lib.client.render.laser.LatticeHarness'],text=True,capture_output=True,timeout=25)
        if result.returncode:
            raise AssertionError('Quarry lattice tessellation failed:\n'+result.stderr)
        return result.stdout.strip()
