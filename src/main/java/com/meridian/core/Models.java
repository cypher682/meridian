package com.meridian.core;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

public final class Models {

    private Models() {
    }

    public record Patient(
            UUID id,
            UUID facilityId,
            String givenName,
            String familyName,
            LocalDate dateOfBirth,
            String phone,
            Instant createdAt) {
    }

    public record Encounter(
            UUID id,
            UUID facilityId,
            UUID patientId,
            String clinicianId,
            String notes,
            JsonNode vitals,
            Instant createdAt) {
    }

    public record LabResult(
            UUID id,
            UUID labOrderId,
            String valueText,
            String unit,
            String referenceRange,
            String releasedBy,
            Instant releasedAt,
            Instant createdAt) {
    }

    public record LabOrder(
            UUID id,
            UUID facilityId,
            UUID encounterId,
            UUID patientId,
            String testCode,
            String status,
            Instant createdAt,
            LabResult result) {
    }

    public record AuditEntry(
            long id,
            UUID facilityId,
            String actorId,
            String action,
            String entityType,
            UUID entityId,
            JsonNode payload,
            @JsonIgnore String payloadText,
            String prevHash,
            String entryHash,
            Instant createdAt) {
    }

    public record AuditVerification(boolean valid, int entries, Long brokenAtId) {
    }
}
