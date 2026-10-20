package com.meridian;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meridian.api.BillingController;
import com.meridian.api.SecurityConfig;
import com.meridian.core.MeridianService;
import com.meridian.core.Models.BillingInvoice;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BillingController.class)
@Import(SecurityConfig.class)
class BillingControllerTest {

    private static final UUID FACILITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ENCOUNTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000123");
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000456");
    private static final UUID PATIENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000789");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MeridianService service;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void createsAnInvoiceForFrontDesk() throws Exception {
        given(service.createInvoice(FACILITY_ID, "frontdesk", null, ENCOUNTER_ID, null))
                .willReturn(invoice("open"));

        mockMvc.perform(post("/api/billing/invoices")
                        .with(frontDeskUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"encounterId\":\"" + ENCOUNTER_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("open"))
                .andExpect(jsonPath("$.totalCents").value(0));
    }

    @Test
    void passesOfflineInvoiceIdAndCaptureTime() throws Exception {
        UUID clientInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000999");
        Instant capturedAt = Instant.parse("2026-10-20T11:30:00Z");
        given(service.createInvoice(FACILITY_ID, "frontdesk", clientInvoiceId, ENCOUNTER_ID, capturedAt))
                .willReturn(invoice("open"));

        mockMvc.perform(post("/api/billing/invoices")
                        .with(frontDeskUser())
                        .header("X-Meridian-Captured-At", capturedAt.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + clientInvoiceId + "\",\"encounterId\":\"" + ENCOUNTER_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("open"));
    }

    @Test
    void rejectsInvoiceCreationByClinician() throws Exception {
        mockMvc.perform(post("/api/billing/invoices")
                        .with(clinicianUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"encounterId\":\"" + ENCOUNTER_ID + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listsInvoicesForAnyAuthenticatedUser() throws Exception {
        given(service.listInvoices(FACILITY_ID, null, null)).willReturn(List.of(invoice("open")));

        mockMvc.perform(get("/api/billing/invoices").with(clinicianUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(INVOICE_ID.toString()));
    }

    @Test
    void rejectsRequestsWithoutAToken() throws Exception {
        mockMvc.perform(get("/api/billing/invoices"))
                .andExpect(status().isUnauthorized());
    }

    private static BillingInvoice invoice(String status) {
        return new BillingInvoice(
                INVOICE_ID, FACILITY_ID, ENCOUNTER_ID, PATIENT_ID, status, 0, "frontdesk",
                Instant.parse("2026-10-20T12:00:00Z"), null, null, List.of());
    }

    private static JwtRequestPostProcessor frontDeskUser() {
        return jwt()
                .jwt(token -> token
                        .subject("frontdesk")
                        .claim("preferred_username", "frontdesk")
                        .claim("facility_id", FACILITY_ID.toString())
                        .claim("realm_access", Map.of("roles", List.of("front_desk"))))
                .authorities(new SimpleGrantedAuthority("ROLE_FRONT_DESK"));
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
