//? source if >=1.21.1
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.client.guide;

import buildcraft.lib.compat.minecraft.recipe.BCRecipeDisplays;
import com.mojang.blaze3d.platform.InputConstants;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;

import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;

import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.lib.internal.statement.IStatement;
import buildcraft.lib.BCLibConfig;
import buildcraft.lib.client.sprite.SpriteNineSliced;
import buildcraft.lib.gui.GuiIcon;
import buildcraft.lib.gui.recipe.RecipeListPhantom;
import buildcraft.lib.misc.ColourUtil;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.item.ItemGuide;
import buildcraft.lib.net.GuideRecipeDisplayCache;
import buildcraft.lib.net.MessageGuideState;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.lib.compat.RenderCompat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * Native BuildCraft guide screen backed by the original BC8 guide registry and markdown pages.
 * <p>
 * The cover/opening animation is intentionally skipped: using the item opens directly on the first guide spread.
 */
public final class GuiGuide extends Screen {
    private static final Identifier LEFT_PAGE =
        Identifier.fromNamespaceAndPath("buildcraft", "guide/gui/left_page.png");
    private static final Identifier RIGHT_PAGE =
        Identifier.fromNamespaceAndPath("buildcraft", "guide/gui/right_page.png");
    private static final Identifier LEFT_PAGE_FIRST =
        Identifier.fromNamespaceAndPath("buildcraft", "guide/gui/left_page_first.png");
    private static final Identifier RIGHT_PAGE_BACK =
        Identifier.fromNamespaceAndPath("buildcraft", "guide/gui/right_page_back.png");
    private static final Identifier RIGHT_PAGE_LAST =
        Identifier.fromNamespaceAndPath("buildcraft", "guide/gui/right_page_last.png");
    private static final Identifier ICONS =
        Identifier.fromNamespaceAndPath("buildcraft", "guide/gui/icons.png");

    private static final int PAGE_TEXTURE_WIDTH = 193;
    private static final int PAGE_TEXTURE_HEIGHT = 248;
    private static final int BOOK_WIDTH = PAGE_TEXTURE_WIDTH * 2;
    private static final int BOOK_HEIGHT = PAGE_TEXTURE_HEIGHT;
    private static final int PAGE_TEXT_WIDTH = 168;
    private static final int PAGE_TEXT_HEIGHT = 190;
    private static final int PAGE_TEXT_TOP = 25;
    private static final int CONTENT_ENTRY_HEIGHT = 17;
    private static final int CONTENT_SUBHEADING_HEIGHT = 15;
    private static final int CONTENT_CHAPTER_HEIGHT = 19;
    private static final int LOADED_GUIDES_PER_PAGE = 10;

    private static final GuiIcon CHAPTER_MARKER_ICON = new GuiIcon(ICONS, 0, 56, 32, 32);
    private static final GuiIcon CHAPTER_MARKER_LEFT_ICON = new GuiIcon(ICONS, 0, 56, 24, 32);
    private static final SpriteNineSliced CHAPTER_BAR =
        new SpriteNineSliced(CHAPTER_MARKER_ICON.sprite, 8, 8, 24, 24, 32);
    private static final SpriteNineSliced CHAPTER_TAB_LEFT =
        new SpriteNineSliced(CHAPTER_MARKER_LEFT_ICON.sprite, 8, 8, 24, 24, 24, 32);

    private static final GuiIcon SEARCH_ICON = new GuiIcon(ICONS, 26, 196, 12, 12);
    private static final GuiIcon SEARCH_TAB_CLOSED = new GuiIcon(ICONS, 58, 196, 14, 6);
    private static final GuiIcon SEARCH_TAB_OPEN = new GuiIcon(ICONS, 40, 209, 106, 14);
    /** Exact crafting grid sprite and dimensions used by BC8's GuideCrafting. */
    private static final GuiIcon CRAFTING_GRID = new GuiIcon(ICONS, 119, 0, 116, 54);
    /** Exact furnace sprite and dimensions used by BC8's GuideSmelting. */
    private static final GuiIcon SMELTING_GRID = new GuiIcon(ICONS, 119, 54, 80, 54);
    /** Exact assembly-table sprite and dimensions used by BC8's GuideAssembly. */
    private static final GuiIcon ASSEMBLY_GRID = new GuiIcon(ICONS, 119, 108, 98, 54);
    private static final int[] DOCUMENT_CHAPTER_COLOURS = { 0x9DD5C0, 0xFAC174, 0x27A4DD };

    private static final List<String> MAIN_TYPE_ORDER =
        List.of("action", "block", "item", "pipe", "trigger");

    /**
     * Logical category order for the Community Edition guide.
     * <p>
     * The manifest is authored in gameplay progression order, while this table controls category-heading order so
     * related chains such as gears, engines, pipes, robotics and refining products remain grouped together.
     */
    private static final Map<String, List<String>> SUBTYPE_ORDER = Map.of(
        "action", List.of("basic", "automation", "pipe_plug", "pipe_item", "robot", "robot_station"),
        "block", List.of("engine", "mining", "fluid", "refining", "automation", "construction", "laser",
            "robot_control"),
        "item", List.of("gear", "component", "tool", "area", "blueprint", "robot_control",
            "robot_station", "robot", "fluid", "pipe_plug"),
        "pipe", List.of("pipe_item", "pipe_fluid", "pipe_power"),
        "trigger", List.of("basic", "item", "fluid", "engine", "automation", "pipe_item", "pipe_fluid",
            "pipe_plug", "robot")
    );
    private static final Map<String, Integer> CHAPTER_COLOURS = Map.of(
        // Exact colour cycle used by GuideChapter.COLOURS in BC8.
        "action", 0x9DD5C0,
        "block", 0xFAC174,
        "item", 0x27A4DD,
        "pipe", 0x9DD5C0,
        "trigger", 0xFAC174
    );

    private static final int TEXT_COLOUR = 0x30251D;
    private static final int MUTED_COLOUR = 0x716355;
    private static final int LINK_COLOUR = 0x315E86;
    private static final int CHAPTER_COLOUR = 0x8F6D43;
    private static final int PAGE_NUMBER_COLOUR = 0x90816A;
    private static final int HOVER_COLOUR = 0xFFD3AD6C;
    private static final int SELECTED_COLOUR = 0x66C6A778;

    private final GuideContent content;
    private final InteractionHand guideHand;
    private final ItemGuide.GuideState initialState;
    private final Map<Identifier, Integer> manifestOrder = new LinkedHashMap<>();
    private final List<GuideContent.Entry> filteredEntries = new ArrayList<>();
    /** -1 when all matches are shown; otherwise the total match count before the configured search cap. */
    private int realSearchResultCount = -1;
    private final List<ContentsPage> contentsPages = new ArrayList<>();
    private final List<ChapterTab> contentsChapters = new ArrayList<>();
    private final List<ClickRegion> clickRegions = new ArrayList<>();
    private final Deque<PageState> history = new ArrayDeque<>();

    private int left;
    private int top;
    private int tick;
    private int searchX;
    private int searchY;
    private int contentsSpread;
    private int documentSpread;
    private boolean showLore = true;
    private boolean showHints;
    private SortMode sortMode = SortMode.TYPE;
    private View view = View.CONTENTS;
    private @Nullable GuideContent.Entry currentEntry;
    private @Nullable DocumentLayout document;
    private @Nullable EditBox searchBox;
    private @Nullable ItemStack hoveredStack;
    private @Nullable Component hoveredText;
    private boolean restoredInitialState;
    private long recipeDisplayRevision = GuideRecipeDisplayCache.revision();

    private enum View {
        CONTENTS,
        DOCUMENT
    }

    private enum SortMode {
        TYPE,
        MODULE,
        ALPHABETICAL
    }

    private enum HorizontalAlignment {
        LEFT,
        CENTRE
    }

    private GuiGuide(ItemStack guideStack, InteractionHand guideHand) {
        super(Component.translatable("item.buildcraft.guide.name"));
        this.guideHand = guideHand;
        this.initialState = ItemGuide.readGuideState(guideStack);
        this.showLore = initialState.showLore;
        this.showHints = initialState.showHints;
        try {
            this.sortMode = SortMode.valueOf(initialState.sortMode);
        } catch (IllegalArgumentException ignored) {
            this.sortMode = SortMode.TYPE;
        }
        content = GuideContent.load();
        int order = 0;
        for (GuideContent.Entry entry : content.getAllEntries()) {
            manifestOrder.put(entry.id, order++);
        }
    }

    public static void open(ItemStack guideStack, InteractionHand hand) {
        Minecraft.getInstance().setScreen(new GuiGuide(guideStack, hand));
    }

    protected void init() {
        super.init();
        left = (width - BOOK_WIDTH) / 2;
        top = (height - BOOK_HEIGHT) / 2;
        String oldSearch = searchBox == null ? "" : searchBox.getValue();
        searchX = left + 46;
        searchY = top + 9;
        searchBox = new EditBox(font, searchX, searchY, 80, 13, Component.translatable("buildcraft.guide.contents.search"));
        searchBox.setMaxLength(80);
        searchBox.setBordered(false);
        searchBox.setTextColor(opaqueGuideTextColour(TEXT_COLOUR));
        searchBox.setValue(oldSearch);
        searchBox.setResponder(value -> rebuildContents());
        rebuildContents();
        if (!restoredInitialState) {
            restoredInitialState = true;
            restoreInitialState();
        }
        updateSearchVisibility();
    }

    public void resize(int width, int height) {
        String search = searchBox == null ? "" : searchBox.getValue();
        super.resize(width, height);
        if (searchBox != null) {
            searchBox.setValue(search);
        }
    }

    public void tick() {
        tick++;
        long revision = GuideRecipeDisplayCache.revision();
        if (revision != recipeDisplayRevision) {
            recipeDisplayRevision = revision;
            if (view == View.DOCUMENT && currentEntry != null) {
                rebuildOpenDocument();
            }
        }
    }

    private void restoreInitialState() {
        if (initialState.document && initialState.entry != null) {
            GuideContent.Entry entry = resolveSavedEntry(initialState.entry);
            if (entry != null) {
                currentEntry = entry;
                document = layoutDocument(entry);
                documentSpread = Mth.clamp(initialState.spread, 0, document.maxSpread());
                view = View.DOCUMENT;
                return;
            }
        }
        view = View.CONTENTS;
        currentEntry = null;
        document = null;
        contentsSpread = Mth.clamp(initialState.spread, 0, maxContentsSpread());
    }

