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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.meridian.core.MeridianService;
import com.meridian.core.Models.LabOrder;
import com.meridian.core.Models.LabResultAmendment;

@RestController
@RequestMapping("/api/lab-orders")
public class LabController {

    private final MeridianService service;

    public LabController(MeridianService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('CLINICIAN','ADMIN')")
    public LabOrder create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateLabOrderRequest request) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.createLabOrder(
                scope.facilityId(),
                scope.actorId(),
                request.encounterId(),
                request.testCode());
    }

    @GetMapping
    public List<LabOrder> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID patientId) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.listLabOrders(scope.facilityId(), status, patientId);
    }

    @GetMapping("/{id}")
    public LabOrder get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.getLabOrder(scope.facilityId(), id);
    }

    @PostMapping("/{id}/collect")
    @PreAuthorize("hasAnyRole('LAB','ADMIN')")
    public LabOrder collect(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.collectSample(scope.facilityId(), scope.actorId(), id);
    }

    @PostMapping("/{id}/results")
    @PreAuthorize("hasAnyRole('LAB','ADMIN')")
    public LabOrder recordResult(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody RecordResultRequest request) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.recordResult(
                scope.facilityId(),
                scope.actorId(),
                id,
                request.valueText(),
                request.unit(),
                request.referenceRange());
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("hasAnyRole('CLINICIAN','ADMIN')")
    public LabOrder release(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody ReleaseResultRequest request) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.releaseResult(
                scope.facilityId(),
                scope.actorId(),
                id,
                request.releasedBy());
    }

    @PostMapping("/{id}/amendments")
    @PreAuthorize("hasAnyRole('CLINICIAN','ADMIN')")
    public LabOrder amend(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody AmendResultRequest request) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.amendResult(
                scope.facilityId(),
                scope.actorId(),
                id,
                request.valueText(),
                request.unit(),
                request.referenceRange(),
                request.reason());
    }

    @GetMapping("/{id}/amendments")
    public List<LabResultAmendment> amendments(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.listAmendments(scope.facilityId(), id);
    }
}
