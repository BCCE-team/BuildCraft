package buildcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import buildcraft.lib.update.ReleaseManifest;

class ReleaseManifestTest {
    @Test
    void acceptsOnlyPublishedTargetIdentifiers() {
        ReleaseManifest manifest = new ReleaseManifest("8.0.30", Set.of(
            "1.19.2-forge", "1.21.11-neoforge", "26.3-neoforge"));
        assertTrue(manifest.supports("1.21.11-neoforge"));
        assertFalse(manifest.supports("1.21.1-neoforge"));
        assertEquals("8.1", new ReleaseManifest("8.0.100", Set.of("1.21.11-neoforge")).version);
    }

    @Test
    void rejectsEmptyOrMalformedTargets() {
        assertThrows(IllegalArgumentException.class, () -> new ReleaseManifest("8.0.30", Set.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new ReleaseManifest("8.0.30", Set.of("1.21.11")));
        assertThrows(IllegalArgumentException.class,
            () -> new ReleaseManifest("8.0.30", Set.of("1.21.11-neoforge/../../bad")));
    }
}