    @Nullable
    private GuideContent.Entry resolveSavedEntry(Identifier id) {
        GuideContent.Entry entry = content.get(id);
        if (entry != null) {
            return entry;
        }
        String prefix = "generated/item/";
        if (!"buildcraftlib".equals(id.getNamespace()) || !id.getPath().startsWith(prefix)) {
            return null;
        }
        String encoded = id.getPath().substring(prefix.length());
        int separator = encoded.indexOf('/');
        if (separator <= 0 || separator == encoded.length() - 1) {
            return null;
        }
        Identifier itemId = Identifier.fromNamespaceAndPath(encoded.substring(0, separator), encoded.substring(separator + 1));
        Item item = BuiltInRegistries.ITEM.get(itemId).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.item.Items.AIR);
        return item == null || item == Items.AIR ? null : GuideContent.createGeneratedItemEntry(item.getDefaultInstance());
    }

    private ItemGuide.GuideState currentGuideState() {
        boolean documentView = view == View.DOCUMENT && currentEntry != null;
        return new ItemGuide.GuideState(
            showLore,
            showHints,
            sortMode.name(),
            documentView,
            documentView ? currentEntry.id : null,
            documentView ? documentSpread : contentsSpread
        );
    }

    private void persistGuideState() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        ItemStack stack = minecraft.player.getItemInHand(guideHand);
        if (!(stack.getItem() instanceof ItemGuide)) {
            return;
        }
        ItemGuide.GuideState state = currentGuideState();
        ItemGuide.writeGuideState(stack, state);
        MessageManager.sendToServer(new MessageGuideState(guideHand, state));
    }

    private void rebuildContents() {
        String query = searchBox == null ? "" : searchBox.getValue();
        filteredEntries.clear();
        for (GuideContent.Entry entry : content.getListedEntries()) {
            // The original item opens the main BuildCraft book. The three buildcraftlib:meta pages belong to the
            // separate configuration guide and must not leak into this contents tree.
            if (!"buildcraftcore:main".equals(entry.book)) {
                continue;
            }
            if (entry.matchesSearch(query)) {
                filteredEntries.add(entry);
            }
        }

        Comparator<GuideContent.Entry> comparator;
        switch (sortMode) {
            case MODULE:
                comparator = Comparator.comparingInt((GuideContent.Entry entry) -> moduleIndex(entry.module))
                    .thenComparingInt(entry -> typeIndex(entry.type))
                    .thenComparingInt(entry -> subtypeIndex(entry.type, entry.subtype))
                    .thenComparingInt(this::manifestIndex);
                break;
            case ALPHABETICAL:
                comparator = Comparator.comparing(entry -> entry.title().toLowerCase(Locale.ROOT));
                break;
            case TYPE:
            default:
                comparator = Comparator.comparingInt((GuideContent.Entry entry) -> typeIndex(entry.type))
                    .thenComparingInt(entry -> subtypeIndex(entry.type, entry.subtype))
                    .thenComparingInt(this::manifestIndex);
                break;
        }
        filteredEntries.sort(comparator.thenComparing(entry -> entry.id.toString()));
        int matchCount = filteredEntries.size();
        int maxSearchResults = Math.max(1, BCLibConfig.maxGuideSearchCount);
        if (!query.isBlank() && matchCount > maxSearchResults) {
            filteredEntries.subList(maxSearchResults, matchCount).clear();
            realSearchResultCount = matchCount;
        } else {
            realSearchResultCount = -1;
        }
        buildContentsPages();
        contentsSpread = Mth.clamp(contentsSpread, 0, maxContentsSpread());
        // Search/sort controls live on the left contents page. When a filter is entered while the saved spread
        // points at a Loaded Guides page, move to the first complete contents spread rather than hiding the results.
        if (!query.isBlank() && !isContentsEntryPage(contentsSpread * 2)) {
            contentsSpread = Math.min(firstFullContentsSpread(), maxContentsSpread());
        }
    }

    private void buildContentsPages() {
        contentsPages.clear();
        contentsChapters.clear();

        List<ContentsLine> lines = new ArrayList<>();
        switch (sortMode) {
            case MODULE:
                appendModuleOrderedLines(lines);
                break;
            case ALPHABETICAL:
                appendAlphabeticalLines(lines);
                break;
            case TYPE:
            default:
                appendTypeOrderedLines(lines);
                break;
        }

        if (lines.isEmpty()) {
            lines.add(ContentsLine.message(Component.translatable("buildcraft.guide.contents.no_results")));
        }

        ContentsPage page = new ContentsPage();
        contentsPages.add(page);
        int usedHeight = 0;
        for (int index = 0; index < lines.size(); index++) {
            ContentsLine line = lines.get(index);
            int required = line.height;
            if (line.kind != ContentsLineKind.ENTRY && index + 1 < lines.size()) {
                required += lines.get(index + 1).height;
            }

            // Top-level chapters in the contents are visual page dividers. Starting one below entries from the
            // previous chapter makes the module/type list look merged, especially in translated languages. This
            // rule applies only to the contents paginator; chapter blocks inside an opened guide article keep their
            // original flowing layout.
            boolean startChapterOnFreshPage = line.kind == ContentsLineKind.CHAPTER && !page.lines.isEmpty();
            boolean pageOverflow = !page.lines.isEmpty() && usedHeight + required > PAGE_TEXT_HEIGHT;
            if (startChapterOnFreshPage || pageOverflow) {
                page = new ContentsPage();
                contentsPages.add(page);
                usedHeight = 0;
            }
            line.y = usedHeight;
            page.lines.add(line);
            if (line.kind == ContentsLineKind.CHAPTER) {
                contentsChapters.add(new ChapterTab(
                    line.groupKey, line.component.getString(), line.colour,
                    firstContentsPageIndex() + contentsPages.size() - 1
                ));
            }
            usedHeight += line.height;
        }
    }

    private void appendTypeOrderedLines(List<ContentsLine> lines) {
        // Native BuildCraft chapters keep MAIN_TYPE_ORDER through the comparator. API2 sections are ordinary dynamic
        // chapters appended after them in section/order registration order instead of being filtered out.
        Map<String, List<GuideContent.Entry>> byType = new LinkedHashMap<>();
        for (GuideContent.Entry entry : filteredEntries) {
            byType.computeIfAbsent(entry.type, ignored -> new ArrayList<>()).add(entry);
        }
        for (Map.Entry<String, List<GuideContent.Entry>> type : byType.entrySet()) {
            List<GuideContent.Entry> typeEntries = type.getValue();
            if (typeEntries.isEmpty()) continue;

            GuideContent.Entry first = typeEntries.get(0);
            lines.add(ContentsLine.chapter(type.getKey(), first.typeName(), chapterColour(type.getKey())));

            // GuideSection is already the API2 grouping primitive. Avoid duplicating the same title as a subtype.
            if (first.isApiGuide()) {
                for (GuideContent.Entry entry : typeEntries) lines.add(ContentsLine.entry(entry));
                continue;
            }

            Map<String, List<GuideContent.Entry>> bySubtype = new LinkedHashMap<>();
            for (GuideContent.Entry entry : typeEntries) {
                bySubtype.computeIfAbsent(entry.subtype, ignored -> new ArrayList<>()).add(entry);
            }
            for (List<GuideContent.Entry> subtypeEntries : bySubtype.values()) {
                lines.add(ContentsLine.subheading(subtypeEntries.get(0).subtypeName()));
                for (GuideContent.Entry entry : subtypeEntries) lines.add(ContentsLine.entry(entry));
            }
        }
    }

    private void appendModuleOrderedLines(List<ContentsLine> lines) {
        Map<String, List<GuideContent.Entry>> byModule = new LinkedHashMap<>();
        for (GuideContent.Entry entry : filteredEntries) {
            byModule.computeIfAbsent(entry.module, ignored -> new ArrayList<>()).add(entry);
        }
        int chapterIndex = 0;
        for (Map.Entry<String, List<GuideContent.Entry>> module : byModule.entrySet()) {
            List<GuideContent.Entry> moduleEntries = module.getValue();
            if (moduleEntries.isEmpty()) continue;
            String groupKey = "module:" + module.getKey();
            lines.add(ContentsLine.chapter(groupKey, moduleEntries.get(0).moduleName(),
                originalChapterColour(chapterIndex++)));

            Map<String, List<GuideContent.Entry>> byType = new LinkedHashMap<>();
            for (GuideContent.Entry entry : moduleEntries) {
                byType.computeIfAbsent(entry.type, ignored -> new ArrayList<>()).add(entry);
            }
            for (List<GuideContent.Entry> typeEntries : byType.values()) {
                lines.add(ContentsLine.subheading(typeEntries.get(0).typeName()));
                for (GuideContent.Entry entry : typeEntries) lines.add(ContentsLine.entry(entry));
            }
        }
    }

    private void appendAlphabeticalLines(List<ContentsLine> lines) {
        // The third BC8 TypeOrder has no grouping tags at all: it is a single flat, alphabetically sorted list.
        for (GuideContent.Entry entry : filteredEntries) {
            lines.add(ContentsLine.entry(entry));
        }
    }

    private static int originalChapterColour(int index) {
        switch (Math.floorMod(index, 3)) {
            case 0: return 0x9DD5C0;
            case 1: return 0xFAC174;
            default: return 0x27A4DD;
        }
    }

    private static int typeIndex(String type) {
        int index = MAIN_TYPE_ORDER.indexOf(type);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private static int subtypeIndex(String type, String subtype) {
        List<String> order = SUBTYPE_ORDER.get(type);
        if (order == null) return Integer.MAX_VALUE;
        int index = order.indexOf(subtype);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private int manifestIndex(GuideContent.Entry entry) {
        return manifestOrder.getOrDefault(entry.id, Integer.MAX_VALUE);
    }

    private static int moduleIndex(String module) {
        switch (module) {
            case "buildcraftcore": return 0;
            case "buildcraftbuilders": return 1;
            case "buildcraftenergy": return 2;
            case "buildcraftfactory": return 3;
            case "buildcraftrobotics": return 4;
            case "buildcraftsilicon": return 5;
            case "buildcrafttransport": return 6;
            case "buildcraftcompat": return 7;
            default: return 100;
        }
    }

    private static int chapterColour(String key) {
        Integer colour = CHAPTER_COLOURS.get(key);
        if (colour != null) return colour;
        int hash = key.hashCode();
        int red = 112 + ((hash >>> 16) & 0x3F);
        int green = 112 + ((hash >>> 8) & 0x3F);
        int blue = 112 + (hash & 0x3F);
        return (red << 16) | (green << 8) | blue;
    }

    private void updateSearchVisibility() {
        if (searchBox != null) {
            int leftPage = contentsSpread * 2;
            boolean visible = view == View.CONTENTS && isContentsEntryPage(leftPage);
            searchBox.setVisible(visible);
            if (!visible && searchBox.isFocused()) {
                // EditBox#setFocused(boolean) is protected in 1.19.2. Clicking outside the widget clears focus.
                searchBox.mouseClicked(new MouseButtonEvent(-1, -1, new MouseButtonInfo(0, 0)), false);
            }
        }
    }

    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        hoveredStack = null;
        hoveredText = null;
        clickRegions.clear();

        renderBookBackground(guiGraphics);
        if (view == View.CONTENTS) {
            renderContents(guiGraphics, mouseX, mouseY);
        } else {
            renderDocument(guiGraphics, mouseX, mouseY);
        }
        renderNavigation(guiGraphics, mouseX, mouseY);

        if (hoveredStack != null && !hoveredStack.isEmpty()) {
            RenderCompat.renderTooltip(guiGraphics, font, hoveredStack, mouseX, mouseY);
        } else if (hoveredText != null) {
            RenderCompat.renderTooltip(guiGraphics, font, hoveredText, mouseX, mouseY);
        }
    }

    private void renderBookBackground(GuiGraphics guiGraphics) {
        int firstPage = currentFirstPage();
        int pageCount = currentPageCount();

        Identifier leftTexture = firstPage == 0 ? LEFT_PAGE_FIRST : LEFT_PAGE;
        RenderCompat.blit(guiGraphics, leftTexture, left, top, 0, 0, PAGE_TEXTURE_WIDTH, PAGE_TEXTURE_HEIGHT, 256, 256);

        Identifier rightTexture;
        if (firstPage + 1 >= pageCount) {
            // Odd page counts show the back of the right page, matching BC8's half-spread behaviour.
            rightTexture = RIGHT_PAGE_BACK;
        } else if (firstPage + 1 == pageCount - 1) {
            rightTexture = RIGHT_PAGE_LAST;
        } else {
            rightTexture = RIGHT_PAGE;
        }
        RenderCompat.blit(guiGraphics, rightTexture, left + PAGE_TEXTURE_WIDTH, top, 0, 0, PAGE_TEXTURE_WIDTH, PAGE_TEXTURE_HEIGHT, 256, 256);
    }

    private void renderContents(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int firstPage = contentsSpread * 2;
        if (firstPage == 0) {
            renderContentsIntroLeft(guiGraphics, mouseX, mouseY);
        } else {
            renderContentsLogicalPage(guiGraphics, firstPage, left + 23, mouseX, mouseY);
        }
        renderContentsLogicalPage(guiGraphics, firstPage + 1, left + PAGE_TEXTURE_WIDTH + 4, mouseX, mouseY);

        boolean leftIsContents = isContentsEntryPage(firstPage);
        if (firstPage == 0 || leftIsContents) {
            renderContentsSearch(guiGraphics, mouseX, mouseY, leftIsContents);
        }
        renderContentsChapters(guiGraphics, mouseX, mouseY);
    }

    private void renderContentsLogicalPage(GuiGraphics guiGraphics, int individualPage, int pageX,
        int mouseX, int mouseY) {
        if (individualPage <= 0 || individualPage >= currentContentsPageCount()) return;
        int loadedPage = individualPage - 1;
        if (loadedPage < loadedGuidePageCount()) {
            renderLoadedGuidesPage(guiGraphics, loadedPage, pageX);
        } else {
            renderContentsPage(guiGraphics, individualPage, pageX, mouseX, mouseY);
        }
    }

    private void renderContentsIntroLeft(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int pageX = left + 23;
        // GuidePageContents starts at the normal page text origin. The two manually wrapped lines reproduce the
        // original 3x title while still fitting Minecraft's modern font metrics.
        float titleScale = 3.0F;
        int titleLineHeight = Math.round(font.lineHeight * titleScale);
        drawScaledCentred(guiGraphics, "BuildCraft", pageX, top + PAGE_TEXT_TOP, PAGE_TEXT_WIDTH, titleScale, 0x17120E);
        drawScaledCentred(guiGraphics, "Guide Book", pageX, top + PAGE_TEXT_TOP + titleLineHeight,
            PAGE_TEXT_WIDTH, titleScale, 0x17120E);
        drawCentred(guiGraphics, Component.translatable("buildcraft.guide.contents.community_edition"), pageX,
            top + PAGE_TEXT_TOP + titleLineHeight * 2,
            PAGE_TEXT_WIDTH, TEXT_COLOUR);

        drawScaledCentred(guiGraphics, GuideContent.translateOrLiteral("buildcraft.guide.contents.options"), pageX, top + PAGE_TEXT_TOP + PAGE_TEXT_HEIGHT - 80,
            PAGE_TEXT_WIDTH, 2.0F, 0x17120E);
        String lore = GuideContent.translateOrLiteral("buildcraft.guide.contents.show_lore") + " " + (showLore ? "[x]" : "[ ]");
        String hints = GuideContent.translateOrLiteral("buildcraft.guide.contents.show_hints") + " " + (showHints ? "[x]" : "[ ]");
        int loreY = top + PAGE_TEXT_TOP + PAGE_TEXT_HEIGHT - 52;
        int hintY = top + PAGE_TEXT_TOP + PAGE_TEXT_HEIGHT - 38;
        boolean loreHovered = isInside(mouseX, mouseY, pageX, loreY, PAGE_TEXT_WIDTH, 10);
        boolean hintsHovered = isInside(mouseX, mouseY, pageX, hintY, PAGE_TEXT_WIDTH, 10);
        drawCentred(guiGraphics, Component.literal(lore), pageX, loreY, PAGE_TEXT_WIDTH,
            loreHovered ? LINK_COLOUR : TEXT_COLOUR);
        drawCentred(guiGraphics, Component.literal(hints), pageX, hintY, PAGE_TEXT_WIDTH,
            hintsHovered ? LINK_COLOUR : TEXT_COLOUR);
        clickRegions.add(new ClickRegion(pageX, loreY, PAGE_TEXT_WIDTH, 11, () -> {
            showLore = !showLore;
            rebuildOpenDocument();
            persistGuideState();
        }));
        clickRegions.add(new ClickRegion(pageX, hintY, PAGE_TEXT_WIDTH, 11, () -> {
            showHints = !showHints;
            rebuildOpenDocument();
            persistGuideState();
        }));
    }

    private void renderLoadedGuidesPage(GuiGraphics guiGraphics, int loadedPage, int pageX) {
        List<String> sources = content.getLoadedGuideSources();
        int from = loadedPage * LOADED_GUIDES_PER_PAGE;
        int to = Math.min(sources.size(), from + LOADED_GUIDES_PER_PAGE);

        int perLineHeight = font.lineHeight + 3;
        int visible = Math.max(0, to - from);
        int blockHeight = (visible + 1) * perLineHeight;
        int y = top + PAGE_TEXT_TOP + (PAGE_TEXT_HEIGHT - blockHeight) / 2;
        Component heading = Component.translatable("buildcraft.guide.contents.loaded_modules").withStyle(ChatFormatting.BOLD);
        drawCentred(guiGraphics, heading, pageX, y, PAGE_TEXT_WIDTH, 0x17120E);
        y += perLineHeight;
        for (int index = from; index < to; index++) {
            drawCentred(guiGraphics, Component.literal(sources.get(index)), pageX, y, PAGE_TEXT_WIDTH, TEXT_COLOUR);
            y += perLineHeight;
        }
    }

    private void renderContentsPage(GuiGraphics guiGraphics, int individualPage, int pageX, int mouseX, int mouseY) {
        int contentIndex = individualPage - firstContentsPageIndex();
        if (contentIndex < 0 || contentIndex >= contentsPages.size()) return;
        ContentsPage page = contentsPages.get(contentIndex);
        for (ContentsLine line : page.lines) {
            renderContentsLine(guiGraphics, line, pageX, top + PAGE_TEXT_TOP + line.y, mouseX, mouseY);
        }
    }

    private void renderContentsSearch(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean showOrders) {
        int pageX = left + 23;
        boolean open = searchBox != null && (searchBox.isFocused() || !searchBox.getValue().isEmpty());
        if (open) {
            SEARCH_TAB_OPEN.drawAt(guiGraphics, pageX - 2, top + 3);
            SEARCH_ICON.drawAt(guiGraphics, pageX + 8, top + 7);
        } else {
            SEARCH_TAB_CLOSED.drawAt(guiGraphics, pageX + 8, top + 5);
            SEARCH_ICON.drawAt(guiGraphics, pageX + 8, top + 6);
        }
        if (showOrders) {
            renderSortButtons(guiGraphics, mouseX, mouseY);
        }
        if (searchBox != null) {
            searchBox.render(guiGraphics, mouseX, mouseY, 0);
            if (realSearchResultCount >= 0) {
                String count = BCLibConfig.maxGuideSearchCount + "/" + realSearchResultCount;
                int countX = pageX + 107;
                drawOverflowText(guiGraphics, Component.literal(count), countX, top + 7, 55, MUTED_COLOUR,
                    HorizontalAlignment.LEFT, count.hashCode());
                if (isInside(mouseX, mouseY, countX, top + 4, 55, 15)) {
                    hoveredText = Component.translatable("buildcraft.guide.too_many_results", realSearchResultCount);
                }
            }
            clickRegions.add(new ClickRegion(pageX - 2, top + 3, 106, 16, () -> {
                if (contentsSpread == 0) {
                    contentsSpread = Math.min(firstFullContentsSpread(), maxContentsSpread());
                    updateSearchVisibility();
                    persistGuideState();
                }
                searchBox.mouseClicked(new MouseButtonEvent(searchX + 1, searchY + 1, new MouseButtonInfo(0, 0)), false);
            }));
        }
    }

    private void renderSortButtons(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int x = left + 13;
        int y = top + 15;
        for (int index = 0; index < SortMode.values().length; index++) {
            SortMode mode = SortMode.values()[index];
            boolean selected = sortMode == mode;
            boolean hovered = isInside(mouseX, mouseY, x, y + index * 14, 14, 14);
            int u = index * 14;
            int v = selected ? 14 : 0;
            if (hovered) v += 28;
            RenderCompat.blit(guiGraphics, ICONS, x, y + index * 14, u, v, 14, 14, 256, 256);
            int clickY = y + index * 14;
            clickRegions.add(new ClickRegion(x, clickY, 14, 14, () -> {
                sortMode = mode;
                rebuildContents();
                contentsSpread = Math.min(Math.max(1, contentsSpread), maxContentsSpread());
                updateSearchVisibility();
                persistGuideState();
            }));
        }
    }

    private void renderContentsLine(GuiGraphics guiGraphics, ContentsLine line, int x, int y, int mouseX, int mouseY) {
        switch (line.kind) {
            case CHAPTER: {
                drawTintedNineSlice(guiGraphics, CHAPTER_BAR, x + 7, y - 4, PAGE_TEXT_WIDTH - 24, 16, line.colour);
                Component text = line.component.copy().withStyle(ChatFormatting.UNDERLINE);
                drawOverflowText(guiGraphics, text, x + 16, y, PAGE_TEXT_WIDTH - 34, TEXT_COLOUR,
                    HorizontalAlignment.LEFT, line.component.getString().hashCode());
                break;
            }
            case SUBHEADING: {
                int textX = x + 32;
                Component text = line.component.copy().withStyle(ChatFormatting.UNDERLINE);
                drawOverflowText(guiGraphics, text, textX, y, PAGE_TEXT_WIDTH - 32, TEXT_COLOUR,
                    HorizontalAlignment.LEFT, line.component.getString().hashCode());
                break;
            }
            case ENTRY: {
                GuideContent.Entry entry = line.entry;
                if (entry == null) break;
                int iconX = x + 14;
                int iconY = y - 5;
                int textX = x + 32;
                boolean hovered = isInside(mouseX, mouseY, x + 12, y - 5, PAGE_TEXT_WIDTH - 12, 18);
                if (hovered) {
                    guiGraphics.fill(textX - 2, y - 2, x + PAGE_TEXT_WIDTH - 2, y + 12, HOVER_COLOUR);
                }
                renderEntryIcon(guiGraphics, entry, iconX, iconY, mouseX, mouseY);
                drawOverflowText(guiGraphics, Component.literal(entry.title()), textX, y, PAGE_TEXT_WIDTH - 34,
                    entryTextColour(entry), HorizontalAlignment.LEFT, entry.id.hashCode());
                clickRegions.add(new ClickRegion(x + 12, y - 5, PAGE_TEXT_WIDTH - 12, 18,
                    () -> openEntry(entry, true)));
                break;
            }
            case MESSAGE:
                drawCentred(guiGraphics, line.component, x, y + 4, PAGE_TEXT_WIDTH, MUTED_COLOUR);
                break;
            default:
                break;
        }
    }

    private void renderEntryIcon(GuiGraphics guiGraphics, GuideContent.Entry entry, int x, int y, int mouseX, int mouseY) {
        if (!entry.stack.isEmpty()) {
            guiGraphics.renderItem(entry.stack, x, y);
            if (isInside(mouseX, mouseY, x, y, 16, 16)) hoveredStack = entry.stack;
            return;
        }
        IStatement statement = GuideContent.resolveStatement(entry.statement);
        ISprite sprite = statement == null ? null : statement.getSprite();
        if (sprite != null) {
            GuiIcon.drawAt(guiGraphics, sprite, x, y, 16, 16);
            return;
        }
        int colour = chapterColour(entry.type);
        guiGraphics.fill(x + 2, y + 2, x + 14, y + 14, 0xFF000000 | colour);
        guiGraphics.fill(x + 4, y + 4, x + 12, y + 12, 0xFF202020);
    }

    private static int entryTextColour(GuideContent.Entry entry) {
        if (entry.stack.isEmpty()) {
            return TEXT_COLOUR;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(entry.stack.getItem());
        if (id == null) {
            return TEXT_COLOUR;
        }
        DyeColor colour = null;
        if (id.getNamespace().equals("buildcrafttransport") && id.getPath().startsWith("wire/")) {
            colour = DyeColor.byName(id.getPath().substring("wire/".length()), null);
        } else if (id.getNamespace().equals("buildcraftsilicon") && id.getPath().equals("plug/lens")) {
            int damage = entry.stack.getDamageValue();
            if (damage < 32) {
                colour = DyeColor.byId(damage & 15);
            }
        }
        return colour == null || !BCLibConfig.useColouredLabels
            ? TEXT_COLOUR : ColourUtil.getLightHex(colour);
    }


    private void renderContentsChapters(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (contentsChapters.isEmpty()) return;
        int step = font.lineHeight + 8;
        int visibleTabs = maximumChapterTabs();
        int anchor = 0;
        int visiblePage = currentFirstPage() + 1;
        for (int index = 0; index < contentsChapters.size(); index++) {
            if (contentsChapters.get(index).pageIndex <= visiblePage) anchor = index;
            else break;
        }
        int start = tabWindowStart(contentsChapters.size(), visibleTabs, anchor);
        int end = Math.min(contentsChapters.size(), start + visibleTabs);
        for (int sourceIndex = start, displayIndex = 0; sourceIndex < end; sourceIndex++, displayIndex++) {
            ChapterTab tab = contentsChapters.get(sourceIndex);
            int maxTextWidth = Math.max(48, left - 26);
            int textWidth = Math.min(font.width(tab.label), maxTextWidth);
            int y = top + step * (displayIndex + 1);
            boolean hovered = isInside(mouseX, mouseY, left - textWidth - 5, y - 4, textWidth + 16, 16);
            int extension = hovered ? 5 : 0;
            int x = left - textWidth - extension + 5;
            drawTintedNineSlice(guiGraphics, CHAPTER_TAB_LEFT, x - 6, y - 4,
                textWidth + 12 + extension, 16, tab.colour);
            drawOverflowText(guiGraphics, Component.literal(tab.label).withStyle(ChatFormatting.UNDERLINE),
                x, y, textWidth, TEXT_COLOUR, HorizontalAlignment.LEFT, tab.key.hashCode());
            clickRegions.add(new ClickRegion(left - textWidth - 5 - extension, y - 4,
                textWidth + 16 + extension, 16, () -> {
                    contentsSpread = Mth.clamp(tab.pageIndex / 2, 0, maxContentsSpread());
                    updateSearchVisibility();
                    persistGuideState();
                }));
        }
    }

    private static void drawTintedNineSlice(GuiGraphics guiGraphics, SpriteNineSliced sprite, double x, double y,
        double width, double height, int colour) {
        int argb = (colour & 0xFF000000) == 0 ? (colour | 0xFF000000) : colour;
        if (sprite == CHAPTER_BAR) {
            drawGuideNineSlice(guiGraphics, x, y, width, height, 32, 32, 8, 8, 24, 24, argb);
        } else if (sprite == CHAPTER_TAB_LEFT) {
            drawGuideNineSlice(guiGraphics, x, y, width, height, 24, 32, 8, 8, 24, 24, argb);
        } else {
            sprite.draw(guiGraphics, x, y, width, height);
        }
    }

    private static void drawGuideNineSlice(GuiGraphics guiGraphics, double x, double y, double width, double height,
        int sourceWidth, int sourceHeight, int xMin, int yMin, int xMax, int yMax, int colour) {
        int dx = (int) Math.round(x);
        int dy = (int) Math.round(y);
        int dw = Math.max(0, (int) Math.round(width));
        int dh = Math.max(0, (int) Math.round(height));
        int left = Math.min(xMin, dw);
        int right = Math.min(Math.max(0, sourceWidth - xMax), Math.max(0, dw - left));
        int top = Math.min(yMin, dh);
        int bottom = Math.min(Math.max(0, sourceHeight - yMax), Math.max(0, dh - top));
        int centreWidth = Math.max(0, dw - left - right);
        int centreHeight = Math.max(0, dh - top - bottom);
        int sourceCentreWidth = Math.max(0, xMax - xMin);
        int sourceCentreHeight = Math.max(0, yMax - yMin);
        int u0 = 0;
        int v0 = 56;

        drawGuideTintedPart(guiGraphics, dx, dy, left, top,
            u0, v0, xMin, yMin, colour);
        drawGuideTintedPart(guiGraphics, dx + left, dy, centreWidth, top,
            u0 + xMin, v0, sourceCentreWidth, yMin, colour);
        drawGuideTintedPart(guiGraphics, dx + left + centreWidth, dy, right, top,
            u0 + xMax, v0, sourceWidth - xMax, yMin, colour);

        drawGuideTintedPart(guiGraphics, dx, dy + top, left, centreHeight,
            u0, v0 + yMin, xMin, sourceCentreHeight, colour);
        drawGuideTintedPart(guiGraphics, dx + left, dy + top, centreWidth, centreHeight,
            u0 + xMin, v0 + yMin, sourceCentreWidth, sourceCentreHeight, colour);
        drawGuideTintedPart(guiGraphics, dx + left + centreWidth, dy + top, right, centreHeight,
            u0 + xMax, v0 + yMin, sourceWidth - xMax, sourceCentreHeight, colour);

        drawGuideTintedPart(guiGraphics, dx, dy + top + centreHeight, left, bottom,
            u0, v0 + yMax, xMin, sourceHeight - yMax, colour);
        drawGuideTintedPart(guiGraphics, dx + left, dy + top + centreHeight, centreWidth, bottom,
            u0 + xMin, v0 + yMax, sourceCentreWidth, sourceHeight - yMax, colour);
        drawGuideTintedPart(guiGraphics, dx + left + centreWidth, dy + top + centreHeight, right, bottom,
            u0 + xMax, v0 + yMax, sourceWidth - xMax, sourceHeight - yMax, colour);
    }

    private static void drawGuideTintedPart(GuiGraphics guiGraphics, int x, int y, int width, int height,
        int u, int v, int sourceWidth, int sourceHeight, int colour) {
        if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return;
        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, ICONS, x, y, (float) u, (float) v,
            width, height, sourceWidth, sourceHeight, 256, 256, colour);
    }

    private void openEntry(GuideContent.Entry entry, boolean clearHistory) {
        if (clearHistory) history.clear();
        currentEntry = entry;
        document = layoutDocument(entry);
        documentSpread = 0;
        view = View.DOCUMENT;
        updateSearchVisibility();
        persistGuideState();
    }

    private void openLinkedEntry(GuideContent.Entry entry) {
        if (currentEntry != null) history.push(new PageState(currentEntry, documentSpread));
        currentEntry = entry;
        document = layoutDocument(entry);
        documentSpread = 0;
        view = View.DOCUMENT;
        updateSearchVisibility();
        persistGuideState();
    }

    private void rebuildOpenDocument() {
        if (currentEntry != null) {
            int oldSpread = documentSpread;
            boolean wasLastSpread = document != null && oldSpread >= document.maxSpread();
            document = layoutDocument(currentEntry);
            // A player reading the final spread should remain at the logical end when lore/hints or live recipe
            // previews add/remove pages. Otherwise clamp the exact spread they were reading.
            documentSpread = wasLastSpread ? document.maxSpread() : Mth.clamp(oldSpread, 0, document.maxSpread());
        }
    }

    private void renderDocument(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (currentEntry == null || document == null) {
            returnToContents();
            return;
        }
        drawCentred(guiGraphics, Component.literal(currentEntry.title()), left + 23, top + 10,
            PAGE_TEXT_WIDTH * 2 + 3, CHAPTER_COLOUR);

        int firstPage = documentSpread * 2;
        renderDocumentPage(guiGraphics, document.page(firstPage), left + 23, top + PAGE_TEXT_TOP, mouseX, mouseY);
        renderDocumentPage(guiGraphics, document.page(firstPage + 1), left + PAGE_TEXTURE_WIDTH + 4,
            top + PAGE_TEXT_TOP, mouseX, mouseY);
        renderDocumentChapters(guiGraphics, mouseX, mouseY);
    }

    private void renderDocumentChapters(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (document == null) return;
        List<DocumentChapter> topLevel = document.chapters.stream()
            .filter(chapter -> chapter.level == 0)
            .collect(Collectors.toList());
        int tabIndex = 0;
        tabIndex = drawDocumentChapterTab(guiGraphics, mouseX, mouseY, tabIndex,
            GuideContent.translateOrLiteral("buildcraft.guide.chapter.contents"),
            DOCUMENT_CHAPTER_COLOURS[0], this::returnToContents);

        int visibleTabs = Math.max(1, maximumChapterTabs() - 1);
        int anchor = 0;
        int visiblePage = documentSpread * 2 + 1;
        for (int index = 0; index < topLevel.size(); index++) {
            if (topLevel.get(index).pageIndex <= visiblePage) anchor = index;
            else break;
        }
        int start = tabWindowStart(topLevel.size(), visibleTabs, anchor);
        int end = Math.min(topLevel.size(), start + visibleTabs);
        for (int index = start; index < end; index++) {
            DocumentChapter chapter = topLevel.get(index);
            int targetSpread = chapter.pageIndex / 2;
            tabIndex = drawDocumentChapterTab(guiGraphics, mouseX, mouseY, tabIndex, chapter.title,
                chapter.colour, () -> {
                    documentSpread = Mth.clamp(targetSpread, 0, document.maxSpread());
                    updateSearchVisibility();
                    persistGuideState();
                });
        }
    }

    private int maximumChapterTabs() {
        int step = Math.max(1, font.lineHeight + 8);
        return Math.max(2, (PAGE_TEXTURE_HEIGHT - 36) / step);
    }

    private static int tabWindowStart(int size, int visible, int anchor) {
        if (size <= visible) return 0;
        int centred = anchor - visible / 2;
        return Mth.clamp(centred, 0, size - visible);
    }

    private int drawDocumentChapterTab(GuiGraphics guiGraphics, int mouseX, int mouseY, int index, String rawLabel,
        int colour, Runnable action) {
        int maxTextWidth = Math.max(48, left - 26);
        int fullWidth = font.width(rawLabel);
        int textWidth = Math.min(fullWidth, maxTextWidth);
        int y = top + (font.lineHeight + 8) * (index + 1);
        boolean hovered = isInside(mouseX, mouseY, left - textWidth - 5, y - 4, textWidth + 16, 16);
        int extension = hovered ? 5 : 0;
        int x = left - textWidth - extension + 5;
        drawTintedNineSlice(guiGraphics, CHAPTER_TAB_LEFT, x - 6, y - 4,
            textWidth + 12 + extension, 16, colour);
        drawOverflowText(guiGraphics, Component.literal(rawLabel).withStyle(ChatFormatting.UNDERLINE),
            x, y, textWidth, TEXT_COLOUR, HorizontalAlignment.LEFT, rawLabel.hashCode());
        clickRegions.add(new ClickRegion(left - textWidth - 5 - extension, y - 4,
            textWidth + 16 + extension, 16, action));
        return index + 1;
    }

    private void renderDocumentPage(GuiGraphics guiGraphics, @Nullable RenderPage page, int originX, int originY,
        int mouseX, int mouseY) {
        if (page == null) return;
        for (RenderElement element : page.elements) {
            int x = originX + element.x;
            int y = originY + element.y;
            switch (element.kind) {
                case TEXT:
                    drawOverflowText(guiGraphics, element.line, x, y, PAGE_TEXT_WIDTH - element.x, TEXT_COLOUR,
                        HorizontalAlignment.LEFT, element.y * 31 + x);
                    if (element.target != null) {
                        if (isInside(mouseX, mouseY, x, y, PAGE_TEXT_WIDTH - element.x, 10)) {
                            guiGraphics.fill(x, y + 9, x + Math.min(element.width, PAGE_TEXT_WIDTH - element.x), y + 10, 0xAA315E86);
                        }
                        clickRegions.add(new ClickRegion(x, y, Math.max(1, PAGE_TEXT_WIDTH - element.x), 10,
                            () -> followTarget(element.target, null)));
                    }
                    break;
                case CHAPTER:
                    if (element.chapterBar) {
                        int indent = Math.max(0, element.x - 12);
                        drawTintedNineSlice(guiGraphics, CHAPTER_BAR, x - 5, y - 4,
                            Math.max(24, PAGE_TEXT_WIDTH - 24 - indent), element.height, element.colour);
                    }
                    drawOverflowText(guiGraphics, element.line, x, y, PAGE_TEXT_WIDTH - element.x, TEXT_COLOUR,
                        HorizontalAlignment.LEFT, element.y * 31 + element.x);
                    break;
                case CODE:
                    guiGraphics.fill(x - 2, y - 1, x + PAGE_TEXT_WIDTH - 2, y + 10, 0x356A5A49);
                    drawOverflowText(guiGraphics, element.line, x, y, PAGE_TEXT_WIDTH - 4, MUTED_COLOUR,
                        HorizontalAlignment.LEFT, element.y * 31 + x);
                    break;
                case LINK:
                    renderDocumentLink(guiGraphics, element, x, y, mouseX, mouseY);
                    break;
                case IMAGE:
                    renderDocumentImage(guiGraphics, element, x, y, mouseX, mouseY);
                    break;
                case RECIPE:
                    renderRecipe(guiGraphics, element.recipe == null ? null : element.recipe.value(), element.stack, x, y, mouseX, mouseY);
                    break;
                default:
                    break;
            }
        }
    }

    private void renderDocumentLink(GuiGraphics guiGraphics, RenderElement element, int x, int y, int mouseX, int mouseY) {
        boolean hovered = isInside(mouseX, mouseY, x, y, PAGE_TEXT_WIDTH, 19);
        if (hovered) guiGraphics.fill(x, y, x + PAGE_TEXT_WIDTH, y + 19, HOVER_COLOUR);
        if (element.stack != null && !element.stack.isEmpty()) {
            guiGraphics.renderItem(element.stack, x + 1, y + 1);
            if (isInside(mouseX, mouseY, x + 1, y + 1, 16, 16)) hoveredStack = element.stack;
        }
        String title = element.component == null ? element.target : element.component.getString();
        if (title == null) title = GuideContent.translateOrLiteral("buildcraft.guide.contents.missing_link");
        drawOverflowText(guiGraphics, Component.literal(title), x + 21, y + 5, 143,
            hovered ? LINK_COLOUR : TEXT_COLOUR, HorizontalAlignment.LEFT, title.hashCode());
        String target = element.target;
        String secondary = element.secondary;
        clickRegions.add(new ClickRegion(x, y, PAGE_TEXT_WIDTH, 19, () -> followTarget(target, secondary)));
    }

    private void renderDocumentImage(GuiGraphics guiGraphics, RenderElement element, int x, int y, int mouseX, int mouseY) {
        if (element.stack != null && !element.stack.isEmpty()) {
            guiGraphics.pose().pushMatrix();
            float scale = Math.max(1.0F, Math.min(element.width, element.height) / 16.0F);
            guiGraphics.pose().translate(x + (element.width - 16 * scale) / 2.0F, y);
            guiGraphics.pose().scale(scale, scale);
            guiGraphics.renderItem(element.stack, 0, 0);
            guiGraphics.pose().popMatrix();
            int itemX = x + Math.round((element.width - 16 * scale) / 2.0F);
            int itemWidth = Math.max(16, Math.round(16 * scale));
            registerStackInteraction(element.stack, itemX, y, itemWidth, Math.max(16, Math.round(16 * scale)),
                mouseX, mouseY);
            return;
        }
        if (element.texture != null) {

            int sourceWidth = Math.max(1, element.sourceWidth);
            int sourceHeight = Math.max(1, element.sourceHeight);
            guiGraphics.pose().pushMatrix();
            guiGraphics.pose().translate(x, y);
            guiGraphics.pose().scale(element.width / (float) sourceWidth, element.height / (float) sourceHeight);
            RenderCompat.blit(guiGraphics, element.texture, 0, 0, 0, 0, sourceWidth, sourceHeight, sourceWidth, sourceHeight);
            guiGraphics.pose().popMatrix();
        }
    }

    private void followTarget(@Nullable String target, @Nullable String type) {
        if (target == null || target.isEmpty()) return;
        if (target.startsWith("http://") || target.startsWith("https://")) {
            try {
                Util.getPlatform().openUri(new URI(target));
            } catch (Exception ignored) {
            }
            return;
        }
        if ("item_stack".equals(type)) {
            ItemStack stack = GuideContent.resolveStackForTag(target);
            if (stack.isEmpty()) return;
            GuideContent.Entry matching = findByStack(stack);
            openLinkedEntry(matching == null ? GuideContent.createGeneratedItemEntry(stack) : matching);
            return;
        }
        GuideContent.Entry entry = content.get(target);
        if (entry != null) openLinkedEntry(entry);
    }

    @Nullable
    private GuideContent.Entry findByStack(ItemStack stack) {
        if (stack.isEmpty()) return null;
        GuideContent.Entry itemFallback = null;
        for (GuideContent.Entry entry : content.getAllEntries()) {
            if (entry.stack.isEmpty() || entry.stack.getItem() != stack.getItem()) continue;
            if (itemFallback == null) itemFallback = entry;
            if (entry.stack.getDamageValue() == stack.getDamageValue()
                && ItemStack.isSameItemSameComponents(entry.stack, stack)) {
                return entry;
            }
        }
        return itemFallback;
    }

    private DocumentLayout layoutDocument(GuideContent.Entry entry) {
        GuideDocument parsed = GuideDocument.parse(entry.markdown, showLore, showHints, BCLibConfig.guideShowDetail);
        LayoutBuilder layout = new LayoutBuilder(entry.title());
        for (GuideDocument.Block block : parsed.blocks) {
            switch (block.kind) {
                case TEXT:
                    layout.addText(block.text == null ? Component.empty() : block.text, block.target);
                    break;
                case SPACE:
                    layout.addSpace(6);
                    break;
                case CHAPTER:
                    layout.addChapter(block.text == null ? Component.empty() : block.text, block.level);
                    break;
                case NEW_PAGE:
                    layout.newPage();
                    break;
                case CODE:
                    layout.addCode(block.text == null ? Component.empty() : block.text);
                    break;
                case LINK:
                    layout.addLink(block.target, block.secondary);
                    break;
                case IMAGE:
                    layout.addImage(block.target, block.width, block.height);
                    break;
                case RECIPES:
                    layout.addRecipeTag(block.secondary, block.target, block.attributes);
                    break;
                default:
                    break;
            }
        }
        // BC8 appended all crafting information to item pages after loading their authored page parts.
        // Avoid duplicates when the markdown already contains explicit <recipe(s)> or <usage(s)> tags.
        if (!entry.stack.isEmpty()) {
            layout.addAutomaticCrafting(entry.stack);
        }
        return layout.finish();
    }

    private final class LayoutBuilder {
        private final List<RenderPage> pages = new ArrayList<>();
        private final List<DocumentChapter> chapters = new ArrayList<>();
        private RenderPage page = new RenderPage();
        private final Set<GuideRecipeDisplayKey> renderedRecipeDisplays = new LinkedHashSet<>();
        private int y;
        // Contents is colour 0. The synthetic page title starts at colour 1, exactly like GuidePage in BC8.
        private int chapterColourIndex = 1;

        LayoutBuilder(String title) {
            pages.add(page);
            addChapter(Component.literal(title), 0);
        }

        void newPage() {
            // Consecutive <new_page/> tags and unresolved dynamic recipe blocks must not create blank interior pages.
            if (page.elements.isEmpty() && y == 0) return;
            page = new RenderPage();
            pages.add(page);
            y = 0;
        }

        void ensure(int height) {
            if (y > 0 && y + height > PAGE_TEXT_HEIGHT) newPage();
        }

        void addSpace(int height) {
            if (y > 0) y = Math.min(PAGE_TEXT_HEIGHT, y + height);
        }

        void addText(Component component, @Nullable String target) {
            List<FormattedCharSequence> lines = font.split(component, PAGE_TEXT_WIDTH);
            if (lines.isEmpty()) {
                addSpace(6);
                return;
            }
            for (FormattedCharSequence line : lines) {
                ensure(10);
                int width = font.width(line);
                page.elements.add(RenderElement.text(y, line, width, target));
                y += 10;
            }
            y += 2;
        }

        void addChapter(Component component, int level) {
            int safeLevel = Math.max(0, level);
            int indent = Math.min(20, safeLevel * 7);
            Component styled = component.copy().withStyle(ChatFormatting.UNDERLINE);
            List<FormattedCharSequence> lines = font.split(styled, PAGE_TEXT_WIDTH - 24 - indent);
            if (lines.isEmpty()) lines = List.of(Component.empty().getVisualOrderText());
            int blockHeight = Math.max(16, lines.size() * 11 + 6);
            // GuideChapter guaranteed room for roughly four text rows, preventing a chapter marker from being left
            // alone at the bottom of a page while its first paragraph starts on the next one. Extremely long addon
            // titles are allowed to continue onto following pages instead of drawing outside PAGE_TEXT_HEIGHT.
            ensure(Math.min(PAGE_TEXT_HEIGHT, Math.max(blockHeight, font.lineHeight * 4)));

            int colour = DOCUMENT_CHAPTER_COLOURS[Math.floorMod(chapterColourIndex++, DOCUMENT_CHAPTER_COLOURS.length)];
            int pageIndex = pages.size() - 1;
            chapters.add(new DocumentChapter(component.getString(), colour, pageIndex, safeLevel));
            for (int index = 0; index < lines.size(); index++) {
                if (index > 0) ensure(11);
                int visibleBlockHeight = index == 0
                    ? Math.max(16, Math.min(blockHeight, PAGE_TEXT_HEIGHT - y)) : 16;
                page.elements.add(RenderElement.chapter(12 + indent, y, lines.get(index), colour,
                    index == 0, visibleBlockHeight));
                y += 11;
            }
            y += 7;
        }

        void addCode(Component component) {
            List<FormattedCharSequence> lines = font.split(component, PAGE_TEXT_WIDTH - 4);
            if (lines.isEmpty()) lines = List.of(Component.empty().getVisualOrderText());
            for (FormattedCharSequence line : lines) {
                ensure(10);
                page.elements.add(RenderElement.code(2, y, line));
                y += 10;
            }
        }

        void addLink(@Nullable String target, @Nullable String type) {
            ensure(20);
            GuideContent.Entry linked = target == null ? null : content.get(target);
            ItemStack stack = ItemStack.EMPTY;
            Component title;
            if ("item_stack".equals(type)) {
                stack = GuideContent.resolveStackForTag(target);
                title = stack.isEmpty() ? Component.literal(target == null ? GuideContent.translateOrLiteral("buildcraft.guide.contents.missing_item") : target)
                    : stack.getHoverName();
            } else if (linked != null) {
                stack = linked.stack;
                title = Component.literal(linked.title());
            } else {
                title = Component.literal(target == null ? GuideContent.translateOrLiteral("buildcraft.guide.contents.missing_link") : target);
            }
            page.elements.add(RenderElement.link(y, title, target, type, stack));
            y += 21;
        }

        void addImage(@Nullable String source, int requestedWidth, int requestedHeight) {
            if (source == null) return;
            ItemStack imageStack = imageStack(source);
            int width = requestedWidth > 0 ? requestedWidth : 160;
            int height = requestedHeight > 0 ? requestedHeight : 160;
            if (!imageStack.isEmpty() && requestedWidth <= 0) width = 64;
            if (!imageStack.isEmpty() && requestedHeight <= 0) height = 64;
            if (width > PAGE_TEXT_WIDTH) {
                float scale = PAGE_TEXT_WIDTH / (float) width;
                width = PAGE_TEXT_WIDTH;
                height = Math.max(1, Math.round(height * scale));
            }
            if (height > PAGE_TEXT_HEIGHT) {
                float scale = PAGE_TEXT_HEIGHT / (float) height;
                height = PAGE_TEXT_HEIGHT;
                width = Math.max(1, Math.round(width * scale));
            }
            ensure(height + 4);
            int x = (PAGE_TEXT_WIDTH - width) / 2;
            Identifier texture = imageStack.isEmpty() ? textureLocation(source) : null;
            int[] sourceSize = sourceTextureSize(source);
            page.elements.add(RenderElement.image(x, y, width, height, sourceSize[0], sourceSize[1], texture, imageStack));
            y += height + 4;
        }

        void addRecipeTag(@Nullable String tagType, @Nullable String rawStack, java.util.Map<String, String> attributes) {
            if (tagType == null) return;
            if ("recipe_id".equals(tagType)) {
                Identifier recipeId = Identifier.tryParse(rawStack);
                if (recipeId == null) return;
                allGuideRecipes().stream()
                    .filter(recipe -> recipeId.equals(recipe.id()))
                    .forEach(recipe -> addRecipe(recipe, ItemStack.EMPTY));
                return;
            }
            ItemStack stack = GuideContent.resolveStackForTag(rawStack, attributes);
            if (stack.isEmpty()) return;
            List<GuideRecipe> recipes = recipesFor(stack);
            List<GuideRecipe> usages = usagesFor(stack);
            switch (tagType) {
                case "recipe":
                    if (!recipes.isEmpty()) addRecipe(recipes.get(0), stack);
                    break;
                case "recipes":
                    for (GuideRecipe recipe : recipes) addRecipe(recipe, stack);
                    break;
                case "usages":
                    for (GuideRecipe recipe : usages) addRecipe(recipe, ItemStack.EMPTY);
                    break;
                case "recipes_usages":
                    if (!recipes.isEmpty()) {
                        newPage();
                        addChapter(recipeChapter(true, recipes.size()),
                            parseInt(attributes.get("chapter_level"), 0));
                        for (GuideRecipe recipe : recipes) addRecipe(recipe, stack);
                    }
                    Set<Identifier> recipeIds = recipes.stream().map(GuideRecipe::id).collect(Collectors.toSet());
                    List<GuideRecipe> uniqueUsages = usages.stream().filter(recipe -> !recipeIds.contains(recipe.id()))
                        .collect(Collectors.toList());
                    if (!uniqueUsages.isEmpty()) {
                        // BC8 only forced another page here when the recipe section did not contain exactly one
                        // recipe. A single recipe and a single usage are allowed to share a page when they fit.
                        if (recipes.size() != 1) newPage();
                        addChapter(recipeChapter(false, uniqueUsages.size()),
                            parseInt(attributes.get("chapter_level"), 0));
                        for (GuideRecipe recipe : uniqueUsages) addRecipe(recipe, ItemStack.EMPTY);
                    }
                    break;
                default:
                    break;
            }
        }

        void addRecipe(GuideRecipe recipe, ItemStack focusedOutput) {
            if (!renderedRecipeDisplays.add(recipe.displayKey())) return;
            ensure(60);
            page.elements.add(RenderElement.recipe(y, recipe, focusedOutput));
            y += 60;
        }

        void addAutomaticCrafting(ItemStack stack) {
            List<GuideRecipe> recipes = recipesFor(stack);
            List<GuideRecipe> usages = usagesFor(stack);
            Set<Identifier> directIds = recipes.stream().map(GuideRecipe::id).collect(Collectors.toSet());
            List<GuideRecipe> missingRecipes = recipes.stream()
                .filter(recipe -> !renderedRecipeDisplays.contains(recipe.displayKey()))
                .collect(Collectors.toList());
            List<GuideRecipe> missingUsages = usages.stream()
                .filter(recipe -> !directIds.contains(recipe.id()))
                .filter(recipe -> !renderedRecipeDisplays.contains(recipe.displayKey()))
                .collect(Collectors.toList());
            if (!missingRecipes.isEmpty()) {
                newPage();
                addChapter(recipeChapter(true, missingRecipes.size()), 0);
                for (GuideRecipe recipe : missingRecipes) addRecipe(recipe, stack);
            }
            if (!missingUsages.isEmpty()) {
                if (missingUsages.size() != 1) newPage();
                addChapter(recipeChapter(false, missingUsages.size()), 0);
                for (GuideRecipe recipe : missingUsages) addRecipe(recipe, ItemStack.EMPTY);
            }
        }

        private Component recipeChapter(boolean creating, int count) {
            String key = creating
                ? (count == 1 ? "buildcraft.guide.recipe.create" : "buildcraft.guide.recipe.create.plural")
                : (count == 1 ? "buildcraft.guide.recipe.use" : "buildcraft.guide.recipe.use.plural");
            return Component.literal(GuideContent.translateOrLiteral(key));
        }

        DocumentLayout finish() {
            while (pages.size() > 1 && pages.get(pages.size() - 1).elements.isEmpty()) {
                pages.remove(pages.size() - 1);
            }
            return new DocumentLayout(pages, chapters);
        }
    }

    private ItemStack imageStack(String source) {
        try {
            Identifier location = Identifier.parse(source);
            String path = location.getPath();
            if (path.startsWith("items/")) {
                String itemPath = path.substring("items/".length());
                return GuideContent.resolveStackForTag(location.getNamespace() + ":" + itemPath);
            }
            if (!path.startsWith("textures/") && !path.endsWith(".png")) {
                Item item = BuiltInRegistries.ITEM.get(location).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.item.Items.AIR);
                if (item != null) return item.getDefaultInstance();
            }
        } catch (RuntimeException ignored) {
        }
        return ItemStack.EMPTY;
    }

    private int[] sourceTextureSize(String source) {
        if (source.endsWith("marker_path.png") || source.endsWith("guide_book.png")) {
            return new int[] { 16, 16 };
        }
        // The only full GUI image referenced by the original pages is the combustion-engine screen.
        return new int[] { 256, 256 };
    }

    @Nullable
    private Identifier textureLocation(String source) {
        try {
            return Identifier.parse(source);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private List<GuideRecipe> recipesFor(ItemStack output) {
        return allGuideRecipes().stream()
            .filter(holder -> recipeOutputs(holder.value()).stream()
                .anyMatch(result -> guideStacksMatch(output, result)))
            .sorted(Comparator.comparing(holder -> holder.id().toString()))
            .collect(Collectors.toList());
    }

    private List<GuideRecipe> usagesFor(ItemStack input) {
        return allGuideRecipes().stream()
            .filter(holder -> recipeUses(holder.value(), input))
            .sorted(Comparator.comparing(holder -> holder.id().toString()))
            .collect(Collectors.toList());
    }

    /**
     * 1.21.11 no longer exposes server RecipeHolder data on the client. The Guide cache carries the authoritative
     * datapack recipe id next to each RecipeDisplayEntry, preserving recipe-id addon pages as well as normal
     * crafting recipe/usages previews on integrated and remote servers.
     */
    private List<GuideRecipe> allGuideRecipes() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return List.of();
        }
        List<GuideRecipe> recipes = new ArrayList<>();
        for (GuideRecipeDisplayCache.Entry entry : GuideRecipeDisplayCache.entriesOr(
            RecipeListPhantom.from(minecraft.player.getRecipeBook()).entries())) {
            recipes.add(new GuideRecipe(entry.recipeId(), entry.display()));
        }
        return recipes;
    }

    private record GuideRecipe(Identifier id, RecipeDisplayEntry value) {
        GuideRecipeDisplayKey displayKey() {
            return new GuideRecipeDisplayKey(id, value.id().index());
        }
    }

    /** A single datapack recipe can expose multiple independent recipe-book displays. */
    private record GuideRecipeDisplayKey(Identifier recipeId, int displayId) {
    }

    @Nullable
    private static ContextMap recipeDisplayContext() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? null : SlotDisplayContext.fromLevel(minecraft.level);
    }

    private static List<ItemStack> recipeOutputs(RecipeDisplayEntry entry) {
        ContextMap context = recipeDisplayContext();
        if (context == null) return List.of();
        try {
            List<ItemStack> outputs = new ArrayList<>();
            for (ItemStack stack : entry.resultItems(context)) {
                if (stack != null && !stack.isEmpty()
                    && outputs.stream().noneMatch(existing -> guideStacksMatch(existing, stack))) {
                    outputs.add(stack.copy());
                }
            }
            return outputs;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static List<SlotDisplay> recipeInputDisplays(RecipeDisplayEntry entry) {
        return BCRecipeDisplays.craftingInputs(entry);
    }

    private static boolean recipeUses(RecipeDisplayEntry entry, ItemStack input) {
        ContextMap context = recipeDisplayContext();
        if (context == null || input.isEmpty()) return false;
        try {
            for (SlotDisplay slot : recipeInputDisplays(entry)) {
                for (ItemStack candidate : slot.resolveForStacks(context)) {
                    if (guideStacksMatch(input, candidate)) return true;
                }
            }
        } catch (RuntimeException ignored) {
            // Dynamic/tag-backed displays may fail while registries are being replaced during a resource reload.
        }
        return false;
    }

    private static boolean guideStacksMatch(ItemStack requested, ItemStack candidate) {
        if (requested.isEmpty() || candidate.isEmpty() || requested.getItem() != candidate.getItem()) return false;
        // BC8's recipe indices matched metadata variants, which is essential for lenses, filters and other legacy
        // damage-value items. Only require component equality when the authored target actually specifies data.
        if (requested.getDamageValue() != candidate.getDamageValue()) return false;
        return !ItemStackUtil.hasCustomData(requested)
            || ItemStack.isSameItemSameComponents(requested, candidate);
    }

    private ItemStack focusedRecipeOutput(RecipeDisplayEntry entry, ItemStack requested) {
        if (!requested.isEmpty()) {
            for (ItemStack output : recipeOutputs(entry)) {
                if (guideStacksMatch(requested, output)) return output.copy();
            }
        }
        List<ItemStack> outputs = recipeOutputs(entry);
        return outputs.isEmpty() ? ItemStack.EMPTY : outputs.get(0).copy();
    }

    private void renderRecipe(GuiGraphics guiGraphics, @Nullable RecipeDisplayEntry entry,
        @Nullable ItemStack requestedOutput, int x, int y, int mouseX, int mouseY) {
        if (entry == null) return;
        ItemStack focus = requestedOutput == null ? ItemStack.EMPTY : requestedOutput;
        try {
            renderCraftingRecipe(guiGraphics, entry, focus, x, y, mouseX, mouseY);
        } catch (RuntimeException ignored) {
            // A broken third-party display must not close the whole guide.
        }
    }

    private void renderCraftingRecipe(GuiGraphics guiGraphics, RecipeDisplayEntry entry, ItemStack requestedOutput,
        int x, int y, int mouseX, int mouseY) {
        CRAFTING_GRID.drawAt(guiGraphics, x, y);

        RecipeDisplay display = entry.display();
        List<SlotDisplay> ingredients = recipeInputDisplays(entry);
        int recipeWidth = 3;
        int recipeHeight = 3;
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            recipeWidth = Mth.clamp(shaped.width(), 1, 3);
            recipeHeight = Mth.clamp(shaped.height(), 1, 3);
        } else if (display instanceof ShapelessCraftingRecipeDisplay) {
            if (ingredients.size() <= 3) {
                recipeWidth = Math.max(1, ingredients.size());
                recipeHeight = 1;
            } else if (ingredients.size() <= 6) {
                recipeWidth = 3;
                recipeHeight = 2;
            }
        }

        int ingredientIndex = 0;
        for (int row = 0; row < recipeHeight; row++) {
            for (int column = 0; column < recipeWidth; column++) {
                if (ingredientIndex >= ingredients.size()) break;
                ItemStack stack = slotDisplayStack(ingredients.get(ingredientIndex), ingredientIndex);
                int slotX = x + 1 + column * 18;
                int slotY = y + 1 + row * 18;
                renderRecipeStack(guiGraphics, stack, slotX, slotY, mouseX, mouseY);
                ingredientIndex++;
            }
        }

        ItemStack result = focusedRecipeOutput(entry, requestedOutput);
        renderRecipeStack(guiGraphics, result, x + 95, y + 19, mouseX, mouseY);
    }

    private ItemStack slotDisplayStack(SlotDisplay display, int offset) {
        ContextMap context = recipeDisplayContext();
        if (context == null) return ItemStack.EMPTY;
        try {
            List<ItemStack> stacks = display.resolveForStacks(context);
            if (stacks.isEmpty()) return ItemStack.EMPTY;
            ItemStack stack = stacks.get(Math.floorMod(tick / 30 + offset, stacks.size()));
            return stack == null ? ItemStack.EMPTY : stack.copy();
        } catch (RuntimeException ignored) {
            return ItemStack.EMPTY;
        }
    }

    private void renderRecipeStack(GuiGraphics guiGraphics, ItemStack stack, int x, int y, int mouseX, int mouseY) {
        if (stack.isEmpty()) return;
        guiGraphics.renderItem(stack, x, y);
        guiGraphics.renderItemDecorations(font, stack, x, y);
        registerStackInteraction(stack, x, y, 16, 16, mouseX, mouseY);
    }

    private void registerStackInteraction(ItemStack stack, int x, int y, int width, int height,
        int mouseX, int mouseY) {
        if (stack.isEmpty()) return;
        ItemStack copy = stack.copy();
        if (isInside(mouseX, mouseY, x, y, width, height)) hoveredStack = copy;
        clickRegions.add(new ClickRegion(x, y, width, height, () -> openStackPage(copy)));
    }

    private void openStackPage(ItemStack stack) {
        GuideContent.Entry matching = findByStack(stack);
        openLinkedEntry(matching == null ? GuideContent.createGeneratedItemEntry(stack) : matching);
    }

    private void renderNavigation(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int firstPage = currentFirstPage();
        int pageCount = currentPageCount();
        int spread = view == View.CONTENTS ? contentsSpread : documentSpread;
        int maxSpread = Math.max(0, (pageCount - 1) / 2);
        int navigationY = top + PAGE_TEXT_TOP + PAGE_TEXT_HEIGHT;
        int pageNumberY = navigationY + 6;

        if (firstPage > 0) {
            int x = left + 23;
            int y = navigationY;
            drawPageArrow(guiGraphics, x, y, false, isInside(mouseX, mouseY, x - 3, y - 4, 24, 18));
            clickRegions.add(new ClickRegion(x - 3, y - 4, 24, 18, () -> changeSpread(-1)));
        }
        if (spread < maxSpread && firstPage + 2 < pageCount) {
            int x = left + PAGE_TEXTURE_WIDTH + 4 + PAGE_TEXT_WIDTH - 18;
            int y = navigationY;
            drawPageArrow(guiGraphics, x, y, true, isInside(mouseX, mouseY, x - 3, y - 4, 24, 18));
            clickRegions.add(new ClickRegion(x - 3, y - 4, 24, 18, () -> changeSpread(1)));
        }
        if (view == View.DOCUMENT) {
            int x = left + PAGE_TEXTURE_WIDTH - 9;
            int y = top + PAGE_TEXTURE_HEIGHT - 11;
            boolean hovered = isInside(mouseX, mouseY, x - 2, y - 2, 21, 13);
            RenderCompat.blit(guiGraphics, ICONS, x, y, 48, hovered ? 152 : 139, 17, 9, 256, 256);
            clickRegions.add(new ClickRegion(x - 2, y - 2, 21, 13, this::goBack));
        }

        if (firstPage < pageCount) {
            drawCentred(guiGraphics, Component.literal((firstPage + 1) + " / " + pageCount),
                left + 23, pageNumberY, PAGE_TEXT_WIDTH, PAGE_NUMBER_COLOUR);
        }
        if (firstPage + 1 < pageCount) {
            drawCentred(guiGraphics, Component.literal((firstPage + 2) + " / " + pageCount),
                left + PAGE_TEXTURE_WIDTH + 4, pageNumberY, PAGE_TEXT_WIDTH, PAGE_NUMBER_COLOUR);
        }
    }

    private int currentFirstPage() {
        return (view == View.CONTENTS ? contentsSpread : documentSpread) * 2;
    }

    private int currentPageCount() {
        if (view == View.CONTENTS) {
            return currentContentsPageCount();
        }
        return document == null ? 0 : Math.max(1, document.pages.size());
    }

    private void drawPageArrow(GuiGraphics guiGraphics, int x, int y, boolean forward, boolean hovered) {
        int u = forward ? 0 : 23;
        int v = hovered ? 152 : 139;
        RenderCompat.blit(guiGraphics, ICONS, x, y, u, v, 18, 10, 256, 256);
    }

    private void changeSpread(int amount) {
        if (view == View.CONTENTS) {
            contentsSpread = Mth.clamp(contentsSpread + amount, 0, maxContentsSpread());
        } else if (document != null) {
            documentSpread = Mth.clamp(documentSpread + amount, 0, document.maxSpread());
        }
        updateSearchVisibility();
        persistGuideState();
    }

    private void goBack() {
        if (!history.isEmpty()) {
            PageState state = history.pop();
            currentEntry = state.entry;
            document = layoutDocument(state.entry);
            documentSpread = Mth.clamp(state.spread, 0, document.maxSpread());
            updateSearchVisibility();
            persistGuideState();
        } else {
            returnToContents();
        }
    }

    private void returnToContents() {
        view = View.CONTENTS;
        currentEntry = null;
        document = null;
        documentSpread = 0;
        updateSearchVisibility();
        persistGuideState();
    }

    private int maxContentsSpread() {
        return Math.max(0, (currentContentsPageCount() - 1) / 2);
    }

    private int currentContentsPageCount() {
        return Math.max(2, firstContentsPageIndex() + contentsPages.size());
    }

    private int loadedGuidePageCount() {
        int size = content.getLoadedGuideSources().size();
        return Math.max(1, (size + LOADED_GUIDES_PER_PAGE - 1) / LOADED_GUIDES_PER_PAGE);
    }

    private int firstContentsPageIndex() {
        int raw = 1 + loadedGuidePageCount();
        // Contents controls are attached to the left page, so reserve a blank right page when the Loaded Guides
        // section would otherwise make the first contents page land on the right side of a spread.
        return (raw + 1) & ~1;
    }

    private boolean isContentsEntryPage(int pageIndex) {
        int first = firstContentsPageIndex();
        return pageIndex >= first && pageIndex < first + contentsPages.size();
    }

    private int firstFullContentsSpread() {
        return firstContentsPageIndex() / 2;
    }

    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (mouseClicked(event.x(), event.y(), event.button())) {
            return true;
        }
        return false;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (searchBox != null && searchBox.visible && searchBox.mouseClicked(new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0)), false)) {
                return true;
            }
            for (int index = clickRegions.size() - 1; index >= 0; index--) {
                ClickRegion region = clickRegions.get(index);
                if (region.contains(mouseX, mouseY)) {
                    region.action.run();
                    return true;
                }
            }
        }
        if (button == 1 && searchBox != null && searchBox.visible
            && isInside(mouseX, mouseY, searchX, searchY, 80, 13)) {
            searchBox.setValue("");
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            changeSpread(scrollY < 0 ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    public boolean keyPressed(KeyEvent event) {
        // Closing is a screen action, not an EditBox action. Handle it before search/navigation.
        if (event.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (keyPressed(event.key(), event.scancode(), event.modifiers())) {
            return true;
        }
        return super.keyPressed(event);
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBox != null && searchBox.visible && searchBox.keyPressed(new KeyEvent(keyCode, scanCode, modifiers))) return true;
        if (Minecraft.getInstance().options.keyInventory.matches(new KeyEvent(keyCode, scanCode, modifiers))) {
            onClose();
            return true;
        }
        if (keyCode == 263) {
            changeSpread(-1);
            return true;
        }
        if (keyCode == 262) {
            changeSpread(1);
            return true;
        }
        if (keyCode == 259 && view == View.DOCUMENT) {
            goBack();
            return true;
        }
        return false;
    }

    public boolean charTyped(CharacterEvent event) {
        if (event.codepoint() <= Character.MAX_VALUE
            && charTyped((char) event.codepoint(), event.modifiers())) {
            return true;
        }
        return super.charTyped(event);
    }

    public boolean charTyped(char codePoint, int modifiers) {
        if (searchBox != null && searchBox.visible
            && searchBox.charTyped(new CharacterEvent(codePoint, modifiers))) {
            return true;
        }
        return false;
    }

    public void removed() {
        persistGuideState();
        super.removed();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    public boolean isPauseScreen() {
        return false;
    }

    private void drawCentred(GuiGraphics guiGraphics, Component text, int x, int y, int availableWidth, int colour) {
        drawOverflowText(guiGraphics, text, x, y, availableWidth, colour, HorizontalAlignment.CENTRE,
            text.getString().hashCode());
    }

    private void drawScaledCentred(GuiGraphics guiGraphics, String text, int x, int y, int availableWidth, float scale,
        int colour) {
        float fittedScale = scale;
        int unscaledWidth = font.width(text);
        if (unscaledWidth > 0) {
            fittedScale = Math.min(scale, availableWidth / (float) unscaledWidth);
        }
        float scaledWidth = unscaledWidth * fittedScale;
        guiGraphics.pose().pushMatrix();
        guiGraphics.pose().translate(x + (availableWidth - scaledWidth) / 2.0F, y);
        guiGraphics.pose().scale(fittedScale, fittedScale);
        guiGraphics.drawString(font, text, 0, 0, opaqueGuideTextColour(colour), false);
        guiGraphics.pose().popMatrix();
    }

    private void drawOverflowText(GuiGraphics guiGraphics, Component text, int x, int y, int availableWidth, int colour,
        HorizontalAlignment alignment, int seed) {
        drawOverflowText(guiGraphics, text.getVisualOrderText(), x, y, availableWidth, colour, alignment, seed);
    }

    private void drawOverflowText(GuiGraphics guiGraphics, FormattedCharSequence text, int x, int y, int availableWidth,
        int colour, HorizontalAlignment alignment, int seed) {
        int lineHeight = font.lineHeight;
        int textWidth = font.width(text);
        if (textWidth <= availableWidth) {
            float drawX = alignment == HorizontalAlignment.CENTRE
                ? x + (availableWidth - textWidth) / 2.0F
                : x;
            guiGraphics.drawString(font, text, (int) drawX, y, opaqueGuideTextColour(colour), false);
            return;
        }
        enableGuiScissor(x, y - 1, availableWidth, lineHeight + 2);
        try {
            float offset = marqueeOffset(textWidth - availableWidth, seed);
            guiGraphics.drawString(font, text, (int) (x + offset), y, opaqueGuideTextColour(colour), false);
        } finally {
            RenderCompat.disableScissor();
        }
    }

    private static int opaqueGuideTextColour(int colour) {
        return (colour & 0xFF000000) == 0 ? (colour | 0xFF000000) : colour;
    }

    private float marqueeOffset(int overflow, int seed) {
        if (overflow <= 0) {
            return 0;
        }
        int pause = 20;
        float travelFrames = Math.max(40.0F, overflow * 2.5F);
        float cycle = pause * 2.0F + travelFrames * 2.0F;
        float phase = Math.floorMod(tick + seed, Math.max(1, Math.round(cycle)));
        if (phase < pause) {
            return 0;
        }
        phase -= pause;
        if (phase < travelFrames) {
            return -overflow * (phase / travelFrames);
        }
        phase -= travelFrames;
        if (phase < pause) {
            return -overflow;
        }
        phase -= pause;
        return -overflow * (1.0F - phase / travelFrames);
    }

    private void enableGuiScissor(int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        Window window = Minecraft.getInstance().getWindow();
        double scale = window.getGuiScale();
        int x0 = Mth.clamp((int) Math.floor(x * scale), 0, window.getWidth());
        int x1 = Mth.clamp((int) Math.ceil((x + width) * scale), 0, window.getWidth());
        int y0 = Mth.clamp((int) Math.floor(window.getHeight() - (y + height) * scale), 0, window.getHeight());
        int y1 = Mth.clamp((int) Math.ceil(window.getHeight() - y * scale), 0, window.getHeight());
        if (x1 <= x0 || y1 <= y0) return;
        RenderCompat.enableScissor(x0, y0, x1 - x0, y1 - y0);
    }

    private static int parseInt(@Nullable String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
    }

    private static final class ContentsPage {
        final List<ContentsLine> lines = new ArrayList<>();
    }

    private enum ContentsLineKind {
        CHAPTER,
        SUBHEADING,
        ENTRY,
        MESSAGE
    }

    private static final class ContentsLine {
        final ContentsLineKind kind;
        final Component component;
        final @Nullable GuideContent.Entry entry;
        final String groupKey;
        final int colour;
        final int height;
        int y;

        private ContentsLine(ContentsLineKind kind, Component component, @Nullable GuideContent.Entry entry,
            String groupKey, int colour, int height) {
            this.kind = kind;
            this.component = component;
            this.entry = entry;
            this.groupKey = groupKey;
            this.colour = colour;
            this.height = height;
        }

        static ContentsLine chapter(String key, String label, int colour) {
            return new ContentsLine(ContentsLineKind.CHAPTER, Component.literal(label), null, key, colour,
                CONTENT_CHAPTER_HEIGHT);
        }

        static ContentsLine subheading(String label) {
            return new ContentsLine(ContentsLineKind.SUBHEADING, Component.literal(label), null, "", 0,
                CONTENT_SUBHEADING_HEIGHT);
        }

        static ContentsLine entry(GuideContent.Entry entry) {
            return new ContentsLine(ContentsLineKind.ENTRY, Component.literal(entry.title()), entry, "", 0,
                CONTENT_ENTRY_HEIGHT);
        }

        static ContentsLine message(Component message) {
            return new ContentsLine(ContentsLineKind.MESSAGE, message, null, "", 0, CONTENT_ENTRY_HEIGHT);
        }
    }

    private static final class ChapterTab {
        final String key;
        final String label;
        final int colour;
        final int pageIndex;

        ChapterTab(String key, String label, int colour, int pageIndex) {
            this.key = key;
            this.label = label;
            this.colour = colour;
            this.pageIndex = pageIndex;
        }
    }

    private static final class ClickRegion {
        final int x;
        final int y;
        final int width;
        final int height;
        final Runnable action;

        ClickRegion(int x, int y, int width, int height, Runnable action) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.action = action;
        }

        boolean contains(double mouseX, double mouseY) {
            return isInside(mouseX, mouseY, x, y, width, height);
        }
    }

    private static final class PageState {
        final GuideContent.Entry entry;
        final int spread;

        PageState(GuideContent.Entry entry, int spread) {
            this.entry = entry;
            this.spread = spread;
        }
    }

    private static final class DocumentLayout {
        final List<RenderPage> pages;
        final List<DocumentChapter> chapters;

        DocumentLayout(List<RenderPage> pages, List<DocumentChapter> chapters) {
            this.pages = pages;
            this.chapters = chapters;
        }

        @Nullable
        RenderPage page(int index) {
            return index < 0 || index >= pages.size() ? null : pages.get(index);
        }

        int maxSpread() {
            return Math.max(0, (pages.size() - 1) / 2);
        }
    }

    private static final class DocumentChapter {
        final String title;
        final int colour;
        final int pageIndex;
        final int level;

        DocumentChapter(String title, int colour, int pageIndex, int level) {
            this.title = title;
            this.colour = colour;
            this.pageIndex = pageIndex;
            this.level = level;
        }
    }

    private static final class RenderPage {
        final List<RenderElement> elements = new ArrayList<>();
    }

    private enum ElementKind {
        TEXT,
        CHAPTER,
        CODE,
        LINK,
        IMAGE,
        RECIPE
    }

    private static final class RenderElement {
        final ElementKind kind;
        final int x;
        final int y;
        final int width;
        final int height;
        final int sourceWidth;
        final int sourceHeight;
        final int colour;
        final boolean chapterBar;
        final @Nullable FormattedCharSequence line;
        final @Nullable Component component;
        final @Nullable String target;
        final @Nullable String secondary;
        final @Nullable ItemStack stack;
        final @Nullable Identifier texture;
        final @Nullable GuideRecipe recipe;

        private RenderElement(ElementKind kind, int x, int y, int width, int height, int sourceWidth,
            int sourceHeight, int colour, boolean chapterBar, @Nullable FormattedCharSequence line,
            @Nullable Component component, @Nullable String target, @Nullable String secondary,
            @Nullable ItemStack stack, @Nullable Identifier texture, @Nullable GuideRecipe recipe) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.colour = colour;
            this.chapterBar = chapterBar;
            this.line = line;
            this.component = component;
            this.target = target;
            this.secondary = secondary;
            this.stack = stack;
            this.texture = texture;
            this.recipe = recipe;
        }

        static RenderElement text(int y, FormattedCharSequence line, int width, @Nullable String target) {
            return new RenderElement(ElementKind.TEXT, 0, y, width, 10, 0, 0, 0, false,
                line, null, target, null, null, null, null);
        }

        static RenderElement chapter(int x, int y, FormattedCharSequence line, int colour,
            boolean chapterBar, int blockHeight) {
            return new RenderElement(ElementKind.CHAPTER, x, y, 0, blockHeight, 0, 0, colour, chapterBar,
                line, null, null, null, null, null, null);
        }

        static RenderElement code(int x, int y, FormattedCharSequence line) {
            return new RenderElement(ElementKind.CODE, x, y, PAGE_TEXT_WIDTH - 4, 10, 0, 0, 0, false,
                line, null, null, null, null, null, null);
        }

        static RenderElement link(int y, Component title, @Nullable String target, @Nullable String type,
            ItemStack stack) {
            return new RenderElement(ElementKind.LINK, 0, y, PAGE_TEXT_WIDTH, 19, 0, 0, 0, false,
                null, title, target, type, stack, null, null);
        }

        static RenderElement image(int x, int y, int width, int height, int sourceWidth, int sourceHeight,
            @Nullable Identifier texture, ItemStack stack) {
            return new RenderElement(ElementKind.IMAGE, x, y, width, height, sourceWidth, sourceHeight, 0, false,
                null, null, null, null, stack, texture, null);
        }

        static RenderElement recipe(int y, GuideRecipe recipe, ItemStack focusedOutput) {
            int width = CRAFTING_GRID.width;
            return new RenderElement(ElementKind.RECIPE, (PAGE_TEXT_WIDTH - width) / 2, y,
                width, 60, 0, 0, 0, false, null, null, null, null,
                focusedOutput.isEmpty() ? null : focusedOutput.copy(), null, recipe);
        }

    }
}
