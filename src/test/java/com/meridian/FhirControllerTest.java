package com.meridian;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meridian.api.SecurityConfig;
import com.meridian.core.MeridianService;
import com.meridian.core.Models.Patient;
import com.meridian.core.NotFoundException;
import com.meridian.fhir.FhirController;
import com.meridian.fhir.FhirMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FhirController.class)
@Import({SecurityConfig.class, FhirMapper.class})
class FhirControllerTest {

    private static final UUID FACILITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PATIENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000123");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MeridianService service;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void servesCapabilityStatement() throws Exception {
        mockMvc.perform(get("/fhir/metadata").with(clinicianUser()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/fhir+json"))
                .andExpect(jsonPath("$.resourceType").value("CapabilityStatement"))
                .andExpect(jsonPath("$.fhirVersion").value("4.0.1"));
    }

    @Test
    void servesAPatient() throws Exception {
        given(service.getPatient(FACILITY_ID, PATIENT_ID)).willReturn(new Patient(
                PATIENT_ID, FACILITY_ID, "Sana", "Smoke", LocalDate.of(1992, 2, 3), "+00000000001",
                Instant.parse("2026-10-20T12:00:00Z")));

        mockMvc.perform(get("/fhir/Patient/{id}", PATIENT_ID).with(clinicianUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceType").value("Patient"))
                .andExpect(jsonPath("$.id").value(PATIENT_ID.toString()))
                .andExpect(jsonPath("$.name[0].family").value("Smoke"));
    }

    @Test
    void returnsAnOperationOutcomeForAMissingPatient() throws Exception {
        given(service.getPatient(FACILITY_ID, PATIENT_ID)).willThrow(new NotFoundException("patient not found"));

        mockMvc.perform(get("/fhir/Patient/{id}", PATIENT_ID).with(clinicianUser()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resourceType").value("OperationOutcome"))
                .andExpect(jsonPath("$.issue[0].diagnostics").value("patient not found"));
    }

    @Test
    void rejectsRequestsWithoutAToken() throws Exception {
        mockMvc.perform(get("/fhir/Patient/{id}", PATIENT_ID))
                .andExpect(status().isUnauthorized());
    }

    private static JwtRequestPostProcessor clinicianUser() {
        return jwt()
                .jwt(token -> token
                        .subject("clinician")
                        .claim("preferred_username", "clinician")
                        .claim("facility_id", FACILITY_ID.toString())
                        .claim("realm_access", Map.of("roles", List.of("clinician"))))
                .authorities(new SimpleGrantedAuthority("ROLE_CLINICIAN"));
    }
}
