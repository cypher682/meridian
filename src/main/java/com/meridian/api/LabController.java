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
import com.meridian.core.Models.LabOrder;

@RestController
@RequestMapping("/api/lab-orders")
public class LabController {

    private final MeridianService service;

    public LabController(MeridianService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LabOrder create(
            @RequestHeader(value = "X-Facility-ID", required = false) String facilityHeader,
            @RequestHeader(value = "X-Actor-ID", required = false) String actorHeader,
            @RequestBody CreateLabOrderRequest request) {
        ApiScope.RequestScope scope = ApiScope.resolve(facilityHeader, actorHeader);
        return service.createLabOrder(
                scope.facilityId(),
                scope.actorId(),
                request.encounterId(),
                request.testCode());
    }

    @GetMapping
    public List<LabOrder> list(
            @RequestHeader(value = "X-Facility-ID", required = false) String facilityHeader,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID patientId) {
        ApiScope.RequestScope scope = ApiScope.resolve(facilityHeader, null);
        return service.listLabOrders(scope.facilityId(), status, patientId);
    }

    @GetMapping("/{id}")
    public LabOrder get(
            @RequestHeader(value = "X-Facility-ID", required = false) String facilityHeader,
            @PathVariable UUID id) {
        ApiScope.RequestScope scope = ApiScope.resolve(facilityHeader, null);
        return service.getLabOrder(scope.facilityId(), id);
    }

    @PostMapping("/{id}/collect")
    public LabOrder collect(
            @RequestHeader(value = "X-Facility-ID", required = false) String facilityHeader,
            @RequestHeader(value = "X-Actor-ID", required = false) String actorHeader,
            @PathVariable UUID id) {
        ApiScope.RequestScope scope = ApiScope.resolve(facilityHeader, actorHeader);
        return service.collectSample(scope.facilityId(), scope.actorId(), id);
    }

    @PostMapping("/{id}/results")
    public LabOrder recordResult(
            @RequestHeader(value = "X-Facility-ID", required = false) String facilityHeader,
            @RequestHeader(value = "X-Actor-ID", required = false) String actorHeader,
            @PathVariable UUID id,
            @RequestBody RecordResultRequest request) {
        ApiScope.RequestScope scope = ApiScope.resolve(facilityHeader, actorHeader);
        return service.recordResult(
                scope.facilityId(),
                scope.actorId(),
                id,
                request.valueText(),
                request.unit(),
                request.referenceRange());
    }

    @PostMapping("/{id}/release")
    public LabOrder release(
            @RequestHeader(value = "X-Facility-ID", required = false) String facilityHeader,
            @RequestHeader(value = "X-Actor-ID", required = false) String actorHeader,
            @PathVariable UUID id,
            @RequestBody ReleaseResultRequest request) {
        ApiScope.RequestScope scope = ApiScope.resolve(facilityHeader, actorHeader);
        return service.releaseResult(
                scope.facilityId(),
                scope.actorId(),
                id,
                request.releasedBy());
    }
}
