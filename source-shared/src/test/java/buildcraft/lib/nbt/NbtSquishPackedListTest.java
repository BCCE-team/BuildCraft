package buildcraft.lib.nbt;

import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.lib.internal.data.NbtSquishConstants;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Corrupted packed blueprint streams must not leak null Tag placeholders or raw index errors. */
public class NbtSquishPackedListTest {
    @Test
    void validPackedEntriesArePreserved() throws IOException {
        NbtSquishMap decoded = NbtSquishMapReader.read(packed(1, 2, new byte[][] {{ 0 }}));
        ListTag list = (ListTag) decoded.complex.get(0);
        Assertions.assertEquals(2, list.size());
        Assertions.assertEquals(list.get(0), list.get(1));
    }

    @Test
    void truncatedPackedBitsetIsAControlledIOException() throws IOException {
        Assertions.assertThrows(InvalidInputDataException.class,
            () -> NbtSquishMapReader.read(packed(1, 2, new byte[][] { {} })));
    }

    @Test
    void unresolvedPackedEntriesAreRejected() throws IOException {
        // The value 1 at one-bit width means 'continue' for both slots.
        Assertions.assertThrows(InvalidInputDataException.class,
            () -> NbtSquishMapReader.read(packed(1, 2, new byte[][] {{ 3 }})));
    }

    @Test
    void outOfRangePackedDictionaryIndexIsRejected() throws IOException {
        // One-bit pass forwards the slot. The two-bit pass references index 2,
        // even though only one dictionary item remains after the first pass.
        Assertions.assertThrows(InvalidInputDataException.class,
            () -> NbtSquishMapReader.read(packed(2, 1, new byte[][] {{ 1 }, { 1 } })));
    }

    private static DataInputStream packed(int dictionaryEntries, int listEntries, byte[][] bitsets) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(0); // WrittenType.BYTE
        out.writeInt(NbtSquishConstants.FLAG_HAS_BYTES | NbtSquishConstants.FLAG_HAS_COMPLEX);
        out.writeByte(dictionaryEntries); // primitive byte dictionary size
        for (int i = 0; i < dictionaryEntries; i++) out.writeByte(i + 1);
        out.writeByte(1); // one complex tag
        out.writeByte(NbtSquishConstants.COMPLEX_LIST_PACKED);
        out.writeByte(dictionaryEntries);
        for (int i = 0; i < dictionaryEntries; i++) out.writeByte(i);
        out.writeByte(listEntries);
        for (byte[] bitset : bitsets) {
            out.writeByte(bitset.length);
            out.write(bitset);
        }
        out.flush();
        return new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    }
}
