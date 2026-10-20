package com.meridian.api;

import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

public final class ApiScope {

    private ApiScope() {
    }

    public static RequestScope from(Jwt jwt) {
        String facilityClaim = jwt.getClaimAsString("facility_id");
        if (facilityClaim == null || facilityClaim.isBlank()) {
            throw new AccessDeniedException("facility claim is missing");
        }

        UUID facilityId;
        try {
            facilityId = UUID.fromString(facilityClaim.trim());
        } catch (IllegalArgumentException error) {
            throw new AccessDeniedException("facility claim must be a UUID");
        }

        String actorId = jwt.getClaimAsString("preferred_username");
        if (actorId == null || actorId.isBlank()) {
            actorId = jwt.getSubject();
        }
        if (actorId == null || actorId.isBlank()) {
            throw new AccessDeniedException("actor claim is missing");
        }

        return new RequestScope(facilityId, actorId);
    }

    public record RequestScope(UUID facilityId, String actorId) {
    }
}
