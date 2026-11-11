package buildcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import buildcraft.lib.update.BCVersion;

class BCVersionTest {
    @Test void micropatchesSortAfterTheirBaseVersion() {
        assertTrue(BCVersion.parse("8.0.20.1").compareTo(BCVersion.parse("8.0.20")) > 0);
        assertTrue(BCVersion.parse("8.0.20.10").compareTo(BCVersion.parse("8.0.20.2")) > 0);
        assertTrue(BCVersion.parse("8.0.21").compareTo(BCVersion.parse("8.0.20.99")) > 0);
    }
    @Test void hundredsOverflowIsCanonicalized() {
        assertEquals(0, BCVersion.parse("8.0.100").compareTo(BCVersion.parse("8.1")));
        assertEquals(0, BCVersion.parse("8.0.101").compareTo(BCVersion.parse("8.1.1")));
        assertEquals(0, BCVersion.parse("8.1.2").compareTo(BCVersion.parse("8.0.102")));
        assertEquals("8.1.1", BCVersion.parse("8.0.101").display());
        assertEquals(0, BCVersion.parse("8.1.1").compareTo(BCVersion.parse("8.0.101")));
        assertTrue(BCVersion.parse("8.1.1").compareTo(BCVersion.parse("8.1")) > 0);
        assertEquals("8.1", BCVersion.parse("8.1").display());
        assertEquals("8.1.0.1", BCVersion.parse("8.0.100.1").display());
    }
    @Test void rejectsNonCanonicalOrOutOfRangeVersions() {
        assertEquals(0, BCVersion.parse("8.0.100.1").compareTo(BCVersion.parse("8.1.0.1")));
        assertTrue(BCVersion.parse("8.0.100.1").compareTo(BCVersion.parse("8.1.1")) < 0);
        assertThrows(IllegalArgumentException.class, () -> BCVersion.parse("8.0.20-dev"));
        assertThrows(IllegalArgumentException.class, () -> BCVersion.parse("8.0"));
        assertThrows(IllegalArgumentException.class, () -> BCVersion.parse("0.1"));
        assertThrows(IllegalArgumentException.class, () -> BCVersion.parse("8.0.0"));
    }
}
