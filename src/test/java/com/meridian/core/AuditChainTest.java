package com.meridian.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.meridian.core.Models.AuditEntry;

class AuditChainTest {

    private static final String FACILITY = "00000000-0000-0000-0000-000000000001";
    private static final String ENTITY = "00000000-0000-0000-0000-000000000020";

    @Test
    void hashIsDeterministic() {
        String first = AuditChain.entryHash(null, FACILITY, "actor", "encounter.created",
                "encounter", ENTITY, "{\"a\":1}");
        String second = AuditChain.entryHash(null, FACILITY, "actor", "encounter.created",
                "encounter", ENTITY, "{\"a\":1}");

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64);
    }

    @Test
    void changingPayloadChangesHash() {
        String original = AuditChain.entryHash(null, FACILITY, "actor", "encounter.created",
                "encounter", ENTITY, "{\"a\":1}");
        String tampered = AuditChain.entryHash(null, FACILITY, "actor", "encounter.created",
                "encounter", ENTITY, "{\"a\":2}");

        assertThat(tampered).isNotEqualTo(original);
    }

    @Test
    void previousHashIsPartOfTheChain() {
        String first = AuditChain.entryHash(null, FACILITY, "actor", "patient.registered",
                "patient", ENTITY, "{\"a\":1}");
        String second = AuditChain.entryHash(first, FACILITY, "actor", "encounter.created",
                "encounter", ENTITY, "{\"a\":1}");

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void detectsTheFirstTamperedEntry() {
        UUID facility = UUID.fromString(FACILITY);
        UUID entity = UUID.fromString(ENTITY);
        String firstPayload = "{\"given_name\":\"Amina\"}";
        String secondPayload = "{\"clinician_id\":\"clinician-demo\"}";

        String firstHash = AuditChain.entryHash(null, FACILITY, "actor", "patient.registered",
                "patient", ENTITY, firstPayload);
        String secondHash = AuditChain.entryHash(firstHash, FACILITY, "actor", "encounter.created",
                "encounter", ENTITY, secondPayload);

        AuditEntry first = new AuditEntry(1, facility, "actor", "patient.registered",
                "patient", entity, null, firstPayload, null, firstHash, Instant.now());
        AuditEntry second = new AuditEntry(2, facility, "actor", "encounter.created",
                "encounter", entity, null, secondPayload, firstHash, secondHash, Instant.now());

        assertThat(AuditChain.firstBrokenEntry(List.of(first, second))).isNull();

        AuditEntry tampered = new AuditEntry(2, facility, "actor", "encounter.created",
                "encounter", entity, null, "{\"clinician_id\":\"someone-else\"}",
                firstHash, secondHash, Instant.now());

        assertThat(AuditChain.firstBrokenEntry(List.of(first, tampered))).isEqualTo(2L);
    }
}
