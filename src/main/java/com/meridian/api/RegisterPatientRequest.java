package com.meridian.api;

import java.time.LocalDate;

public record RegisterPatientRequest(
        String givenName,
        String familyName,
        LocalDate dateOfBirth,
        String phone) {
}
