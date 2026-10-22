//? source if >=1.21.11
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.client.guide;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.minecraft.core.registries.BuiltInRegistries;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.guide.GuideEntry;
import buildcraft.api.v2.guide.GuidePage;
import buildcraft.api.v2.guide.GuideSection;
import buildcraft.api.v2.guide.GuideService;
import buildcraft.lib.internal.api.v2.GuideOwnershipView;
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.lib.internal.statement.IStatement;
import buildcraft.lib.internal.statement.StatementManager;
import buildcraft.lib.compat.NbtCompat;

/**
 * Loads the BuildCraft Guide Book index and its localized packed pages.
 * <p>
 * The manifest contains language-neutral entry metadata. {@link GuidePageStore} combines one shared page-layout file
 * with the selected language text pack and its configured fallbacks before the Markdown document is parsed.
 */
public final class GuideContent {
    private static final Identifier MANIFEST =
        Identifier.fromNamespaceAndPath("buildcraft", "guide/original_manifest.json");
    private static final Pattern FIRST_CHAPTER = Pattern.compile("<chapter\\s+name=\"([^\"]+)\"[^>]*/>");
    private static final Pattern FIRST_HEADING = Pattern.compile("(?m)^#+\\s+(.+)$");
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    private static final String API_SECTION_TYPE_PREFIX = "api_section/";

    private final List<Entry> allEntries;
    private final List<Entry> listedEntries;
    private final Map<Identifier, Entry> byId;
    private final List<String> loadedGuideSources;

    private GuideContent(List<Entry> entries) {
        allEntries = Collections.unmodifiableList(entries);
        List<Entry> listed = new ArrayList<>();
        Map<Identifier, Entry> ids = new LinkedHashMap<>();
        for (Entry entry : entries) {
            ids.put(entry.id, entry);
            if (entry.listed) {
                listed.add(entry);
            }
        }
        listedEntries = Collections.unmodifiableList(listed);
        byId = Collections.unmodifiableMap(ids);
        loadedGuideSources = Collections.unmodifiableList(buildLoadedGuideSources(entries));
    }

