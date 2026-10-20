package com.meridian.fhir;

import com.meridian.api.ApiScope;
import com.meridian.core.MeridianService;
import com.meridian.core.Models.LabOrder;
import com.meridian.core.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/fhir")
@PreAuthorize("isAuthenticated()")
public class FhirController {

    private static final MediaType FHIR_JSON = MediaType.parseMediaType("application/fhir+json");

    private final MeridianService service;
    private final FhirMapper mapper;

    public FhirController(MeridianService service, FhirMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping("/metadata")
    public ResponseEntity<Map<String, Object>> metadata() {
        return ok(mapper.capabilityStatement());
    }

    @GetMapping("/Patient/{id}")
    public ResponseEntity<Map<String, Object>> patient(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return ok(mapper.patient(service.getPatient(scope.facilityId(), id)));
    }

    @GetMapping("/Patient")
    public ResponseEntity<Map<String, Object>> searchPatients(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "name", defaultValue = "") String name) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        List<Map<String, Object>> resources = service.searchPatients(scope.facilityId(), name).stream()
                .map(mapper::patient)
                .toList();
        return ok(mapper.bundle(resources));
    }

    @GetMapping("/Encounter/{id}")
    public ResponseEntity<Map<String, Object>> encounter(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return ok(mapper.encounter(service.getEncounter(scope.facilityId(), id)));
    }

    @GetMapping("/Encounter")
    public ResponseEntity<Map<String, Object>> searchEncounters(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "patient") UUID patientId) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        List<Map<String, Object>> resources = service.listEncounters(scope.facilityId(), patientId).stream()
                .map(mapper::encounter)
                .toList();
        return ok(mapper.bundle(resources));
    }

    @GetMapping("/Observation/{id}")
    public ResponseEntity<Map<String, Object>> observation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return ok(mapper.observation(service.getLabOrder(scope.facilityId(), id)));
    }

    @GetMapping("/Observation")
    public ResponseEntity<Map<String, Object>> searchObservations(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "patient") UUID patientId) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return ok(mapper.bundle(mapOrders(service.listLabOrders(scope.facilityId(), null, patientId), false)));
    }

    @GetMapping("/DiagnosticReport/{id}")
    public ResponseEntity<Map<String, Object>> diagnosticReport(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return ok(mapper.diagnosticReport(service.getLabOrder(scope.facilityId(), id)));
    }

    @GetMapping("/DiagnosticReport")
    public ResponseEntity<Map<String, Object>> searchDiagnosticReports(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "patient") UUID patientId) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return ok(mapper.bundle(mapOrders(service.listLabOrders(scope.facilityId(), null, patientId), true)));
    }

    @ExceptionHandler({NotFoundException.class, EmptyResultDataAccessException.class})
    public ResponseEntity<Map<String, Object>> notFound(Exception error) {
        String message = error instanceof NotFoundException ? error.getMessage() : "not found";
        return ResponseEntity.status(404)
                .contentType(FHIR_JSON)
                .body(mapper.operationOutcome(message));
    }

    private List<Map<String, Object>> mapOrders(List<LabOrder> orders, boolean reports) {
        List<Map<String, Object>> resources = new ArrayList<>();
        for (LabOrder order : orders) {
            resources.add(reports ? mapper.diagnosticReport(order) : mapper.observation(order));
        }
        return resources;
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok().contentType(FHIR_JSON).body(body);
    }
}
