"""Regression guards for BC8 item transit lifecycle and transfer ownership."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
FLOW = pathlib.Path('src/main/java/buildcraft/transport/pipe/flow/PipeFlowItems.java')
FLOW_VARIANTS = [
    ROOT / 'source-platforms/forge' / FLOW,
    ROOT / 'source-platforms/neoforge' / FLOW,
    ROOT / 'source-family-platforms/1.21.X/neoforge' / FLOW,
    ROOT / 'source-family-platforms/26.X/neoforge' / FLOW,
    ROOT / 'source-downports/26.X/26.1.2/neoforge' / FLOW,
    ROOT / 'source-downports/26.X/26.2/neoforge' / FLOW,
]


class ItemTransitStage6Tests(unittest.TestCase):
    def test_native_flow_variants(self):
        phases = ('SIDE_CHECK', 'SPLIT', 'FIND_DEST', 'MODIFY_SPEED')
        for path in FLOW_VARIANTS:
            with self.subTest(path=path):
                source = path.read_text(encoding='utf-8')
                for phase in phases:
                    self.assertIn('fireTransit(ItemTransitPhase.' + phase, source)
                self.assertIn('PipeTransitHandoff.begin(item.apiTransitId, item.apiTransitPayload)', source)
                self.assertIn('PipeTransitHandoff.claim()', source)
                self.assertIn('UUID.randomUUID()', source)
                self.assertNotIn('findDest.items.size() == 1 ? item.apiTransitId : null', source)

    def test_nested_transfer_ownership(self):
        java = ROOT / 'source-shared/src/main/java/buildcraft/transport/api2/PipeTransitHandoff.java'
        with tempfile.TemporaryDirectory() as tmp:
            test = pathlib.Path(tmp) / 'Probe.java'
            test.write_text('''import java.util.UUID;
import buildcraft.transport.api2.PipeTransitHandoff;
import buildcraft.api.v2.pipe.ItemTransitPayload;
import java.util.Map;
public class Probe {
  public static void main(String[] args) {
    UUID first=UUID.randomUUID(), second=UUID.randomUUID();
    if (PipeTransitHandoff.take()!=null) throw new AssertionError("unscoped");
    try (var a=PipeTransitHandoff.begin(first)) {
      try (var b=PipeTransitHandoff.begin(second)) {
        if (!second.equals(PipeTransitHandoff.take())) throw new AssertionError("nested");
        if (PipeTransitHandoff.take()!=null) throw new AssertionError("reused");
      }
      if (!first.equals(PipeTransitHandoff.take())) throw new AssertionError("outer");
      if (PipeTransitHandoff.take()!=null) throw new AssertionError("outer reused");
    }
    if (PipeTransitHandoff.take()!=null) throw new AssertionError("leaked");
    byte[] external={1,2,3};
    ItemTransitPayload payload=new ItemTransitPayload(Map.of("logistics:route", external));
    external[0]=8;
    if (payload.get("logistics:route").orElseThrow()[0]!=1) throw new AssertionError("mutable input");
    payload.get("logistics:route").orElseThrow()[0]=9;
    if (payload.get("logistics:route").orElseThrow()[0]!=1) throw new AssertionError("mutable output");
    try (var scope=PipeTransitHandoff.begin(first,payload)) {
      var handoff=PipeTransitHandoff.claim();
      if (!first.equals(handoff.id()) || !payload.equals(handoff.payload())) throw new AssertionError("payload lost");
      if (PipeTransitHandoff.claim().id()!=null) throw new AssertionError("payload claimed twice");
    }
    try { new ItemTransitPayload(Map.of("other:big",new byte[ItemTransitPayload.MAX_ENTRY_BYTES+1]));
      throw new AssertionError("unbounded payload");
    } catch (IllegalArgumentException expected) { }
    try { payload.with("not-namespaced",new byte[]{1});
      throw new AssertionError("invalid namespace");
    } catch (IllegalArgumentException expected) { }
  }
}''', encoding='utf-8')
            subprocess.run(['javac', '-d', tmp, str(ROOT / 'source-shared/src/main/java/buildcraft/api/v2/pipe/ItemTransitPayload.java'), str(java), str(test)], check=True, capture_output=True)
            subprocess.run(['java', '-cp', tmp, 'Probe'], check=True, capture_output=True)


if __name__ == '__main__':
    unittest.main()