    public static GuideContent load() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            ResourceManager resources = minecraft.getResourceManager();
            String manifestText = readText(resources, MANIFEST);
            JsonObject root = JsonParser.parseString(manifestText).getAsJsonObject();
            GuidePageStore pageStore = GuidePageStore.load(resources, minecraft.options.languageCode);
            JsonArray array = root.getAsJsonArray("entries");
            List<Entry> entries = new ArrayList<>(array.size());
            for (JsonElement element : array) {
                try {
                    JsonObject json = element.getAsJsonObject();
                    String requiredMod = getString(json, "requires_mod");
                    if (requiredMod != null && !requiredMod.isBlank() && !ModList.get().isLoaded(requiredMod)) {
                        continue;
                    }
                    Identifier id = Identifier.parse(json.get("id").getAsString());
                    String page = getString(json, "page");
                    if (page == null || page.isBlank()) {
                        BCLog.logger.warn("[lib.guide] Entry {} has no page key", id);
                        continue;
                    }
                    String text = pageStore.get(page);
                    if (text == null) {
                        BCLog.logger.warn("[lib.guide] Page '{}' for {} is missing from language chain {}",
                            page, id, pageStore.loadOrder());
                        continue;
                    }
                    String stackId = getString(json, "stack");
                    String stackNbt = getString(json, "stack_nbt");
                    ItemStack stack = resolveStack(stackId, stackNbt);
                    String statement = getString(json, "statement");
                    entries.add(new Entry(
                        id,
                        page,
                        getString(json, "module"),
                        getString(json, "type"),
                        getString(json, "subtype"),
                        getString(json, "name"),
                        getString(json, "book"),
                        json.has("listed") && json.get("listed").getAsBoolean(),
                        stackId,
                        statement,
                        stack,
                        text
                    ));
                } catch (RuntimeException entryError) {
                    BCLog.logger.warn("[lib.guide] Skipping malformed guide entry {}", guideEntryDebugName(element),
                        entryError);
                }
            }
            appendApiEntries(entries);
            return new GuideContent(entries);
        } catch (Exception ex) {
            BCLog.logger.error("[lib.guide] Failed to load the BuildCraft guide", ex);
            return new GuideContent(new ArrayList<>());
        }
    }


    private static void appendApiEntries(List<Entry> entries) {
        Optional<GuideService> optional = BuildCraftApi.runtime().service(BuildCraftServices.GUIDE);
        if (optional.isEmpty()) return;
        GuideService guide = optional.get();
        Set<Identifier> appended = new LinkedHashSet<>();

        // Sections are the public Guide API's ordering/grouping primitive. Walking them first makes section.order()
        // authoritative in the native contents tree, while entries(section) already honours GuideEntry.order().
        for (GuideSection section : guide.sections()) {
            for (GuideEntry apiEntry : guide.entries(section.id())) {
                try {
                    appendApiEntry(entries, guide, apiEntry, section);
                    appended.add(apiEntry.id());
                } catch (RuntimeException entryError) {
                    BCLog.logger.warn("[lib.guide] Skipping broken API v2 guide entry {}", apiEntry.id(), entryError);
                }
            }
        }
        // Keep malformed/forward-compatible contributions reachable even if their section was not registered.
        for (GuideEntry apiEntry : guide.entries()) {
            if (appended.add(apiEntry.id())) {
                try {
                    appendApiEntry(entries, guide, apiEntry, guide.section(apiEntry.section()).orElse(null));
                } catch (RuntimeException entryError) {
                    BCLog.logger.warn("[lib.guide] Skipping broken API v2 guide entry {}", apiEntry.id(), entryError);
                }
            }
        }
    }

    private static void appendApiEntry(List<Entry> entries, GuideService guide, GuideEntry apiEntry,
        @Nullable GuideSection section) {
        boolean duplicate = entries.stream().anyMatch(existing -> existing.id.equals(apiEntry.id()));
        if (duplicate) {
            BCLog.logger.warn("[lib.guide] Ignoring API v2 guide entry {} because that id is already present", apiEntry.id());
            return;
        }

        String owner = guideOwner(guide, apiEntry);
        String sectionKey = section == null ? apiEntry.section().toString() : section.id().toString();
        String sectionTitle = section == null
            ? titleCase(apiEntry.section().getPath())
            : guideSectionTitle(guide, section);
        String title = translateOrLiteral(apiEntry.titleKey());
        String stackId = apiEntry.icon().map(Identifier::toString).orElse(null);
        entries.add(new Entry(
            apiEntry.id(),
            apiEntry.id().toString(),
            owner,
            API_SECTION_TYPE_PREFIX + sectionKey,
            sectionTitle,
            title,
            "buildcraftcore:main",
            true,
            stackId,
            null,
            resolveStack(stackId, null),
            renderApiPages(apiEntry)
        ));
    }

    private static String guideOwner(GuideService guide, GuideEntry entry) {
        if (guide instanceof GuideOwnershipView ownership) {
            return ownership.ownerOfEntry(entry.id()).orElse(entry.id().getNamespace());
        }
        return entry.id().getNamespace();
    }

    private static String guideSectionTitle(GuideService guide, GuideSection leaf) {
        List<String> path = new ArrayList<>();
        Set<Identifier> visited = new LinkedHashSet<>();
        GuideSection section = leaf;
        while (section != null && visited.add(section.id())) {
            path.add(0, translateOrLiteral(section.titleKey()));
            section = section.parent().flatMap(guide::section).orElse(null);
        }
        return String.join(" / ", path);
    }

    private static List<String> buildLoadedGuideSources(List<Entry> entries) {
        boolean hasBuildCraft = false;
        Set<String> addonOwners = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (!entry.listed || !"buildcraftcore:main".equals(entry.book)) continue;
            if (entry.isApiGuide()) {
                addonOwners.add(entry.module);
            } else if (entry.module.startsWith("buildcraft")) {
                hasBuildCraft = true;
            }
        }
        List<String> result = new ArrayList<>();
        if (hasBuildCraft) result.add("BuildCraft");
        for (String owner : addonOwners) {
            // An official BuildCraft module that contributes through API2 is still displayed as one BuildCraft guide.
            if (owner.startsWith("buildcraft")) {
                if (!result.contains("BuildCraft")) result.add(0, "BuildCraft");
                continue;
            }
            result.add(modDisplayName(owner));
        }
        return result;
    }

    private static String modDisplayName(String modId) {
        if (modId == null || modId.isBlank()) return "Unknown";
        if (modId.startsWith("buildcraft")) return "BuildCraft";
        return ModList.get().getModContainerById(modId)
            .map(container -> container.getModInfo().getDisplayName())
            .filter(name -> name != null && !name.isBlank())
            .orElse(modId);
    }

    private static String renderApiPages(GuideEntry entry) {
        StringBuilder markdown = new StringBuilder();
        // LayoutBuilder already creates the entry title chapter. API pages start with their actual payload so
        // addon-provided titles do not appear twice and do not consume an extra pagination block.
        boolean first = true;
        for (GuidePage page : entry.pages()) {
            if (!first) markdown.append("<new_page/>\n");
            first = false;
            if (page instanceof GuidePage.Text text) {
                markdown.append(text.translatable() ? translateOrLiteral(text.value()) : text.value()).append('\n');
            } else if (page instanceof GuidePage.Image image) {
                markdown.append("<image src=\"").append(image.texture()).append("\" width=\"")
                    .append(image.width()).append("\" height=\"").append(image.height()).append("\"/>\n");
                if (image.captionKey() != null && !image.captionKey().isBlank()) {
                    markdown.append(translateOrLiteral(image.captionKey())).append('\n');
                }
            } else if (page instanceof GuidePage.Link link) {
                markdown.append('[').append(translateOrLiteral(link.labelKey())).append("](")
                    .append(link.targetEntry()).append(")\n");
            } else if (page instanceof GuidePage.Item item) {
                markdown.append(translateOrLiteral(item.textKey())).append('\n')
                    .append("<recipes_usages stack=\"").append(item.itemId()).append("\"/>\n");
            } else if (page instanceof GuidePage.Recipe recipe) {
                markdown.append("<recipe_id id=\"").append(recipe.recipeId()).append("\"/>\n");
            }
        }
        return markdown.toString();
    }

    private static String readText(ResourceManager resources, Identifier location) throws IOException {
        Optional<Resource> optional = resources.getResource(location);
        if (optional.isEmpty()) {
            throw new IOException("Missing resource " + location);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(optional.get().open(), StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        }
    }

    @Nullable
    private static String getString(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static ItemStack resolveStack(@Nullable String rawId, @Nullable String rawNbt) {
        ItemStack stack = resolveStackForTag(rawId, Map.of());
        if (!stack.isEmpty() && rawNbt != null && !rawNbt.isBlank()) {
            try {
                ItemStackUtil.setCustomData(stack, NbtCompat.parseTag(rawNbt));
            } catch (Exception ex) {
                BCLog.logger.warn("[lib.guide] Invalid stack NBT '{}' for {}", rawNbt, rawId, ex);
            }
        }
        return stack;
    }

    public static ItemStack resolveStackForTag(@Nullable String rawId) {
        return resolveStackForTag(rawId, Map.of());
    }

    /**
     * Resolves a stack reference from the original 1.12 guide against the current registries.
     * <p>
     * BC8 pages use a mixture of plain registry names, {@code {id,count,data}} shortcuts and XML attributes. Modern
     * BuildCraft split several metadata items into separate registry entries, so resolving only the literal item id
     * breaks whole classes of pages (pipes, engines, coloured wires, lenses and filters).
     */
    public static ItemStack resolveStackForTag(@Nullable String rawId, Map<String, String> attributes) {
        LegacyStackSpec spec = LegacyStackSpec.parse(rawId, attributes);
        if (spec == null) {
            return ItemStack.EMPTY;
        }

        String remappedId = remapLegacyStackId(spec.id, spec.data);
        Identifier id;
        try {
            id = Identifier.parse(remappedId);
        } catch (RuntimeException ex) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(id).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.item.Items.AIR);
        if (item == null) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = item.getDefaultInstance();
        stack.setCount(Math.max(1, spec.count));

        if (id.equals(Identifier.fromNamespaceAndPath("buildcraftsilicon", "plug/lens"))) {
            int legacyData = spec.data;
            boolean oldFilterId = "buildcraftsilicon:plug_filter".equals(spec.id);
            boolean filter = oldFilterId || legacyData >= 16;
            int oldDyeDamage = Math.floorMod(legacyData, 16);
            // EnumDyeColor.byDyeDamage(0) was black in 1.12, while DyeColor.byId(0) is white in modern versions.
            int modernColourId = 15 - oldDyeDamage;
            stack.setDamageValue(modernColourId + (filter ? 16 : 0));
        } else if (spec.hasExplicitData && spec.data >= 0) {
            stack.setDamageValue(spec.data);
        }
        return stack;
    }

    /** Visible for the resource regression tests. */
    static String remapLegacyStackId(String id, int data) {
        id = id.trim();
        if (id.startsWith("buildcraftcore:items/")) {
            id = "buildcraftcore:" + id.substring("buildcraftcore:items/".length());
        }
        if (id.startsWith("buildcraftcore:gear_")) {
            return "buildcraftcore:gears/" + id.substring("buildcraftcore:".length());
        }
        if (id.equals("buildcraftcore:paintbrush")) return "buildcraftcore:paintbrush/clean";
        if (id.equals("buildcraftcore:engine")) {
            if (data == 1) return "buildcraftenergy:engine_stone";
            if (data == 2) return "buildcraftenergy:engine_iron";
            return "buildcraftcore:engine_redstone";
        }
        if (id.equals("buildcraftsilicon:plug_facade")) return "buildcraftsilicon:plug/facade";
        if (id.equals("buildcraftsilicon:plug_gate")) return "buildcraftsilicon:plug/gate";
        if (id.equals("buildcraftsilicon:plug_filter")) return "buildcraftsilicon:plug/lens";
        if (id.equals("buildcraftsilicon:plug_lens")) return "buildcraftsilicon:plug/lens";
        if (id.equals("buildcraftsilicon:plug_light_sensor")) return "buildcraftsilicon:plug/light_sensor";
        if (id.equals("buildcraftsilicon:plug_pulsar")) return "buildcraftsilicon:plug/pulsar";
        if (id.equals("buildcrafttransport:wire")) return "buildcrafttransport:wire/white";

        if (id.startsWith("buildcrafttransport:pipe_")) {
            String path = id.substring("buildcrafttransport:pipe_".length());
            if (path.equals("cobble_item")) path = "cobblestone_item";
            if (path.equals("cobble_fluid")) path = "cobblestone_fluid";
            if (path.equals("cobble_power")) path = "cobblestone_power";
            return "buildcrafttransport:" + path;
        }
        return id;
    }

    /** Resolves original statement aliases for both index titles and sprites. */
    @Nullable
    public static IStatement resolveStatement(@Nullable String rawId) {
        if (rawId == null || rawId.isEmpty()) return null;
        return StatementManager.statements.get(remapLegacyStatementId(rawId));
    }

    /** Visible for the resource regression tests. */
    static String remapLegacyStatementId(String rawId) {
        String id = rawId.trim();
        if (id.startsWith("buildcraft.") && id.indexOf(':') < 0) {
            id = "buildcraft:" + id.substring("buildcraft.".length());
        }
        switch (id) {
            case "buildcraft:pipe.items.traversing": return "buildcraft:pipe_contains_items";
            case "buildcraft:pipe.fluids.traversing": return "buildcraft:pipe_contains_fluids";
            default: return id;
        }
    }

    private static final class LegacyStackSpec {
        final String id;
        final int count;
        final int data;
        final boolean hasExplicitData;

        private LegacyStackSpec(String id, int count, int data, boolean hasExplicitData) {
            this.id = id;
            this.count = count;
            this.data = data;
            this.hasExplicitData = hasExplicitData;
        }

        @Nullable
        static LegacyStackSpec parse(@Nullable String rawId, Map<String, String> attributes) {
            if (rawId == null || rawId.trim().isEmpty()) return null;
            String value = rawId.trim();
            if ((value.startsWith("{") && value.endsWith("}"))
                || (value.startsWith("(") && value.endsWith(")"))) {
                value = value.substring(1, value.length() - 1).trim();
            }
            String[] split = value.split(",", -1);
            String id = split.length == 0 ? "" : split[0].trim();
            if (id.isEmpty()) return null;

            int count = parseInt(attributes.get("count"), split.length > 1 ? parseInt(split[1], 1) : 1);
            boolean hasDataInValue = split.length > 2 && !split[2].trim().isEmpty();
            boolean hasDataAttribute = attributes.containsKey("data") && attributes.get("data") != null
                && !attributes.get("data").trim().isEmpty();
            int data = hasDataAttribute ? parseInt(attributes.get("data"), 0)
                : hasDataInValue ? parseInt(split[2], 0) : 0;
            return new LegacyStackSpec(id, count, data, hasDataInValue || hasDataAttribute);
        }
    }

    private static int parseInt(@Nullable String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public List<Entry> getAllEntries() {
        return allEntries;
    }

    public List<Entry> getListedEntries() {
        return listedEntries;
    }

    /** BuildCraft first, followed by addons that actually contributed API2 guide entries. */
    public List<String> getLoadedGuideSources() {
        return loadedGuideSources;
    }

    @Nullable
    public Entry get(Identifier id) {
        return byId.get(id);
    }

    @Nullable
    public Entry get(String id) {
        try {
            return get(Identifier.parse(id));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public static Entry createGeneratedItemEntry(ItemStack stack) {
        Identifier registryId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (registryId == null) {
            registryId = Identifier.fromNamespaceAndPath("minecraft", "air");
        }
        Identifier pageId = Identifier.fromNamespaceAndPath(
            "buildcraftlib", "generated/item/" + registryId.getNamespace() + "/" + registryId.getPath()
        );
        return new Entry(
            pageId, pageId.toString(), registryId.getNamespace(), "item", "generated", registryId.getPath(),
            "buildcraftcore:main", false, registryId.toString(), null, stack.copy(), ""
        );
    }

    private static String normalizeSearch(String value) {
        if (value == null || value.isBlank()) return "";
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(lower.length());
        boolean previousSpace = true;
        for (int index = 0; index < lower.length(); index++) {
            char ch = lower.charAt(index);
            if (Character.isLetterOrDigit(ch)) {
                normalized.append(ch);
                previousSpace = false;
            } else if (!previousSpace) {
                normalized.append(' ');
                previousSpace = true;
            }
        }
        int length = normalized.length();
        if (length > 0 && normalized.charAt(length - 1) == ' ') normalized.setLength(length - 1);
        return normalized.toString();
    }

    public static final class Entry {
        public final Identifier id;
        public final String page;
        public final String module;
        public final String type;
        public final String subtype;
        public final String name;
        public final String book;
        public final boolean listed;
        public final @Nullable String stackId;
        public final @Nullable String statement;
        public final ItemStack stack;
        public final String markdown;
        public final String searchText;

        private Entry(Identifier id, String page, @Nullable String module, @Nullable String type,
            @Nullable String subtype, @Nullable String name, @Nullable String book, boolean listed,
            @Nullable String stackId, @Nullable String statement, ItemStack stack, String markdown) {
            this.id = id;
            this.page = page;
            this.module = module == null ? id.getNamespace() : module;
            this.type = type == null ? "other" : type;
            this.subtype = subtype == null ? "other" : subtype;
            this.name = name == null ? id.getPath() : name;
            this.book = book == null ? "buildcraftcore:main" : book;
            this.listed = listed;
            this.stackId = stackId;
            this.statement = statement;
            this.stack = stack;
            this.markdown = markdown;
            this.searchText = normalizeSearch(title() + " " + id + " " + moduleName() + " " + typeName() + " "
                + subtypeName() + " " + plainText(markdown));
        }

        /** Matches every search token, independent of punctuation, spacing and token order. */
        public boolean matchesSearch(String query) {
            String normalized = normalizeSearch(query);
            if (normalized.isEmpty()) return true;
            for (String token : normalized.split(" ")) {
                if (!token.isEmpty() && !searchText.contains(token)) return false;
            }
            return true;
        }

        public boolean isApiGuide() {
            return type.startsWith(API_SECTION_TYPE_PREFIX);
        }

        public String title() {
            // API2 explicitly supplies a title key. The icon is only an icon and must not silently replace that title
            // with the hovered item name. Native BC8 entries keep their original stack/statement naming rules.
            if (isApiGuide()) {
                return name;
            }
            if (!stack.isEmpty()) {
                return stack.getHoverName().getString();
            }
            IStatement resolved = GuideContent.resolveStatement(statement);
            if (resolved != null) {
                try {
                    var description = resolved.getDescription();
                    if (description != null) {
                        String text = description.getString();
                        if (!text.isBlank()) {
                            return text;
                        }
                    }
                } catch (RuntimeException descriptionError) {
                    BCLog.logger.warn("[lib.guide] Failed to resolve statement title for {} ({})", id, statement,
                        descriptionError);
                }
            }
            String pageKey = "buildcraft.guide.page." + name;
            String translatedPage = translateOrLiteral(pageKey);
            if (!translatedPage.equals(pageKey)) {
                return translatedPage;
            }
            // Listed entries are named by their registry value in BC8. Their first authored chapter is a section of
            // the page, not the page title (using it caused dozens of unrelated entries to be called Pipe Mechanics).
            if (listed) {
                return titleCase(name);
            }
            Matcher heading = FIRST_HEADING.matcher(markdown);
            if (heading.find()) {
                return translateOrLiteral(heading.group(1).trim());
            }
            Matcher chapter = FIRST_CHAPTER.matcher(markdown);
            if (chapter.find()) {
                return translateOrLiteral(chapter.group(1));
            }
            return titleCase(name);
        }

        public String moduleName() {
            if (module.startsWith("buildcraft")) {
                String suffix = module.substring("buildcraft".length());
                if (suffix.isEmpty()) return translateOrLiteral("buildcraft.guide.chapter.mod.buildcraft");
                String translated = translateOrLiteral("buildcraft.guide.chapter.submod." + suffix);
                if (!translated.startsWith("buildcraft.guide.")) return translated;
                return "BuildCraft " + Character.toUpperCase(suffix.charAt(0)) + suffix.substring(1);
            }
            return modDisplayName(module);
        }

        public String typeName() {
            if (isApiGuide()) return subtype;
            String key = "buildcraft.guide.chapter.type." + type;
            String translated = translateOrLiteral(key);
            return translated.equals(key) ? titleCase(type) : translated;
        }

        public String subtypeName() {
            if (isApiGuide()) return subtype;
            String key = "buildcraft.guide.chapter.subtype." + subtype;
            String translated = translateOrLiteral(key);
            return translated.equals(key) ? titleCase(subtype) : translated;
        }
    }

    static String translateOrLiteral(String value) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value;
        }
        try {
            if (I18n.exists(value)) {
                return I18n.get(value);
            }
        } catch (RuntimeException translationError) {
            BCLog.logger.warn("[lib.guide] Failed to translate '{}'", value, translationError);
        }
        return value;
    }

    private static String guideEntryDebugName(JsonElement element) {
        try {
            if (element != null && element.isJsonObject()) {
                JsonElement id = element.getAsJsonObject().get("id");
                if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()) {
                    return id.getAsString();
                }
            }
        } catch (RuntimeException ignored) {
            // Diagnostics must never mask the original guide-entry error.
        }
        return "<unknown>";
    }

    static String titleCase(String value) {
        String[] words = value.replace('/', ' ').replace('_', ' ').split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) builder.append(word.substring(1));
        }
        return builder.toString();
    }

    private static String plainText(String markdown) {
        String text = TAGS.matcher(markdown).replaceAll(" ");
        text = text.replaceAll("(?m)^#+\\s*", " ");
        text = text.replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1");
        return text.replace("&lt;", "<").replace("&gt;", ">").replaceAll("\\s+", " ");
    }
}
