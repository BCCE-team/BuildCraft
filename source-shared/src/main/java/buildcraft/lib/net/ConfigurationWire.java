package buildcraft.lib.net;

import buildcraft.api.v2.config.ConfigDefinition;
import buildcraft.api.v2.config.ConfigDefinition.*;
import java.io.*;
import java.util.*;

/** Bounded, loader-independent snapshot codec. Only a complete, validated snapshot reaches the editor. */
public final class ConfigurationWire {
    public static final int MAX_REQUEST_BYTES = 24_000;
    public static final int FRAME_BYTES = 24_000;
    public static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    public static final int MAX_ENTRIES = 2048;
    private ConfigurationWire() {}

    public record Entry(ConfigDefinition definition, String value, String effective) {
        public Entry {
            Objects.requireNonNull(definition);
            if (definition.scope() != Scope.SERVER) throw new IllegalArgumentException("Unexpected client configuration in server snapshot");
            value = definition.validate(value);
            effective = definition.validate(effective);
        }
    }
    public record Result(boolean allowed, long revision, String error, List<Entry> entries) {
        public Result {
            Objects.requireNonNull(error);
            entries = List.copyOf(entries);
            if (revision < 0 || entries.size() > MAX_ENTRIES || (!allowed && !entries.isEmpty()))
                throw new IllegalArgumentException("Invalid configuration snapshot");
            Set<String> ids = new HashSet<>();
            for (Entry entry : entries) if (!ids.add(entry.definition().id()))
                throw new IllegalArgumentException("Duplicate configuration definition");
        }
    }
    public record Frame(long token, int total, int offset, byte[] data) {
        public Frame {
            if (data.length != frameLength(total, offset)) throw new IllegalArgumentException("Invalid configuration frame length");
            data = data.clone();
        }
        @Override public byte[] data() { return data.clone(); }
    }
    public static int frameLength(int total, int offset) {
        if (total <= 0 || total > MAX_SNAPSHOT_BYTES || offset < 0 || offset >= total || offset % FRAME_BYTES != 0)
            throw new IllegalArgumentException("Invalid configuration frame bounds");
        return Math.min(FRAME_BYTES, total - offset);
    }
    /** Conservative bound for FriendlyByteBuf UTF-8 encoding, including the request header. */
    public static long requestBytes(Map<String, String> changes) {
        long bytes = 32;
        if (changes.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many configuration changes");
        for (var entry : changes.entrySet()) {
            if (entry.getKey().length() > 192 || entry.getValue().length() > 4096)
                throw new IllegalArgumentException("Configuration change is too long");
            bytes += (entry.getKey().length() + entry.getValue().length()) * 3L + 12;
        }
        return bytes;
    }
    public static List<Frame> frames(long token, Result result) {
        byte[] data = encode(result);
        var frames = new ArrayList<Frame>();
        for (int offset = 0; offset < data.length; offset += FRAME_BYTES) {
            int end = Math.min(offset + FRAME_BYTES, data.length);
            frames.add(new Frame(token, data.length, offset, Arrays.copyOfRange(data, offset, end)));
        }
        return List.copyOf(frames);
    }

    private static final class BoundedBytes extends ByteArrayOutputStream {
        private void reserve(int length) {
            if (length > MAX_SNAPSHOT_BYTES - size()) throw new IllegalArgumentException("Server configuration exceeds the 8 MiB snapshot limit");
        }
        @Override public synchronized void write(int value) { reserve(1); super.write(value); }
        @Override public synchronized void write(byte[] data, int offset, int length) { reserve(length); super.write(data, offset, length); }
    }
    private static void text(DataOutputStream out, String value, int max) throws IOException {
        if (value.length() > max) throw new IllegalArgumentException("Configuration text exceeds limit");
        out.writeUTF(value);
    }
    private static String text(DataInputStream in, int max) throws IOException {
        String value = in.readUTF();
        if (value.length() > max) throw new IOException("Configuration text exceeds limit");
        return value;
    }
    private static void strings(DataOutputStream out, List<String> values, int count, int max) throws IOException {
        if (values.size() > count) throw new IllegalArgumentException("Configuration list exceeds limit");
        out.writeInt(values.size());
        for (String value : values) text(out, value, max);
    }
    private static int count(DataInputStream in, int max) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > max) throw new IOException("Invalid configuration count");
        return count;
    }
    private static List<String> strings(DataInputStream in, int count, int max) throws IOException {
        int size = count(in, count);
        var values = new ArrayList<String>(size);
        for (int i = 0; i < size; i++) values.add(text(in, max));
        return values;
    }
    private static <E extends Enum<E>> E enumValue(DataInputStream in, E[] values) throws IOException {
        return values[count(in, values.length - 1)];
    }
    private static byte[] encode(Result result) {
        var bytes = new BoundedBytes();
        try (var out = new DataOutputStream(bytes)) {
            out.writeBoolean(result.allowed()); out.writeLong(result.revision());
            text(out, result.error().substring(0, Math.min(1024, result.error().length())), 1024);
            out.writeInt(result.entries().size());
            for (Entry entry : result.entries()) {
                var d = entry.definition();
                text(out, d.id(), 192); out.writeInt(d.section().ordinal()); strings(out, d.category(), 12, 96);
                out.writeInt(d.scope().ordinal()); out.writeInt(d.kind().ordinal()); text(out, d.defaultValue(), 4096);
                out.writeDouble(d.minimum()); out.writeDouble(d.maximum()); strings(out, d.choices(), 128, 96);
                text(out, d.nameKey(), 512); text(out, d.descriptionKey(), 512); text(out, d.tooltipKey(), 512);
                text(out, d.fallbackName(), 512); text(out, d.fallbackDescription(), 2048); text(out, d.fallbackTooltip(), 4096);
                out.writeInt(d.applyPolicy().ordinal()); text(out, entry.value(), 4096); text(out, entry.effective(), 4096);
            }
        } catch (IOException e) { throw new IllegalArgumentException("Unable to encode configuration snapshot", e); }
        return bytes.toByteArray();
    }
    private static Result decode(byte[] bytes) {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            boolean allowed = in.readBoolean(); long revision = in.readLong(); String error = text(in, 1024);
            int count = count(in, MAX_ENTRIES);
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                var d = new ConfigDefinition(text(in, 192), enumValue(in, Section.values()), strings(in, 12, 96),
                    enumValue(in, Scope.values()), enumValue(in, Kind.values()), text(in, 4096), in.readDouble(), in.readDouble(),
                    strings(in, 128, 96), text(in, 512), text(in, 512), text(in, 512), text(in, 512), text(in, 2048), text(in, 4096),
                    enumValue(in, ApplyPolicy.values()));
                entries.add(new Entry(d, text(in, 4096), text(in, 4096)));
            }
            if (in.available() != 0) throw new IOException("Trailing bytes in configuration snapshot");
            return new Result(allowed, revision, error, entries);
        } catch (IOException | IllegalArgumentException e) { throw new IllegalArgumentException("Invalid configuration snapshot", e); }
    }

    /** Exactly one pending request per client editor. Ignore unsolicited/stale responses before allocating. */
    public static final class Inbox {
        private long token;
        private boolean waiting;
        private byte[] bytes;
        private int received;
        private Result result;
        public synchronized void begin(long token) { clear(); this.token = token; waiting = true; }
        public synchronized void cancel(long token) { if (this.token == token) clear(); }
        public synchronized int received(long token) { return waiting && this.token == token ? received : 0; }
        public synchronized Result take(long token) {
            if (!waiting || this.token != token || result == null) return null;
            Result complete = result; clear(); return complete;
        }
        private void clear() { waiting = false; bytes = null; received = 0; result = null; }
        public synchronized void accept(Frame frame) {
            if (!waiting || token != frame.token() || result != null) return;
            try {
                if (frame.offset() != received || (bytes != null && frame.total() != bytes.length))
                    throw new IllegalArgumentException("Out of sequence configuration frame");
                if (bytes == null) bytes = new byte[frame.total()];
                System.arraycopy(frame.data, 0, bytes, received, frame.data.length);
                received += frame.data.length;
                if (received == bytes.length) { result = decode(bytes); bytes = null; }
            } catch (IllegalArgumentException e) {
                bytes = null; result = new Result(false, 0, "Invalid configuration response; reopen the editor", List.of());
            }
        }
    }
}
