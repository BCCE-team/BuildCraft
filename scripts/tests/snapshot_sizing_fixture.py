#!/usr/bin/env python3
"""Compile the real Snapshot.getDataSize() in both source families.

The security limit is 2,097,152 blocks and 512 blocks along any axis.
Boundary probes must reject oversized requests rather than relaxing the limit.
"""
from __future__ import annotations

from pathlib import Path
import shutil
import subprocess
import tempfile


FAMILIES = ('old', '1.21.X')
EXPECTED = '23 Snapshot size bounds PASS'


def execute() -> str:
    if not shutil.which('javac') or not shutil.which('java'):
        raise RuntimeError('Java toolchain required for Snapshot sizing fixture')

    root = Path(__file__).resolve().parents[2]
    for family in FAMILIES:
        source_file = root / 'source-families' / family / 'src/main/java/buildcraft/builders/snapshot/Snapshot.java'
        source = source_file.read_text(encoding='utf-8')
        start = source.index('    public static int getDataSize(int x, int y, int z) {')
        end = source.index('    public static int getDataSize(BlockPos size)', start)
        method = source[start:end]

        code = '''public class SnapshotSizingHarness {
            METHOD
            private static int checks;

            static void valid(int x, int y, int z, int expected) {
                int actual = getDataSize(x, y, z);
                if (actual != expected) {
                    throw new AssertionError("wrong size for " + x + "x" + y + "x" + z
                            + ": expected " + expected + ", got " + actual);
                }
                checks++;
            }

            static void invalid(int x, int y, int z) {
                try {
                    getDataSize(x, y, z);
                } catch (IllegalArgumentException expected) {
                    checks++;
                    return;
                }
                throw new AssertionError("invalid dimensions accepted: " + x + "x" + y + "x" + z);
            }

            public static void main(String[] args) {
                // Exactly at the 2,097,152-block limit in several orientations.
                valid(1, 1, 1, 1);
                valid(128, 128, 128, 2_097_152);
                valid(512, 512, 8, 2_097_152);
                valid(512, 8, 512, 2_097_152);
                valid(8, 512, 512, 2_097_152);
                valid(256, 256, 32, 2_097_152);
                valid(512, 1, 512, 262_144);
                valid(1, 512, 512, 262_144);

                // Zero/negative dimensions are invalid on every axis.
                invalid(0, 1, 1);
                invalid(-1, 16, 16);
                invalid(1, 0, 1);
                invalid(1, -1, 1);
                invalid(1, 1, 0);
                invalid(1, 1, -1);

                // Exceeding the per-axis or total-volume limit must fail closed.
                invalid(513, 1, 1);
                invalid(1, 513, 1);
                invalid(1, 1, 513);
                invalid(256, 256, 33);
                invalid(512, 512, 9);
                invalid(256, 256, 256);
                invalid(512, 128, 256);

                // Multiplication of hostile dimensions must never overflow to a valid size.
                invalid(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
                invalid(Integer.MAX_VALUE, 1, Integer.MAX_VALUE);

                System.out.println(checks + " Snapshot size bounds PASS");
            }
        }
        '''.replace('METHOD', method)

        with tempfile.TemporaryDirectory(prefix='bc-snapshot-size-') as tmp:
            source_path = Path(tmp) / 'SnapshotSizingHarness.java'
            source_path.write_text(code, encoding='utf-8')
            compiled = subprocess.run(
                ['javac', '-d', tmp, str(source_path)],
                capture_output=True, text=True, timeout=20,
            )
            if compiled.returncode:
                raise AssertionError(f'{family}: {compiled.stdout}{compiled.stderr}')
            executed = subprocess.run(
                ['java', '-cp', tmp, 'SnapshotSizingHarness'],
                capture_output=True, text=True, timeout=20,
            )
            if executed.returncode:
                raise AssertionError(f'{family}: {executed.stdout}{executed.stderr}')
            if executed.stdout.strip() != EXPECTED:
                raise AssertionError(f'{family}: unexpected harness output {executed.stdout!r}')

    return f'{EXPECTED} ({", ".join(FAMILIES)})'
