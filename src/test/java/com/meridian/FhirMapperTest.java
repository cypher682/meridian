package com.meridian;

import static org.assertj.core.api.Assertions.assertThat;

import com.meridian.core.Models.Encounter;
import com.meridian.core.Models.LabOrder;
import com.meridian.core.Models.LabResult;
import com.meridian.core.Models.Patient;
import com.meridian.fhir.FhirMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FhirMapperTest {

    private static final UUID FACILITY_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID ENCOUNTER_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private final FhirMapper mapper = new FhirMapper();

    @Test
    void mapsAPatient() {
        Patient patient = new Patient(
                PATIENT_ID, FACILITY_ID, "Sana", "Smoke", LocalDate.of(1992, 2, 3), "+00000000001", NOW);

        Map<String, Object> resource = mapper.patient(patient);

        assertThat(resource).containsEntry("resourceType", "Patient");
        assertThat(resource).containsEntry("id", PATIENT_ID.toString());
        Map<String, Object> name = first(resource, "name");
        assertThat(name).containsEntry("family", "Smoke");
        assertThat(name.get("given")).isEqualTo(List.of("Sana"));
    }

    @Test
    void mapsAReleasedObservation() {
        Map<String, Object> resource = mapper.observation(order("released", result(null)));

        assertThat(resource).containsEntry("resourceType", "Observation");
        assertThat(resource).containsEntry("status", "final");
        assertThat(resource).containsEntry("subject", Map.of("reference", "Patient/" + PATIENT_ID));
        Map<String, Object> value = map(resource, "valueQuantity");
        assertThat(value).containsEntry("value", new java.math.BigDecimal("13.2"));
        assertThat(value).containsEntry("unit", "g/dL");
    }

    @Test
    void marksAnAmendedObservationAsCorrected() {
        Map<String, Object> resource = mapper.observation(order("released", result(NOW.plusSeconds(60))));

        assertThat(resource).containsEntry("status", "corrected");
    }

    @Test
    void keepsANonNumericResultAsAString() {
        LabResult result = new LabResult(
                UUID.randomUUID(), ORDER_ID, "positive", null, null, null, null, null, null, NOW);
        Map<String, Object> resource = mapper.observation(order("resulted", result));

        assertThat(resource).containsEntry("valueString", "positive");
        assertThat(resource).doesNotContainKey("valueQuantity");
    }

    @Test
    void mapsADiagnosticReportAndBundle() {
        LabOrder order = order("released", result(NOW));
        Map<String, Object> report = mapper.diagnosticReport(order);
        Map<String, Object> bundle = mapper.bundle(List.of(report));

        assertThat(report).containsEntry("resourceType", "DiagnosticReport");
        assertThat(first(report, "result")).containsEntry("reference", "Observation/" + ORDER_ID);
        assertThat(bundle).containsEntry("resourceType", "Bundle");
        assertThat(bundle).containsEntry("total", 1);
    }

    @Test
    void mapsAnEncounter() {
        Encounter encounter = new Encounter(
                ENCOUNTER_ID, FACILITY_ID, PATIENT_ID, "clinician", "notes", null, NOW);

        Map<String, Object> resource = mapper.encounter(encounter);

        assertThat(resource).containsEntry("resourceType", "Encounter");
        assertThat(resource).containsEntry("status", "finished");
        assertThat(resource).containsEntry("subject", Map.of("reference", "Patient/" + PATIENT_ID));
    }

    private LabOrder order(String status, LabResult result) {
        return new LabOrder(ORDER_ID, FACILITY_ID, ENCOUNTER_ID, PATIENT_ID, "CBC", status, NOW, result);
    }

    private LabResult result(Instant amendedAt) {
        return new LabResult(
                UUID.randomUUID(),
                ORDER_ID,
                "13.2",
                "g/dL",
                "12.0-16.0",
                "clinician",
                NOW,
                amendedAt == null ? null : "clinician",
                amendedAt,
                NOW);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> first(Map<String, Object> resource, String key) {
        return (Map<String, Object>) ((List<Object>) resource.get(key)).get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Map<String, Object> resource, String key) {
        return (Map<String, Object>) resource.get(key);
    }
}
