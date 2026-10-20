package com.meridian.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
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
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public List<AuditEntry> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.listAudit(scope.facilityId(), limit);
    }

    @GetMapping("/verify")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public AuditVerification verify(
            @AuthenticationPrincipal Jwt jwt) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.verifyAudit(scope.facilityId());
    }
}
