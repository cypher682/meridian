package com.meridian.api;

import java.util.UUID;

import com.meridian.core.BadRequestException;

public final class ApiScope {

    private static final UUID DEFAULT_FACILITY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String DEFAULT_ACTOR_ID = "demo-user";

    private ApiScope() {
    }

    public static RequestScope resolve(String facilityHeader, String actorHeader) {
        UUID facilityId;
        if (facilityHeader == null || facilityHeader.isBlank()) {
            facilityId = DEFAULT_FACILITY_ID;
        } else {
            try {
                facilityId = UUID.fromString(facilityHeader.trim());
            } catch (IllegalArgumentException error) {
                throw new BadRequestException("X-Facility-ID must be a UUID");
            }
        }
        String actorId = actorHeader == null || actorHeader.isBlank()
                ? DEFAULT_ACTOR_ID
                : actorHeader.trim();
        return new RequestScope(facilityId, actorId);
    }

    public record RequestScope(UUID facilityId, String actorId) {
    }
}
