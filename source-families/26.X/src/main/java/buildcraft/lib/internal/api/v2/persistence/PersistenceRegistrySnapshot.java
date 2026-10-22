package buildcraft.lib.internal.api.v2.persistence;

import buildcraft.api.v2.persistence.CodecResult;
import buildcraft.api.v2.persistence.EncodedPayload;
import buildcraft.api.v2.persistence.KnownPayload;
import buildcraft.api.v2.persistence.PayloadCopier;
import buildcraft.api.v2.persistence.PayloadResolution;
import buildcraft.api.v2.persistence.PersistentType;
import buildcraft.api.v2.persistence.SchemaMigration;
import buildcraft.api.v2.persistence.UnknownPayload;
import buildcraft.api.v2.persistence.UnknownPayloadReason;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * Immutable persisted-type registry used for runtime decode/encode.
 */
public final class PersistenceRegistrySnapshot<T, P> {
    private final Map<Identifier, PersistentType<T, P>> types;
    private final Map<Identifier, Identifier> aliases;

    PersistenceRegistrySnapshot(
        Map<Identifier, PersistentType<T, P>> types,
        Map<Identifier, Identifier> aliases
    ) {
        this.types = types;
        this.aliases = aliases;
    }

    public Optional<PersistentType<T, P>> type(Identifier id) {
        Objects.requireNonNull(id, "id");
        Identifier canonical = canonicalId(id).orElse(null);
        return canonical == null ? Optional.empty() : Optional.of(types.get(canonical));
    }

    public Optional<Identifier> canonicalId(Identifier id) {
        Objects.requireNonNull(id, "id");
        if (types.containsKey(id)) {
            return Optional.of(id);
        }
        return Optional.ofNullable(aliases.get(id));
    }

    public Map<Identifier, PersistentType<T, P>> types() {
        return types;
    }

    public Map<Identifier, Identifier> aliases() {
        return aliases;
    }

    public PayloadResolution<T, P> decode(EncodedPayload<P> encoded, PayloadCopier<P> copier) {
        Objects.requireNonNull(encoded, "encoded");
        Objects.requireNonNull(copier, "copier");

        Identifier storedId = encoded.typeId();
        int storedVersion = encoded.schemaVersion();
        P original = copier.copy(encoded.payload());
        Identifier canonical = canonicalId(storedId).orElse(null);
        if (canonical == null) {
            return unknown(storedId, storedVersion, original, UnknownPayloadReason.MISSING_TYPE,
                List.of("No persisted type is registered for " + storedId));
        }

        PersistentType<T, P> type = types.get(canonical);
        int currentVersion = type.schemaVersion();
        if (storedVersion > currentVersion) {
            return unknown(storedId, storedVersion, original, UnknownPayloadReason.NEWER_SCHEMA,
                List.of("Stored schema " + storedVersion + " is newer than supported schema " + currentVersion + " for " + canonical));
        }

        P migrated = copier.copy(encoded.payload());
        if (storedVersion < currentVersion) {
            MigrationOutcome<P> outcome = migrate(type, storedVersion, migrated);
            if (!outcome.successful) {
                return unknown(storedId, storedVersion, original, outcome.reason, outcome.errors);
            }
            migrated = outcome.payload;
        }

        CodecResult<T> decoded;
        try {
            decoded = Objects.requireNonNull(type.codec().decode(migrated), "codec.decode result");
        } catch (RuntimeException ex) {
            return unknown(storedId, storedVersion, original, UnknownPayloadReason.DECODE_FAILED,
                List.of("Codec threw while decoding " + canonical + ": " + safeMessage(ex)));
        }
        if (!decoded.successful()) {
            return unknown(storedId, storedVersion, original, UnknownPayloadReason.DECODE_FAILED, decoded.errors());
        }
        return new KnownPayload<>(storedId, canonical, storedVersion, currentVersion, decoded.valueOrThrow());
    }

    public CodecResult<EncodedPayload<P>> encode(Identifier typeId, T value) {
        Objects.requireNonNull(typeId, "typeId");
        Objects.requireNonNull(value, "value");
        Identifier canonical = canonicalId(typeId).orElse(null);
        if (canonical == null) {
            return CodecResult.failure("No persisted type is registered for " + typeId);
        }
        PersistentType<T, P> type = types.get(canonical);
        CodecResult<P> encoded;
        try {
            encoded = Objects.requireNonNull(type.codec().encode(value), "codec.encode result");
        } catch (RuntimeException ex) {
            return CodecResult.failure("Codec threw while encoding " + canonical + ": " + safeMessage(ex));
        }
        if (!encoded.successful()) {
            return CodecResult.failure(encoded.errors());
        }
        return CodecResult.success(new EncodedPayload<>(canonical, type.schemaVersion(), encoded.valueOrThrow()));
    }

    private MigrationOutcome<P> migrate(PersistentType<T, P> type, int startVersion, P startPayload) {
        Map<Integer, SchemaMigration<P>> byFrom = new LinkedHashMap<>();
        for (SchemaMigration<P> migration : type.migrations()) {
            byFrom.put(migration.fromVersion(), migration);
        }

        int version = startVersion;
        P payload = startPayload;
        while (version < type.schemaVersion()) {
            SchemaMigration<P> migration = byFrom.get(version);
            if (migration == null) {
                return MigrationOutcome.failure(
                    UnknownPayloadReason.MISSING_MIGRATION,
                    List.of("Missing migration for " + type.id() + " from schema " + version + " to " + type.schemaVersion())
                );
            }
            CodecResult<P> migrated;
            try {
                migrated = Objects.requireNonNull(migration.migrate(payload), "migration result");
            } catch (RuntimeException ex) {
                return MigrationOutcome.failure(
                    UnknownPayloadReason.MIGRATION_FAILED,
                    List.of("Migration " + version + " -> " + migration.toVersion() + " for " + type.id() + " threw: " + safeMessage(ex))
                );
            }
            if (!migrated.successful()) {
                return MigrationOutcome.failure(UnknownPayloadReason.MIGRATION_FAILED, migrated.errors());
            }
            payload = migrated.valueOrThrow();
            version = migration.toVersion();
        }
        if (version != type.schemaVersion()) {
            return MigrationOutcome.failure(
                UnknownPayloadReason.MIGRATION_FAILED,
                List.of("Migration chain for " + type.id() + " ended at schema " + version + " instead of " + type.schemaVersion())
            );
        }
        return MigrationOutcome.success(payload);
    }

    private static <T, P> UnknownPayload<T, P> unknown(
        Identifier id,
        int version,
        P payload,
        UnknownPayloadReason reason,
        List<String> diagnostics
    ) {
        return new UnknownPayload<>(id, version, payload, reason, diagnostics);
    }

    private static String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private static final class MigrationOutcome<P> {
        private final boolean successful;
        private final P payload;
        private final UnknownPayloadReason reason;
        private final List<String> errors;

        private MigrationOutcome(boolean successful, P payload, UnknownPayloadReason reason, List<String> errors) {
            this.successful = successful;
            this.payload = payload;
            this.reason = reason;
            this.errors = errors == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(errors));
        }

        private static <P> MigrationOutcome<P> success(P payload) {
            return new MigrationOutcome<>(true, payload, null, List.of());
        }

        private static <P> MigrationOutcome<P> failure(UnknownPayloadReason reason, List<String> errors) {
            return new MigrationOutcome<>(false, null, reason, errors);
        }
    }
}
