package com.meridian.api;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import com.meridian.core.BadRequestException;

public final class OfflineMetadata {

    private OfflineMetadata() {
    }

    public static Instant capturedAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException error) {
            throw new BadRequestException("X-Meridian-Captured-At must be an ISO-8601 instant");
        }
    }
}
