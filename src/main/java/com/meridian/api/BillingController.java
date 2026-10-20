package com.meridian.api;

import com.meridian.core.MeridianService;
import com.meridian.core.Models.BillingInvoice;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/billing/invoices")
public class BillingController {

    private final MeridianService service;

    public BillingController(MeridianService service) {
        this.service = service;
    }

    @GetMapping
    public List<BillingInvoice> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID patientId) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.listInvoices(scope.facilityId(), status, patientId);
    }

    @GetMapping("/{id}")
    public BillingInvoice get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.getInvoice(scope.facilityId(), id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'ADMIN')")
    public BillingInvoice create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateInvoiceRequest request) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.createInvoice(scope.facilityId(), scope.actorId(), request.encounterId());
    }

    @PostMapping("/{id}/charges")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'ADMIN')")
    public BillingInvoice addCharge(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody AddChargeRequest request) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.addCharge(
                scope.facilityId(), scope.actorId(), id, request.description(), request.amountCents());
    }

    @PostMapping("/{id}/pay")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'ADMIN')")
    public BillingInvoice pay(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.payInvoice(scope.facilityId(), scope.actorId(), id);
    }

    public record CreateInvoiceRequest(UUID encounterId) {}

    public record AddChargeRequest(String description, long amountCents) {}
}
