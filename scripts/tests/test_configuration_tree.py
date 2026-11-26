"""Exercise the actual loader-independent navigation model (no Minecraft required)."""
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODEL = ROOT / "source-shared/src/main/java/buildcraft/lib/client/config/ConfigurationTree.java"
PROBE = r'''package buildcraft.lib.client.config;
import java.util.*;
public class TreeProbe {
    static void ok(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        var accessibility=List.of("Accessibility");
        var parent=List.of("Interface");
        var guide=List.of("Interface","Guide");
        var deeper=List.of("Interface","Guide","Search");
        var slash=List.of("Interface/Guide"); // distinct real category, not a joined-string alias
        var categories=List.of(accessibility,parent,guide,deeper,slash);
        Set<List<String>> expanded=new HashSet<>();
        var collapsed=ConfigurationTree.visible(categories,expanded);
        ok(collapsed.size()==3);
        ok(collapsed.stream().noneMatch(n->n.path().isEmpty()));
        ok(collapsed.stream().filter(n->n.path().equals(parent)).allMatch(ConfigurationTree.Node::branch));
        expanded.add(parent);
        var opened=ConfigurationTree.visible(categories,expanded);
        ok(opened.stream().anyMatch(n->n.path().equals(parent)&&n.general()&&!n.branch()&&n.depth()==1));
        ok(opened.stream().anyMatch(n->n.path().equals(guide)&&n.branch()));
        ok(opened.stream().noneMatch(n->n.path().equals(deeper)));
        expanded.add(guide);
        var nested=ConfigurationTree.visible(categories,expanded);
        ok(nested.stream().anyMatch(n->n.path().equals(guide)&&n.general()&&!n.branch()));
        ok(nested.stream().anyMatch(n->n.path().equals(deeper)&&!n.branch()));
        ok(ConfigurationTree.under(deeper,parent));
        ok(!ConfigurationTree.under(slash,parent));
        expanded.remove(parent);
        ok(ConfigurationTree.visible(categories,expanded).equals(collapsed));
        System.out.println("Configuration tree tests passed");
    }
}
'''

class ConfigurationTreeTest(unittest.TestCase):
    def test_tree(self):
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            probe = work / "TreeProbe.java"
            probe.write_text(PROBE, encoding="utf-8")
            subprocess.run(["javac", "--release", "17", "-d", str(work), str(MODEL), str(probe)], check=True, timeout=60)
            result = subprocess.run(["java", "-cp", str(work), "buildcraft.lib.client.config.TreeProbe"], capture_output=True, text=True, check=True, timeout=30)
            self.assertIn("Configuration tree tests passed", result.stdout)

if __name__ == "__main__":
    unittest.main()
