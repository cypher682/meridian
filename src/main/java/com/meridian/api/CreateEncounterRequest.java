package com.meridian.api;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record CreateEncounterRequest(
        UUID patientId,
        String clinicianId,
        String notes,
        JsonNode vitals) {
}
