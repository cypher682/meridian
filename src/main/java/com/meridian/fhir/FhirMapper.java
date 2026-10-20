package com.meridian.fhir;

import com.meridian.core.Models.Encounter;
import com.meridian.core.Models.LabOrder;
import com.meridian.core.Models.LabResult;
import com.meridian.core.Models.Patient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class FhirMapper {

    private static final String TEST_CODE_SYSTEM = "urn:meridian:test-code";
    private static final String UCUM = "http://unitsofmeasure.org";
    private static final String ACT_CODE = "http://terminology.hl7.org/CodeSystem/v3-ActCode";
    private static final String LAB_CATEGORY = "http://terminology.hl7.org/CodeSystem/v2-0074";

    public Map<String, Object> patient(Patient patient) {
        Map<String, Object> resource = resource("Patient", patient.id().toString());
        resource.put("identifier", List.of(map(
                "system", "urn:meridian:patient",
                "value", patient.id().toString())));
        Map<String, Object> name = map(
                "use", "official",
                "family", patient.familyName());
        name.put("given", List.of(patient.givenName()));
        resource.put("name", List.of(name));
        if (patient.dateOfBirth() != null) {
            resource.put("birthDate", patient.dateOfBirth().toString());
        }
        if (patient.phone() != null && !patient.phone().isBlank()) {
            resource.put("telecom", List.of(map(
                    "system", "phone",
                    "value", patient.phone(),
                    "use", "mobile")));
        }
        resource.put("meta", map("lastUpdated", instant(patient.createdAt())));
        return resource;
    }

    public Map<String, Object> encounter(Encounter encounter) {
        Map<String, Object> resource = resource("Encounter", encounter.id().toString());
        resource.put("status", "finished");
        resource.put("class", map(
                "system", ACT_CODE,
                "code", "AMB",
                "display", "ambulatory"));
        resource.put("subject", reference("Patient", encounter.patientId().toString()));
        if (encounter.clinicianId() != null && !encounter.clinicianId().isBlank()) {
            resource.put("participant", List.of(map(
                    "individual", map("display", encounter.clinicianId()))));
        }
        resource.put("period", map("start", instant(encounter.createdAt())));
        resource.put("meta", map("lastUpdated", instant(encounter.createdAt())));
        return resource;
    }

    public Map<String, Object> observation(LabOrder order) {
        Map<String, Object> resource = resource("Observation", order.id().toString());
        LabResult result = order.result();
        resource.put("status", observationStatus(order));
        resource.put("code", code(order.testCode()));
        resource.put("subject", reference("Patient", order.patientId().toString()));
        resource.put("encounter", reference("Encounter", order.encounterId().toString()));
        resource.put("effectiveDateTime", instant(result != null ? result.createdAt() : order.createdAt()));

        if (result != null) {
            resource.put("issued", instant(result.createdAt()));
            if (result.valueText() != null && !result.valueText().isBlank()) {
                BigDecimal numeric = numeric(result.valueText());
                if (numeric != null && result.unit() != null && !result.unit().isBlank()) {
                    resource.put("valueQuantity", map(
                            "value", numeric,
                            "unit", result.unit(),
                            "system", UCUM,
                            "code", result.unit()));
                } else {
                    resource.put("valueString", result.valueText());
                }
            }
            if (result.referenceRange() != null && !result.referenceRange().isBlank()) {
                resource.put("referenceRange", List.of(map("text", result.referenceRange())));
            }
            if (result.releasedBy() != null && !result.releasedBy().isBlank()) {
                resource.put("performer", List.of(map("display", result.releasedBy())));
            }
            resource.put("meta", map("lastUpdated", instant(result.createdAt())));
        } else {
            resource.put("meta", map("lastUpdated", instant(order.createdAt())));
        }
        return resource;
    }

    public Map<String, Object> diagnosticReport(LabOrder order) {
        Map<String, Object> resource = resource("DiagnosticReport", order.id().toString());
        LabResult result = order.result();
        resource.put("status", reportStatus(order));
        resource.put("category", List.of(map(
                "coding", List.of(map(
                        "system", LAB_CATEGORY,
                        "code", "LAB",
                        "display", "Laboratory")))));
        resource.put("code", code(order.testCode()));
        resource.put("subject", reference("Patient", order.patientId().toString()));
        resource.put("encounter", reference("Encounter", order.encounterId().toString()));
        resource.put("effectiveDateTime", instant(result != null ? result.createdAt() : order.createdAt()));
        if (result != null) {
            resource.put("issued", instant(result.createdAt()));
            resource.put("result", List.of(reference("Observation", order.id().toString())));
            resource.put("meta", map("lastUpdated", instant(result.createdAt())));
        } else {
            resource.put("meta", map("lastUpdated", instant(order.createdAt())));
        }
        return resource;
    }

    public Map<String, Object> bundle(List<Map<String, Object>> resources) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> resource : resources) {
            entries.add(map("resource", resource));
        }
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("resourceType", "Bundle");
        bundle.put("type", "searchset");
        bundle.put("total", resources.size());
        bundle.put("entry", entries);
        return bundle;
    }

    public Map<String, Object> capabilityStatement() {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("resourceType", "CapabilityStatement");
        resource.put("status", "active");
        resource.put("date", Instant.now().toString());
        resource.put("kind", "instance");
        resource.put("software", map("name", "Meridian"));
        resource.put("fhirVersion", "4.0.1");
        resource.put("format", List.of("json"));
        resource.put("rest", List.of(map(
                "mode", "server",
                "security", map("service", List.of(map(
                        "coding", List.of(map(
                                "system", "http://terminology.hl7.org/CodeSystem/restful-security-service",
                                "code", "OAuth"))))),
                "resource", List.of(
                        resourceCapability("Patient", "read", "search-type"),
                        resourceCapability("Encounter", "read", "search-type"),
                        resourceCapability("Observation", "read", "search-type"),
                        resourceCapability("DiagnosticReport", "read", "search-type")))));
        return resource;
    }

    public Map<String, Object> operationOutcome(String message) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("resourceType", "OperationOutcome");
        resource.put("issue", List.of(map(
                "severity", "error",
                "code", "not-found",
                "diagnostics", message)));
        return resource;
    }

    private Map<String, Object> resource(String type, String id) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("resourceType", type);
        resource.put("id", id);
        return resource;
    }

    private Map<String, Object> resourceCapability(String type, String... interactions) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (String interaction : interactions) {
            values.add(map("code", interaction));
        }
        return map("type", type, "interaction", values);
    }

    private Map<String, Object> code(String testCode) {
        return map(
                "coding", List.of(map(
                        "system", TEST_CODE_SYSTEM,
                        "code", testCode,
                        "display", testCode)),
                "text", testCode);
    }

    private Map<String, Object> reference(String type, String id) {
        return map("reference", type + "/" + id);
    }

    private String observationStatus(LabOrder order) {
        if (order.result() == null) {
            return "registered";
        }
        if (order.result().amendedAt() != null) {
            return "corrected";
        }
        if ("released".equals(order.status())) {
            return "final";
        }
        return "preliminary";
    }

    private String reportStatus(LabOrder order) {
        if (order.result() == null) {
            return "registered";
        }
        if (order.result().amendedAt() != null) {
            return "corrected";
        }
        if ("released".equals(order.status())) {
            return "final";
        }
        return "preliminary";
    }

    private BigDecimal numeric(String value) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private Map<String, Object> map(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            if (pairs[index + 1] != null) {
                values.put((String) pairs[index], pairs[index + 1]);
            }
        }
        return values;
    }
}
