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
import java.util.HashSet;
import java.util.LinkedHashMap;
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

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import buildcraft.lib.internal.debug.BCLog;

/**
 * Loads the compact, localized Guide Book resources.
 * <p>
 * Page mechanics are stored once in {@code page_layouts.json}. Every language contributes only arrays of visible text
 * in one compact resource. Text packs are overlaid from the default language through configured fallbacks to the
 * selected language, and then inserted into the shared Markdown templates.
 */
final class GuidePageStore {
    static final ResourceLocation LANGUAGES = ResourceLocation.fromNamespaceAndPath("buildcraft", "guide/languages.json");
    static final ResourceLocation LAYOUTS = ResourceLocation.fromNamespaceAndPath("buildcraft", "guide/page_layouts.json");
    private static final String TEXT_PACK_PREFIX = "guide/text/";
    private static final Pattern TEXT_SLOT = Pattern.compile("\\{\\{bc_text:(\\d+)\\}\\}");

    private final String requestedLanguage;
    private final String resolvedLanguage;
    private final List<String> loadOrder;
    private final Map<String, String> pages;

    private GuidePageStore(String requestedLanguage, String resolvedLanguage, List<String> loadOrder,
        Map<String, String> pages) {
        this.requestedLanguage = requestedLanguage;
        this.resolvedLanguage = resolvedLanguage;
        this.loadOrder = Collections.unmodifiableList(new ArrayList<>(loadOrder));
        this.pages = Collections.unmodifiableMap(new LinkedHashMap<>(pages));
    }

    static GuidePageStore load(ResourceManager manager, String requestedLanguage) throws IOException {
        String requested = normalizeLanguage(requestedLanguage);
        JsonObject languages = readJson(manager, LANGUAGES, true);
        JsonObject layoutRoot = readJson(manager, LAYOUTS, true);
        String defaultLanguage = normalizeLanguage(getString(languages, "default", "en_us"));
        Map<String, String> aliases = readAliases(languages.getAsJsonObject("aliases"));
        Map<String, List<String>> fallbacks = readFallbacks(languages.getAsJsonObject("fallbacks"));

        String resolved = resolveAlias(requested, aliases);
        List<String> order = buildLoadOrder(defaultLanguage, resolved, aliases, fallbacks);
        Map<String, PageLayout> layouts = readLayouts(layoutRoot);
        Map<String, List<String>> localizedText = new LinkedHashMap<>();

        for (String language : order) {
            ResourceLocation location = textPackLocation(language);
            JsonObject pack = readJson(manager, location, language.equals(resolveAlias(defaultLanguage, aliases)));
            if (pack == null) {
                continue;
            }
            overlayTextPack(location, pack, layouts, localizedText);
        }

        Map<String, String> pages = new LinkedHashMap<>();
        for (Map.Entry<String, PageLayout> entry : layouts.entrySet()) {
            List<String> values = localizedText.get(entry.getKey());
            PageLayout layout = entry.getValue();
            if (values == null) {
                BCLog.logger.warn("[lib.guide] Page '{}' has a layout but no text in language chain {}",
                    entry.getKey(), order);
                continue;
            }
            if (values.size() < layout.slots) {
                BCLog.logger.warn("[lib.guide] Page '{}' provides {} text slots but its layout requires {}",
                    entry.getKey(), values.size(), layout.slots);
                values = paddedCopy(values, layout.slots);
            }
            pages.put(entry.getKey(), renderTemplate(layout.template, values));
        }
        return new GuidePageStore(requested, resolved, order, pages);
    }

    @Nullable
    String get(String page) {
        return pages.get(page);
    }

    String requestedLanguage() {
        return requestedLanguage;
    }

    String resolvedLanguage() {
        return resolvedLanguage;
    }

    List<String> loadOrder() {
        return loadOrder;
    }

    Map<String, String> pages() {
        return pages;
    }

    static ResourceLocation textPackLocation(String language) {
        return ResourceLocation.fromNamespaceAndPath("buildcraft", TEXT_PACK_PREFIX + normalizeLanguage(language) + ".json");
    }

