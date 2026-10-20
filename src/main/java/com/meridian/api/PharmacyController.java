package com.meridian.api;

import com.meridian.core.MeridianService;
import com.meridian.core.Models.PharmacyDispense;
import com.meridian.core.Models.PharmacyItem;
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
@RequestMapping("/api/pharmacy")
public class PharmacyController {

    private final MeridianService service;

    public PharmacyController(MeridianService service) {
        this.service = service;
    }

    @GetMapping("/items")
    public List<PharmacyItem> listItems(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String q) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.listPharmacyItems(scope.facilityId(), q);
    }

    @GetMapping("/items/{id}")
    public PharmacyItem getItem(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.getPharmacyItem(scope.facilityId(), id);
    }

    @PostMapping("/items")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'ADMIN')")
    public PharmacyItem createItem(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreatePharmacyItemRequest request) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.createPharmacyItem(
                scope.facilityId(),
                scope.actorId(),
                request.sku(),
                request.name(),
                request.unit(),
                valueOrZero(request.stockQuantity()),
                valueOrZero(request.reorderLevel()));
    }

    @PostMapping("/items/{id}/stock")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'ADMIN')")
    public PharmacyItem adjustStock(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody AdjustStockRequest request) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.adjustStock(scope.facilityId(), scope.actorId(), id, request.delta());
    }

    @GetMapping("/dispenses")
    public List<PharmacyDispense> listDispenses(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID patientId) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.listDispenses(scope.facilityId(), patientId);
    }

    @PostMapping("/dispenses")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'ADMIN')")
    public PharmacyDispense dispense(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody DispenseRequest request) {
        ApiScope.RequestScope scope = ApiScope.from(jwt);
        return service.dispenseMedication(
                scope.facilityId(),
                scope.actorId(),
                request.itemId(),
                request.encounterId(),
                request.quantity());
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    public record CreatePharmacyItemRequest(
            String sku,
            String name,
            String unit,
            Integer stockQuantity,
            Integer reorderLevel) {}

    public record AdjustStockRequest(int delta) {}

    public record DispenseRequest(UUID itemId, UUID encounterId, int quantity) {}
}
