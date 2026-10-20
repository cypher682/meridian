package com.meridian.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
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
    public Patient register(
            @RequestHeader(value = "X-Facility-ID", required = false) String facilityHeader,
            @RequestHeader(value = "X-Actor-ID", required = false) String actorHeader,
            @RequestBody RegisterPatientRequest request) {
        ApiScope.RequestScope scope = ApiScope.resolve(facilityHeader, actorHeader);
        return service.registerPatient(
                scope.facilityId(),
                scope.actorId(),
                request.givenName(),
                request.familyName(),
                request.dateOfBirth(),
                request.phone());
    }

    @GetMapping("/{id}")
    public Patient get(
            @RequestHeader(value = "X-Facility-ID", required = false) String facilityHeader,
            @PathVariable UUID id) {
        ApiScope.RequestScope scope = ApiScope.resolve(facilityHeader, null);
        return service.getPatient(scope.facilityId(), id);
    }

    @GetMapping
    public List<Patient> search(
            @RequestHeader(value = "X-Facility-ID", required = false) String facilityHeader,
            @RequestParam(value = "q", defaultValue = "") String query) {
        ApiScope.RequestScope scope = ApiScope.resolve(facilityHeader, null);
        return service.searchPatients(scope.facilityId(), query);
    }

    @GetMapping("/{id}/encounters")
    public List<Encounter> encounters(
            @RequestHeader(value = "X-Facility-ID", required = false) String facilityHeader,
            @PathVariable UUID id) {
        ApiScope.RequestScope scope = ApiScope.resolve(facilityHeader, null);
        return service.listEncounters(scope.facilityId(), id);
    }
}
