package com.meridian.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.meridian.core.MeridianService;
import com.meridian.core.Models.AuditEntry;
import com.meridian.core.Models.AuditVerification;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final MeridianService service;

    public AuditController(MeridianService service) {
        this.service = service;
    }

    @GetMapping
    public List<AuditEntry> list(
            @RequestHeader(value = "X-Facility-ID", required = false) String facilityHeader,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        ApiScope.RequestScope scope = ApiScope.resolve(facilityHeader, null);
        return service.listAudit(scope.facilityId(), limit);
    }

    @GetMapping("/verify")
    public AuditVerification verify(
            @RequestHeader(value = "X-Facility-ID", required = false) String facilityHeader) {
        ApiScope.RequestScope scope = ApiScope.resolve(facilityHeader, null);
        return service.verifyAudit(scope.facilityId());
    }
}
