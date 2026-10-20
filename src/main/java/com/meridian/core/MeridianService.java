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
import com.meridian.core.Models.Encounter;
import com.meridian.core.Models.LabOrder;
import com.meridian.core.Models.LabResult;
import com.meridian.core.Models.Patient;

@Service
public class MeridianService {

    private static final int MAX_AUDIT_LIMIT = 500;

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
                       r.released_by, r.released_at, r.created_at AS result_created_at
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
                       r.released_by, r.released_at, r.created_at AS result_created_at
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
