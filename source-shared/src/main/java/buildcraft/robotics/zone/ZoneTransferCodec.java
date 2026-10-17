package buildcraft.robotics.zone;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import java.util.Arrays;

/** Byte-budgeted multipart transport for large Zone Planner layers. */
public final class ZoneTransferCodec {
    public static final int PART_BYTES = 8192;
    public static final int MAX_LAYER_BYTES = 1024 * 1024;

    private ZoneTransferCodec() {}

    public static byte[] encode(ZonePlan zone) {
        ByteBuf data = Unpooled.buffer();
        try {
            (zone == null ? new ZonePlan() : zone).writeToByteBuf(new FriendlyByteBuf(data));
            if (data.readableBytes() > MAX_LAYER_BYTES) {
                throw new IllegalArgumentException("Zone Planner layer exceeds 1 MiB network safety limit");
            }
            byte[] bytes = new byte[data.readableBytes()];
            data.getBytes(data.readerIndex(), bytes);
            return bytes;
        } finally {
            data.release();
        }
    }

    public static ZonePlan decode(byte[] data) {
        if (data == null || data.length < 4 || data.length > MAX_LAYER_BYTES) {
            throw new DecoderException("Invalid Zone Planner layer size");
        }
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            FriendlyByteBuf input = new FriendlyByteBuf(buf);
            ZonePlan result = new ZonePlan().readFromByteBuf(input);
            if (input.readableBytes() != 0) throw new DecoderException("Trailing Zone Planner payload bytes");
            return result;
        } finally {
            buf.release();
        }
    }

    public static byte[] part(byte[] data, int offset) {
        if (offset < 0 || offset >= data.length) throw new IllegalArgumentException("Invalid zone offset");
        return Arrays.copyOfRange(data, offset, Math.min(data.length, offset + PART_BYTES));
    }

    /** Reject missing/reordered/overlapping parts without mutating live zone state. */
    public static final class Assembler {
        private byte[] bytes;
        private int next;
        private int layer = -1;
        private int revision = -1;

        public void reset() {
            bytes = null;
            next = 0;
            layer = -1;
            revision = -1;
        }

        public ZonePlan accept(int revision, int layer, int total, int offset, byte[] part) {
            if (layer < 0 || layer >= 16 || total < 4 || total > MAX_LAYER_BYTES
                    || part == null || part.length == 0 || part.length > PART_BYTES
                    || offset < 0 || offset > total || part.length > total - offset) {
                reset();
                throw new DecoderException("Invalid Zone Planner multipart packet");
            }
            if (offset == 0) {
                bytes = new byte[total];
                next = 0;
                this.revision = revision;
                this.layer = layer;
            }
            if (bytes == null || bytes.length != total || this.layer != layer
                    || this.revision != revision || offset != next) {
                reset();
                throw new DecoderException("Out of order Zone Planner multipart packet");
            }
            System.arraycopy(part, 0, bytes, next, part.length);
            next += part.length;
            if (next != total) return null;
            byte[] complete = bytes;
            reset();
            return decode(complete);
        }
    }
}
