/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.client.guide;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Parser for the subset of Markdown/XML used by the official BuildCraftGuide 8.0.x pages. */
public final class GuideDocument {
    private static final Pattern TAG = Pattern.compile("^<([a-zA-Z0-9_]+)([^>]*)/?>$");
    private static final Pattern ATTRIBUTE = Pattern.compile("([a-zA-Z0-9_]+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^]]+)]\\(([^)]+)\\)");

    public final List<Block> blocks;

    private GuideDocument(List<Block> blocks) {
        this.blocks = blocks;
    }

    public static GuideDocument parse(String markdown, boolean showLore, boolean showHints, boolean showDetail) {
        List<Block> blocks = new ArrayList<>();
        Deque<VisibilitySection> visibility = new ArrayDeque<>();
        boolean inCode = false;
        String codeTag = null;

        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String original : lines) {
            String line = original;
            String trimmed = line.trim();

            if (inCode) {
                String closing = "</" + codeTag + ">";
                if (trimmed.equals(closing)) {
                    inCode = false;
                    codeTag = null;
                } else if (isVisible(visibility)) {
                    blocks.add(Block.code(line));
                }
                continue;
            }

            if (trimmed.equals("<guide_md>") || trimmed.equals("<json_insn>")) {
                inCode = true;
                codeTag = trimmed.substring(1, trimmed.length() - 1);
                continue;
            }

            // Deprecated markdown shortcuts still used by the original guide.md page.
            if (trimmed.startsWith("$[special.new_page]")) {
                blocks.add(Block.newPage());
                continue;
            }
            if (trimmed.startsWith("$[special.all_crafting]")) {
                String stack = trimmed.substring("$[special.all_crafting]".length()).trim();
                if ((stack.startsWith("(") && stack.endsWith(")"))
                    || (stack.startsWith("\"") && stack.endsWith("\""))) {
                    stack = stack.substring(1, stack.length() - 1);
                }
                Map<String, String> attributes = new LinkedHashMap<>();
                if (stack.startsWith("{") && stack.endsWith("}")) {
                    String[] split = stack.substring(1, stack.length() - 1).split(",");
                    stack = split.length == 0 ? "" : split[0].trim();
                    if (split.length > 1) attributes.put("count", split[1].trim());
                    if (split.length > 2) attributes.put("data", split[2].trim());
                }
                blocks.add(Block.recipes("recipes_usages", stack, attributes));
                continue;
            }

            String sectionName = sectionStart(trimmed);
            if (sectionName != null) {
                visibility.push(new VisibilitySection(sectionName, sectionVisible(sectionName, showLore, showHints, showDetail)));
                line = line.substring(line.indexOf('>') + 1).trim();
                trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
            }

            String sectionEnd = sectionEnd(trimmed);
            if (sectionEnd != null) {
                popSection(visibility, sectionEnd);
                line = line.substring(line.indexOf('>') + 1).trim();
                trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
            }

            if (!isVisible(visibility)) {
                continue;
            }
            if (trimmed.startsWith("//")) {
                continue;
            }
            if (trimmed.startsWith("\\/\\/")) {
                line = "//" + trimmed.substring(4);
                trimmed = line.trim();
            }

            if (trimmed.startsWith("#")) {
                int hashes = 0;
                while (hashes < trimmed.length() && trimmed.charAt(hashes) == '#') hashes++;
                String title = trimmed.substring(hashes).trim();
                blocks.add(Block.chapter(GuideContent.translateOrLiteral(title), Math.max(0, hashes - 1)));
                continue;
            }

            ParsedTag parsed = parseStandaloneTag(trimmed);
            if (parsed != null) {
                switch (parsed.name) {
                    case "new_page":
                        blocks.add(Block.newPage());
                        continue;
                    case "chapter": {
                        String title = parsed.attributes.get("name");
                        int level = parseInt(parsed.attributes.get("level"), 0);
                        if (title != null) {
                            blocks.add(Block.chapter(GuideContent.translateOrLiteral(title), Math.max(0, level)));
                        }
                        continue;
                    }
                    case "link":
                        blocks.add(Block.link(parsed.attributes.get("to"), parsed.attributes.get("type")));
                        continue;
                    case "image":
                        blocks.add(Block.image(
                            parsed.attributes.get("src"),
                            parseInt(parsed.attributes.get("width"), -1),
                            parseInt(parsed.attributes.get("height"), -1)
                        ));
                        continue;
                    case "recipe":
                    case "recipes":
                    case "usages":
                    case "recipes_usages":
                        blocks.add(Block.recipes(parsed.name, parsed.attributes.get("stack"), parsed.attributes));
                        continue;
                    case "recipe_id":
                        blocks.add(Block.recipes(parsed.name, parsed.attributes.get("id"), parsed.attributes));
                        continue;
                    case "group":
                        // No production BC8 pages currently depend on dynamic registry groups. Preserve the tag visibly
                        // instead of silently discarding content if an addon supplies one later.
                        blocks.add(Block.text(parseInline(line), null));
                        continue;
                    default:
                        break;
                }
            }

            if (trimmed.isEmpty()) {
                blocks.add(Block.space());
            } else {
                InlineResult rich = parseInline(line);
                blocks.add(Block.text(rich.component, rich.linkTarget));
            }
        }
        return new GuideDocument(blocks);
    }

    @Nullable
    private static String sectionStart(String line) {
        for (String name : new String[] { "lore", "no_lore", "hint", "no_hint", "detail", "no_detail", "note" }) {
            if (line.startsWith("<" + name + ">") || line.startsWith("<" + name + " ")) {
                return name;
            }
        }
        return null;
    }

    @Nullable
    private static String sectionEnd(String line) {
        for (String name : new String[] { "lore", "no_lore", "hint", "no_hint", "detail", "no_detail", "note" }) {
            if (line.startsWith("</" + name + ">")) {
                return name;
            }
        }
        return null;
    }

    private static boolean sectionVisible(String name, boolean showLore, boolean showHints, boolean showDetail) {
        switch (name) {
            case "lore": return showLore;
            case "no_lore": return !showLore;
            case "hint": return showHints;
            case "no_hint": return !showHints;
            case "detail": return showDetail;
            case "no_detail": return !showDetail;
            default: return true;
        }
    }

    private static boolean isVisible(Deque<VisibilitySection> sections) {
        for (VisibilitySection section : sections) {
            if (!section.visible) return false;
        }
        return true;
    }

    private static void popSection(Deque<VisibilitySection> sections, String name) {
        if (sections.isEmpty()) return;
        if (sections.peek().name.equals(name)) {
            sections.pop();
            return;
        }
        Deque<VisibilitySection> temporary = new ArrayDeque<>();
        while (!sections.isEmpty() && !sections.peek().name.equals(name)) {
            temporary.push(sections.pop());
        }
        if (!sections.isEmpty()) sections.pop();
        while (!temporary.isEmpty()) sections.push(temporary.pop());
    }

    @Nullable
    private static ParsedTag parseStandaloneTag(String line) {
        if (!line.startsWith("<") || !line.endsWith(">")) return null;
        Matcher matcher = TAG.matcher(line);
        if (!matcher.matches()) return null;
        Map<String, String> attributes = new LinkedHashMap<>();
        Matcher attribute = ATTRIBUTE.matcher(matcher.group(2));
        while (attribute.find()) {
            attributes.put(attribute.group(1), attribute.group(2));
        }
        return new ParsedTag(matcher.group(1), attributes);
    }

    private static int parseInt(@Nullable String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static InlineResult parseInline(String line) {
        MutableComponent result = Component.empty();
        Set<ChatFormatting> formats = EnumSet.noneOf(ChatFormatting.class);
        Deque<ChatFormatting> colours = new ArrayDeque<>();
        StringBuilder text = new StringBuilder();
        String linkTarget = null;
        int codeDepth = 0;

        int index = 0;
        while (index < line.length()) {
            if (line.startsWith("&lt;", index)) {
                text.append('<');
                index += 4;
                continue;
            }
            if (line.startsWith("&gt;", index)) {
                text.append('>');
                index += 4;
                continue;
            }
            if (line.charAt(index) == '<') {
                int end = line.indexOf('>', index);
                if (end >= 0) {
                    String token = line.substring(index + 1, end).trim();
                    boolean closing = token.startsWith("/");
                    String name = closing ? token.substring(1) : token;
                    if (name.equalsIgnoreCase("code")) {
                        flush(result, text, formats, colours, codeDepth > 0);
                        if (closing) {
                            if (codeDepth > 0) codeDepth--;
                        } else {
                            codeDepth++;
                        }
                        index = end + 1;
                        continue;
                    }
                    ChatFormatting formatting = formatting(name);
                    if (formatting != null) {
                        flush(result, text, formats, colours, codeDepth > 0);
                        if (closing) {
                            formats.remove(formatting);
                            colours.remove(formatting);
                        } else if (formatting.isColor()) {
                            colours.push(formatting);
                        } else {
                            formats.add(formatting);
                        }
                        index = end + 1;
                        continue;
                    }
                }
            }
            if (line.charAt(index) == '[') {
                Matcher link = MARKDOWN_LINK.matcher(line.substring(index));
                if (link.lookingAt()) {
                    flush(result, text, formats, colours, codeDepth > 0);
                    MutableComponent linked = Component.literal(link.group(1));
                    linked.withStyle(ChatFormatting.BLUE, ChatFormatting.UNDERLINE);
                    result.append(linked);
                    linkTarget = link.group(2);
                    index += link.end();
                    continue;
                }
            }
            text.append(line.charAt(index++));
        }
        flush(result, text, formats, colours, codeDepth > 0);
        return new InlineResult(result, linkTarget);
    }

    private static void flush(MutableComponent result, StringBuilder text, Set<ChatFormatting> formats,
        Deque<ChatFormatting> colours, boolean code) {
        if (text.length() == 0) return;
        MutableComponent part = Component.literal(text.toString());
        if (code) part.withStyle(ChatFormatting.DARK_AQUA);
        if (!colours.isEmpty()) part.withStyle(colours.peek());
        for (ChatFormatting formatting : formats) part.withStyle(formatting);
        result.append(part);
        text.setLength(0);
    }

    @Nullable
    private static ChatFormatting formatting(String name) {
        switch (name.toLowerCase(Locale.ROOT)) {
            case "bold": return ChatFormatting.BOLD;
            case "italic": return ChatFormatting.ITALIC;
            case "underline": return ChatFormatting.UNDERLINE;
            case "strikethrough": return ChatFormatting.STRIKETHROUGH;
            case "black": return ChatFormatting.BLACK;
            case "dark_blue": return ChatFormatting.DARK_BLUE;
            case "dark_green": return ChatFormatting.DARK_GREEN;
            case "dark_aqua": return ChatFormatting.DARK_AQUA;
            case "dark_red": return ChatFormatting.DARK_RED;
            case "dark_purple": return ChatFormatting.DARK_PURPLE;
            case "gold": return ChatFormatting.GOLD;
            case "gray": return ChatFormatting.GRAY;
            case "dark_gray": return ChatFormatting.DARK_GRAY;
            case "blue": return ChatFormatting.BLUE;
            case "green": return ChatFormatting.GREEN;
            case "aqua": return ChatFormatting.AQUA;
            case "red": return ChatFormatting.RED;
            case "light_purple": return ChatFormatting.LIGHT_PURPLE;
            case "yellow": return ChatFormatting.YELLOW;
            case "white": return ChatFormatting.WHITE;
            default: return null;
        }
    }

    public enum Kind {
        TEXT,
        SPACE,
        CHAPTER,
        NEW_PAGE,
        LINK,
        IMAGE,
        RECIPES,
        CODE
    }

    public static final class Block {
        public final Kind kind;
        public final @Nullable Component text;
        public final @Nullable String target;
        public final @Nullable String secondary;
        public final int level;
        public final int width;
        public final int height;
        public final Map<String, String> attributes;

        private Block(Kind kind, @Nullable Component text, @Nullable String target, @Nullable String secondary,
            int level, int width, int height, Map<String, String> attributes) {
            this.kind = kind;
            this.text = text;
            this.target = target;
            this.secondary = secondary;
            this.level = level;
            this.width = width;
            this.height = height;
            this.attributes = attributes;
        }

        static Block text(InlineResult result, @Nullable String ignored) {
            return text(result.component, result.linkTarget);
        }

        static Block text(Component component, @Nullable String link) {
            return new Block(Kind.TEXT, component, link, null, 0, 0, 0, Map.of());
        }

        static Block space() {
            return new Block(Kind.SPACE, null, null, null, 0, 0, 0, Map.of());
        }

        static Block chapter(String title, int level) {
            return new Block(Kind.CHAPTER, Component.literal(title), null, null, level, 0, 0, Map.of());
        }

        static Block newPage() {
            return new Block(Kind.NEW_PAGE, null, null, null, 0, 0, 0, Map.of());
        }

        static Block link(@Nullable String target, @Nullable String type) {
            return new Block(Kind.LINK, null, target, type, 0, 0, 0, Map.of());
        }

        static Block image(@Nullable String source, int width, int height) {
            return new Block(Kind.IMAGE, null, source, null, 0, width, height, Map.of());
        }

        static Block recipes(String type, @Nullable String stack, Map<String, String> attributes) {
            return new Block(Kind.RECIPES, null, stack, type, 0, 0, 0, Map.copyOf(attributes));
        }

        static Block code(String line) {
            return new Block(Kind.CODE, Component.literal(line), null, null, 0, 0, 0, Map.of());
        }
    }

    private static final class ParsedTag {
        final String name;
        final Map<String, String> attributes;

        ParsedTag(String name, Map<String, String> attributes) {
            this.name = name;
            this.attributes = attributes;
        }
    }

    private static final class VisibilitySection {
        final String name;
        final boolean visible;

        VisibilitySection(String name, boolean visible) {
            this.name = name;
            this.visible = visible;
        }
    }

    private static final class InlineResult {
        final MutableComponent component;
        final @Nullable String linkTarget;

        InlineResult(MutableComponent component, @Nullable String linkTarget) {
            this.component = component;
            this.linkTarget = linkTarget;
        }
    }
}
