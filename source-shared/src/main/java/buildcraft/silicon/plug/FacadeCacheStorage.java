/* This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. */
package buildcraft.silicon.plug;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Disposable, per-namespace discovery cache. No runtime registry IDs or Java object serialization. */
final class FacadeCacheStorage {
    private static final int FORMAT = 1;
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final int MAX_RECORDS = 1_000_000;
    private static final int MAX_PROPERTIES = 256;
    private final Path directory;

    record State(Map<String, String> properties, List<String> varying) {
        State {
            properties = Map.copyOf(properties);
            varying = List.copyOf(varying);
        }
    }

    FacadeCacheStorage(Path directory) { this.directory = directory; }

    private Path path(String namespace) {
        if (!namespace.matches("[a-z0-9_.-]+") || namespace.equals(".") || namespace.equals("..")) {
            throw new IllegalArgumentException("Invalid cache namespace");
        }
        return directory.resolve("section-" + namespace + ".bin");
    }

    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        // Length-prefix the strings so two different inputs cannot have the same concatenation.
        int n = bytes.length;
        digest.update((byte) (n >>> 24)); digest.update((byte) (n >>> 16));
        digest.update((byte) (n >>> 8)); digest.update((byte) n);
        digest.update(bytes);
    }

    static String finish(MessageDigest digest) { return HexFormat.of().formatHex(digest.digest()); }

    /** null means missing, invalid or obsolete. An empty map is a valid negative cache. */
    Map<String, List<State>> read(String namespace, String fingerprint) throws IOException {
        Path path = path(namespace);
        if (!Files.isRegularFile(path)) return null;
        long size = Files.size(path);
        if (size < 32 || size > MAX_BYTES) throw new IOException("Invalid section size");
        byte[] bytes = Files.readAllBytes(path);
        int length = bytes.length - 32;
        if (length < 0 || bytes.length > MAX_BYTES) throw new IOException("Invalid section size");
        MessageDigest digest = digest();
        digest.update(bytes, 0, length);
        if (!MessageDigest.isEqual(digest.digest(), Arrays.copyOfRange(bytes, length, bytes.length))) {
            throw new IOException("Section checksum mismatch");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, length))) {
            if (in.readInt() != FORMAT || !in.readUTF().equals(fingerprint)) return null;
            int count = count(in, MAX_RECORDS);
            Map<String, List<State>> result = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String block = in.readUTF();
                if (!block.startsWith(namespace + ":")) throw new IOException("Wrong namespace");
                int variants = count(in, MAX_RECORDS);
                List<State> states = new ArrayList<>();
                for (int j = 0; j < variants; j++) {
                    Map<String, String> properties = new TreeMap<>();
                    int propertyCount = count(in, MAX_PROPERTIES);
                    for (int k = 0; k < propertyCount; k++) {
                        if (properties.put(in.readUTF(), in.readUTF()) != null) throw new IOException("Duplicate property");
                    }
                    List<String> varying = new ArrayList<>();
                    int varyingCount = count(in, MAX_PROPERTIES);
                    for (int k = 0; k < varyingCount; k++) {
                        String name = in.readUTF();
                        if (!properties.containsKey(name) || varying.contains(name)) throw new IOException("Invalid varying property");
                        varying.add(name);
                    }
                    states.add(new State(properties, varying));
                }
                if (result.put(block, List.copyOf(states)) != null) throw new IOException("Duplicate block");
            }
            if (in.read() != -1) throw new IOException("Trailing section data");
            return result;
        }
    }

    private static int count(DataInputStream in, int maximum) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > maximum) throw new IOException("Invalid record count");
        return count;
    }

    void write(String namespace, String fingerprint, Map<String, List<State>> blocks) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(payload)) {
            out.writeInt(FORMAT);
            out.writeUTF(fingerprint);
            out.writeInt(blocks.size());
            for (var block : new TreeMap<>(blocks).entrySet()) {
                out.writeUTF(block.getKey());
                out.writeInt(block.getValue().size());
                for (State state : block.getValue()) {
                    out.writeInt(state.properties().size());
                    for (var property : new TreeMap<>(state.properties()).entrySet()) {
                        out.writeUTF(property.getKey()); out.writeUTF(property.getValue());
                    }
                    out.writeInt(state.varying().size());
                    for (String name : state.varying()) out.writeUTF(name);
                }
            }
        }
        byte[] bytes = payload.toByteArray();
        if (bytes.length > MAX_BYTES - 32) throw new IOException("Section too large");
        payload.write(digest().digest(bytes));
        atomicWrite(path(namespace), payload.toByteArray());
    }

    void writeManifest(Map<String, String> versions) throws IOException {
        // Human-readable inventory, written only if it changes. Not used as the authoritative cache key.
        StringBuilder content = new StringBuilder();
        for (var entry : new TreeMap<>(versions).entrySet()) {
            content.append(entry.getKey()).append('=').append(entry.getValue().replace("\n", " ").replace("\r", " ")).append('\n');
        }
        Path manifest = directory.resolve("mods.txt");
        byte[] bytes = content.toString().getBytes(StandardCharsets.UTF_8);
        if (!Files.isRegularFile(manifest) || Files.size(manifest) != bytes.length
            || !Arrays.equals(bytes, Files.readAllBytes(manifest))) atomicWrite(manifest, bytes);
    }

    void prune(Set<String> namespaces) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "section-*.bin")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!namespaces.contains(name.substring(8, name.length() - 4))) Files.deleteIfExists(file);
            }
        }
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
