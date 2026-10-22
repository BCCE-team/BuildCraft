#!/usr/bin/env python3
"""Compile the real Snapshot.getDataSize() to check dimension overflow limits."""
from __future__ import annotations

from pathlib import Path
import shutil
import subprocess
import tempfile


def execute() -> str:
    if not shutil.which('javac') or not shutil.which('java'):
        raise RuntimeError('Java toolchain required for Snapshot sizing fixture')
    root=Path(__file__).resolve().parents[2]
    f=root/'source-families/1.21.X/src/main/java/buildcraft/builders/snapshot/Snapshot.java'
    s=f.read_text()
    a=s.index('    public static int getDataSize(int x, int y, int z) {')
    b=s.index('    public static int getDataSize(BlockPos size)',a)
    method=s[a:b]
    code='''public class SnapshotSizingHarness {
    METHOD
        static void valid(int x,int y,int z,int expected) {
            if (getDataSize(x,y,z)!=expected) throw new AssertionError("wrong size");
        }
        static void invalid(int x,int y,int z) {
            try {getDataSize(x,y,z);} catch(IllegalArgumentException good) {return;}
            throw new AssertionError("invalid dimensions accepted");
        }
        public static void main(String[] args) {
            valid(1,1,1,1);valid(256,256,256,16_777_216);
            valid(512,128,256,16_777_216);
            invalid(0,1,1);invalid(-1,16,16);invalid(256,256,257);
            invalid(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE);
            invalid(Integer.MAX_VALUE,1,Integer.MAX_VALUE);
            System.out.println("8 Snapshot size bounds PASS");
        }
    }
    '''.replace('METHOD',method)
    with tempfile.TemporaryDirectory(prefix='bc-p1-snapshot-') as d:
     p=Path(d);(p/'SnapshotSizingHarness.java').write_text(code)
     x=subprocess.run(['javac','-d',d,str(p/'SnapshotSizingHarness.java')],capture_output=True,text=True,timeout=20)
     if x.returncode:raise AssertionError(x.stderr)
     x=subprocess.run(['java','-cp',d,'SnapshotSizingHarness'],capture_output=True,text=True,timeout=20)
     if x.returncode:raise AssertionError(x.stdout+x.stderr)
     return x.stdout.strip()
