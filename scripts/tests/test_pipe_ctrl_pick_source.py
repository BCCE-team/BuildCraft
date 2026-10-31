"""Guardrails for the BCCE Ctrl+pick client serialization fix.

Run in the BCCE repo after ./Apply-fix.ps1.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
PIPES = [
    "source-platforms/forge/src/main/java/buildcraft/transport/pipe/Pipe.java",
    "source-platforms/neoforge/src/main/java/buildcraft/transport/pipe/Pipe.java",
    "source-family-platforms/26.X/neoforge/src/main/java/buildcraft/transport/pipe/Pipe.java",
    "source-downports/26.X/26.1.2/neoforge/src/main/java/buildcraft/transport/pipe/Pipe.java",
    "source-downports/26.X/26.2/neoforge/src/main/java/buildcraft/transport/pipe/Pipe.java",
]


class PipeCopySafetyTest(unittest.TestCase):
    def test_component_runtime_still_rejects_client_world_saves(self):
        source = (ROOT / "source-shared/src/main/java/buildcraft/transport/api2/PipeComponentRuntime.java").read_text()
        self.assertIn("public boolean isClientReplica() { return client; }", source)
        self.assertIn('if (client) throw new IllegalStateException("Cannot save pipe components on the client");', source)

    def test_client_copy_does_not_touch_authoritative_runtime_state(self):
        for name in PIPES:
            with self.subTest(name=name):
                source = (ROOT / name).read_text()
                start = source.index("public CompoundTag writeToNbt()")
                end = source.index("// network", start)
                method = source[start:end]
                client_start = method.index("if (apiRuntime.isClientReplica())")
                client_end = method.index("return nbt;", client_start)
                client_branch = method[client_start:client_end]
                self.assertIn('nbt.put("flow", new CompoundTag());', client_branch)
                self.assertIn('nbt.put("beh", new CompoundTag());', client_branch)
                self.assertNotIn("apiRuntime.save()", client_branch)
                self.assertNotIn("flow.writeToNbt()", client_branch)
                self.assertNotIn("behaviour.writeToNbt()", client_branch)
                self.assertIn("apiRuntime.save()", method[client_end:])
                self.assertIn("flow.writeToNbt()", method[client_end:])
                self.assertIn('nbt.putInt("con", connectionData)', method[client_end:])


if __name__ == "__main__":
    unittest.main()
