package com.meridian.core;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
            String amendedBy,
            Instant amendedAt,
            Instant createdAt) {
    }

    public record LabResultAmendment(
            UUID id,
            UUID facilityId,
            UUID labOrderId,
            String previousValueText,
            String previousUnit,
            String previousReferenceRange,
            String valueText,
            String unit,
            String referenceRange,
            String reason,
            String amendedBy,
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

    public record BillingCharge(
            UUID id,
            UUID invoiceId,
            String description,
            long amountCents,
            String createdBy,
            Instant createdAt) {
    }

    public record BillingInvoice(
            UUID id,
            UUID facilityId,
            UUID encounterId,
            UUID patientId,
            String status,
            long totalCents,
            String createdBy,
            Instant createdAt,
            Instant paidAt,
            String paidBy,
            List<BillingCharge> charges) {
    }

    public record PharmacyItem(
            UUID id,
            UUID facilityId,
            String sku,
            String name,
            String unit,
            int stockQuantity,
            int reorderLevel,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record PharmacyDispense(
            UUID id,
            UUID facilityId,
            UUID itemId,
            UUID encounterId,
            UUID patientId,
            int quantity,
            String dispensedBy,
            Instant createdAt) {
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
