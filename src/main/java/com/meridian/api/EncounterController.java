package com.meridian.api;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.meridian.core.MeridianService;
import com.meridian.core.Models.Encounter;

@RestController
@RequestMapping("/api/encounters")
public class EncounterController {

    private final MeridianService service;

    public EncounterController(MeridianService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('FRONT_DESK','CLINICIAN','ADMIN')")
    public Encounter create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateEncounterRequest request,
            @RequestHeader(value = "X-Meridian-Captured-At", required = false) String capturedAtHeader) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.createEncounter(
                scope.facilityId(),
                scope.actorId(),
                request.id(),
                request.patientId(),
                request.clinicianId(),
                request.notes(),
                request.vitals(),
                OfflineMetadata.capturedAt(capturedAtHeader));
    }

    @GetMapping("/{id}")
    public Encounter get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.getEncounter(scope.facilityId(), id);
    }
}
