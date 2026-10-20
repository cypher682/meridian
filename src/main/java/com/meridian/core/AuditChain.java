package com.meridian.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import com.meridian.core.Models.AuditEntry;

public final class AuditChain {

    private AuditChain() {
    }

    public static String entryHash(
            String prevHash,
            String facilityId,
            String actorId,
            String action,
            String entityType,
            String entityId,
            String payloadJson) {
        String canonical = String.join(
                "|",
                prevHash == null ? "" : prevHash,
                facilityId,
                actorId,
                action,
                entityType,
                entityId,
                payloadJson);
        return sha256Hex(canonical);
    }

    public static Long firstBrokenEntry(List<AuditEntry> entries) {
        String previous = null;
        for (AuditEntry entry : entries) {
            String expected = entryHash(
                    previous,
                    entry.facilityId().toString(),
                    entry.actorId(),
                    entry.action(),
                    entry.entityType(),
                    entry.entityId().toString(),
                    entry.payloadText());
            if (!expected.equals(entry.entryHash()) || !Objects.equals(previous, entry.prevHash())) {
                return entry.id();
            }
            previous = entry.entryHash();
        }
        return null;
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required", error);
        }
    }
}
