package com.meridian.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.core.Models.AuditEntry;
import com.meridian.core.Models.AuditVerification;
import com.meridian.core.Models.BillingCharge;
import com.meridian.core.Models.BillingInvoice;
import com.meridian.core.Models.Encounter;
import com.meridian.core.Models.LabOrder;
import com.meridian.core.Models.LabResult;
import com.meridian.core.Models.LabResultAmendment;
import com.meridian.core.Models.Patient;
import com.meridian.core.Models.PharmacyDispense;
import com.meridian.core.Models.PharmacyItem;

@Service
public class MeridianService {

    private static final int MAX_AUDIT_LIMIT = 500;

    private static final String INVOICE_SELECT = """
            SELECT i.id, i.facility_id, i.encounter_id, i.patient_id, i.status,
                   COALESCE(SUM(c.amount_cents), 0) AS total_cents,
                   i.created_by, i.created_at, i.paid_at, i.paid_by
            FROM billing_invoices i
            LEFT JOIN billing_charges c ON c.invoice_id = i.id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MeridianService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Patient registerPatient(
            UUID facilityId,
            String actorId,
            String givenName,
            String familyName,
            LocalDate dateOfBirth,
            String phone) {
        requireText(givenName, "givenName is required");
        requireText(familyName, "familyName is required");
        requireFacility(facilityId);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("facilityId", facilityId)
                .addValue("givenName", givenName.trim())
                .addValue("familyName", familyName.trim())
                .addValue("dob", dateOfBirth, Types.DATE)
                .addValue("phone", phone);

        Patient patient = jdbc.queryForObject(
                """
                INSERT INTO patients (facility_id, given_name, family_name, date_of_birth, phone)
                VALUES (:facilityId, :givenName, :familyName, :dob, :phone)
                RETURNING id, facility_id, given_name, family_name, date_of_birth, phone, created_at
                """,
                params,
                patientRow());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("given_name", patient.givenName());
        payload.put("family_name", patient.familyName());
        payload.put("date_of_birth", patient.dateOfBirth());
        audit(facilityId, actorId, "patient.registered", "patient", patient.id(), payload);
        return patient;
    }

    public Patient getPatient(UUID facilityId, UUID patientId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("facilityId", facilityId)
                .addValue("patientId", patientId);
        return jdbc.queryForObject(
                """
                SELECT id, facility_id, given_name, family_name, date_of_birth, phone, created_at
                FROM patients
                WHERE id = :patientId AND facility_id = :facilityId
                """,
                params,
                patientRow());
    }

    public List<Patient> searchPatients(UUID facilityId, String query) {
        String term = query == null ? "" : query.trim();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("facilityId", facilityId)
                .addValue("term", "%" + term + "%");
        return jdbc.query(
                """
                SELECT id, facility_id, given_name, family_name, date_of_birth, phone, created_at
                FROM patients
                WHERE facility_id = :facilityId
                  AND (:term = '%%' OR given_name ILIKE :term OR family_name ILIKE :term)
                ORDER BY family_name, given_name
                LIMIT 100
                """,
                params,
                patientRow());
    }

    @Transactional
    public Encounter createEncounter(
            UUID facilityId,
            String actorId,
            UUID patientId,
            String clinicianId,
            String notes,
            JsonNode vitals) {
        requireText(clinicianId, "clinicianId is required");
        getPatient(facilityId, patientId);
        String vitalsJson = writeJson(vitals == null ? objectMapper.createObjectNode() : vitals);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("facilityId", facilityId)
                .addValue("patientId", patientId)
                .addValue("clinicianId", clinicianId.trim())
                .addValue("notes", notes)
                .addValue("vitals", vitalsJson);

        Encounter encounter = jdbc.queryForObject(
                """
                INSERT INTO encounters (facility_id, patient_id, clinician_id, notes, vitals)
                VALUES (:facilityId, :patientId, :clinicianId, :notes, :vitals::jsonb)
                RETURNING id, facility_id, patient_id, clinician_id, notes, vitals, created_at
                """,
                params,
                encounterRow());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patient_id", patientId);
        payload.put("clinician_id", encounter.clinicianId());
        audit(facilityId, actorId, "encounter.created", "encounter", encounter.id(), payload);
        return encounter;
    }

    public Encounter getEncounter(UUID facilityId, UUID encounterId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("facilityId", facilityId)
                .addValue("encounterId", encounterId);
        return jdbc.queryForObject(
                """
                SELECT id, facility_id, patient_id, clinician_id, notes, vitals, created_at
                FROM encounters
                WHERE id = :encounterId AND facility_id = :facilityId
                """,
                params,
                encounterRow());
    }

    public List<Encounter> listEncounters(UUID facilityId, UUID patientId) {
        getPatient(facilityId, patientId);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("facilityId", facilityId)
                .addValue("patientId", patientId);
        return jdbc.query(
                """
                SELECT id, facility_id, patient_id, clinician_id, notes, vitals, created_at
                FROM encounters
                WHERE facility_id = :facilityId AND patient_id = :patientId
                ORDER BY created_at DESC
                """,
                params,
                encounterRow());
    }

    @Transactional
    public LabOrder createLabOrder(UUID facilityId, String actorId, UUID encounterId, String testCode) {
        requireText(testCode, "testCode is required");
        Encounter encounter = getEncounter(facilityId, encounterId);
        String normalizedCode = testCode.trim().toUpperCase();

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("facilityId", facilityId)
                .addValue("encounterId", encounterId)
                .addValue("patientId", encounter.patientId())
                .addValue("testCode", normalizedCode);

        LabOrder order = jdbc.queryForObject(
                """
                INSERT INTO lab_orders (facility_id, encounter_id, patient_id, test_code, status)
                VALUES (:facilityId, :encounterId, :patientId, :testCode, 'ordered')
                RETURNING id, facility_id, encounter_id, patient_id, test_code, status, created_at
                """,
                params,
                labOrderRow());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("encounter_id", encounterId);
        payload.put("test_code", normalizedCode);
        audit(facilityId, actorId, "lab_order.created", "lab_order", order.id(), payload);
        return order;
    }

    public LabOrder getLabOrder(UUID facilityId, UUID orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("facilityId", facilityId)
                .addValue("orderId", orderId);
        return jdbc.queryForObject(
                """
                SELECT o.id, o.facility_id, o.encounter_id, o.patient_id, o.test_code, o.status, o.created_at,
                       r.id AS result_id, r.value_text, r.unit, r.reference_range,
                       r.released_by, r.released_at, r.amended_by, r.amended_at, r.created_at AS result_created_at
                FROM lab_orders o
                LEFT JOIN lab_results r ON r.lab_order_id = o.id
                WHERE o.id = :orderId AND o.facility_id = :facilityId
                """,
                params,
                labOrderWithResultRow());
    }

    public List<LabOrder> listLabOrders(UUID facilityId, String status, UUID patientId) {
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim();
        if (normalizedStatus != null
                && !List.of("ordered", "collected", "resulted", "released", "cancelled")
                        .contains(normalizedStatus)) {
            throw new BadRequestException("unknown lab order status");
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("facilityId", facilityId)
                .addValue("status", normalizedStatus)
                .addValue("patientId", patientId);
        return jdbc.query(
                """
                SELECT o.id, o.facility_id, o.encounter_id, o.patient_id, o.test_code, o.status, o.created_at,
                       r.id AS result_id, r.value_text, r.unit, r.reference_range,
                       r.released_by, r.released_at, r.amended_by, r.amended_at, r.created_at AS result_created_at
                FROM lab_orders o
                LEFT JOIN lab_results r ON r.lab_order_id = o.id
                WHERE o.facility_id = :facilityId
                  AND (:status::text IS NULL OR o.status = :status::text)
                  AND (:patientId::uuid IS NULL OR o.patient_id = :patientId::uuid)
                ORDER BY o.created_at DESC
                LIMIT 100
                """,
                params,
                labOrderWithResultRow());
    }

    @Transactional
    public LabOrder collectSample(UUID facilityId, String actorId, UUID orderId) {
        LabOrder order = getLabOrder(facilityId, orderId);
        requireStatus(order.status(), "ordered", "sample can only be collected for an ordered test");

        int updated = jdbc.update(
                """
                UPDATE lab_orders SET status = 'collected'
                WHERE id = :orderId AND facility_id = :facilityId AND status = 'ordered'
                """,
                new MapSqlParameterSource()
                        .addValue("facilityId", facilityId)
                        .addValue("orderId", orderId));
        if (updated == 0) {
            throw new ConflictException("sample was already collected");
        }

        audit(facilityId, actorId, "lab_order.collected", "lab_order", orderId,
                Map.of("test_code", order.testCode()));
        return getLabOrder(facilityId, orderId);
    }

    @Transactional
    public LabOrder recordResult(
            UUID facilityId,
            String actorId,
            UUID orderId,
            String valueText,
            String unit,
            String referenceRange) {
        requireText(valueText, "valueText is required");
        LabOrder order = getLabOrder(facilityId, orderId);
        if (order.result() != null) {
            throw new ConflictException("a result already exists for this lab order");
        }
        requireStatus(order.status(), "collected", "result can only be recorded after sample collection");

        int updated = jdbc.update(
                """
                UPDATE lab_orders SET status = 'resulted'
                WHERE id = :orderId AND status = 'collected'
                """,
                new MapSqlParameterSource().addValue("orderId", orderId));
        if (updated == 0) {
            throw new ConflictException("order is no longer awaiting a result");
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("valueText", valueText.trim())
                .addValue("unit", unit)
                .addValue("referenceRange", referenceRange);

        jdbc.update(
                """
                INSERT INTO lab_results (lab_order_id, value_text, unit, reference_range)
                VALUES (:orderId, :valueText, :unit, :referenceRange)
                """,
                params);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("value_text", valueText.trim());
        payload.put("unit", unit);
        payload.put("reference_range", referenceRange);
        audit(facilityId, actorId, "lab_result.recorded", "lab_order", orderId, payload);
        return getLabOrder(facilityId, orderId);
    }

    @Transactional
    public LabOrder ingestHl7Result(
            UUID facilityId,
            String actorId,
            UUID orderId,
            String valueText,
            String unit,
            String referenceRange) {
        LabOrder order = getLabOrder(facilityId, orderId);
        if ("ordered".equals(order.status())) {
            collectSample(facilityId, actorId, orderId);
        }
        return recordResult(facilityId, actorId, orderId, valueText, unit, referenceRange);
    }

    @Transactional
    public LabOrder releaseResult(UUID facilityId, String actorId, UUID orderId, String releasedBy) {
        requireText(releasedBy, "releasedBy is required");
        LabOrder order = getLabOrder(facilityId, orderId);
        if (order.result() == null) {
            throw new ConflictException("no result exists for this lab order");
        }
        requireStatus(order.status(), "resulted", "only a resulted order can be released");

        int updated = jdbc.update(
                """
                UPDATE lab_orders SET status = 'released'
                WHERE id = :orderId AND status = 'resulted'
                """,
                new MapSqlParameterSource().addValue("orderId", orderId));
        if (updated == 0) {
            throw new ConflictException("order is no longer awaiting release");
        }

        jdbc.update(
                """
                UPDATE lab_results
                SET released_by = :releasedBy, released_at = now()
                WHERE lab_order_id = :orderId
                """,
                new MapSqlParameterSource()
                        .addValue("orderId", orderId)
                        .addValue("releasedBy", releasedBy.trim()));

        audit(facilityId, actorId, "lab_result.released", "lab_order", orderId,
                Map.of("released_by", releasedBy.trim()));
        return getLabOrder(facilityId, orderId);
    }

    @Transactional
    public LabOrder amendResult(
            UUID facilityId,
            String actorId,
            UUID orderId,
            String valueText,
            String unit,
            String referenceRange,
            String reason) {
        requireText(valueText, "valueText is required");
        requireText(reason, "reason is required");

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("facilityId", facilityId)
                .addValue("orderId", orderId);

        String status;
        try {
            status = jdbc.queryForObject(
                    """
                    SELECT status FROM lab_orders
                    WHERE id = :orderId AND facility_id = :facilityId
                    FOR UPDATE
                    """,
                    params,
                    String.class);
        } catch (EmptyResultDataAccessException error) {
            throw new NotFoundException("lab order not found");
        }
        requireStatus(status, "released", "only a released result can be amended");

        LabResult current;
        try {
            current = jdbc.queryForObject(
                    """
                    SELECT id, lab_order_id, value_text, unit, reference_range,
                           released_by, released_at, amended_by, amended_at, created_at
                    FROM lab_results
                    WHERE lab_order_id = :orderId
                    FOR UPDATE
                    """,
                    params,
                    labResultRow());
        } catch (EmptyResultDataAccessException error) {
            throw new ConflictException("no result exists for this lab order");
        }

        String nextValue = valueText.trim();
        String nextReason = reason.trim();
        jdbc.update(
                """
                INSERT INTO lab_result_amendments
                    (facility_id, lab_order_id, previous_value_text, previous_unit,
                     previous_reference_range, value_text, unit, reference_range, reason, amended_by)
                VALUES
                    (:facilityId, :orderId, :previousValueText, :previousUnit,
                     :previousReferenceRange, :valueText, :unit, :referenceRange, :reason, :amendedBy)
                """,
                new MapSqlParameterSource()
                        .addValue("facilityId", facilityId)
                        .addValue("orderId", orderId)
                        .addValue("previousValueText", current.valueText())
                        .addValue("previousUnit", current.unit())
                        .addValue("previousReferenceRange", current.referenceRange())
                        .addValue("valueText", nextValue)
                        .addValue("unit", unit)
                        .addValue("referenceRange", referenceRange)
                        .addValue("reason", nextReason)
                        .addValue("amendedBy", actorId));

        jdbc.update(
                """
                UPDATE lab_results
                SET value_text = :valueText,
                    unit = :unit,
                    reference_range = :referenceRange,
                    amended_by = :amendedBy,
                    amended_at = now()
                WHERE lab_order_id = :orderId
                """,
                new MapSqlParameterSource()
                        .addValue("orderId", orderId)
                        .addValue("valueText", nextValue)
                        .addValue("unit", unit)
                        .addValue("referenceRange", referenceRange)
                        .addValue("amendedBy", actorId));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("previous_value_text", current.valueText());
        payload.put("previous_unit", current.unit());
        payload.put("previous_reference_range", current.referenceRange());
        payload.put("value_text", nextValue);
        payload.put("unit", unit);
        payload.put("reference_range", referenceRange);
        payload.put("reason", nextReason);
        audit(facilityId, actorId, "lab_result.amended", "lab_order", orderId, payload);
        return getLabOrder(facilityId, orderId);
    }

    public List<LabResultAmendment> listAmendments(UUID facilityId, UUID orderId) {
        getLabOrder(facilityId, orderId);
        return jdbc.query(
                """
                SELECT id, facility_id, lab_order_id,
                       previous_value_text, previous_unit, previous_reference_range,
                       value_text, unit, reference_range, reason, amended_by, created_at
                FROM lab_result_amendments
                WHERE facility_id = :facilityId AND lab_order_id = :orderId
                ORDER BY created_at DESC, id DESC
                """,
                new MapSqlParameterSource()
                        .addValue("facilityId", facilityId)
                        .addValue("orderId", orderId),
                labResultAmendmentRow());
    }

    @Transactional
    public BillingInvoice createInvoice(UUID facilityId, String actorId, UUID encounterId) {
        if (encounterId == null) {
            throw new BadRequestException("encounterId is required");
        }
        Encounter encounter = getEncounter(facilityId, encounterId);
        UUID invoiceId = UUID.randomUUID();
        try {
            jdbc.update(
                    """
                    INSERT INTO billing_invoices
                        (id, facility_id, encounter_id, patient_id, created_by)
                    VALUES
                        (:id, :facilityId, :encounterId, :patientId, :createdBy)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", invoiceId)
                            .addValue("facilityId", facilityId)
                            .addValue("encounterId", encounterId)
                            .addValue("patientId", encounter.patientId())
                            .addValue("createdBy", actorId));
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("this encounter already has an invoice");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("encounter_id", encounterId.toString());
        payload.put("patient_id", encounter.patientId().toString());
        audit(facilityId, actorId, "billing_invoice.created", "billing_invoice", invoiceId, payload);
        return getInvoice(facilityId, invoiceId);
    }

    public BillingInvoice getInvoice(UUID facilityId, UUID invoiceId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("facilityId", facilityId)
                .addValue("invoiceId", invoiceId);
        BillingInvoice invoice;
        try {
            invoice = jdbc.queryForObject(
                    INVOICE_SELECT + """
                            WHERE i.facility_id = :facilityId AND i.id = :invoiceId
                            GROUP BY i.id
                            """,
                    params,
                    billingInvoiceRow());
        } catch (EmptyResultDataAccessException exception) {
            throw new NotFoundException("invoice not found");
        }
        if (invoice == null) {
            throw new NotFoundException("invoice not found");
        }

        List<BillingCharge> charges = jdbc.query(
                """
                SELECT id, invoice_id, description, amount_cents, created_by, created_at
                FROM billing_charges
                WHERE facility_id = :facilityId AND invoice_id = :invoiceId
                ORDER BY created_at, id
                """,
                params,
                billingChargeRow());
        return new BillingInvoice(
                invoice.id(),
                invoice.facilityId(),
                invoice.encounterId(),
                invoice.patientId(),
                invoice.status(),
                invoice.totalCents(),
                invoice.createdBy(),
                invoice.createdAt(),
                invoice.paidAt(),
                invoice.paidBy(),
                charges);
    }

    public List<BillingInvoice> listInvoices(UUID facilityId, String status, UUID patientId) {
        StringBuilder sql = new StringBuilder(INVOICE_SELECT);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("facilityId", facilityId);
        sql.append(" WHERE i.facility_id = :facilityId");
        if (status != null && !status.isBlank()) {
            sql.append(" AND i.status = :status");
            params.addValue("status", status.trim());
        }
        if (patientId != null) {
            sql.append(" AND i.patient_id = :patientId");
            params.addValue("patientId", patientId);
        }
        sql.append(" GROUP BY i.id ORDER BY i.created_at DESC LIMIT 100");
        return jdbc.query(sql.toString(), params, billingInvoiceRow());
    }

    @Transactional
    public BillingInvoice addCharge(
            UUID facilityId,
            String actorId,
            UUID invoiceId,
            String description,
            long amountCents) {
        requireText(description, "description is required");
        if (amountCents <= 0) {
            throw new BadRequestException("amountCents must be greater than zero");
        }
        String status = lockInvoiceStatus(facilityId, invoiceId);
        requireStatus(status, "open", "charges can only be added to an open invoice");

        UUID chargeId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO billing_charges
                    (id, facility_id, invoice_id, description, amount_cents, created_by)
                VALUES
                    (:id, :facilityId, :invoiceId, :description, :amountCents, :createdBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", chargeId)
                        .addValue("facilityId", facilityId)
                        .addValue("invoiceId", invoiceId)
                        .addValue("description", description.trim())
                        .addValue("amountCents", amountCents)
                        .addValue("createdBy", actorId));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invoice_id", invoiceId.toString());
        payload.put("description", description.trim());
        payload.put("amount_cents", amountCents);
        audit(facilityId, actorId, "billing_charge.added", "billing_charge", chargeId, payload);
        return getInvoice(facilityId, invoiceId);
    }

    @Transactional
    public BillingInvoice payInvoice(UUID facilityId, String actorId, UUID invoiceId) {
        String status = lockInvoiceStatus(facilityId, invoiceId);
        requireStatus(status, "open", "only an open invoice can be paid");

        Long total = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(amount_cents), 0) FROM billing_charges
                WHERE facility_id = :facilityId AND invoice_id = :invoiceId
                """,
                new MapSqlParameterSource()
                        .addValue("facilityId", facilityId)
                        .addValue("invoiceId", invoiceId),
                Long.class);
        if (total == null || total == 0) {
            throw new ConflictException("cannot pay an invoice with no charges");
        }

        jdbc.update(
                """
                UPDATE billing_invoices
                SET status = 'paid', paid_at = now(), paid_by = :paidBy
                WHERE facility_id = :facilityId AND id = :invoiceId AND status = 'open'
                """,
                new MapSqlParameterSource()
                        .addValue("facilityId", facilityId)
                        .addValue("invoiceId", invoiceId)
                        .addValue("paidBy", actorId));
        audit(facilityId, actorId, "billing_invoice.paid", "billing_invoice", invoiceId,
                Map.of("total_cents", total));
        return getInvoice(facilityId, invoiceId);
    }

    @Transactional
    public PharmacyItem createPharmacyItem(
            UUID facilityId,
            String actorId,
            String sku,
            String name,
            String unit,
            int stockQuantity,
            int reorderLevel) {
        requireText(sku, "sku is required");
        requireText(name, "name is required");
        if (stockQuantity < 0) {
            throw new BadRequestException("stockQuantity cannot be negative");
        }
        if (reorderLevel < 0) {
            throw new BadRequestException("reorderLevel cannot be negative");
        }
        UUID itemId = UUID.randomUUID();
        try {
            jdbc.update(
                    """
                    INSERT INTO pharmacy_items
                        (id, facility_id, sku, name, unit, stock_quantity, reorder_level)
                    VALUES
                        (:id, :facilityId, :sku, :name, :unit, :stockQuantity, :reorderLevel)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", itemId)
                            .addValue("facilityId", facilityId)
                            .addValue("sku", sku.trim().toUpperCase())
                            .addValue("name", name.trim())
                            .addValue("unit", unit == null || unit.isBlank() ? "unit" : unit.trim())
                            .addValue("stockQuantity", stockQuantity)
                            .addValue("reorderLevel", reorderLevel));
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("an item with this sku already exists");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sku", sku.trim().toUpperCase());
        payload.put("name", name.trim());
        payload.put("stock_quantity", stockQuantity);
        audit(facilityId, actorId, "pharmacy_item.created", "pharmacy_item", itemId, payload);
        return getPharmacyItem(facilityId, itemId);
    }

    public PharmacyItem getPharmacyItem(UUID facilityId, UUID itemId) {
        try {
            return jdbc.queryForObject(
                    """
                    SELECT id, facility_id, sku, name, unit, stock_quantity, reorder_level, created_at, updated_at
                    FROM pharmacy_items
                    WHERE facility_id = :facilityId AND id = :itemId
                    """,
                    new MapSqlParameterSource()
                            .addValue("facilityId", facilityId)
                            .addValue("itemId", itemId),
                    pharmacyItemRow());
        } catch (EmptyResultDataAccessException exception) {
            throw new NotFoundException("pharmacy item not found");
        }
    }

    public List<PharmacyItem> listPharmacyItems(UUID facilityId, String query) {
        String term = query == null ? "" : query.trim();
        return jdbc.query(
                """
                SELECT id, facility_id, sku, name, unit, stock_quantity, reorder_level, created_at, updated_at
                FROM pharmacy_items
                WHERE facility_id = :facilityId
                  AND (:term = '' OR name ILIKE :pattern OR sku ILIKE :pattern)
                ORDER BY name, sku
                LIMIT 200
                """,
                new MapSqlParameterSource()
                        .addValue("facilityId", facilityId)
                        .addValue("term", term)
                        .addValue("pattern", "%" + term + "%"),
                pharmacyItemRow());
    }

    @Transactional
    public PharmacyItem adjustStock(UUID facilityId, String actorId, UUID itemId, int delta) {
        if (delta == 0) {
            throw new BadRequestException("delta cannot be zero");
        }
        PharmacyItem item = lockPharmacyItem(facilityId, itemId);
        int next = item.stockQuantity() + delta;
        if (next < 0) {
            throw new ConflictException("stock cannot go below zero");
        }
        jdbc.update(
                """
                UPDATE pharmacy_items
                SET stock_quantity = :stockQuantity, updated_at = now()
                WHERE facility_id = :facilityId AND id = :itemId
                """,
                new MapSqlParameterSource()
                        .addValue("facilityId", facilityId)
                        .addValue("itemId", itemId)
                        .addValue("stockQuantity", next));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("delta", delta);
        payload.put("stock_quantity", next);
        audit(facilityId, actorId, "pharmacy_item.stock_adjusted", "pharmacy_item", itemId, payload);
        return getPharmacyItem(facilityId, itemId);
    }

    @Transactional
    public PharmacyDispense dispenseMedication(
            UUID facilityId,
            String actorId,
            UUID itemId,
            UUID encounterId,
            int quantity) {
        if (itemId == null) {
            throw new BadRequestException("itemId is required");
        }
        if (encounterId == null) {
            throw new BadRequestException("encounterId is required");
        }
        if (quantity <= 0) {
            throw new BadRequestException("quantity must be greater than zero");
        }
        Encounter encounter = getEncounter(facilityId, encounterId);
        PharmacyItem item = lockPharmacyItem(facilityId, itemId);
        if (item.stockQuantity() < quantity) {
            throw new ConflictException("insufficient stock");
        }

        jdbc.update(
                """
                UPDATE pharmacy_items
                SET stock_quantity = stock_quantity - :quantity, updated_at = now()
                WHERE facility_id = :facilityId AND id = :itemId
                """,
                new MapSqlParameterSource()
                        .addValue("facilityId", facilityId)
                        .addValue("itemId", itemId)
                        .addValue("quantity", quantity));

        UUID dispenseId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO pharmacy_dispenses
                    (id, facility_id, item_id, encounter_id, patient_id, quantity, dispensed_by)
                VALUES
                    (:id, :facilityId, :itemId, :encounterId, :patientId, :quantity, :dispensedBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", dispenseId)
                        .addValue("facilityId", facilityId)
                        .addValue("itemId", itemId)
                        .addValue("encounterId", encounterId)
                        .addValue("patientId", encounter.patientId())
                        .addValue("quantity", quantity)
                        .addValue("dispensedBy", actorId));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("item_id", itemId.toString());
        payload.put("encounter_id", encounterId.toString());
        payload.put("patient_id", encounter.patientId().toString());
        payload.put("quantity", quantity);
        audit(facilityId, actorId, "pharmacy.dispensed", "pharmacy_dispense", dispenseId, payload);
        return getDispense(facilityId, dispenseId);
    }

    public List<PharmacyDispense> listDispenses(UUID facilityId, UUID patientId) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, facility_id, item_id, encounter_id, patient_id, quantity, dispensed_by, created_at
                FROM pharmacy_dispenses
                WHERE facility_id = :facilityId
                """);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("facilityId", facilityId);
        if (patientId != null) {
            sql.append(" AND patient_id = :patientId");
            params.addValue("patientId", patientId);
        }
        sql.append(" ORDER BY created_at DESC LIMIT 200");
        return jdbc.query(sql.toString(), params, pharmacyDispenseRow());
    }

    private PharmacyDispense getDispense(UUID facilityId, UUID dispenseId) {
        try {
            return jdbc.queryForObject(
                    """
                    SELECT id, facility_id, item_id, encounter_id, patient_id, quantity, dispensed_by, created_at
                    FROM pharmacy_dispenses
                    WHERE facility_id = :facilityId AND id = :dispenseId
                    """,
                    new MapSqlParameterSource()
                            .addValue("facilityId", facilityId)
                            .addValue("dispenseId", dispenseId),
                    pharmacyDispenseRow());
        } catch (EmptyResultDataAccessException exception) {
            throw new NotFoundException("dispense not found");
        }
    }

    private String lockInvoiceStatus(UUID facilityId, UUID invoiceId) {
        try {
            return jdbc.queryForObject(
                    """
                    SELECT status FROM billing_invoices
                    WHERE facility_id = :facilityId AND id = :invoiceId
                    FOR UPDATE
                    """,
                    new MapSqlParameterSource()
                            .addValue("facilityId", facilityId)
                            .addValue("invoiceId", invoiceId),
                    String.class);
        } catch (EmptyResultDataAccessException exception) {
            throw new NotFoundException("invoice not found");
        }
    }

    private PharmacyItem lockPharmacyItem(UUID facilityId, UUID itemId) {
        try {
            return jdbc.queryForObject(
                    """
                    SELECT id, facility_id, sku, name, unit, stock_quantity, reorder_level, created_at, updated_at
                    FROM pharmacy_items
                    WHERE facility_id = :facilityId AND id = :itemId
                    FOR UPDATE
                    """,
                    new MapSqlParameterSource()
                            .addValue("facilityId", facilityId)
                            .addValue("itemId", itemId),
                    pharmacyItemRow());
        } catch (EmptyResultDataAccessException exception) {
            throw new NotFoundException("pharmacy item not found");
        }
    }

    public List<AuditEntry> listAudit(UUID facilityId, int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_AUDIT_LIMIT));
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("facilityId", facilityId)
                .addValue("limit", bounded);
        List<AuditEntry> entries = jdbc.query(
                """
                SELECT id, facility_id, actor_id, action, entity_type, entity_id, payload, payload_text,
                       prev_hash, entry_hash, created_at
                FROM audit_log
                WHERE facility_id = :facilityId
                ORDER BY id DESC
                LIMIT :limit
                """,
                params,
                auditRow());
        return entries;
    }

    public AuditVerification verifyAudit(UUID facilityId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("facilityId", facilityId);
        List<AuditEntry> entries = jdbc.query(
                """
                SELECT id, facility_id, actor_id, action, entity_type, entity_id, payload, payload_text,
                       prev_hash, entry_hash, created_at
                FROM audit_log
                WHERE facility_id = :facilityId
                ORDER BY id
                """,
                params,
                auditRow());

        Long brokenAt = AuditChain.firstBrokenEntry(entries);
        return new AuditVerification(brokenAt == null, entries.size(), brokenAt);
    }

    private void audit(
            UUID facilityId,
            String actorId,
            String action,
            String entityType,
            UUID entityId,
            Map<String, Object> payload) {
        requireText(actorId, "actorId is required");
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtext(:key))",
                new MapSqlParameterSource("key", facilityId.toString()),
                (rs, rowNum) -> null);

        String previous = null;
        try {
            previous = jdbc.queryForObject(
                    """
                    SELECT entry_hash FROM audit_log
                    WHERE facility_id = :facilityId
                    ORDER BY id DESC LIMIT 1
                    """,
                    new MapSqlParameterSource("facilityId", facilityId),
                    String.class);
        } catch (EmptyResultDataAccessException ignored) {
            previous = null;
        }

        String payloadJson = writeJson(payload);
        String entryHash = AuditChain.entryHash(
                previous,
                facilityId.toString(),
                actorId,
                action,
                entityType,
                entityId.toString(),
                payloadJson);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("facilityId", facilityId)
                .addValue("actorId", actorId)
                .addValue("action", action)
                .addValue("entityType", entityType)
                .addValue("entityId", entityId)
                .addValue("payload", payloadJson)
                .addValue("payloadText", payloadJson)
                .addValue("prevHash", previous)
                .addValue("entryHash", entryHash);
        jdbc.update(
                """
                INSERT INTO audit_log
                    (facility_id, actor_id, action, entity_type, entity_id, payload, payload_text, prev_hash, entry_hash)
                VALUES
                    (:facilityId, :actorId, :action, :entityType, :entityId, :payload::jsonb, :payloadText, :prevHash, :entryHash)
                """,
                params);
    }

    private void requireFacility(UUID facilityId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM facilities WHERE id = :facilityId",
                new MapSqlParameterSource("facilityId", facilityId),
                Integer.class);
        if (count == null || count == 0) {
            throw new NotFoundException("facility not found");
        }
    }

    private static void requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new BadRequestException(message);
        }
    }

    private static void requireStatus(String actual, String expected, String message) {
        if (!expected.equals(actual)) {
            throw new ConflictException(message + " (status is " + actual + ")");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("serialize audit payload", error);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("parse json", error);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private RowMapper<BillingInvoice> billingInvoiceRow() {
        return (rs, rowNum) -> new BillingInvoice(
                rs.getObject("id", UUID.class),
                rs.getObject("facility_id", UUID.class),
                rs.getObject("encounter_id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getString("status"),
                rs.getLong("total_cents"),
                rs.getString("created_by"),
                instant(rs, "created_at"),
                instant(rs, "paid_at"),
                rs.getString("paid_by"),
                List.of());
    }

    private RowMapper<BillingCharge> billingChargeRow() {
        return (rs, rowNum) -> new BillingCharge(
                rs.getObject("id", UUID.class),
                rs.getObject("invoice_id", UUID.class),
                rs.getString("description"),
                rs.getLong("amount_cents"),
                rs.getString("created_by"),
                instant(rs, "created_at"));
    }

    private RowMapper<PharmacyItem> pharmacyItemRow() {
        return (rs, rowNum) -> new PharmacyItem(
                rs.getObject("id", UUID.class),
                rs.getObject("facility_id", UUID.class),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getString("unit"),
                rs.getInt("stock_quantity"),
                rs.getInt("reorder_level"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private RowMapper<PharmacyDispense> pharmacyDispenseRow() {
        return (rs, rowNum) -> new PharmacyDispense(
                rs.getObject("id", UUID.class),
                rs.getObject("facility_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getObject("encounter_id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getInt("quantity"),
                rs.getString("dispensed_by"),
                instant(rs, "created_at"));
    }

    private RowMapper<Patient> patientRow() {
        return (rs, rowNum) -> new Patient(
                rs.getObject("id", UUID.class),
                rs.getObject("facility_id", UUID.class),
                rs.getString("given_name"),
                rs.getString("family_name"),
                rs.getObject("date_of_birth", LocalDate.class),
                rs.getString("phone"),
                instant(rs, "created_at"));
    }

    private RowMapper<Encounter> encounterRow() {
        return (rs, rowNum) -> new Encounter(
                rs.getObject("id", UUID.class),
                rs.getObject("facility_id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getString("clinician_id"),
                rs.getString("notes"),
                readJson(rs.getString("vitals")),
                instant(rs, "created_at"));
    }

    private RowMapper<LabResult> labResultRow() {
        return (rs, rowNum) -> new LabResult(
                rs.getObject("id", UUID.class),
                rs.getObject("lab_order_id", UUID.class),
                rs.getString("value_text"),
                rs.getString("unit"),
                rs.getString("reference_range"),
                rs.getString("released_by"),
                instant(rs, "released_at"),
                rs.getString("amended_by"),
                instant(rs, "amended_at"),
                instant(rs, "created_at"));
    }

    private RowMapper<LabResultAmendment> labResultAmendmentRow() {
        return (rs, rowNum) -> new LabResultAmendment(
                rs.getObject("id", UUID.class),
                rs.getObject("facility_id", UUID.class),
                rs.getObject("lab_order_id", UUID.class),
                rs.getString("previous_value_text"),
                rs.getString("previous_unit"),
                rs.getString("previous_reference_range"),
                rs.getString("value_text"),
                rs.getString("unit"),
                rs.getString("reference_range"),
                rs.getString("reason"),
                rs.getString("amended_by"),
                instant(rs, "created_at"));
    }

    private RowMapper<LabOrder> labOrderRow() {
        return (rs, rowNum) -> new LabOrder(
                rs.getObject("id", UUID.class),
                rs.getObject("facility_id", UUID.class),
                rs.getObject("encounter_id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getString("test_code"),
                rs.getString("status"),
                instant(rs, "created_at"),
                null);
    }

    private RowMapper<LabOrder> labOrderWithResultRow() {
        return (rs, rowNum) -> {
            LabResult result = null;
            UUID resultId = rs.getObject("result_id", UUID.class);
            if (resultId != null) {
                result = new LabResult(
                        resultId,
                        rs.getObject("id", UUID.class),
                        rs.getString("value_text"),
                        rs.getString("unit"),
                        rs.getString("reference_range"),
                        rs.getString("released_by"),
                        instant(rs, "released_at"),
                        rs.getString("amended_by"),
                        instant(rs, "amended_at"),
                        instant(rs, "result_created_at"));
            }
            return new LabOrder(
                    rs.getObject("id", UUID.class),
                    rs.getObject("facility_id", UUID.class),
                    rs.getObject("encounter_id", UUID.class),
                    rs.getObject("patient_id", UUID.class),
                    rs.getString("test_code"),
                    rs.getString("status"),
                    instant(rs, "created_at"),
                    result);
        };
    }

    private RowMapper<AuditEntry> auditRow() {
        return (rs, rowNum) -> new AuditEntry(
                rs.getLong("id"),
                rs.getObject("facility_id", UUID.class),
                rs.getString("actor_id"),
                rs.getString("action"),
                rs.getString("entity_type"),
                rs.getObject("entity_id", UUID.class),
                readJson(rs.getString("payload")),
                rs.getString("payload_text"),
                rs.getString("prev_hash"),
                rs.getString("entry_hash"),
                instant(rs, "created_at"));
    }
}
