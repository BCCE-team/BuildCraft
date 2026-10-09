package buildcraft.lib.internal.api.v2;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.Identifier;

/** Generates collision-resistant ids for registrations made through the legacy mutable API. */
public final class LegacyEnergyFluidIds {
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private LegacyEnergyFluidIds() {}

    public static Identifier next(String kind, Identifier subject) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subject, "subject");
        long sequence = SEQUENCE.getAndIncrement();
        String path = "legacy/" + sanitize(kind) + "/" + sanitize(subject.getNamespace()) + "/"
            + sanitize(subject.getPath()) + "/" + sequenceToken(sequence);
        return Objects.requireNonNull(Identifier.tryParse("buildcraft:" + path));
    }

    public static Identifier nextAnonymous(String kind) {
        long sequence = SEQUENCE.getAndIncrement();
        return Objects.requireNonNull(Identifier.tryParse(
            "buildcraft:legacy/" + sanitize(kind) + "/anonymous/" + sequenceToken(sequence)
        ));
    }

    private static String sequenceToken(long sequence) {
        // Lexicographic order must match registration order because API 2 uses the id
        // as the deterministic tie-breaker for equal-priority legacy definitions.
        String raw = Long.toUnsignedString(sequence, 36);
        return "0".repeat(13 - raw.length()) + raw;
    }

    private static String sanitize(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = Character.toLowerCase(value.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '/' || c == '.') {
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }
}
