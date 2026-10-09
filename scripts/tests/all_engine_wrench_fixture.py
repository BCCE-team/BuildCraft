"""Compile actual BlockEngineBase_BC8 wrench entrypoints against typed Java doubles."""
from __future__ import annotations
import subprocess
import tempfile
from pathlib import Path

from dynamo_rotation_fixture import extract_method


def execute(source: Path) -> str:
    implementation = extract_method(source.read_text(encoding='utf-8'),
        '    public InteractionResult attemptRotation(Level world, BlockPos pos, BlockState state, Direction sideWrenched) {')
    # Exercise the unchanged production signature and body, not a reimplementation of its behavior.
    source_code = '''import java.util.*;
enum Direction { DOWN, UP, NORTH, SOUTH, WEST, EAST }
enum InteractionResult { SUCCESS, FAIL }
record BlockPos(int x,int y,int z) {}
class BlockState {}
class BlockEntity {}
class TileEngineBase_BC8 extends BlockEntity {
    final String kind;
    int manualCalls, automaticCalls;
    Direction facing = Direction.UP;
    TileEngineBase_BC8(String kind) { this.kind = kind; }
    InteractionResult attemptRotation() { automaticCalls++; return InteractionResult.FAIL; }
    InteractionResult attemptManualRotation() {
        manualCalls++;
        facing = Direction.values()[(facing.ordinal()+1)%Direction.values().length];
        return InteractionResult.SUCCESS;
    }
}
class Level {
    boolean isClientSide;
    BlockEntity tile;
    boolean isClientSide() { return isClientSide; }
    BlockEntity getBlockEntity(BlockPos at) { return tile; }
}
public class EngineWrenchHarness {
    private int checks;
    private void check(boolean test) { checks++; if(!test) throw new AssertionError("assertion "+checks); }
'''+implementation+'''
    private void run() {
        String[] types = {"redstone", "stirling", "combustion", "creative", "fe"};
        for (String type : types) {
            Level level = new Level();
            TileEngineBase_BC8 tile = new TileEngineBase_BC8(type);
            level.tile = tile;
            BlockPos pos = new BlockPos(2,3,4);
            BlockState state = new BlockState();
            level.isClientSide = true;
            check(attemptRotation(level,pos,state,Direction.UP)==InteractionResult.SUCCESS);
            check(tile.manualCalls==0 && tile.automaticCalls==0 && tile.facing==Direction.UP);
            level.isClientSide = false;
            for(int n=1;n<=6;n++) {
                check(attemptRotation(level,pos,state,Direction.DOWN)==InteractionResult.SUCCESS);
                check(tile.manualCalls==n);
                check(tile.automaticCalls==0);
                check(tile.facing==Direction.values()[(Direction.UP.ordinal()+n)%6]);
            }
            check(tile.facing==Direction.UP);
            level.tile=null;
            check(attemptRotation(level,pos,state,Direction.UP)==InteractionResult.FAIL);
            level.tile=new BlockEntity();
            check(attemptRotation(level,pos,state,Direction.UP)==InteractionResult.FAIL);
        }
        System.out.println(checks+" assertions PASS");
    }
    public static void main(String[] args){new EngineWrenchHarness().run();}
}
'''
    with tempfile.TemporaryDirectory(prefix='bc-engine-wrench-java-') as temp:
        java=Path(temp)/'EngineWrenchHarness.java'
        java.write_text(source_code,encoding='utf-8')
        result=subprocess.run(['javac','--release','17',str(java)],capture_output=True,text=True,timeout=40)
        if result.returncode:raise AssertionError('Block engine wrench did not compile:\n'+result.stderr)
        result=subprocess.run(['java','-cp',temp,'EngineWrenchHarness'],capture_output=True,text=True,timeout=20)
        if result.returncode:raise AssertionError('Block engine wrench behavior failed:\n'+result.stderr)
        return result.stdout.strip()
