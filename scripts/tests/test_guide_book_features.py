#!/usr/bin/env python3
"""Guide Book filtering, addon pages, pagination, and live-preview regression tests."""
from __future__ import annotations

from pathlib import Path
import sys
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_layout import load_properties, materialize_target
from source_lookup import read_materialized_java


class GuideBookFeatures(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bc-guide-features-")
        cls.props = load_properties()
        cls.roots: dict[str, Path] = {}
        for target in ("1.21.1-neoforge", "1.21.11-neoforge"):
            path = Path(cls.temp.name) / target
            materialize_target(target, path, cls.props)
            cls.roots[target] = path

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def java(self, target: str, logical: str) -> str:
        return (self.roots[target] / "src/main/java" / logical).read_text(encoding="utf-8")

    def test_filtering_is_tokenized_and_order_independent(self):
        for target in self.roots:
            content = self.java(target, "buildcraft/lib/client/guide/GuideContent.java")
            gui = self.java(target, "buildcraft/lib/client/guide/GuiGuide.java")
            self.assertIn("private static String normalizeSearch(String value)", content, target)
            self.assertIn("Character.isLetterOrDigit(ch)", content, target)
            self.assertIn("public boolean matchesSearch(String query)", content, target)
            self.assertIn('for (String token : normalized.split(" "))', content, target)
            self.assertIn("entry.matchesSearch(query)", gui, target)
            self.assertNotIn("entry.searchText.contains(query)", gui, target)


    def test_filtering_honours_configured_result_cap(self):
        for target in self.roots:
            gui = self.java(target, "buildcraft/lib/client/guide/GuiGuide.java")
            config = self.java(target, "buildcraft/core/BCCoreConfig.java")
            self.assertIn("BCLibConfig.maxGuideSearchCount = Mth.clamp(propMaxGuideSearchResults.get(), 500, 5000);", config, target)
            self.assertIn("int maxSearchResults = Math.max(1, BCLibConfig.maxGuideSearchCount);", gui, target)
            self.assertIn("filteredEntries.subList(maxSearchResults, matchCount).clear();", gui, target)
            self.assertIn("realSearchResultCount = matchCount;", gui, target)
            self.assertIn('Component.translatable("buildcraft.guide.too_many_results", realSearchResultCount)', gui, target)

    def test_filter_results_move_to_a_visible_left_contents_page(self):
        for target in self.roots:
            gui = self.java(target, "buildcraft/lib/client/guide/GuiGuide.java")
            self.assertIn("if (!query.isBlank() && !isContentsEntryPage(contentsSpread * 2))", gui, target)
            self.assertIn("contentsSpread = Math.min(firstFullContentsSpread(), maxContentsSpread());", gui, target)
            self.assertIn("boolean visible = view == View.CONTENTS && isContentsEntryPage(leftPage);", gui, target)
            self.assertIn("if (!visible && searchBox.isFocused())", gui, target)

    def test_addon_loaded_pages_do_not_shift_contents_to_right_page(self):
        for target in self.roots:
            gui = self.java(target, "buildcraft/lib/client/guide/GuiGuide.java")
            self.assertIn("int raw = 1 + loadedGuidePageCount();", gui, target)
            self.assertIn("return (raw + 1) & ~1;", gui, target)
            self.assertIn("return firstContentsPageIndex() / 2;", gui, target)

    def test_api_pages_do_not_duplicate_the_synthetic_title(self):
        for target in self.roots:
            content = self.java(target, "buildcraft/lib/client/guide/GuideContent.java")
            start = content.index("private static String renderApiPages")
            end = content.index("private static String readText", start)
            block = content[start:end]
            self.assertNotIn('markdown.append("# ")', block, target)
            self.assertIn("page instanceof GuidePage.Text", block, target)
            self.assertIn("page instanceof GuidePage.Image", block, target)
            self.assertIn("page instanceof GuidePage.Link", block, target)
            self.assertIn("page instanceof GuidePage.Item", block, target)
            self.assertIn("page instanceof GuidePage.Recipe", block, target)

    def test_pagination_handles_half_spreads_blank_pages_and_last_page_state(self):
        for target in self.roots:
            gui = self.java(target, "buildcraft/lib/client/guide/GuiGuide.java")
            self.assertIn("rightTexture = RIGHT_PAGE_BACK;", gui, target)
            self.assertIn("if (page.elements.isEmpty() && y == 0) return;", gui, target)
            self.assertIn("boolean wasLastSpread = document != null && oldSpread >= document.maxSpread();", gui, target)
            self.assertIn("documentSpread = wasLastSpread ? document.maxSpread()", gui, target)

    def test_long_titles_and_many_addon_chapters_are_bounded(self):
        for target in self.roots:
            gui = self.java(target, "buildcraft/lib/client/guide/GuiGuide.java")
            self.assertIn("Math.min(PAGE_TEXT_HEIGHT, Math.max(blockHeight, font.lineHeight * 4))", gui, target)
            self.assertIn("if (index > 0) ensure(11);", gui, target)
            self.assertIn("PAGE_TEXT_HEIGHT - y", gui, target)
            self.assertIn("private int maximumChapterTabs()", gui, target)
            self.assertIn("private static int tabWindowStart", gui, target)
            self.assertIn("topLevel = document.chapters.stream()", gui, target)

    def test_1_21_11_recipe_cache_preserves_datapack_ids_and_has_revision(self):
        cache = self.java("1.21.11-neoforge", "buildcraft/lib/net/GuideRecipeDisplayCache.java")
        message = self.java("1.21.11-neoforge", "buildcraft/lib/net/MessageGuideRecipeDisplays.java")
        gui = self.java("1.21.11-neoforge", "buildcraft/lib/client/guide/GuiGuide.java")

        self.assertIn("record Entry(Identifier recipeId, RecipeDisplayEntry display)", cache)
        self.assertIn("AtomicLong REVISION", cache)
        self.assertIn("public static long revision()", cache)
        self.assertIn("private static volatile boolean synchronised", cache)
        self.assertIn("if (synchronised) return entries;", cache)

        # Fault isolation must preserve the authoritative datapack id, rather than
        # synthesising an id from the client display or dropping healthy recipes.
        self.assertIn('Identifier recipeId = GuideFaultIsolation.value("recipe id", "recipe list", () ->', message)
        self.assertIn("recipe.id().identifier(), null);", message)
        self.assertIn("if (recipeId == null) continue;", message)
        self.assertIn("new GuideRecipeDisplayCache.Entry(recipeId, display)", message)
        self.assertIn('Boolean selected = GuideFaultIsolation.value("recipe type", String.valueOf(recipeId), () ->', message)
        self.assertIn("recipe.value().getType() == RecipeType.CRAFTING", message)
        self.assertIn("|| recipe.value().getType() == BCSiliconRecipes.ASSEMBLY_TYPE.get()", message)
        self.assertIn("if (!selected) continue;", message)

        # The hardened protocol stages each display independently and transmits
        # only successfully encoded, length-framed entries. Decoding reads the
        # same id from each isolated frame, not from the surrounding packet.
        self.assertIn("staging.writeIdentifier(entry.recipeId());", message)
        self.assertIn("Identifier recipeId = entryBuffer.readIdentifier();", message)
        self.assertIn("if (entryBuffer.readableBytes() != 0)", message)
        self.assertIn("buffer.writeVarInt(WIRE_MAGIC);", message)
        self.assertIn("buffer.writeVarInt(WIRE_VERSION);", message)
        self.assertIn("buffer.writeVarInt(frames.size());", message)
        self.assertIn("buffer.writeVarInt(frame.length);", message)
        self.assertIn("buffer.readBytes(payload);", message)
        self.assertLess(message.index("staging.writeIdentifier(entry.recipeId());"),
                        message.index("buffer.writeVarInt(WIRE_MAGIC);"))

        self.assertIn("private long recipeDisplayRevision = GuideRecipeDisplayCache.revision();", gui)
        self.assertIn("if (revision != recipeDisplayRevision)", gui)
        self.assertIn("new GuideRecipe(entry.recipeId(), entry.display())", gui)
        self.assertNotIn("clientRecipeKey(RecipeDisplayEntry", gui)


    def test_1_21_11_keeps_multiple_displays_from_one_recipe_distinct(self):
        gui = self.java("1.21.11-neoforge", "buildcraft/lib/client/guide/GuiGuide.java")
        cache = self.java("1.21.11-neoforge", "buildcraft/lib/net/GuideRecipeDisplayCache.java")
        self.assertIn("Set<GuideRecipeDisplayKey> renderedRecipeDisplays", gui)
        self.assertIn("new GuideRecipeDisplayKey(id, value.id().index())", gui)
        # Faulty addon displays are ignored, but deduplication still uses both
        # the datapack recipe id and the individual display id for healthy ones.
        self.assertIn('GuideRecipeDisplayKey key = GuideFaultIsolation.value("guide layout",', gui)
        self.assertIn("recipe::displayKey, null);", gui)
        self.assertIn("if (key == null || !renderedRecipeDisplays.add(key)) return;", gui)
        self.assertIn(".filter(recipe -> !renderedRecipeDisplays.contains(recipe.displayKey()))", gui)
        self.assertIn("Set<Identifier> directIds = recipes.stream().map(GuideRecipe::id)", gui)
        self.assertIn(".forEach(recipe -> addRecipe(recipe, ItemStack.EMPTY));", gui)
        self.assertNotIn(".findFirst()\n                    .ifPresent(recipe -> addRecipe(recipe, ItemStack.EMPTY));", gui)
        self.assertIn("Integer.toUnsignedString(display.id().index(), 16)", cache)

    def test_1_21_11_datapack_reload_resends_live_recipe_previews(self):
        event = self.java("1.21.11-neoforge", "buildcraft/lib/BCLibEventDist.java")
        self.assertIn("OnDatapackSyncEvent", event)
        self.assertIn("public static void onDatapackSync(OnDatapackSyncEvent event)", event)
        self.assertIn("if (event.getPlayer() == null)", event)
        self.assertIn("event.getRelevantPlayers().forEach", event)
        self.assertIn("MessageUtil.doDelayedServer(1", event)
        # A full reload must resend every recipe display using the packet-batched path.
        # create(player) is limited to a small, single-packet index and fails on large packs.
        self.assertIn("MessageGuideRecipeDisplays.sendAll(player)", event)
        self.assertIn("MessageGuideRecipeDisplays.sendAll(playerMP)", event)
        self.assertNotIn("MessageGuideRecipeDisplays.create(", event)
        message = self.java("1.21.11-neoforge", "buildcraft/lib/net/MessageGuideRecipeDisplays.java")
        self.assertIn("public static void sendAll(ServerPlayer player)", message)
        self.assertIn("current.size() >= MAX_ENTRIES_PER_PACKET", message)
        self.assertIn("bytes + estimated > MAX_PACKET_BYTES", message)
        self.assertIn("MessageManager.sendTo(new MessageGuideRecipeDisplays(batches.get(i), i, batches.size()), player);", message)

        # The 1.21.1 downport keeps its native RecipeManager-backed guide path and must not acquire
        # the 1.21.11-only display-sync protocol just to implement the common UI hardening.
        old_event = self.java("1.21.1-neoforge", "buildcraft/lib/BCLibEventDist.java")
        self.assertNotIn("MessageGuideRecipeDisplays", old_event)
        self.assertNotIn("OnDatapackSyncEvent", old_event)


# Compile the actual selected marquee methods against lightweight rendering probes.
# This exercises clipping and restoration, not the Minecraft GPU implementation.
class GuideMarqueeClipping(unittest.TestCase):
    def test_label_clipping_on_all_targets(self):
        classes = []
        for index, target in enumerate(load_properties()["targets"].split(",")):
            gui = read_materialized_java(target, "src/main/java/buildcraft/lib/client/guide/GuiGuide.java")
            start = gui.index("    private void drawOverflowText(", gui.index("    private void drawOverflowText(") + 1)
            end = gui.index("    private static int parseInt", start)
            methods = gui[start:end]
            legacy = "PoseStack pose" in methods
            graphics = "PoseStack" if legacy else ("GuiGraphicsExtractor" if "GuiGraphicsExtractor guiGraphics" in methods else "GuiGraphics")
            if not legacy:
                self.assertIn("guiGraphics.enableScissor(", methods, target)
                self.assertIn("guiGraphics.disableScissor();", methods, target)
                self.assertNotIn("RenderSystem.enableScissor", methods, target)
                self.assertNotIn("RenderCompat.enableScissor", methods, target)
            classes.append(f"""
    static class Target{index} {{
        int width=320, height=240, tick;
        Font font=new Font();
        {methods}
        void draw(int x,int y,int w,int textWidth, HorizontalAlignment alignment) {{
            drawOverflowText(new {graphics}(), new FormattedCharSequence(textWidth), x,y,w,0,alignment,0);
        }}
        void run() {{
            reset(); tick=0;
            draw(50,30,30,100,HorizontalAlignment.LEFT);
            check(50,29,80,40,50);
            tick=195; // the other end of the same marquee
            draw(50,30,30,100,HorizontalAlignment.LEFT);
            check(50,29,80,40,-20);
            tick=0;
            // Negative origin, e.g. a long chapter tab on a small window.
            draw(-10,0,30,100,HorizontalAlignment.LEFT);
            check(0,0,20,10,-10);
            draw(310,235,30,100,HorizontalAlignment.LEFT);
            check(310,234,320,240,310);
            int before=drawCount;
            draw(-50,30,30,100,HorizontalAlignment.LEFT);
            draw(330,30,30,100,HorizontalAlignment.LEFT);
            draw(50,250,30,100,HorizontalAlignment.LEFT);
            draw(50,30,0,100,HorizontalAlignment.LEFT);
            if(drawCount!=before || !clips.isEmpty()) throw new AssertionError("empty clip drew text");
            draw(50,30,30,10,HorizontalAlignment.CENTRE);
            if(renderedClip!=null || renderedX!=60 || !clips.isEmpty()) throw new AssertionError("short label changed");
            failDraw=true;
            try {{ draw(50,30,30,100,HorizontalAlignment.LEFT); throw new AssertionError("missing draw error"); }}
            catch (IllegalStateException expected) {{ }}
            finally {{ failDraw=false; }}
            if(!clips.isEmpty()) throw new AssertionError("clip leaked after error");
            if(!{str(legacy).lower()}) {{
                // The native GUI stack must intersect and then restore its parent.
                push(55,20,70,50);
                draw(50,30,30,100,HorizontalAlignment.LEFT);
                if(!renderedClip.equals(new Rect(55,29,70,40)) || !clips.peek().equals(new Rect(55,20,70,50)))
                    throw new AssertionError("parent clip lost");
                clips.pop();
            }}
        }}
    }}
""")
        probe = r"""
import java.util.ArrayDeque;
public class GuideMarqueeProbe {
    enum HorizontalAlignment { LEFT, CENTRE }
    record FormattedCharSequence(int width) {}
    record Rect(int l,int t,int r,int b) {}
    static ArrayDeque<Rect> clips=new ArrayDeque<>();
    static Rect renderedClip;
    static float renderedX;
    static int drawCount;
    static boolean failDraw;
    static void reset() { clips.clear(); renderedClip=null; drawCount=0; failDraw=false; }
    static void push(int l,int t,int r,int b) {
        Rect parent=clips.peek();
        if(parent!=null) { l=Math.max(l,parent.l); t=Math.max(t,parent.t); r=Math.min(r,parent.r); b=Math.min(b,parent.b); }
        clips.push(new Rect(l,t,r,b));
    }
    static void recordDraw(float x) {
        if(failDraw) throw new IllegalStateException("draw failed");
        drawCount++; renderedClip=clips.peek(); renderedX=x;
    }
    static void check(int l,int t,int r,int b,int x) {
        if(!new Rect(l,t,r,b).equals(renderedClip) || renderedX!=x || !clips.isEmpty())
            throw new AssertionError("expected "+new Rect(l,t,r,b)+" at "+x+", got "+renderedClip+" at "+renderedX);
    }
    static class PoseStack {}
    static class Font {
        int lineHeight=9;
        int width(FormattedCharSequence text) { return text.width; }
        void draw(PoseStack pose,FormattedCharSequence text,float x,int y,int colour) { recordDraw(x); }
    }
    static class GuiGraphics {
        void enableScissor(int l,int t,int r,int b) { push(l,t,r,b); }
        void disableScissor() { clips.pop(); }
        void drawString(Font font,FormattedCharSequence text,int x,int y,int colour,boolean shadow) { recordDraw(x); }
        void text(Font font,FormattedCharSequence text,int x,int y,int colour,boolean shadow) { recordDraw(x); }
    }
    static class GuiGraphicsExtractor extends GuiGraphics {}
    static class Window {
        double getGuiScale() { return 2; }
        int getWidth() { return 640; }
        int getHeight() { return 480; }
    }
    static class Minecraft {
        static Minecraft getInstance() { return new Minecraft(); }
        Window getWindow() { return new Window(); }
    }
    static class Mth { static int clamp(int x,int lo,int hi) { return Math.min(hi,Math.max(lo,x)); } }
    static class RenderSystem {
        static void enableScissor(int x,int y,int w,int h) { push(x/2,(480-y-h)/2,(x+w)/2,(480-y)/2); }
        static void disableScissor() { clips.pop(); }
    }
""" + "\n".join(classes) + "\n    public static void main(String[] args) {\n" + "\n".join(
            f"        new Target{i}().run();" for i in range(len(classes))
        ) + "\n    }\n}\n"
        with tempfile.TemporaryDirectory(prefix="bc-guide-marquee-") as temp:
            path = Path(temp) / "GuideMarqueeProbe.java"
            path.write_text(probe, encoding="utf-8")
            subprocess.run(["javac", "--release", "17", "-d", temp, str(path)], check=True, timeout=60)
            subprocess.run(["java", "-cp", temp, "GuideMarqueeProbe"], check=True, timeout=30)


if __name__ == "__main__":
    unittest.main()
