package com.meridian.api;

import java.time.LocalDate;
import java.util.UUID;

public record RegisterPatientRequest(
        UUID id,
        String givenName,
        String familyName,
        LocalDate dateOfBirth,
        String phone) {
}
