"""Compile the *actual* corrected TileEngineBase rotation methods against typed Java doubles."""
from __future__ import annotations
import subprocess
import tempfile
from pathlib import Path


def extract_method(source: str, start: str) -> str:
    index = source.index(start)
    open_brace = source.index('{', index)
    depth = 0
    for n in range(open_brace, len(source)):
        if source[n] == '{':
            depth += 1
        elif source[n] == '}':
            depth -= 1
            if depth == 0:
                return source[index:n+1]
    raise AssertionError(f'Method braces unmatched: {start}')


def execute(source: Path) -> str:
    text = source.read_text(encoding='utf-8')
    methods = [extract_method(text, start) for start in (
        '    public InteractionResult attemptRotation() {',
        '    public InteractionResult attemptManualRotation() {',
        '    private InteractionResult attemptRotation(boolean manual) {',
        '    public void rotateIfInvalid() {',
    )]
    java='''import java.util.*;
    enum Direction { DOWN, UP, NORTH, SOUTH, WEST, EAST }
    enum InteractionResult { SUCCESS, FAIL }
    class OrderedEnumMap<T extends Enum<T>> {
       private final T[] values;
       OrderedEnumMap(T[] values){this.values=values;}
       T next(T current){
           if(current == null)return values[0];
           return values[(current.ordinal()+1)%values.length];
       }
    }
    class VanillaRotationHandlers {
       static final OrderedEnumMap<Direction> ROTATE_FACING=new OrderedEnumMap<>(Direction.values());
    }
    record BlockPos(int x,int y,int z) {
       BlockPos relative(Direction d){return new BlockPos(x+d.ordinal(),y,z);}
    }
    class Block {}
    class BlockState {Block getBlock(){return new Block();}}
    class Level {
       boolean isClientSide;
       boolean isClientSide(){return isClientSide;}
       int invalidations, notifications;
       void invalidateCapabilities(BlockPos pos){invalidations++;}
       void neighborChanged(BlockPos pos,Block source, BlockPos original){notifications++;}
    }
    public class EngineHarness {
       final Level level=new Level();
       final BlockPos worldPosition=new BlockPos(0,0,0);
       Direction currentDirection=Direction.UP;
       private boolean manuallySelectedDirection;
       Direction receiver;
       int renderUpdates, redrawn, dirty, persisted;
       static final int NET_RENDER_DATA=7;
       boolean isFacingReceiver(Direction d){return d==receiver;}
       void sendNetworkUpdate(int id){renderUpdates++;}
       void redrawBlock(){redrawn++;}
       void markChunkDirty(){dirty++;}
       void capturePersistedState(){persisted++;}
       BlockState getBlockState(){return new BlockState();}
    ''' + '\n'.join(methods) + '''
       static int assertions;
       static void check(boolean b){assertions++;if(!b)throw new AssertionError("assertion "+assertions);}
       public static void main(String[] args){
          var free=new EngineHarness();
          check(free.attemptRotation()==InteractionResult.FAIL);
          check(free.currentDirection==Direction.UP);
          check(free.attemptManualRotation()==InteractionResult.SUCCESS);
          check(free.currentDirection==Direction.NORTH);
          check(free.manuallySelectedDirection);
          check(free.dirty==1 && free.renderUpdates==1 && free.redrawn==1 && free.persisted==1);
          check(free.level.notifications==2);
          free.receiver=Direction.DOWN;
          free.rotateIfInvalid();
          check(free.currentDirection==Direction.NORTH);
          check(free.dirty==1);
          check(free.attemptManualRotation()==InteractionResult.SUCCESS);
          check(free.currentDirection==Direction.SOUTH);
          check(free.dirty==2);
          check(free.level.notifications==4);
          var automatic=new EngineHarness();
          automatic.receiver=Direction.WEST;
          automatic.rotateIfInvalid();
          check(automatic.currentDirection==Direction.WEST);
          check(!automatic.manuallySelectedDirection);
          check(automatic.renderUpdates==1);
          var idle=new EngineHarness();
          idle.rotateIfInvalid();
          check(idle.currentDirection==Direction.UP);
          check(idle.dirty==0);
          var client=new EngineHarness();client.level.isClientSide=true;
          check(client.attemptManualRotation()==InteractionResult.FAIL);
          check(client.currentDirection==Direction.UP);
          check(client.dirty==0);
          System.out.println(assertions+" assertions PASS");
       }
    }
    '''
    with tempfile.TemporaryDirectory(prefix='bc-dynamo-rotation-java-') as td:
        path=Path(td)/'EngineHarness.java'
        path.write_text(java,encoding='utf-8')
        compiled=subprocess.run(['javac','--release','17',str(path)],capture_output=True,text=True,timeout=45)
        if compiled.returncode:
            raise AssertionError('Rotation methods could not compile: '+compiled.stderr)
        launched=subprocess.run(['java','-cp',td,'EngineHarness'],capture_output=True,text=True,timeout=20)
        if launched.returncode:
            raise AssertionError('Rotation harness failed: '+launched.stderr)
        return launched.stdout.strip()
