package buildcraft.lib.platform.config;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Read-only migration of the primitive TOML subset formerly used by the loaders. */
final class LegacyToml {
    private LegacyToml() {}

    static Map<String, String> parseToml(List<String> lines) {
        Map<String, String> result = new LinkedHashMap<>();
        String section = "";
        for (String line : lines) {
            String cleaned = stripComment(line).trim();
            if (cleaned.isEmpty()) continue;
            if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
                section = cleaned.substring(1, cleaned.length()-1).trim();
                continue;
            }
            int equals = cleaned.indexOf('=');
            if (equals < 1) continue;
            String key = cleaned.substring(0, equals).trim();
            String value = cleaned.substring(equals + 1).trim();
            if (key.isBlank()) continue;
            result.put(section.isEmpty() ? key : section + "." + key, value);
        }
        return result;
    }

    private static String stripComment(String line) {
        boolean quoted = false, escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && quoted) { escaped = true; continue; }
            if (c == '"') { quoted = !quoted; continue; }
            if (c == '#' && !quoted) return line.substring(0, i);
        }
        return line;
    }

    static String readString(String value) {
        if (value.length() < 2 || value.charAt(0) != '"'
            || value.charAt(value.length() - 1) != '"') {
            throw new IllegalArgumentException("Expected quoted TOML string");
        }
        StringBuilder out = new StringBuilder();
        boolean escaped = false;
        for (int i = 1; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (escaped) {
                out.append(switch (c) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '"', '\\' -> c;
                    default -> throw new IllegalArgumentException("Unknown TOML escape " + c);
                });
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                out.append(c);
            }
        }
        if (escaped) throw new IllegalArgumentException("Dangling TOML escape");
        return out.toString();
    }

    static String writeString(String value) {
        return "\"" + value.replace("\\", "\\\\")
            .replace("\"", "\\\"").replace("\n", "\\n")
            .replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
