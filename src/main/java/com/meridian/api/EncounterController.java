package com.meridian.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
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
    public Encounter create(
            @RequestHeader(value = "X-Facility-ID", required = false) String facilityHeader,
            @RequestHeader(value = "X-Actor-ID", required = false) String actorHeader,
            @RequestBody CreateEncounterRequest request) {
        ApiScope.RequestScope scope = ApiScope.resolve(facilityHeader, actorHeader);
        return service.createEncounter(
                scope.facilityId(),
                scope.actorId(),
                request.patientId(),
                request.clinicianId(),
                request.notes(),
                request.vitals());
    }

    @GetMapping("/{id}")
    public Encounter get(
            @RequestHeader(value = "X-Facility-ID", required = false) String facilityHeader,
            @PathVariable UUID id) {
        ApiScope.RequestScope scope = ApiScope.resolve(facilityHeader, null);
        return service.getEncounter(scope.facilityId(), id);
    }
}
