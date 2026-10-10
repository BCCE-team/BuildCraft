#!/usr/bin/env python3
"""26.3 compile error followup after the removed-NeoForge-API shim migration.

Tests actual selected/materialized sources, not only the handwritten transform.
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_layout import load_properties, target_layout, resolve_effective_source, _materialize_text_file
from transforms.neoforge263 import upgrade_minecraft_263_apis
from minecraft_compat_fixture import parse_sources

ERROR_FILES = (
    'src/main/java/buildcraft/builders/BCBuilders.java',
    'src/main/java/buildcraft/builders/BCBuildersSchematics.java',
    'src/main/java/buildcraft/builders/item/ItemSchematicSingle.java',
    'src/main/java/buildcraft/builders/menu/ContainerBuilder.java',
    'src/main/java/buildcraft/builders/snapshot/SchematicBlockDefault.java',
    'src/main/java/buildcraft/builders/snapshot/SchematicBlockFluid.java',
    'src/main/java/buildcraft/core/BCCore.java',
    'src/main/java/buildcraft/core/client/render/RenderEngine_BC8.java',
    'src/main/java/buildcraft/core/item/ItemMapLocation.java',
    'src/main/java/buildcraft/core/item/ItemMarkerConnector.java',
    'src/main/java/buildcraft/core/item/ItemWrench.java',
    'src/main/java/buildcraft/energy/BCEnergy.java',
    'src/main/java/buildcraft/energy/BCEnergyBiomeModifiers.java',
    'src/main/java/buildcraft/energy/BCEnergyFluids.java',
    'src/main/java/buildcraft/energy/generation/features/OilStructure.java',
    'src/main/java/buildcraft/factory/BCFactory.java',
    'src/main/java/buildcraft/factory/client/render/RenderHeatExchange.java',
    'src/main/java/buildcraft/factory/container/ContainerAutoCraftItems.java',
    'src/main/java/buildcraft/lib/client/guide/GuiGuide.java',
    'src/main/java/buildcraft/lib/fluid/BCFluid.java',
    'src/main/java/buildcraft/lib/gui/BuildCraftGui.java',
    'src/main/java/buildcraft/lib/gui/component/AbstractComponent.java',
    'src/main/java/buildcraft/lib/gui/component/TankComponent.java',
    'src/main/java/buildcraft/lib/misc/InventoryUtil.java',
    'src/main/java/buildcraft/robotics/boards/BoardRobotFarmer.java',
    'src/main/java/buildcraft/robotics/boards/BoardRobotLumberjack.java',
    'src/main/java/buildcraft/robotics/boards/BoardRobotShovelman.java',
    'src/main/java/buildcraft/robotics/client/render/RenderRobot.java',
    'src/main/java/buildcraft/robotics/entity/EntityRobot.java',
    'src/main/java/buildcraft/robotics/gui/GuiZonePlanner.java',
    'src/main/java/buildcraft/robotics/plug/RobotStationPluggable.java',
    'src/main/java/buildcraft/robotics/tile/TileZonePlanner.java',
    'src/main/java/buildcraft/silicon/BCSilicon.java',
    'src/main/java/buildcraft/silicon/client/model/plug/PlugBakerFacade.java',
    'src/main/java/buildcraft/transport/BCTransport.java',
    'src/main/java/buildcraft/transport/block/BlockPipeHolder.java',
    'src/main/java/buildcraft/transport/client/render/RenderPipeHolder.java',
    'src/main/java/buildcraft/transport/pipe/behaviour/PipeBehaviourObsidian.java',
    'src/main/java/buildcraft/transport/pipe/behaviour/PipeBehaviourStripes.java',
    'src/main/java/buildcraft/transport/pipe/behaviour/PipeBehaviourWood.java',
    'src/main/java/buildcraft/transport/pipe/flow/PipeFlowPower.java',
    'src/main/java/buildcraft/transport/stripes/StripesHandlerHoe.java',
)

EXTRA_FILES = (
    "src/main/java/buildcraft/lib/internal/mj/IMjReceiver.java",
    "src/main/java/buildcraft/lib/internal/mj/MjBattery.java",
)

BLOCKED = (
    "getGuiLeft()", "getGuiTop()", "getXSize()", "getYSize()", "Type.COMMON",
    ".blocksMotion()", "PushReaction.DESTROY", "instanceof HoeItem",
    "instanceof AxeItem", "instanceof ShovelItem", "GLFW.GLFW_KEY",
    "import org.lwjgl.glfw.GLFW;", "material.shade()", ".mulPose(Axis.",
    "chunkPos.x,", "BlockPos::new", "public void playerDestroy(Level world",
    "spawnDestroyParticles(world, player", "Util.getPlatform().openUri",
    "player.swingingArm =",
)

class CompileFollowup263(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="bcce-263-followup-")
        props = load_properties()
        cls.sources = {}
        for version in ("26.3", "26.2"):
            layout = target_layout(version + "-neoforge", props)
            for path in (*ERROR_FILES, *EXTRA_FILES) if version == "26.3" else (
                "src/main/java/buildcraft/lib/gui/BuildCraftGui.java",
                "src/main/java/buildcraft/energy/BCEnergyBiomeModifiers.java",
                "src/main/java/buildcraft/lib/internal/mj/IMjReceiver.java",
                "src/main/java/buildcraft/robotics/boards/BoardRobotFarmer.java",
            ):
                original = resolve_effective_source(layout, props, path)
                if original is None:
                    raise AssertionError(f"Missing {version} selected source: {path}")
                dst = Path(cls.tmp.name) / version / path
                _materialize_text_file(original, dst, logical_relative=path,
                    minecraft=version, family=layout.family, platform=layout.platform,
                    preprocess=True)
                cls.sources[version, path] = dst.read_text(encoding="utf-8")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def get_source(self, path):
        return self.sources["26.3", "src/main/java/buildcraft/" + path]

    def test_original_compilation_log_all_failing_sources_selected(self):
        self.assertEqual(42, len(ERROR_FILES))
        self.assertEqual(len(ERROR_FILES) + len(EXTRA_FILES), len([k for k in self.sources if k[0] == "26.3"]))

    def test_263_reported_sources_parse_syntax(self):
        result = parse_sources((Path(self.tmp.name) / "26.3",))
        self.assertIn("0 errors", result)

    def test_removed_263_symbols_not_in_effective_java(self):
        for (v, rel), src in self.sources.items():
            if v != "26.3": continue
            with self.subTest(path=rel):
                for token in BLOCKED:
                    self.assertNotIn(token, src, token)

    def test_262_transformation_is_identity(self):
        sample = ("Type.COMMON\ngetGuiLeft()\nPushReaction.DESTROY\n"
                  "foo.mulPose(Axis.XP.rotationDegrees(90));")
        self.assertEqual(sample, upgrade_minecraft_263_apis(
            sample, minecraft="26.2", relative="src/main/java/buildcraft/Test.java"))
        self.assertIn("gui::getXSize", self.sources["26.2", "src/main/java/buildcraft/lib/gui/BuildCraftGui.java"])

    def test_required_263_migrations_are_present(self):
        checks = (
            ("lib/gui/BuildCraftGui.java", "gui::getImageWidth"),
            ("energy/BCEnergyBiomeModifiers.java", "modify(RegistryAccess registries,"),
            ("energy/BCEnergyFluids.java", "PushReaction.POPPED"),
            ("robotics/gui/GuiZonePlanner.java", "InputConstants.KEY_RETURN"),
            ("robotics/tile/TileZonePlanner.java", "chunkPos.x()"),
            ("transport/client/render/RenderPipeHolder.java", "setUv3(float u, float v)"),
            ("builders/snapshot/SchematicBlockFluid.java", "pos.getZ()"),
            ("core/item/ItemWrench.java", "SwingAnimation.DEFAULT"),
            ("transport/pipe/flow/PipeFlowPower.java", "FluidAction.valueOf(action.name())"),
            ("lib/internal/mj/IMjReceiver.java", "Enum<?> action"),
            ("lib/internal/mj/MjBattery.java", "Enum<?> action"),
            ("robotics/boards/BoardRobotFarmer.java", "ItemTags.HOES"),
            ("factory/container/ContainerAutoCraftItems.java", "new ItemProvider(slot ->"),
            ("builders/menu/ContainerBuilder.java", "new ItemProvider(slot ->"),
        )
        for rel, token in checks:
            with self.subTest(rel=rel):
                self.assertIn(token, self.get_source(rel))

    def test_last_three_reported_263_compile_errors(self):
        pipe = self.get_source("transport/block/BlockPipeHolder.java")
        self.assertIn(
            "public void playerDestroy(ServerLevel world, net.minecraft.server.level.ServerPlayer player,",
            pipe)
        self.assertNotIn("public void playerDestroy(ServerLevel world, Player player,", pipe)

        zone = self.get_source("robotics/gui/GuiZonePlanner.java")
        self.assertIn("InputConstants.KEY_MINUS", zone)
        self.assertIn("org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_KP_MINUS", zone)
        self.assertNotIn("InputConstants.KEY_SUBTRACT", zone)
        self.assertNotIn("GLFW.GLFW_KEY_KP_SUBTRACT", zone)

        renderer = self.get_source("transport/client/render/RenderPipeHolder.java")
        self.assertEqual(1, renderer.count("public VertexConsumer setUv3(float u, float v)"))
        self.assertIn("@Override\n        public VertexConsumer setUv3(float u, float v)", renderer)
        self.assertNotIn("//? if >=26.3", renderer)

    def test_263_discarding_consumer_conforms_to_vertexconsumer_java(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("JDK not available")
        src = self.get_source("transport/client/render/RenderPipeHolder.java")
        begin = src.index("private static final VertexConsumer DISCARDING_VERTEX_CONSUMER")
        end = src.index("public static final Direction[] renderFacing", begin)
        body = src[begin:end]
        contract = """interface VertexConsumer {
            VertexConsumer addVertex(float x,float y,float z);
            VertexConsumer setColor(int r,int g,int b,int a);
            VertexConsumer setUv(float u,float v);
            VertexConsumer setUv1(int u,int v);
            VertexConsumer setUv2(int u,int v);
            VertexConsumer setUv3(float u,float v);
            VertexConsumer setNormal(float x,float y,float z);
            VertexConsumer setLineWidth(float w);
        }
        public class CheckVertex263 {
        """ + body + """
            public static void main(String[] args) {
                var c = DISCARDING_VERTEX_CONSUMER;
                if (c.setUv3(.25f, .75f) != c || c.setUv2(4, 12) != c)
                    throw new AssertionError("Discarding consumer must be chainable");
                System.out.println("VERTEX CONTRACT PASS");
            }
        }
        """
        with tempfile.TemporaryDirectory(prefix="bcce-263-vertex-final-") as tmp:
            path = Path(tmp) / "CheckVertex263.java"
            path.write_text(contract, encoding="utf-8")
            build = subprocess.run(["javac", str(path)], capture_output=True,
                text=True, timeout=30)
            self.assertEqual(0, build.returncode, build.stderr)
            run = subprocess.run(["java", "-cp", tmp, "CheckVertex263"],
                capture_output=True, text=True, timeout=20)
            self.assertEqual(0, run.returncode, run.stderr)
            self.assertIn("VERTEX CONTRACT PASS", run.stdout)

    def test_263_player_destroy_overrides_server_player_signature(self):
        if not shutil.which("javac"):
            self.skipTest("JDK not available")
        source = self.get_source("transport/block/BlockPipeHolder.java")
        start = source.index("public void playerDestroy(")
        end = source.index("\n\t}", start) + len("\n\t}")
        method = source[start:end]
        fixture = """class Level {}
        class ServerLevel extends Level {}
        class Player {}
        class ServerPlayer extends Player {}
        class BlockPos {}
        class BlockState {}
        class BlockEntity {}
        class ItemStack {}
        class Block {
          public void playerDestroy(ServerLevel world, ServerPlayer player,
              BlockPos pos, BlockState state, BlockEntity be, ItemStack stack) {}
        }
        public class CheckDestroy263 extends Block {
        """ + "\n    @Override\n    " + method.replace(
            "net.minecraft.server.level.ServerPlayer", "ServerPlayer") + "\n}\n"
        with tempfile.TemporaryDirectory(prefix="bcce-263-player-final-") as tmp:
            path = Path(tmp) / "CheckDestroy263.java"
            path.write_text(fixture, encoding="utf-8")
            build = subprocess.run(["javac", str(path)], capture_output=True,
                text=True, timeout=30)
            self.assertEqual(0, build.returncode, build.stderr)

    def test_262_renderer_and_player_signatures_unchanged(self):
        props = load_properties()
        layout = target_layout("26.2-neoforge", props)
        for file in ("transport/client/render/RenderPipeHolder.java",
                     "transport/block/BlockPipeHolder.java",
                     "robotics/gui/GuiZonePlanner.java"):
            path = "src/main/java/buildcraft/" + file
            original = resolve_effective_source(layout, props, path)
            self.assertIsNotNone(original)
            with tempfile.TemporaryDirectory(prefix="bcce-262-no-regression-") as tmp:
                output = Path(tmp) / "Output.java"
                _materialize_text_file(original, output, logical_relative=path,
                    minecraft="26.2", family=layout.family, platform=layout.platform,
                    preprocess=True)
                result = output.read_text(encoding="utf-8")
                if file.endswith("RenderPipeHolder.java"):
                    self.assertNotIn("public VertexConsumer setUv3(float u, float v)", result)
                elif file.endswith("BlockPipeHolder.java"):
                    self.assertIn("public void playerDestroy(Level world, Player player,", result)
                else:
                    self.assertIn("GLFW.GLFW_KEY_KP_SUBTRACT", result)

    def test_vertex_fallback_only_inserts_once(self):
        sample = """class Test {
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }
        }"""
        path = "src/main/java/buildcraft/transport/client/render/RenderPipeHolder.java"
        output = upgrade_minecraft_263_apis(sample, minecraft="26.3", relative=path)
        self.assertEqual(1, output.count("setUv3(float u, float v)"))
        output = upgrade_minecraft_263_apis(output, minecraft="26.3", relative=path)
        self.assertEqual(1, output.count("setUv3(float u, float v)"))

    def test_power_adapter_java_execution(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("JDK not available")
        with tempfile.TemporaryDirectory(prefix="bcce-263-power-") as tmp:
            root = Path(tmp)
            fixture = {
                "buildcraft/lib/internal/mj/IMjReceiver.java": self.get_source("lib/internal/mj/IMjReceiver.java"),
                "buildcraft/lib/internal/mj/IMjConnector.java": "package buildcraft.lib.internal.mj; public interface IMjConnector {}",
                "buildcraft/lib/compat/neoforge263/fluids/IFluidHandler.java":
                    "package buildcraft.lib.compat.neoforge263.fluids; public interface IFluidHandler { enum FluidAction { EXECUTE, SIMULATE; } }",
                "buildcraft/transport/internal/pipe/FluidAction.java":
                    "package buildcraft.transport.internal.pipe; public enum FluidAction { EXECUTE, SIMULATE; }",
                "VerifyPower263.java": POWER_HARNESS,
            }
            for name, value in fixture.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(value, encoding="utf-8")
            build = subprocess.run(["javac", "-d", str(root / "classes"),
                *[str(root / name) for name in fixture]], capture_output=True, text=True, timeout=30)
            self.assertEqual(0, build.returncode, build.stderr)
            run = subprocess.run(["java", "-cp", str(root / "classes"), "VerifyPower263"],
                capture_output=True, text=True, timeout=20)
            self.assertEqual(0, run.returncode, run.stderr)
            self.assertIn("POWER BRIDGE PASS", run.stdout)

POWER_HARNESS = """
import buildcraft.lib.internal.mj.IMjReceiver;
import buildcraft.lib.compat.neoforge263.fluids.IFluidHandler.FluidAction;
class Legacy implements IMjReceiver {
  long executed = 0; long simulated = 0;
  public long getPowerRequested() { return 1000; }
  public long receivePower(long n, FluidAction mode) {
    if (mode == FluidAction.EXECUTE) executed += n;
    else simulated += n;
    return 0;
  }
}
class Transport implements IMjReceiver {
  long executed = 0; long simulated = 0;
  public long getPowerRequested() { return 1000; }
  public long receivePower(long n, buildcraft.transport.internal.pipe.FluidAction mode) {
    if (mode == buildcraft.transport.internal.pipe.FluidAction.EXECUTE) executed += n;
    else simulated += n;
    return 0;
  }
  public long receivePower(long n, FluidAction mode) {
    return receivePower(n, buildcraft.transport.internal.pipe.FluidAction.valueOf(mode.name()));
  }
}
public class VerifyPower263 {
  public static void main(String[] args) {
    Legacy a = new Legacy(); Transport b = new Transport();
    IMjReceiver ra = a, rb = b;
    ra.receivePower(14, buildcraft.transport.internal.pipe.FluidAction.SIMULATE);
    ra.receivePower(12, buildcraft.transport.internal.pipe.FluidAction.EXECUTE);
    rb.receivePower(7, FluidAction.EXECUTE);
    rb.receivePower(11, buildcraft.transport.internal.pipe.FluidAction.SIMULATE);
    if (a.simulated != 14 || a.executed != 12 || b.executed != 7 || b.simulated != 11)
      throw new AssertionError("Power-action crossover broken");
    System.out.println("POWER BRIDGE PASS");
  }
}
"""

if __name__ == "__main__": unittest.main()