    static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "en_us";
        }
        return language.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    static String resolveAlias(String language, Map<String, String> aliases) {
        String current = normalizeLanguage(language);
        Set<String> visited = new HashSet<>();
        while (visited.add(current)) {
            String next = aliases.get(current);
            if (next == null || next.isBlank()) {
                return current;
            }
            current = normalizeLanguage(next);
        }
        BCLog.logger.warn("[lib.guide] Language alias cycle detected for {}", language);
        return normalizeLanguage(language);
    }

    static List<String> buildLoadOrder(String defaultLanguage, String selectedLanguage, Map<String, String> aliases,
        Map<String, List<String>> fallbacks) {
        String defaultCode = resolveAlias(defaultLanguage, aliases);
        String selectedCode = resolveAlias(selectedLanguage, aliases);
        List<String> order = new ArrayList<>();
        addLanguage(order, defaultCode);
        addFallbacks(order, selectedCode, aliases, fallbacks, new HashSet<>());
        addLanguage(order, selectedCode);
        return order;
    }

    static String renderTemplate(String template, List<String> values) {
        Matcher matcher = TEXT_SLOT.matcher(template);
        StringBuffer output = new StringBuffer(template.length());
        while (matcher.find()) {
            int index;
            try {
                index = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                index = -1;
            }
            String replacement = index >= 0 && index < values.size() && values.get(index) != null
                ? values.get(index) : "";
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static Map<String, PageLayout> readLayouts(JsonObject root) throws IOException {
        JsonObject pages = root.getAsJsonObject("pages");
        if (pages == null) {
            throw new IOException("Guide layout resource has no 'pages' object");
        }
        Map<String, PageLayout> layouts = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : pages.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                BCLog.logger.warn("[lib.guide] Layout '{}' is not an object", entry.getKey());
                continue;
            }
            JsonObject object = entry.getValue().getAsJsonObject();
            JsonElement template = object.get("template");
            JsonElement slots = object.get("slots");
            if (template == null || !template.isJsonPrimitive() || !template.getAsJsonPrimitive().isString()
                || slots == null || !slots.isJsonPrimitive() || !slots.getAsJsonPrimitive().isNumber()) {
                BCLog.logger.warn("[lib.guide] Layout '{}' has invalid template or slot count", entry.getKey());
                continue;
            }
            int slotCount = Math.max(0, slots.getAsInt());
            layouts.put(entry.getKey(), new PageLayout(template.getAsString(), slotCount));
        }
        return layouts;
    }

    private static void overlayTextPack(ResourceLocation location, JsonObject pack, Map<String, PageLayout> layouts,
        Map<String, List<String>> localizedText) {
        JsonObject pages = pack.getAsJsonObject("pages");
        if (pages == null) {
            BCLog.logger.warn("[lib.guide] Text pack {} has no 'pages' object", location);
            return;
        }
        for (Map.Entry<String, JsonElement> entry : pages.entrySet()) {
            PageLayout layout = layouts.get(entry.getKey());
            if (layout == null) {
                BCLog.logger.warn("[lib.guide] Text pack {} contains unknown page '{}'", location, entry.getKey());
                continue;
            }
            if (!entry.getValue().isJsonArray()) {
                BCLog.logger.warn("[lib.guide] Text for page '{}' in {} is not an array", entry.getKey(), location);
                continue;
            }
            JsonArray array = entry.getValue().getAsJsonArray();
            List<String> target = localizedText.computeIfAbsent(entry.getKey(), key -> emptySlots(layout.slots));
            if (target.size() < layout.slots) {
                target.addAll(Collections.nCopies(layout.slots - target.size(), null));
            }
            int count = Math.min(array.size(), layout.slots);
            for (int index = 0; index < count; index++) {
                JsonElement value = array.get(index);
                if (value == null || value.isJsonNull()) {
                    continue;
                }
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    target.set(index, value.getAsString());
                } else {
                    BCLog.logger.warn("[lib.guide] Slot {} for page '{}' in {} is not text or null",
                        index, entry.getKey(), location);
                }
            }
            if (array.size() > layout.slots) {
                BCLog.logger.warn("[lib.guide] Text page '{}' in {} has {} slots but its layout has {}",
                    entry.getKey(), location, array.size(), layout.slots);
            }
        }
    }

    private static List<String> emptySlots(int count) {
        return new ArrayList<>(Collections.nCopies(count, null));
    }

    private static List<String> paddedCopy(List<String> source, int count) {
        List<String> copy = new ArrayList<>(source);
        while (copy.size() < count) {
            copy.add("");
        }
        return copy;
    }

    private static void addFallbacks(List<String> order, String language, Map<String, String> aliases,
        Map<String, List<String>> fallbacks, Set<String> visiting) {
        String resolved = resolveAlias(language, aliases);
        if (!visiting.add(resolved)) {
            return;
        }
        List<String> parents = fallbacks.get(resolved);
        if (parents != null) {
            for (String parent : parents) {
                String parentResolved = resolveAlias(parent, aliases);
                addFallbacks(order, parentResolved, aliases, fallbacks, visiting);
                addLanguage(order, parentResolved);
            }
        }
        visiting.remove(resolved);
    }

    private static void addLanguage(List<String> order, String language) {
        if (!order.contains(language)) {
            order.add(language);
        }
    }

    private static Map<String, String> readAliases(JsonObject object) {
        Map<String, String> aliases = new LinkedHashMap<>();
        if (object == null) {
            return aliases;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
                aliases.put(normalizeLanguage(entry.getKey()), normalizeLanguage(entry.getValue().getAsString()));
            }
        }
        return aliases;
    }

    private static Map<String, List<String>> readFallbacks(JsonObject object) {
        Map<String, List<String>> fallbacks = new LinkedHashMap<>();
        if (object == null) {
            return fallbacks;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!entry.getValue().isJsonArray()) {
                continue;
            }
            List<String> parents = new ArrayList<>();
            for (JsonElement parent : entry.getValue().getAsJsonArray()) {
                if (parent.isJsonPrimitive() && parent.getAsJsonPrimitive().isString()) {
                    parents.add(normalizeLanguage(parent.getAsString()));
                }
            }
            fallbacks.put(normalizeLanguage(entry.getKey()), parents);
        }
        return fallbacks;
    }

    private static String getString(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
            ? element.getAsString() : fallback;
    }

    @Nullable
    private static JsonObject readJson(ResourceManager manager, ResourceLocation location, boolean required)
        throws IOException {
        Optional<Resource> optional = manager.getResource(location);
        if (optional.isEmpty()) {
            if (required) {
                throw new IOException("Missing guide resource " + location);
            }
            return null;
        }
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(optional.get().open(), StandardCharsets.UTF_8))) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IOException("Guide resource is not a JSON object: " + location);
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Invalid guide JSON resource " + location, exception);
        }
    }

    private static final class PageLayout {
        final String template;
        final int slots;

        PageLayout(String template, int slots) {
            this.template = template;
            this.slots = slots;
        }
    }
}
