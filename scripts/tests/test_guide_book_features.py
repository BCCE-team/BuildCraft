#!/usr/bin/env python3
"""Guide Book filtering, addon pages, pagination, and live-preview regression tests."""
from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_layout import load_properties, materialize_target


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

    def test_tiny_window_scissors_are_clamped_to_framebuffer(self):
        for target in self.roots:
            gui = self.java(target, "buildcraft/lib/client/guide/GuiGuide.java")
            self.assertIn("window.getWidth()", gui, target)
            self.assertIn("window.getHeight()", gui, target)
            self.assertIn("if (x1 <= x0 || y1 <= y0) return;", gui, target)

    def test_12111_recipe_cache_preserves_datapack_ids_and_has_revision(self):
        cache = self.java("1.21.11-neoforge", "buildcraft/lib/net/GuideRecipeDisplayCache.java")
        message = self.java("1.21.11-neoforge", "buildcraft/lib/net/MessageGuideRecipeDisplays.java")
        gui = self.java("1.21.11-neoforge", "buildcraft/lib/client/guide/GuiGuide.java")

        self.assertIn("record Entry(Identifier recipeId, RecipeDisplayEntry display)", cache)
        self.assertIn("AtomicLong REVISION", cache)
        self.assertIn("public static long revision()", cache)
        self.assertIn("private static volatile boolean synchronised", cache)
        self.assertIn("if (synchronised) return entries;", cache)

        self.assertIn("Identifier recipeId = recipe.id().identifier();", message)
        self.assertIn("buffer.writeIdentifier(entry.recipeId());", message)
        self.assertIn("Identifier recipeId = buffer.readIdentifier();", message)
        self.assertIn("new GuideRecipeDisplayCache.Entry(recipeId, display)", message)

        self.assertIn("private long recipeDisplayRevision = GuideRecipeDisplayCache.revision();", gui)
        self.assertIn("if (revision != recipeDisplayRevision)", gui)
        self.assertIn("new GuideRecipe(entry.recipeId(), entry.display())", gui)
        self.assertNotIn("clientRecipeKey(RecipeDisplayEntry", gui)


    def test_12111_keeps_multiple_displays_from_one_recipe_distinct(self):
        gui = self.java("1.21.11-neoforge", "buildcraft/lib/client/guide/GuiGuide.java")
        cache = self.java("1.21.11-neoforge", "buildcraft/lib/net/GuideRecipeDisplayCache.java")
        self.assertIn("Set<GuideRecipeDisplayKey> renderedRecipeDisplays", gui)
        self.assertIn("new GuideRecipeDisplayKey(id, value.id().index())", gui)
        self.assertIn("renderedRecipeDisplays.add(recipe.displayKey())", gui)
        self.assertIn("Set<Identifier> directIds = recipes.stream().map(GuideRecipe::id)", gui)
        self.assertIn(".forEach(recipe -> addRecipe(recipe, ItemStack.EMPTY));", gui)
        self.assertNotIn(".findFirst()\n                    .ifPresent(recipe -> addRecipe(recipe, ItemStack.EMPTY));", gui)
        self.assertIn("Integer.toUnsignedString(display.id().index(), 16)", cache)

    def test_12111_datapack_reload_resends_live_recipe_previews(self):
        event = self.java("1.21.11-neoforge", "buildcraft/lib/BCLibEventDist.java")
        self.assertIn("OnDatapackSyncEvent", event)
        self.assertIn("public static void onDatapackSync(OnDatapackSyncEvent event)", event)
        self.assertIn("if (event.getPlayer() == null)", event)
        self.assertIn("event.getRelevantPlayers().forEach", event)
        self.assertIn("MessageUtil.doDelayedServer(1", event)
        self.assertIn("MessageGuideRecipeDisplays.create(player)", event)

        # The 1.21.1 downport keeps its native RecipeManager-backed guide path and must not acquire
        # the 1.21.11-only display-sync protocol just to implement the common UI hardening.
        old_event = self.java("1.21.1-neoforge", "buildcraft/lib/BCLibEventDist.java")
        self.assertNotIn("MessageGuideRecipeDisplays", old_event)
        self.assertNotIn("OnDatapackSyncEvent", old_event)


if __name__ == "__main__":
    unittest.main()
