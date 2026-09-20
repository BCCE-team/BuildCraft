#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
LOGIC_ROOT = ROOT / "source-shared/src/main/java/buildcraft/lib/logic"

PROBE = r'''
import buildcraft.lib.logic.blueprint.BlueprintRotation;
import buildcraft.lib.logic.distribution.EqualFlowMath;
import buildcraft.lib.logic.distribution.WeightedAllocation;
import buildcraft.lib.logic.energy.EnergyMath;
import buildcraft.lib.logic.request.RequestMath;
import buildcraft.lib.logic.routing.PriorityGroups;
import buildcraft.lib.logic.routing.WeightedOrder;
import buildcraft.lib.logic.zone.ChunkGridMath;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class PureLogicProbe {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void eq(long actual, long expected, String message) {
        check(actual == expected, message + " expected=" + expected + " actual=" + actual);
    }
    private static void eq(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < 1.0e-9, message + " expected=" + expected + " actual=" + actual);
    }

    public static void main(String[] args) {
        var groups = PriorityGroups.group(new int[]{0,-2,-2,5,0,5}, new boolean[]{true,true,true,true,true,true});
        eq(groups.size(), 3, "priority group count");
        check(Arrays.equals(groups.get(0), new int[]{1,2}), "highest priority group");
        check(Arrays.equals(groups.get(1), new int[]{0,4}), "middle priority group");
        check(Arrays.equals(groups.get(2), new int[]{3,5}), "lowest priority group");
        var equal = PriorityGroups.group(new int[]{7,7,7}, new boolean[]{true,false,true});
        eq(equal.size(), 1, "equal priorities collapse");
        check(Arrays.equals(equal.get(0), new int[]{0,2}), "allowed indices preserved");
        eq(PriorityGroups.group(new int[]{0}, new boolean[]{false}).size(), 0, "empty priorities");

        LinkedHashMap<String,Long> weights = new LinkedHashMap<>();
        weights.put("a", 1L); weights.put("b", 3L); weights.put("ignored", 0L);
        AtomicInteger randomIndex = new AtomicInteger();
        long[] randoms = {0L, 0L};
        var orderA = WeightedOrder.order(weights, () -> randoms[randomIndex.getAndIncrement()]);
        check(orderA.equals(List.of("a", "b")), "weighted order low choice");
        var orderB = WeightedOrder.order(weights, () -> 2L);
        check(orderB.equals(List.of("b", "a")), "weighted order weighted choice");
        LinkedHashMap<String,Long> huge = new LinkedHashMap<>();
        huge.put("a", Long.MAX_VALUE); huge.put("b", 100L);
        check(WeightedOrder.order(huge, () -> Long.MAX_VALUE - 1).size() == 2, "weighted order overflow safe");

        WeightedAllocation allocation = new WeightedAllocation();
        allocation.add(10, 1); allocation.add(30, 1);
        check(allocation.hasDemand(), "allocation has demand");
        eq(allocation.offer(40, 10, 1), 10, "allocation first share");
        eq(allocation.offer(30, 30, 1), 30, "allocation second share");
        WeightedAllocation rejected = new WeightedAllocation();
        rejected.add(10, 1); rejected.add(30, 1);
        eq(rejected.offer(40, 10, 1), 10, "rejected allocation first offer");
        eq(rejected.offer(40, 30, 1), 40, "rejected amount remains available to later route");
        WeightedAllocation overflow = new WeightedAllocation();
        overflow.add(Long.MAX_VALUE, Long.MAX_VALUE); overflow.add(Long.MAX_VALUE, Long.MAX_VALUE);
        check(overflow.offer(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE) > 0, "allocation overflow safe");
        eq(new WeightedAllocation().offer(100, 1, 1), 0, "allocation empty denominator");

        eq(EqualFlowMath.share(100, 100, 2, 100), 50, "equal flow half");
        eq(EqualFlowMath.share(100, 100, 2, 250), 100, "equal flow full capacity");
        eq(EqualFlowMath.share(1, 100, 4, 1), 1, "equal flow keeps one unit");
        eq(EqualFlowMath.share(100, 100, 0, 100), 0, "equal flow zero paths");

        eq(RequestMath.sanitizeTemplateCount(0, 64), 1, "request sanitize zero");
        eq(RequestMath.sanitizeTemplateCount(90, 64), 64, "request sanitize max");
        eq(RequestMath.sanitizeTemplateCount(30, 16), 16, "request item max");
        eq(RequestMath.missingAmount(20, 7), 13, "request missing");
        eq(RequestMath.missingAmount(20, 25), 0, "request fulfilled missing");
        eq(RequestMath.acceptedAmount(50, 20, 7), 13, "request accepted clamps missing");
        check(RequestMath.fulfilled(20,20), "request fulfilled exact");
        check(!RequestMath.fulfilled(20,19), "request not fulfilled");
        eq(RequestMath.comparatorSignal(new int[]{10,10}, new int[]{5,10}, new boolean[]{true,true}), 11, "request comparator partial");
        eq(RequestMath.comparatorSignal(new int[]{10,10}, new int[]{0,0}, new boolean[]{false,false}), 0, "request comparator empty");
        eq(RequestMath.comparatorSignal(new int[]{10}, new int[]{10}, new boolean[]{true}), 15, "request comparator full");

        eq(ChunkGridMath.chunkCoordinate(0), 0, "chunk zero");
        eq(ChunkGridMath.chunkCoordinate(15), 0, "chunk edge");
        eq(ChunkGridMath.chunkCoordinate(16), 1, "chunk next");
        eq(ChunkGridMath.chunkCoordinate(-1), -1, "chunk negative");
        eq(ChunkGridMath.localCoordinate(-1), 15, "local negative");
        eq(ChunkGridMath.minBlock(-2), -32, "chunk min");
        eq(ChunkGridMath.centerBlock(-2), -24, "chunk center");
        eq(ChunkGridMath.squaredDistanceToChunkCenter(0, 0, 0, 0), 128.0D, "chunk distance");

        var point = new BlueprintRotation.Point(0.2D, 0.4D, 0.7D);
        var r0 = BlueprintRotation.rotateUnit(point, 0);
        eq(r0.x(), 0.2D, "rotation 0 x"); eq(r0.z(), 0.7D, "rotation 0 z");
        var r1 = BlueprintRotation.rotateUnit(point, 1);
        eq(r1.x(), 0.3D, "rotation 90 x"); eq(r1.z(), 0.2D, "rotation 90 z");
        var r2 = BlueprintRotation.rotateUnit(point, 2);
        eq(r2.x(), 0.8D, "rotation 180 x"); eq(r2.z(), 0.3D, "rotation 180 z");
        var r3 = BlueprintRotation.rotateUnit(point, 3);
        eq(r3.x(), 0.7D, "rotation 270 x"); eq(r3.z(), 0.8D, "rotation 270 z");
        eq(BlueprintRotation.invertQuarterTurns(1), 3, "rotation invert 90");
        eq(BlueprintRotation.invertQuarterTurns(3), 1, "rotation invert 270");
        eq(BlueprintRotation.invertQuarterTurns(2), 2, "rotation invert 180");

        eq(EnergyMath.saturatingAdd(Long.MAX_VALUE - 2, 10), Long.MAX_VALUE, "long saturating add");
        eq(EnergyMath.saturatingAdd(Integer.MAX_VALUE - 2, 10), Integer.MAX_VALUE, "int saturating add");
        eq(EnergyMath.applyResistance(100, 100, 1000), 90, "energy resistance");
        eq(EnergyMath.applyResistance(1, 999, 1000), 1, "energy minimum retained quantum");
        eq(EnergyMath.applyResistance(100, 1000, 1000), 0, "energy full resistance");
        eq(EnergyMath.inputForDelivered(90, 100, 100, 1000), 100, "energy inverse resistance");
        eq(EnergyMath.inputForDelivered(200, 100, 0, 1000), 100, "energy inverse clamps input");

        System.out.println("Pure Java logic probes: " + checks + " assertions passed");
    }
}
'''


def run() -> str:
    if not shutil.which("javac") or not shutil.which("java"):
        raise RuntimeError("Java javac/java required; pure logic probes were NOT run")
    sources = sorted(LOGIC_ROOT.rglob("*.java"))
    if not sources:
        raise AssertionError("No buildcraft.lib.logic Java sources found")
    with tempfile.TemporaryDirectory(prefix="bc-pure-logic-") as temporary:
        root = Path(temporary)
        probe = root / "PureLogicProbe.java"
        probe.write_text(PROBE, encoding="utf-8")
        classes = root / "classes"
        compiled = subprocess.run(
            ["javac", "--release", "17", "-d", str(classes), *map(str, sources), str(probe)],
            text=True,
            capture_output=True,
        )
        if compiled.returncode:
            raise AssertionError(compiled.stdout + compiled.stderr)
        ran = subprocess.run(
            ["java", "-ea", "-cp", str(classes), "PureLogicProbe"],
            text=True,
            capture_output=True,
        )
        if ran.returncode:
            raise AssertionError(ran.stdout + ran.stderr)
        return ran.stdout.strip()
