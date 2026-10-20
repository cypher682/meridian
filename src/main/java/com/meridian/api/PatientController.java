package com.meridian.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.meridian.core.MeridianService;
import com.meridian.core.Models.Encounter;
import com.meridian.core.Models.Patient;

@RestController
@RequestMapping("/api/patients")
public class PatientController {

    private final MeridianService service;

    public PatientController(MeridianService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('FRONT_DESK','ADMIN')")
    public Patient register(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody RegisterPatientRequest request,
            @RequestHeader(value = "X-Meridian-Captured-At", required = false) String capturedAtHeader) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.registerPatient(
                scope.facilityId(),
                scope.actorId(),
                request.id(),
                request.givenName(),
                request.familyName(),
                request.dateOfBirth(),
                request.phone(),
                OfflineMetadata.capturedAt(capturedAtHeader));
    }

    @GetMapping("/{id}")
    public Patient get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.getPatient(scope.facilityId(), id);
    }

    @GetMapping
    public List<Patient> search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "q", defaultValue = "") String query) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.searchPatients(scope.facilityId(), query);
    }

    @GetMapping("/{id}/encounters")
    public List<Encounter> encounters(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.listEncounters(scope.facilityId(), id);
    }
}
